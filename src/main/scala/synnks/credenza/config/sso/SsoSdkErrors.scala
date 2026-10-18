package synnks.credenza.config.sso

import io.circe.parser.parse
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.ssl.SslHandshakeTimeoutException
import io.netty.handler.timeout.TimeoutException as NettyTimeoutException
import software.amazon.awssdk.awscore.exception.{ AwsErrorDetails, AwsServiceException }
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException,
  SdkException
}
import software.amazon.awssdk.thirdparty.jackson.core.JsonProcessingException
import synnks.credenza.config.sso.SsoApiClient.Error

import java.util.concurrent.TimeoutException

private[sso] object SsoSdkErrors {
  private val causePrecedence = List(Error.InvalidResponse, Error.TimedOut)

  private val causeFailure: PartialFunction[Throwable, Error] = {
    case _: SsoResponseLimit.Exceeded | _: JsonProcessingException => Error.InvalidResponse
    case _: ApiCallTimeoutException | _: ApiCallAttemptTimeoutException | _: TimeoutException |
        _: NettyTimeoutException | _: ConnectTimeoutException | _: SslHandshakeTimeoutException =>
      Error.TimedOut
  }

  def fromSdk(error: SdkException): Error = {
    val detected = causes(error).flatMap(causeFailure.lift).toSet
    causePrecedence.find(detected.contains).getOrElse(sdkFailure(error))
  }

  def causes(error: Throwable): Iterator[Throwable] =
    Iterator.unfold(Option(error))(_.map(cause => cause -> Option(cause.getCause)))

  private def sdkFailure(error: SdkException): Error = error match {
    case service: AwsServiceException => serviceFailure(service.statusCode(), serviceCode(service))
    case _: SdkClientException        => Error.TransportUnavailable
    case _                            => Error.InvalidResponse
  }

  private def serviceFailure(status: Int, code: Option[String]): Error = (status, code) match {
    case (429, _)                                                 => Error.Throttled
    case (401, _)                                                 => Error.AuthRequired
    case (400, Some("invalid_client" | "InvalidClientException")) => Error.AuthRequired
    case (400, Some("invalid_grant" | "InvalidGrantException"))   => Error.InvalidGrant
    case (status, _)                                              => Error.RemoteFailure(status)
  }

  private def serviceCode(error: AwsServiceException): Option[String] =
    Option(error.awsErrorDetails()).flatMap(details => Option(details.errorCode()).orElse(oauthCode(details)))

  private def oauthCode(details: AwsErrorDetails): Option[String] = for {
    body <- Option(details.rawResponse())
    json <- parse(body.asUtf8String()).toOption
    code <- json.hcursor.get[String]("error").toOption
  } yield code
}
