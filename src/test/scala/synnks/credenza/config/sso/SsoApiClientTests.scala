package synnks.credenza.config.sso

import cats.effect.{ Deferred, IO, Resource }
import cats.syntax.all.*
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.ssl.SslHandshakeTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import munit.CatsEffectSuite
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.async.{ AsyncExecuteRequest, SdkAsyncHttpClient }
import software.amazon.awssdk.services.sso.SsoAsyncClient
import software.amazon.awssdk.services.sso.model.{
  GetRoleCredentialsRequest,
  GetRoleCredentialsResponse,
  RoleCredentials as SdkRoleCredentials
}
import software.amazon.awssdk.services.ssooidc.SsoOidcAsyncClient
import software.amazon.awssdk.services.ssooidc.model.{ CreateTokenRequest, CreateTokenResponse, SsoOidcException }
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.sso.SsoApiClient.Error
import synnks.credenza.config.sso.SsoCachedToken.{ RefreshMaterial, Secret }

import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.concurrent.{ CompletableFuture, ConcurrentLinkedQueue, CountDownLatch, TimeoutException, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SsoApiClientTests extends CatsEffectSuite {
  private def valid[A](result: Either[ValueError, A]): A = result.fold(e => fail(e.expected), identity)
  private def secret(value: String): Secret              = Secret.from(value).getOrElse(fail("Expected a synthetic test secret"))

  private val profile  = SsoProfile(
    valid(ProfileName.from("staging")),
    SsoSession(
      valid(SessionName.from("Work")),
      valid(SsoStartUrl.from("https://example.awsapps.com/start")),
      valid(Region.from("eu-central-1"))
    ),
    valid(AccountId.from("000011112222")),
    valid(RoleName.from("Admin+ReadOnly")),
    None
  )
  private val material = RefreshMaterial(
    secret("synthetic-client-id"),
    secret("synthetic-client-secret"),
    secret("synthetic-refresh-token"),
    Instant.parse("2030-06-01T00:00:00Z")
  )
  private val role     = SdkRoleCredentials
    .builder()
    .accessKeyId("synthetic-key-id")
    .secretAccessKey("synthetic-secret-key")
    .sessionToken("synthetic-session-token")
    .expiration(1893456000000L)
    .build()
  private val token    = CreateTokenResponse
    .builder()
    .accessToken("synthetic-new-token")
    .expiresIn(3600)
    .refreshToken("synthetic-new-refresh")
    .build()

  private def client(
    getRole: GetRoleCredentialsRequest => CompletableFuture[GetRoleCredentialsResponse] = _ =>
      CompletableFuture.completedFuture(GetRoleCredentialsResponse.builder().roleCredentials(role).build()),
    createToken: CreateTokenRequest => CompletableFuture[CreateTokenResponse] = _ =>
      CompletableFuture.completedFuture(token)
  ): SsoApiClient = {
    val tokenCall = createToken
    new SsoApiClient(
      new SsoAsyncClient     {
        override def serviceName(): String               = "sso"
        override def close(): Unit                       = ()
        override def getRoleCredentials(
          request: GetRoleCredentialsRequest
        ): CompletableFuture[GetRoleCredentialsResponse] = getRole(request)
      },
      new SsoOidcAsyncClient {
        override def serviceName(): String                                                            = "ssooidc"
        override def close(): Unit                                                                    = ()
        override def createToken(request: CreateTokenRequest): CompletableFuture[CreateTokenResponse] = tokenCall(
          request
        )
      }
    )
  }

  test("map validated inputs to SDK requests and retain redacted results with actual expiry") {
    val api = client(
      getRole = request => {
        assertEquals(request.accountId(), "000011112222")
        assertEquals(request.roleName(), "Admin+ReadOnly")
        assertEquals(request.accessToken(), "synthetic-access-token")
        CompletableFuture.completedFuture(GetRoleCredentialsResponse.builder().roleCredentials(role).build())
      },
      createToken = request => {
        assertEquals(request.clientId(), "synthetic-client-id")
        assertEquals(request.clientSecret(), "synthetic-client-secret")
        assertEquals(request.refreshToken(), "synthetic-refresh-token")
        assertEquals(request.grantType(), "refresh_token")
        CompletableFuture.completedFuture(token)
      }
    )
    for {
      credentials <- api.getRoleCredentials(profile, secret("synthetic-access-token"))
      refreshed   <- api.refresh(material)
    } yield {
      val result   = credentials.toOption.getOrElse(fail("Expected role credentials"))
      assertEquals(result.expiration, Instant.parse("2030-01-01T00:00:00Z"))
      assertEquals(result.toString, "RoleCredentials(<redacted>)")
      val newToken = refreshed.toOption.getOrElse(fail("Expected refreshed token"))
      assertEquals(newToken.expiresIn, 1.hour)
      assertEquals(newToken.refreshToken.map(_.value), Some("synthetic-new-refresh"))
      assertEquals(newToken.toString, "RefreshedToken(<redacted>)")
    }
  }

  test("reject missing or empty SDK role fields and non-positive expiration") {
    val responses = List(
      GetRoleCredentialsResponse.builder().build(),
      GetRoleCredentialsResponse.builder().roleCredentials(role.toBuilder.accessKeyId("").build()).build(),
      GetRoleCredentialsResponse.builder().roleCredentials(role.toBuilder.secretAccessKey(null).build()).build(),
      GetRoleCredentialsResponse.builder().roleCredentials(role.toBuilder.sessionToken("").build()).build(),
      GetRoleCredentialsResponse.builder().roleCredentials(role.toBuilder.expiration(0L).build()).build()
    )
    responses.traverse_ { response =>
      client(getRole = _ => CompletableFuture.completedFuture(response))
        .getRoleCredentials(profile, secret("access"))
        .map(result => assertEquals(result, Left(Error.InvalidResponse)))
    }
  }

  test("reject invalid token fields and accept an omitted replacement refresh token") {
    val invalid = List(
      CreateTokenResponse.builder().build(),
      token.toBuilder.accessToken("").build(),
      token.toBuilder.expiresIn(0).build(),
      token.toBuilder.refreshToken("").build()
    )
    invalid.traverse_ { response =>
      client(createToken = _ => CompletableFuture.completedFuture(response))
        .refresh(material)
        .map(result => assertEquals(result, Left(Error.InvalidResponse)))
    } *> client(createToken = _ => CompletableFuture.completedFuture(token.toBuilder.refreshToken(null).build()))
      .refresh(material)
      .map(result => assertEquals(result.toOption.map(_.refreshToken), Some(None)))
  }

  test("classify SDK service failures by status and code without exposing exception content") {
    List(
      (401, "unauthorized", Error.AuthRequired),
      (400, "invalid_client", Error.AuthRequired),
      (400, "InvalidClientException", Error.AuthRequired),
      (400, "invalid_grant", Error.InvalidGrant),
      (400, "InvalidGrantException", Error.InvalidGrant),
      (429, "invalid_grant", Error.Throttled),
      (500, "invalid_grant", Error.RemoteFailure(500))
    ).traverse_ { (status, code, expected) =>
      val error = SsoOidcException
        .builder()
        .statusCode(status)
        .message("synthetic-secret-value")
        .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("synthetic-secret-value").build())
        .build()
      client(createToken = _ => CompletableFuture.failedFuture(error)).refresh(material).map { result =>
        assertEquals(result, Left(expected))
        assert(!result.toString.contains("synthetic-secret-value"))
        assert(!result.swap.toOption.get.message.contains("synthetic-secret-value"))
      }
    }
  }

  test("unwrap SDK transport causes and redact timeout and oversized-response failures") {
    List(
      (new IOException("synthetic-secret-value"), Error.TransportUnavailable),
      (new TimeoutException("synthetic-secret-value"), Error.TimedOut),
      (new ConnectTimeoutException("synthetic-secret-value"), Error.TimedOut),
      (new SslHandshakeTimeoutException("synthetic-secret-value"), Error.TimedOut),
      (ReadTimeoutException.INSTANCE, Error.TimedOut),
      (new SsoResponseLimit.Exceeded, Error.InvalidResponse)
    ).traverse_ { (cause, expected) =>
      val error = SdkClientException.builder().message("synthetic-secret-value").cause(cause).build()
      client(createToken = _ => CompletableFuture.failedFuture(error)).refresh(material).map { result =>
        assertEquals(result, Left(expected))
        assert(!result.swap.toOption.get.message.contains("synthetic-secret-value"))
      }
    }
  }

  test("defer SDK invocation and classify synchronous SDK exceptions") {
    val calls = new AtomicInteger
    val api   = client(createToken = _ => {
      calls.incrementAndGet()
      throw SdkClientException.create("synthetic-secret-value")
    })
    val load  = api.refresh(material)
    assertEquals(calls.get(), 0)
    load.map { result =>
      assertEquals(calls.get(), 1)
      assertEquals(result, Left(Error.TransportUnavailable))
    }
  }

  test("cancel the SDK future when the Cats Effect caller is canceled") {
    val started = new CountDownLatch(1)
    val pending = new CompletableFuture[CreateTokenResponse]
    val api     = client(createToken = _ => { started.countDown(); pending })
    for {
      fiber   <- api.refresh(material).start
      _       <- IO.blocking(assert(started.await(3, TimeUnit.SECONDS), "SDK call did not start"))
      _       <- fiber.cancel
      outcome <- fiber.join
    } yield {
      assert(outcome.isCanceled)
      assert(pending.isCancelled)
    }
  }

  private def transport(
    opened: AtomicInteger,
    closed: AtomicInteger,
    record: URI => Unit = _ => ()
  ): Resource[IO, SdkAsyncHttpClient] = Resource.fromAutoCloseable(IO.delay {
    opened.incrementAndGet()
    new SdkAsyncHttpClient {
      override def execute(request: AsyncExecuteRequest): CompletableFuture[Void] = {
        record(request.request().getUri)
        val error = new IOException("synthetic transport failure")
        request.responseHandler().onError(error)
        CompletableFuture.failedFuture(error)
      }
      override def close(): Unit                                                  = { closed.incrementAndGet(); () }
    }
  })

  test("acquire the shared transport lazily once and close it when the client resource is released") {
    val opened   = new AtomicInteger
    val closed   = new AtomicInteger
    val resource = SsoApiClient.resourceWithTransport(profile.session.region, transport(opened, closed), 30.seconds)
    assertEquals(opened.get(), 0)
    resource
      .use { _ =>
        IO {
          assertEquals(opened.get(), 1)
          assertEquals(closed.get(), 0)
        }
      }
      .map(_ => assertEquals(closed.get(), 1))
  }

  test("resolve both service endpoints from the SSO region and share one owned transport") {
    val opened   = new AtomicInteger
    val closed   = new AtomicInteger
    val requests = new ConcurrentLinkedQueue[URI]
    SsoApiClient
      .resourceWithTransport(
        profile.session.region,
        transport(opened, closed, uri => { requests.add(uri); () }),
        30.seconds
      )
      .use { api =>
        for {
          credentials <- api.getRoleCredentials(profile, secret("synthetic-access-token"))
          refreshed   <- api.refresh(material)
        } yield {
          assertEquals(credentials, Left(Error.TransportUnavailable))
          assertEquals(refreshed, Left(Error.TransportUnavailable))
          assertEquals(opened.get(), 1)
          assertEquals(closed.get(), 0)
          assertEquals(
            requests.iterator().asScala.map(uri => uri.getHost -> uri.getPath).toList,
            List(
              "portal.sso.eu-central-1.amazonaws.com" -> "/federation/credentials",
              "oidc.eu-central-1.amazonaws.com"       -> "/token"
            )
          )
        }
      }
      .map(_ => assertEquals(closed.get(), 1))
  }

  test("release the owned transport when the client resource scope is canceled") {
    val opened = new AtomicInteger
    val closed = new AtomicInteger
    for {
      ready   <- Deferred[IO, Unit]
      fiber   <- SsoApiClient
                   .resourceWithTransport(profile.session.region, transport(opened, closed), 30.seconds)
                   .use(_ => ready.complete(()) *> IO.never[Unit])
                   .start
      _       <- ready.get
      _       <- fiber.cancel
      outcome <- fiber.join
    } yield {
      assert(outcome.isCanceled)
      assertEquals(opened.get(), 1)
      assertEquals(closed.get(), 1)
    }
  }

  test("release the owned transport when the second service client fails acquisition") {
    val opened = new AtomicInteger
    val closed = new AtomicInteger
    SsoApiClient
      .resourceWithTransport(
        profile.session.region,
        transport(opened, closed),
        30.seconds,
        oidcEndpoint = Some(URI.create("relative-endpoint"))
      )
      .use(_ => IO.unit)
      .attempt
      .map { result =>
        assert(result.isLeft)
        assertEquals(opened.get(), 1)
        assertEquals(closed.get(), 1)
      }
  }
}
