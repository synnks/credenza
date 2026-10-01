package synnks.credenza.config.sso

import cats.data.EitherNec
import io.circe.{ Json, JsonObject }
import io.circe.parser.parse
import munit.FunSuite
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.SessionName
import SsoCachedToken.Error as SsoTokenError

import java.time.Instant
import scala.io.Source
import scala.util.Using

class SsoTokenDecoderTests extends FunSuite {
  private def valid[A](value: Either[ValueError, A]): A = value.fold(e => fail(e.expected), identity)

  private val session       = SsoSession(
    valid(SessionName.from("Work")),
    valid(SsoStartUrl.from("https://example.awsapps.com/start")),
    valid(Region.from("eu-central-1"))
  )
  private val fixture       =
    Using.resource(Source.fromInputStream(getClass.getResourceAsStream("/sso-token.json"), "UTF-8"))(_.mkString)
  private val fixtureFields =
    parse(fixture).fold(_ => fail("Invalid test fixture"), _.asObject.getOrElse(fail("Expected JSON object")))

  private def withFields(update: JsonObject => JsonObject): String = Json.fromJsonObject(update(fixtureFields)).noSpaces

  private def errors(result: EitherNec[SsoTokenError, SsoCachedToken]): List[SsoTokenError] = result match {
    case Left(values) => values.toNonEmptyList.toList
    case Right(_)     => fail("Expected token errors")
  }

  test("decode refreshable CLI tokens and timestamps with UTC and numeric offsets") {
    val token   = SsoTokenDecoder.decode(fixture, session).toOption.getOrElse(fail("Expected cached token"))
    assertEquals(token.accessToken.value, "synthetic-access-token")
    assertEquals(token.expiresAt, Instant.parse("2030-05-01T12:00:00Z"))
    val refresh = token.refresh.getOrElse(fail("Expected refresh material"))
    assertEquals(refresh.clientId.value, "synthetic-client-id")
    assertEquals(refresh.clientSecret.value, "synthetic-client-secret")
    assertEquals(refresh.refreshToken.value, "synthetic-refresh-token")
    assertEquals(refresh.registrationExpiresAt, Instant.parse("2030-06-01T12:00:00Z"))
  }

  test("decode a usable token without refresh material, including fractional timestamp and offset") {
    val text  = """{"startUrl":"https://example.awsapps.com/start","region":"eu-central-1",
                 |"accessToken":"synthetic-access-token","expiresAt":"2030-05-01T14:00:00.123+02:00"} """.stripMargin
    val token = SsoTokenDecoder.decode(text, session).toOption.getOrElse(fail("Expected cached token"))
    assertEquals(token.expiresAt, Instant.parse("2030-05-01T12:00:00.123Z"))
    assertEquals(token.refresh, None)
  }

  test("decode timestamps in UTC Z notation") {
    val text  = withFields(_.add("expiresAt", Json.fromString("2030-05-01T12:00:00Z")))
    val token = SsoTokenDecoder.decode(text, session).toOption.getOrElse(fail("Expected cached token"))
    assertEquals(token.expiresAt, Instant.parse("2030-05-01T12:00:00Z"))
  }

  test("incomplete refresh material does not invalidate a usable access token") {
    val text  = withFields(_.remove("refreshToken"))
    val token = SsoTokenDecoder.decode(text, session).toOption.getOrElse(fail("Expected cached token"))
    assertEquals(token.refresh, None)
  }

  test("expiry is checked at use, and an expired record remains readable for later refresh") {
    val token = SsoTokenDecoder.decode(fixture, session).toOption.getOrElse(fail("Expected cached token"))
    assertEquals(
      token.validAccessToken(Instant.parse("2030-05-01T11:59:59Z")).map(_.value),
      Right("synthetic-access-token")
    )
    assertEquals(token.validAccessToken(token.expiresAt), Left(SsoTokenError.ExpiredToken))
    assertEquals(token.validAccessToken(Instant.parse("2031-01-01T00:00:00Z")), Left(SsoTokenError.ExpiredToken))
  }

  test("reject metadata from another session even when the start URL matches") {
    val other    = session.copy(region = valid(Region.from("us-east-1")))
    assertEquals(errors(SsoTokenDecoder.decode(fixture, other)), List(SsoTokenError.SessionMismatch))
    val wrongUrl = withFields(_.add("startUrl", Json.fromString("https://other.awsapps.com/start")))
    assertEquals(errors(SsoTokenDecoder.decode(wrongUrl, session)), List(SsoTokenError.SessionMismatch))
  }

  test("report malformed JSON, missing fields, malformed expiry, and missing token") {
    assertEquals(errors(SsoTokenDecoder.decode("{", session)), List(SsoTokenError.MalformedJson))
    assertEquals(errors(SsoTokenDecoder.decode("[]", session)), List(SsoTokenError.MalformedJson))
    assertEquals(
      errors(SsoTokenDecoder.decode("{}", session)),
      List(
        SsoTokenError.MissingField("startUrl"),
        SsoTokenError.MissingField("region"),
        SsoTokenError.MissingField("accessToken"),
        SsoTokenError.MissingField("expiresAt")
      )
    )
    val text = withFields(_.remove("accessToken").add("expiresAt", Json.fromString("not-a-date")))
    assertEquals(
      errors(SsoTokenDecoder.decode(text, session)),
      List(SsoTokenError.MissingField("accessToken"), SsoTokenError.InvalidField("expiresAt"))
    )
    assertEquals(
      errors(SsoTokenDecoder.decode(withFields(_.add("accessToken", Json.fromString(""))), session)),
      List(SsoTokenError.InvalidField("accessToken"))
    )
  }

  test("validate present but malformed optional refresh material") {
    val text = withFields(_.add("registrationExpiresAt", Json.fromString("bad-date")).add("clientSecret", Json.Null))
    assertEquals(
      errors(SsoTokenDecoder.decode(text, session)),
      List(SsoTokenError.InvalidField("clientSecret"), SsoTokenError.InvalidField("registrationExpiresAt"))
    )
  }

  test("secret material and raw JSON stay out of diagnostic representations") {
    val token       = SsoTokenDecoder.decode(fixture, session).toOption.getOrElse(fail("Expected cached token"))
    val diagnostics =
      List(token.toString, token.accessToken.toString, token.refresh.toString, token.refresh.get.toString)
    List("synthetic-access-token", "synthetic-client-id", "synthetic-client-secret", "synthetic-refresh-token")
      .foreach(secret => assert(!diagnostics.exists(_.contains(secret))))
    val bad         = withFields(_.add("accessToken", Json.fromString("")))
    errors(SsoTokenDecoder.decode(bad, session)).foreach { error =>
      assert(!error.message.contains("synthetic-client-secret"))
      assert(!error.toString.contains("synthetic-refresh-token"))
      assert(!error.message.contains("unknownField"))
    }
  }
}
