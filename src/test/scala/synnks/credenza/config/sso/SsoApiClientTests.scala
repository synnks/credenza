package synnks.credenza.config.sso

import cats.data.Kleisli
import cats.effect.{ IO, Ref }
import munit.CatsEffectSuite
import org.http4s.{ Headers, HttpApp, Method, Request, Response, Status, Uri }
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import com.comcast.ip4s.*
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import SsoCachedToken.{ RefreshMaterial, Secret }

import java.time.Instant
import java.io.IOException
import scala.concurrent.duration.*

class SsoApiClientTests extends CatsEffectSuite {
  import SsoApiClient.*

  private def valid[A](result: Either[ValueError, A]): A = result.fold(e => fail(e.expected), identity)
  private def secret(value: String): Secret              = Secret.from(value).getOrElse(fail("Expected nonempty test secret"))

  private val profile = SsoProfile(
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

  final private case class Seen(method: Method, uri: Uri, headers: Headers, body: String) {
    def header(name: String): Option[String] = headers.headers.find(_.name == CIString(name)).map(_.value)
  }

  private def withServer(app: HttpApp[IO])(use: (Uri, Client[IO]) => IO[Unit]): IO[Unit] = {
    val resource = for {
      server <- EmberServerBuilder.default[IO].withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(app).build
      client <- EmberClientBuilder.default[IO].build
    } yield (server, client)

    resource.use { (server, client) =>
      use(server.baseUri, client)
    }
  }

  private def stub(status: Status, body: String): SsoApiClient = {
    val app: HttpApp[IO] = Kleisli(_ => IO.pure(Response[IO](status).withEntity(body)))
    val uri              = Uri.unsafeFromString("http://local.test")
    new SsoApiClient(Client.fromHttpApp(app), uri, uri)
  }

  private def recordingApp(seen: Ref[IO, Option[Seen]], response: String): HttpApp[IO] =
    Kleisli { request =>
      request.as[String].flatMap { body =>
        seen
          .set(Some(Seen(request.method, request.uri, request.headers, body)))
          .as(Response[IO](Status.Ok).withEntity(response))
      }
    }

  test("GetRoleCredentials sends only the SSO bearer token and decodes epoch-millisecond expiry") {
    val response = """{"roleCredentials":{"accessKeyId":"synthetic-key-id","secretAccessKey":"synthetic-secret-key",
                   |"sessionToken":"synthetic-session-token","expiration":1893456000000}}""".stripMargin
    for {
      seen     <- Ref.of[IO, Option[Seen]](None)
      _        <- withServer(recordingApp(seen, response)) { (base, client) =>
                    new SsoApiClient(client, base, base)
                      .getRoleCredentials(profile, secret("synthetic-access-token"))
                      .flatMap(result =>
                        IO {
                          val role = result.toOption.getOrElse(fail("Expected role credentials"))
                          assertEquals(role.accessKeyId.value, "synthetic-key-id")
                          assertEquals(role.expiration, Instant.parse("2030-01-01T00:00:00Z"))
                          assert(!role.toString.contains("synthetic-secret-key"))
                        }
                      )
                  }
      observed <- seen.get
    } yield {
      val request = observed.getOrElse(fail("Expected a request"))
      assertEquals(request.method, Method.GET)
      assertEquals(request.uri.path.renderString, "/federation/credentials")
      assertEquals(request.uri.query.params, Map("account_id" -> "000011112222", "role_name" -> "Admin+ReadOnly"))
      assertEquals(request.header("x-amz-sso_bearer_token"), Some("synthetic-access-token"))
      assertEquals(request.header("Authorization"), None)
      assertEquals(request.header("X-Amz-Date"), None)
      assertEquals(request.body, "")
    }
  }

  test("CreateToken sends the refresh grant as JSON without IAM signing") {
    val response = """{"accessToken":"synthetic-new-token","expiresIn":3600,"refreshToken":"synthetic-new-refresh"}"""
    for {
      seen     <- Ref.of[IO, Option[Seen]](None)
      _        <- withServer(recordingApp(seen, response)) { (base, client) =>
                    new SsoApiClient(client, base, base)
                      .refresh(material)
                      .flatMap(result =>
                        IO {
                          val refreshed = result.toOption.getOrElse(fail("Expected refreshed token"))
                          assertEquals(refreshed.accessToken.value, "synthetic-new-token")
                          assertEquals(refreshed.expiresIn, 1.hour)
                          assertEquals(refreshed.refreshToken.map(_.value), Some("synthetic-new-refresh"))
                          assert(!refreshed.toString.contains("synthetic-new-token"))
                        }
                      )
                  }
      observed <- seen.get
    } yield {
      val request = observed.getOrElse(fail("Expected a request"))
      assertEquals(request.method, Method.POST)
      assertEquals(request.uri.path.renderString, "/token")
      assertEquals(request.header("Content-Type"), Some("application/json"))
      assertEquals(request.header("Authorization"), None)
      assertEquals(request.header("X-Amz-Date"), None)
      val body    = io.circe.parser.parse(request.body).getOrElse(fail("Expected JSON request body"))
      assertEquals(body.hcursor.get[String]("grantType").toOption, Some("refresh_token"))
      assertEquals(body.hcursor.get[String]("clientId").toOption, Some("synthetic-client-id"))
      assertEquals(body.hcursor.get[String]("clientSecret").toOption, Some("synthetic-client-secret"))
      assertEquals(body.hcursor.get[String]("refreshToken").toOption, Some("synthetic-refresh-token"))
    }
  }

  test("classify authorization, invalid grant, and throttling without exposing service responses") {
    val sensitiveBody = """{"error":"invalid_grant","error_description":"synthetic-secret-value"}"""
    for {
      unauthorized  <- stub(Status.Unauthorized, sensitiveBody).getRoleCredentials(profile, secret("access"))
      invalidGrant  <- stub(Status.BadRequest, sensitiveBody).refresh(material)
      invalidClient <- stub(Status.BadRequest, """{"error":"invalid_client"}""").refresh(material)
      throttled     <- stub(Status.TooManyRequests, sensitiveBody).refresh(material)
    } yield {
      assertEquals(unauthorized, Left(Error.AuthRequired))
      assertEquals(invalidGrant, Left(Error.InvalidGrant))
      assertEquals(invalidClient, Left(Error.AuthRequired))
      assertEquals(throttled, Left(Error.Throttled))
      assert(!invalidGrant.toString.contains("synthetic-secret-value"))
      assert(!invalidGrant.swap.toOption.get.message.contains("synthetic-secret-value"))
    }
  }

  test("reject invalid success bodies and handle absent or null replacement refresh tokens") {
    for {
      invalidRole       <- stub(Status.Ok, "{}").getRoleCredentials(profile, secret("access"))
      invalidToken      <- stub(Status.Ok, """{"accessToken":"synthetic-new-token","expiresIn":0}""").refresh(material)
      nullRefresh       <- stub(Status.Ok, """{"accessToken":"synthetic-new-token","expiresIn":10,"refreshToken":null}""")
                             .refresh(material)
      refreshed         <- stub(Status.Ok, """{"accessToken":"synthetic-new-token","expiresIn":10}""").refresh(material)
      failure           <- stub(Status.InternalServerError, "synthetic-secret-value").refresh(material)
      misleadingFailure <- stub(Status.InternalServerError, """{"error":"invalid_grant"}""").refresh(material)
    } yield {
      assertEquals(invalidRole, Left(Error.InvalidResponse))
      assertEquals(invalidToken, Left(Error.InvalidResponse))
      val nullToken    = nullRefresh.toOption.getOrElse(fail("Expected a token with no replacement"))
      val omittedToken = refreshed.toOption.getOrElse(fail("Expected a token with no replacement"))
      assertEquals(nullToken.refreshToken, None)
      assertEquals(omittedToken.refreshToken, None)
      assertEquals(failure, Left(Error.RemoteFailure(Status.InternalServerError)))
      assertEquals(misleadingFailure, Left(Error.RemoteFailure(Status.InternalServerError)))
      assert(!failure.toString.contains("synthetic-secret-value"))
    }
  }

  test("classify transport failures without returning exception text") {
    val app: HttpApp[IO] = Kleisli(_ => IO.raiseError(new IOException("synthetic-secret-value")))
    val base             = Uri.unsafeFromString("http://local.test")
    val client           = new SsoApiClient(Client.fromHttpApp(app), base, base)

    client.refresh(material).map { result =>
      assertEquals(result, Left(Error.TransportUnavailable))
      assert(!result.toString.contains("synthetic-secret-value"))
    }
  }
}
