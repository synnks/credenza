package synnks.credenza.config.sso

import cats.effect.IO
import fs2.{ Chunk, Stream }
import io.circe.parser.{ decode, parse }
import io.circe.syntax.*
import io.circe.{ Codec, Decoder, Encoder }
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{ Header, MediaType, Method, Request, Status, Uri }
import org.typelevel.ci.CIString
import synnks.credenza.config.model.SsoProfile
import synnks.credenza.config.sso.SsoApiClient.*
import synnks.credenza.config.sso.SsoCachedToken.{ RefreshMaterial, Secret }

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.{ FiniteDuration, SECONDS }

final class SsoApiClient(client: Client[IO], ssoEndpoint: Uri, oidcEndpoint: Uri) {

  def getRoleCredentials(profile: SsoProfile, token: Secret): IO[Either[Error, RoleCredentials]] = {
    val uri     = ssoEndpoint
      .withPath(ssoEndpoint.path / "federation" / "credentials")
      .withQueryParams(Map("account_id" -> profile.accountId.value, "role_name" -> profile.roleName.value))
    val request = Request[IO](Method.GET, uri)
      .putHeaders(Header.Raw(CIString("x-amz-sso_bearer_token"), token.value))

    execute(request, Service.Portal) { body =>
      decode[RoleResponse](body)
        .map(_.roleCredentials)
        .left
        .map(_ => Error.InvalidResponse)
    }
  }

  def refresh(material: RefreshMaterial): IO[Either[Error, RefreshedToken]] = {
    val body    = RefreshRequest(material.clientId, material.clientSecret, "refresh_token", material.refreshToken).asJson
    val uri     = oidcEndpoint.withPath(oidcEndpoint.path / "token")
    val request = Request[IO](Method.POST, uri)
      .withEntity(body.noSpaces)
      .putHeaders(`Content-Type`(MediaType.application.json))

    execute(request, Service.Oidc) { body =>
      decode[RefreshedToken](body).left
        .map(_ => Error.InvalidResponse)
    }
  }

  private def execute[A](request: Request[IO], service: Service)(
    decode: String => Either[Error, A]
  ): IO[Either[Error, A]] =
    client
      .run(request)
      .use { response =>
        response.body.take(MaxResponseBytes.toLong + 1).compile.to(Chunk).flatMap { bytes =>
          if (bytes.size > MaxResponseBytes) IO.pure(Left(Error.InvalidResponse))
          else
            response.withBodyStream(Stream.chunk(bytes)).as[String].map { body =>
              if (response.status.isSuccess) decode(body)
              else Left(classify(response.status, body, service))
            }
        }
      }
      .recover {
        case _: TimeoutException => Left(Error.TimedOut)
        case _: IOException      => Left(Error.TransportUnavailable)
      }
}

object SsoApiClient {
  private[sso] val MaxResponseBytes = 1024 * 1024

  private enum Service {
    case Portal, Oidc
  }

  final private case class RefreshRequest(
    clientId: Secret,
    clientSecret: Secret,
    grantType: String,
    refreshToken: Secret
  ) derives Encoder.AsObject {
    override def toString: String = "RefreshRequest(<redacted>)"
  }

  final case class RoleCredentials private[sso] (
    accessKeyId: Secret,
    secretAccessKey: Secret,
    sessionToken: Secret,
    expiration: Instant
  ) derives Decoder {
    override def toString: String = "RoleCredentials(<redacted>)"
  }

  final private case class RoleResponse(roleCredentials: RoleCredentials) derives Decoder

  final case class RefreshedToken private[sso] (
    accessToken: Secret,
    expiresIn: FiniteDuration,
    refreshToken: Option[Secret]
  ) derives Decoder {
    override def toString: String = "RefreshedToken(<redacted>)"
  }

  enum Error {
    case AuthRequired
    case InvalidGrant
    case Throttled
    case InvalidResponse
    case RemoteFailure(status: Status)
    case TimedOut
    case TransportUnavailable

    def message: String = this match {
      case AuthRequired          => "The SSO session or client registration must be renewed."
      case InvalidGrant          => "The SSO refresh grant is invalid; sign in again."
      case Throttled             => "The SSO service is throttling requests."
      case InvalidResponse       => "The SSO service returned an invalid response."
      case RemoteFailure(status) => s"The SSO service returned HTTP ${status.code}."
      case TimedOut              => "The SSO request timed out."
      case TransportUnavailable  => "Could not reach the SSO service."
    }
  }

  private given Codec[Secret] = Codec.from(
    Decoder.decodeString.emap(value => Secret.from(value).toRight("Expected a nonempty credential")),
    Encoder.encodeString.contramap[Secret](_.value)
  )

  private given Decoder[Instant] = Decoder.decodeLong.emap { millis =>
    Either.cond(millis > 0L, Instant.ofEpochMilli(millis), "Expected a positive expiration")
  }

  private given Decoder[FiniteDuration] = Decoder.decodeInt.emap { seconds =>
    Either.cond(seconds > 0, FiniteDuration(seconds.toLong, SECONDS), "Expected a positive duration")
  }

  private def classify(status: Status, body: String, service: Service): Error = {
    val errorCode = parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption)
    (status, service, errorCode) match {
      case (Status.TooManyRequests, _, _)                            => Error.Throttled
      case (Status.Unauthorized, _, _)                               => Error.AuthRequired
      case (Status.BadRequest, Service.Oidc, Some("invalid_client")) => Error.AuthRequired
      case (Status.BadRequest, Service.Oidc, Some("invalid_grant"))  => Error.InvalidGrant
      case _                                                         => Error.RemoteFailure(status)
    }
  }
}
