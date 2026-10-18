package synnks.credenza.config.sso

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import io.netty.handler.ssl.SslProvider
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region as AwsRegion
import software.amazon.awssdk.services.sso.SsoAsyncClient
import software.amazon.awssdk.services.sso.model.{ GetRoleCredentialsRequest, GetRoleCredentialsResponse }
import software.amazon.awssdk.services.ssooidc.SsoOidcAsyncClient
import software.amazon.awssdk.services.ssooidc.model.{ CreateTokenRequest, CreateTokenResponse }
import synnks.credenza.config.model.{ Region, SsoProfile }
import synnks.credenza.config.sso.SsoApiClient.*
import synnks.credenza.config.sso.SsoCachedToken.{ RefreshMaterial, Secret }

import java.net.URI
import java.time.Instant
import java.util.concurrent.{ CompletableFuture, TimeoutException }
import scala.concurrent.duration.*

final class SsoApiClient private[sso] (sso: SsoAsyncClient, oidc: SsoOidcAsyncClient) {
  def getRoleCredentials(profile: SsoProfile, token: Secret): IO[Either[Error, RoleCredentials]] =
    execute {
      sso.getRoleCredentials(
        GetRoleCredentialsRequest
          .builder()
          .accountId(profile.accountId.value)
          .roleName(profile.roleName.value)
          .accessToken(token.value)
          .build()
      )
    }(roleCredentials)

  def refresh(material: RefreshMaterial): IO[Either[Error, RefreshedToken]] =
    execute {
      oidc.createToken(
        CreateTokenRequest
          .builder()
          .clientId(material.clientId.value)
          .clientSecret(material.clientSecret.value)
          .grantType("refresh_token")
          .refreshToken(material.refreshToken.value)
          .build()
      )
    }(refreshedToken)

  private def execute[A, B](call: => CompletableFuture[A])(validate: A => Either[Error, B]): IO[Either[Error, B]] =
    IO.fromCompletableFuture(IO.delay(call)).map(validate).recover {
      case error: SdkException => Left(SsoSdkErrors.fromSdk(error))
      case _: TimeoutException => Left(Error.TimedOut)
    }
}

object SsoApiClient {
  private[sso] val MaxResponseBytes = 1024 * 1024

  /** Owns one shared HTTP transport and both service clients, using SDK regional endpoint resolution. */
  def resource(
    region: Region,
    timeout: FiniteDuration = 30.seconds
  ): Resource[IO, SsoApiClient] =
    resourceWithTransport(
      region,
      Resource.fromAutoCloseable(IO.blocking(NettyNioAsyncHttpClient.builder().sslProvider(SslProvider.JDK).build())),
      timeout
    )

  private[sso] def resourceWithTransport(
    region: Region,
    transport: Resource[IO, SdkAsyncHttpClient],
    timeout: FiniteDuration,
    ssoEndpoint: Option[URI] = None,
    oidcEndpoint: Option[URI] = None
  ): Resource[IO, SsoApiClient] =
    for {
      responseLimit <- SsoResponseLimit.resource
      configuration <- Resource.eval(IO.delay {
                         ClientOverrideConfiguration
                           .builder()
                           .apiCallTimeout(java.time.Duration.ofNanos(timeout.toNanos))
                           .retryStrategy(AwsRetryStrategy.doNotRetry())
                           .addExecutionInterceptor(responseLimit)
                           .build()
                       })
      http          <- transport
      sso           <- Resource.fromAutoCloseable(IO.blocking {
                         val builder = SsoAsyncClient
                           .builder()
                           .region(AwsRegion.of(region.value))
                           .credentialsProvider(AnonymousCredentialsProvider.create())
                           .httpClient(http)
                           .overrideConfiguration(configuration)
                         ssoEndpoint.foreach(builder.endpointOverride)
                         builder.build()
                       })
      oidc          <- Resource.fromAutoCloseable(IO.blocking {
                         val builder = SsoOidcAsyncClient
                           .builder()
                           .region(AwsRegion.of(region.value))
                           .credentialsProvider(AnonymousCredentialsProvider.create())
                           .httpClient(http)
                           .overrideConfiguration(configuration)
                         oidcEndpoint.foreach(builder.endpointOverride)
                         builder.build()
                       })
    } yield new SsoApiClient(sso, oidc)

  final case class RoleCredentials private[sso] (
    accessKeyId: Secret,
    secretAccessKey: Secret,
    sessionToken: Secret,
    expiration: Instant
  ) {
    override def toString: String = "RoleCredentials(<redacted>)"
  }

  final case class RefreshedToken private[sso] (
    accessToken: Secret,
    expiresIn: FiniteDuration,
    refreshToken: Option[Secret]
  ) {
    override def toString: String = "RefreshedToken(<redacted>)"
  }

  enum Error {
    case AuthRequired, InvalidGrant, Throttled, InvalidResponse, TimedOut, TransportUnavailable
    case RemoteFailure(status: Int)

    def message: String = this match {
      case AuthRequired          => "The SSO session or client registration must be renewed."
      case InvalidGrant          => "The SSO refresh grant is invalid; sign in again."
      case Throttled             => "The SSO service is throttling requests."
      case InvalidResponse       => "The SSO service returned an invalid response."
      case RemoteFailure(status) => s"The SSO service returned HTTP $status."
      case TimedOut              => "The SSO request timed out."
      case TransportUnavailable  => "Could not reach the SSO service."
    }
  }

  private def secret(value: String): Either[Error, Secret] =
    Option(value).flatMap(Secret.from).toRight(Error.InvalidResponse)

  private def roleCredentials(response: GetRoleCredentialsResponse): Either[Error, RoleCredentials] = for {
    credentials  <- Option(response.roleCredentials()).toRight(Error.InvalidResponse)
    accessKey    <- secret(credentials.accessKeyId())
    secretKey    <- secret(credentials.secretAccessKey())
    sessionToken <- secret(credentials.sessionToken())
    expiration   <- Option(credentials.expiration()).filter(_.longValue > 0L).toRight(Error.InvalidResponse)
  } yield RoleCredentials(accessKey, secretKey, sessionToken, Instant.ofEpochMilli(expiration.longValue))

  private def refreshedToken(response: CreateTokenResponse): Either[Error, RefreshedToken] = for {
    accessToken  <- secret(response.accessToken())
    expiresIn    <- Option(response.expiresIn()).filter(_.intValue > 0).toRight(Error.InvalidResponse)
    refreshToken <- Option(response.refreshToken()).traverse(secret)
  } yield RefreshedToken(accessToken, expiresIn.longValue.seconds, refreshToken)

}
