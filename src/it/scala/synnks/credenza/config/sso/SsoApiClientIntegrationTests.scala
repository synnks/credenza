package synnks.credenza.config.sso

import cats.effect.{ IO, Ref, Resource }
import cats.syntax.all.*
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import io.circe.Json
import munit.{ AnyFixture, Assertions, CatsEffectSuite }
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.Uri
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.sso.SsoApiClient.Error
import synnks.credenza.config.sso.SsoApiClientIntegrationTests.*
import synnks.credenza.config.sso.SsoCachedToken.{ RefreshMaterial, Secret }

import java.time.Instant
import javax.net.ssl.SSLException
import scala.concurrent.duration.*

class SsoApiClientIntegrationTests extends CatsEffectSuite {

  override val munitIOTimeout: FiniteDuration = 2.minutes

  private val connection = ResourceSuiteLocalFixture("https", HttpsTestContainers.resource)

  override def munitFixtures: Seq[AnyFixture[?]] = List(connection)

  private def client(trust: Trust = Trust.TestCa): Resource[IO, Client[IO]] =
    SsoApiClientIntegrationTests.client(connection(), trust)

  private def integration(name: String)(body: => IO[Unit]): Unit = test(name)(body.timeout(35.seconds))

  private def endpoint(scenario: String): Uri = connection().baseUri.withPath(connection().baseUri.path / scenario)

  private def wireMock: WireMock = new WireMock(connection().adminUri.host.get.value, connection().adminUri.port.get)

  integration("GetRoleCredentials sends the exact unsigned request over verified HTTPS") {
    client().use { http =>
      val wiremock = wireMock
      val base     = endpoint("role")
      val path     = urlPathEqualTo((base.path / "federation" / "credentials").renderString)
      for {
        _      <- IO.blocking(wiremock.register(get(path).willReturn(okJson(roleResponse.noSpaces))))
        result <- new SsoApiClient(http, base, base).getRoleCredentials(profile, secret("synthetic-access-token"))
        _      <- IO.blocking {
                    wiremock.verifyThat(
                      1,
                      getRequestedFor(path)
                        .withHeader("x-amz-sso_bearer_token", equalTo("synthetic-access-token"))
                        .withHeader("Authorization", absent())
                        .withHeader("X-Amz-Date", absent())
                    )
                    val requests = wiremock.find(getRequestedFor(path))
                    assertEquals(requests.size(), 1)
                    assert(Option(requests.get(0).getBodyAsString).forall(_.isEmpty))
                    assertEquals(
                      Uri.unsafeFromString(requests.get(0).getUrl).query.params,
                      Map("account_id" -> "000011112222", "role_name" -> "Admin+ReadOnly")
                    )
                  }
      } yield {
        val credentials = result.toOption.getOrElse(fail(s"Expected role credentials, got ${result.left.toOption}"))
        assertEquals(credentials.accessKeyId.value, "synthetic-key-id")
        assertEquals(credentials.secretAccessKey.value, "synthetic-key")
        assertEquals(credentials.sessionToken.value, "synthetic-session")
        assertEquals(credentials.expiration, Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals(credentials.toString, "RoleCredentials(<redacted>)")
      }
    }
  }

  integration("CreateToken sends the exact unsigned JSON grant over verified HTTPS") {
    client().use { http =>
      val wiremock = wireMock
      val base     = endpoint("refresh")
      val path     = urlPathEqualTo((base.path / "token").renderString)
      for {
        _      <- IO.blocking(wiremock.register(post(path).willReturn(okJson(tokenResponse.noSpaces))))
        result <- new SsoApiClient(http, base, base).refresh(material)
        _      <- IO.blocking(
                    wiremock.verifyThat(
                      1,
                      postRequestedFor(path)
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withHeader("Authorization", absent())
                        .withHeader("X-Amz-Date", absent())
                        .withRequestBody(equalToJson(refreshGrant.noSpaces))
                    )
                  )
      } yield {
        val token = result.toOption.getOrElse(fail(s"Expected refreshed token, got ${result.left.toOption}"))
        assertEquals(token.accessToken.value, "synthetic-new-token")
        assertEquals(token.expiresIn, 1.hour)
        assertEquals(token.refreshToken.map(_.value), Some("synthetic-new-refresh"))
        assertEquals(token.toString, "RefreshedToken(<redacted>)")
      }
    }
  }

  integration("HTTPS decodes and redacts a service error") {
    client().use { http =>
      val base = endpoint("invalid-grant")
      for {
        _      <-
          IO.blocking(
            wireMock.register(
              post(urlPathEqualTo((base.path / "token").renderString))
                .willReturn(jsonResponse(errorResponse.noSpaces, 400))
            )
          )
        result <- new SsoApiClient(http, base, base).refresh(material)
      } yield {
        assertEquals(result, Left(Error.InvalidGrant))
        assert(!result.toString.contains("synthetic-secret"))
        assert(!result.swap.toOption.get.message.contains("synthetic-secret"))
      }
    }
  }

  integration("a delayed HTTPS response returns a typed timeout") {
    client().use { http =>
      val base = endpoint("timeout")
      IO.blocking(
        wireMock.register(
          post(urlPathEqualTo((base.path / "token").renderString))
            .willReturn(okJson(tokenResponse.noSpaces).withFixedDelay(10000))
        )
      ) *>
        new SsoApiClient(http, base, base)
          .refresh(material)
          .map(result => assertEquals(result, Left(Error.TimedOut)))
    }
  }

  integration("HTTPS rejects an oversized successful response before decoding") {
    client().use { http =>
      val base = endpoint("oversized")
      IO.blocking(
        wireMock.register(
          post(urlPathEqualTo((base.path / "token").renderString))
            .willReturn(okJson(tokenResponse.noSpaces + " " * SsoApiClient.MaxResponseBytes))
        )
      ) *>
        new SsoApiClient(http, base, base)
          .refresh(material)
          .map(result => assertEquals(result, Left(Error.InvalidResponse)))
    }
  }

  integration("HTTPS rejects an untrusted CA and redacts the TLS failure") {
    rejected(Trust.System, connection().baseUri)
  }

  integration("HTTPS rejects a trusted certificate for the wrong hostname") {
    rejected(Trust.TestCa, connection().wrongHostUri)
  }

  private def rejected(trust: Trust, base: Uri): IO[Unit] = client(trust).use { http =>
    for {
      failure <- Ref.of[IO, Option[Throwable]](None)
      observed =
        Client[IO](request => http.run(request).onError { case error => Resource.eval(failure.set(Some(error))) })
      result  <- new SsoApiClient(observed, base, base).refresh(material)
      cause   <- failure.get
    } yield {
      assertEquals(result, Left(Error.TransportUnavailable))
      assert(
        cause.exists(_.isInstanceOf[SSLException]),
        "Expected a certificate-related SSLException, not a timeout or connection failure"
      )
    }
  }

}

private object SsoApiClientIntegrationTests extends Assertions {
  private def valid[A](value: Either[ValueError, A]): A = value.fold(e => fail(e.expected), identity)
  private def secret(value: String): Secret             = Secret.from(value).getOrElse(fail("Expected nonempty test secret"))

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

  private enum Trust {
    case TestCa, System
  }

  private def client(connection: HttpsTestConnection, trust: Trust): Resource[IO, Client[IO]] = trust match {
    case Trust.TestCa =>
      for {
        context <- Resource.eval(HttpsTestCertificates.trustCertificate(connection.caPem))
        http    <- EmberClientBuilder
                     .default[IO]
                     .withTLSContext(context)
                     .withTimeout(5.seconds)
                     .build
      } yield http
    case Trust.System => EmberClientBuilder.default[IO].withTimeout(5.seconds).build
  }

  private val roleResponse  = Json.obj(
    "roleCredentials" -> Json.obj(
      "accessKeyId"     -> Json.fromString("synthetic-key-id"),
      "secretAccessKey" -> Json.fromString("synthetic-key"),
      "sessionToken"    -> Json.fromString("synthetic-session"),
      "expiration"      -> Json.fromLong(1893456000000L)
    )
  )
  private val tokenResponse = Json.obj(
    "accessToken"  -> Json.fromString("synthetic-new-token"),
    "expiresIn"    -> Json.fromInt(3600),
    "refreshToken" -> Json.fromString("synthetic-new-refresh")
  )
  private val refreshGrant  = Json.obj(
    "clientId"     -> Json.fromString("synthetic-client-id"),
    "clientSecret" -> Json.fromString("synthetic-client-secret"),
    "refreshToken" -> Json.fromString("synthetic-refresh-token"),
    "grantType"    -> Json.fromString("refresh_token")
  )
  private val errorResponse = Json.obj(
    "error"             -> Json.fromString("invalid_grant"),
    "error_description" -> Json.fromString("synthetic-secret")
  )
}
