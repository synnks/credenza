package synnks.credenza.config.sso

import cats.effect.{ IO, Ref, Resource }
import cats.syntax.all.*
import fs2.io.net.tls.{ S2nConfig, SSLException, TLSContext }
import io.circe.syntax.*
import munit.{ AnyFixture, Assertions, CatsEffectSuite }
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{ Method, Status, Uri }
import org.typelevel.ci.CIString
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.sso.SsoApiClient.Error
import synnks.credenza.config.sso.SsoApiClientIntegrationTests.*
import synnks.credenza.config.sso.SsoCachedToken.{ RefreshMaterial, Secret }
import synnks.credenza.config.sso.WireMock.StringMatch.{ Absent, EqualTo }
import synnks.credenza.config.sso.WireMock.{ JsonMatch, RequestPattern, StubResponse }

import java.time.Instant
import scala.concurrent.duration.*

class SsoApiClientIntegrationTests extends CatsEffectSuite {

  override val munitIOTimeout: FiniteDuration = 2.minutes

  private val connection = ResourceSuiteLocalFixture("https", HttpsTestConnection.resource)

  override def munitFixtures: Seq[AnyFixture[?]] = List(connection)

  private def client(trust: Trust = Trust.TestCa): Resource[IO, Client[IO]] =
    SsoApiClientIntegrationTests.client(connection(), trust)

  private def integration(name: String)(body: => IO[Unit]): Unit = test(name)(body.timeout(35.seconds))

  private def endpoint(scenario: String): Uri = connection().baseUri.withPath(connection().baseUri.path / scenario)

  integration("GetRoleCredentials sends the exact unsigned request over verified HTTPS") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("role")
      val request  = RequestPattern(
        Method.GET,
        base.path / "federation" / "credentials",
        headers = unsigned + (CIString("x-amz-sso_bearer_token") -> EqualTo("synthetic-access-token")),
        queryParameters = Map("account_id" -> EqualTo("000011112222"), "role_name" -> EqualTo("Admin+ReadOnly"))
      )
      for {
        _        <- wiremock.stub(request, StubResponse.json(Status.Ok, SsoApiFixtures.role))
        result   <- new SsoApiClient(http, base, base).getRoleCredentials(profile, secret("synthetic-access-token"))
        recorded <- wiremock.recordedRequests(request)
      } yield {
        assertEquals(recorded.size, 1)
        assertEquals(recorded.head.method, Method.GET)
        assertEquals(recorded.head.url.path, request.urlPath)
        assertEquals(
          recorded.head.url.query.params,
          Map("account_id" -> "000011112222", "role_name" -> "Admin+ReadOnly")
        )
        assert(recorded.head.body.forall(_.isEmpty))
        val credentials = result.toOption.getOrElse(fail(s"Expected role credentials, got ${result.left.toOption}"))
        assertEquals(credentials.accessKeyId.value, SsoApiFixtures.role.roleCredentials.accessKeyId)
        assertEquals(credentials.secretAccessKey.value, SsoApiFixtures.role.roleCredentials.secretAccessKey)
        assertEquals(credentials.sessionToken.value, SsoApiFixtures.role.roleCredentials.sessionToken)
        assertEquals(credentials.expiration, Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals(credentials.toString, "RoleCredentials(<redacted>)")
      }
    }
  }

  integration("CreateToken sends the exact unsigned JSON grant over verified HTTPS") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("refresh")
      for {
        _      <- wiremock.stub(refreshRequest(base), StubResponse.json(Status.Ok, SsoApiFixtures.rotatedToken))
        result <- new SsoApiClient(http, base, base).refresh(material)
      } yield {
        val token = result.toOption.getOrElse(fail(s"Expected refreshed token, got ${result.left.toOption}"))
        assertEquals(token.accessToken.value, SsoApiFixtures.rotatedToken.accessToken)
        assertEquals(token.expiresIn, 1.hour)
        assertEquals(token.refreshToken.map(_.value), Some(SsoApiFixtures.rotatedToken.refreshToken))
        assertEquals(token.toString, "RefreshedToken(<redacted>)")
      }
    }
  }

  errors.foreach { scenario =>
    integration(s"HTTPS classifies ${scenario.name} without exposing the service response") {
      client().use { http =>
        val wiremock = new WireMock(http, connection().adminUri)
        val base     = endpoint(scenario.name)
        for {
          _      <- wiremock.stub(refreshRequest(base), StubResponse.json(scenario.status, scenario.response))
          result <- new SsoApiClient(http, base, base).refresh(material)
        } yield {
          assertEquals(result, Left(scenario.expected))
          assert(!result.toString.contains(scenario.response.error_description))
          assert(!result.swap.toOption.get.message.contains(scenario.response.error_description))
        }
      }
    }
  }

  integration("malformed successful HTTPS responses fail decoding") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("not-json")
      wiremock.stub(refreshRequest(base), StubResponse.raw(Status.Ok, "{")) *>
        new SsoApiClient(http, base, base)
          .refresh(material)
          .map(result => assertEquals(result, Left(Error.InvalidResponse)))
    }
  }

  integration("HTTPS rejects a non-positive token lifetime") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("invalid-expiry")
      wiremock.stub(refreshRequest(base), StubResponse.json(Status.Ok, SsoApiFixtures.token.copy(expiresIn = 0))) *>
        new SsoApiClient(http, base, base)
          .refresh(material)
          .map(result => assertEquals(result, Left(Error.InvalidResponse)))
    }
  }

  integration("HTTPS refresh accepts an omitted replacement refresh token") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("no-replacement")
      for {
        _      <- wiremock.stub(refreshRequest(base), StubResponse.json(Status.Ok, SsoApiFixtures.token))
        result <- new SsoApiClient(http, base, base).refresh(material)
      } yield assertEquals(result.toOption.map(_.refreshToken), Some(None))
    }
  }

  integration("a delayed HTTPS response returns a typed timeout") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("timeout")
      wiremock.stub(refreshRequest(base), StubResponse.json(Status.Ok, SsoApiFixtures.token, delay = 10.seconds)) *>
        new SsoApiClient(http, base, base)
          .refresh(material)
          .map(result => assertEquals(result, Left(Error.TimedOut)))
    }
  }

  integration("HTTPS rejects an oversized successful response before decoding") {
    client().use { http =>
      val wiremock = new WireMock(http, connection().adminUri)
      val base     = endpoint("oversized")
      val body     = SsoApiFixtures.token.asJson.noSpaces + " " * SsoApiClient.MaxResponseBytes
      wiremock.stub(refreshRequest(base), StubResponse.raw(Status.Ok, body)) *>
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
    secret(SsoApiFixtures.grant.clientId),
    secret(SsoApiFixtures.grant.clientSecret),
    secret(SsoApiFixtures.grant.refreshToken),
    Instant.parse("2030-06-01T00:00:00Z")
  )

  private enum Trust {
    case TestCa, System
  }

  private def client(connection: HttpsTestConnection, trust: Trust): Resource[IO, Client[IO]] = trust match {
    case Trust.TestCa =>
      for {
        config <- S2nConfig.builder.withWipedTrustStore.withPemsToTrustStore(List(connection.caPem)).build[IO]
        http   <- EmberClientBuilder
                    .default[IO]
                    .withTLSContext(TLSContext.Builder.forAsync[IO].fromS2nConfig(config))
                    .withTimeout(5.seconds)
                    .build
      } yield http
    case Trust.System => EmberClientBuilder.default[IO].withTimeout(5.seconds).build
  }

  private val unsigned: Map[CIString, WireMock.StringMatch] =
    Map(CIString("Authorization") -> Absent, CIString("X-Amz-Date") -> Absent)

  private def refreshRequest(base: Uri): RequestPattern = RequestPattern(
    Method.POST,
    base.path / "token",
    headers = unsigned + (CIString("Content-Type") -> EqualTo("application/json")),
    bodyPatterns = List(JsonMatch.equalTo(SsoApiFixtures.grant))
  )

  private case class ErrorCase(name: String, status: Status, response: SsoApiFixtures.ErrorResponse, expected: Error)

  private val errors = List(
    ErrorCase("invalid-grant", Status.BadRequest, SsoApiFixtures.invalidGrant, Error.InvalidGrant),
    ErrorCase("invalid-client", Status.BadRequest, SsoApiFixtures.invalidClient, Error.AuthRequired),
    ErrorCase("unauthorized", Status.Unauthorized, SsoApiFixtures.invalidGrant, Error.AuthRequired),
    ErrorCase("throttled", Status.TooManyRequests, SsoApiFixtures.invalidGrant, Error.Throttled),
    ErrorCase(
      "remote",
      Status.InternalServerError,
      SsoApiFixtures.invalidGrant,
      Error.RemoteFailure(Status.InternalServerError)
    )
  )
}
