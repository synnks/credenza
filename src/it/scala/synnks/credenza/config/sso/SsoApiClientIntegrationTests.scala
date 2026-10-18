package synnks.credenza.config.sso

import cats.effect.{ IO, Outcome, Resource }
import cats.syntax.all.*
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.circe.Json
import munit.{ AnyFixture, Assertions, CatsEffectSuite }
import sttp.model.Uri
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.sso.SsoApiClient.Error
import synnks.credenza.config.sso.HttpsTestTls.{ TransportFailure, Trust }
import synnks.credenza.config.sso.SsoApiClientIntegrationTests.*
import synnks.credenza.config.sso.SsoCachedToken.{ RefreshMaterial, Secret }

import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SsoApiClientIntegrationTests extends CatsEffectSuite {

  override val munitIOTimeout: FiniteDuration = 2.minutes

  private val connection = ResourceSuiteLocalFixture("https", HttpsTestContainers.resource)

  override def munitFixtures: Seq[AnyFixture[?]] = List(connection)

  private def integration(name: String)(body: => IO[Unit]): Unit = test(name)(body.timeout(scenarioTimeout))

  integration("GetRoleCredentials sends the exact unsigned request over verified HTTPS") {
    withClient("role") { (base, api) =>
      val path = urlPathEqualTo(base.addPath("federation", "credentials").pathToString)
      for {
        _        <- stub(get(path).willReturn(okJson(roleResponse.noSpaces)))
        result   <- api.getRoleCredentials(profile, secret("synthetic-access-token"))
        _        <- verifyRequest(
                      getRequestedFor(path)
                        .withHeader("x-amz-sso_bearer_token", equalTo("synthetic-access-token"))
                        .withHeader("Authorization", absent())
                        .withHeader("X-Amz-Date", absent())
                        .withQueryParam("account_id", equalTo("000011112222"))
                        .withQueryParam("role_name", equalTo("Admin+ReadOnly"))
                    )
        requests <- recordedRequests(getRequestedFor(path))
      } yield {
        val credentials = success(result)
        assertEquals(requests, List(RecordedRequest(bodyIsEmpty = true, Set("account_id", "role_name"))))
        assertEquals(credentials.accessKeyId.value, "synthetic-key-id")
        assertEquals(credentials.secretAccessKey.value, "synthetic-key")
        assertEquals(credentials.sessionToken.value, "synthetic-session")
        assertEquals(credentials.expiration, Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals(credentials.toString, "RoleCredentials(<redacted>)")
      }
    }
  }

  integration("CreateToken sends the exact unsigned JSON grant over verified HTTPS") {
    withClient("refresh") { (base, api) =>
      val path = urlPathEqualTo(base.addPath("token").pathToString)
      for {
        _      <- stub(post(path).willReturn(okJson(tokenResponse.noSpaces)))
        result <- api.refresh(material)
        _      <- verifyRequest(
                    postRequestedFor(path)
                      .withHeader("Content-Type", equalTo("application/json"))
                      .withHeader("Authorization", absent())
                      .withHeader("X-Amz-Date", absent())
                      .withRequestBody(equalToJson(refreshGrant.noSpaces))
                  )
      } yield {
        val token = success(result)
        assertEquals(token.accessToken.value, "synthetic-new-token")
        assertEquals(token.expiresIn, 1.hour)
        assertEquals(token.refreshToken.map(_.value), Some("synthetic-new-refresh"))
        assertEquals(token.toString, "RefreshedToken(<redacted>)")
      }
    }
  }

  List(
    ("oauth-error", "an OAuth error body", jsonResponse(errorResponse.noSpaces, 400)),
    (
      "aws-exception",
      "AWS exception metadata",
      jsonResponse(errorResponse.noSpaces, 400).withHeader("x-amzn-ErrorType", "InvalidGrantException")
    )
  ).foreach { (scenario, source, response) =>
    integration(s"HTTPS decodes and redacts a service error from $source") {
      withClient(scenario) { (base, api) =>
        for {
          _      <- stub(post(urlPathEqualTo(base.addPath("token").pathToString)).willReturn(response))
          result <- api.refresh(material)
        } yield {
          val error = result.swap.getOrElse(fail("Expected an invalid refresh grant"))
          assertEquals(error, Error.InvalidGrant)
          assert(!result.toString.contains("synthetic-secret"))
          assert(!error.message.contains("synthetic-secret"))
        }
      }
    }
  }

  integration("HTTPS reports malformed successful response JSON as an invalid response") {
    withClient("malformed") { (base, api) =>
      val malformed = tokenResponse.noSpaces.dropRight(1)
      for {
        _      <- stub(post(urlPathEqualTo(base.addPath("token").pathToString)).willReturn(okJson(malformed)))
        result <- api.refresh(material)
      } yield assertEquals(result, Left(Error.InvalidResponse))
    }
  }

  integration("a delayed HTTPS response returns a typed timeout") {
    withClient("timeout") { (base, api) =>
      for {
        _      <- stub(
                    post(urlPathEqualTo(base.addPath("token").pathToString))
                      .willReturn(okJson(tokenResponse.noSpaces).withFixedDelay(delayedResponse.toMillis.toInt))
                  )
        result <- api.refresh(material)
      } yield assertEquals(result, Left(Error.TimedOut))
    }
  }

  List(("successful", 200, tokenResponse), ("service-error", 400, errorResponse)).foreach { (kind, status, body) =>
    integration(s"HTTPS rejects an oversized $kind response and permits a subsequent request") {
      withClient(s"oversized-$kind") { (base, api) =>
        val path = urlPathEqualTo(base.addPath("token").pathToString)
        for {
          _      <- stub(
                      post(path)
                        .atPriority(2)
                        .willReturn(jsonResponse(body.noSpaces + " " * SsoApiClient.MaxResponseBytes, status))
                    )
          result <- api.refresh(material)
          _      <- stub(post(path).atPriority(1).willReturn(okJson(tokenResponse.noSpaces)))
          next   <- api.refresh(material).timeout(connectionRecoveryTimeout)
        } yield {
          assertEquals(result, Left(Error.InvalidResponse))
          assertEquals(success(next).accessToken.value, "synthetic-new-token")
        }
      }
    }
  }

  integration("HTTPS rejects an untrusted CA and redacts the TLS failure") {
    rejected(Trust.System, connection().baseUri)
  }

  integration("canceling an SDK request releases the connection for a subsequent request") {
    withClient("cancellation") { (base, api) =>
      val path = urlPathEqualTo(base.addPath("token").pathToString)
      for {
        _       <- stub(
                     post(path)
                       .atPriority(2)
                       .willReturn(okJson(tokenResponse.noSpaces).withFixedDelay(delayedResponse.toMillis.toInt))
                   )
        outcome <- cancelAfterRequest(api.refresh(material), postRequestedFor(path))
        _       <- stub(post(path).atPriority(1).willReturn(okJson(tokenResponse.noSpaces)))
        next    <- api.refresh(material).timeout(connectionRecoveryTimeout)
      } yield {
        assert(outcome.isCanceled)
        assertEquals(success(next).accessToken.value, "synthetic-new-token")
      }
    }
  }

  integration("HTTPS rejects a trusted certificate for the wrong hostname") {
    rejected(Trust.TestCa, connection().wrongHostUri)
  }

  private def withClient[A](scenario: String)(run: (Uri, SsoApiClient) => IO[A]): IO[A] = {
    val base = connection().baseUri.addPath(scenario)
    HttpsTestTls.client(connection(), profile.session.region, base, apiTimeout).use((api, _) => run(base, api))
  }

  private def wireMock: WireMock = new WireMock(connection().adminHost, connection().adminPort)

  private def stub(mapping: MappingBuilder): IO[Unit] = IO.blocking(wireMock.register(mapping)).void

  private def verifyRequest(request: RequestPatternBuilder): IO[Unit] = IO.blocking(wireMock.verifyThat(1, request))

  private def recordedRequests(request: RequestPatternBuilder): IO[List[RecordedRequest]] = IO.blocking {
    wireMock
      .find(request)
      .asScala
      .iterator
      .map { logged =>
        RecordedRequest(Option(logged.getBodyAsString).forall(_.isEmpty), logged.getQueryParams.keySet().asScala.toSet)
      }
      .toList
  }

  private def awaitRequest(request: RequestPatternBuilder): IO[Unit] =
    (IO.sleep(requestPollInterval) *> IO.blocking(!wireMock.find(request).isEmpty))
      .iterateUntil(identity)
      .void
      .timeoutTo(observationTimeout, IO(fail("WireMock did not receive the request before cancellation")))

  private def cancelAfterRequest[A](request: IO[A], matcher: RequestPatternBuilder): IO[Outcome[IO, Throwable, A]] =
    Resource.make(request.start)(_.cancel).use { fiber =>
      awaitRequest(matcher) *> fiber.cancel *> fiber.join
    }

  private def rejected(trust: Trust, base: Uri): IO[Unit] =
    HttpsTestTls.client(connection(), profile.session.region, base, apiTimeout, trust).use { (api, failure) =>
      for {
        result <- api.refresh(material)
        cause  <- failure.timeout(observationTimeout)
      } yield {
        val error = result.swap.getOrElse(fail("Expected a TLS failure"))
        assertEquals(error, Error.TransportUnavailable)
        assertEquals(error.message, "Could not reach the SSO service.")
        assertEquals(cause, TransportFailure.Tls)
      }
    }

}

private object SsoApiClientIntegrationTests extends Assertions {
  private val apiTimeout                = 5.seconds
  private val scenarioTimeout           = 35.seconds
  private val delayedResponse           = 10.seconds
  private val observationTimeout        = 3.seconds
  private val requestPollInterval       = 20.millis
  private val connectionRecoveryTimeout = 2.seconds

  private def valid[A](value: Either[ValueError, A]): A = value.fold(e => fail(e.expected), identity)
  private def secret(value: String): Secret             = Secret.from(value).getOrElse(fail("Expected nonempty test secret"))
  private def success[A](result: Either[Error, A]): A   =
    result.fold(error => fail(s"Expected success, got ${error.message}"), identity)

  final private case class RecordedRequest(bodyIsEmpty: Boolean, queryParameters: Set[String])

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
