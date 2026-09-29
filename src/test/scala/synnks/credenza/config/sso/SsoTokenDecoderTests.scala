package synnks.credenza.config.sso

import cats.data.EitherNec
import munit.FunSuite
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.SessionName
import SsoCachedToken.Error as SsoTokenError

import java.time.Instant
import scala.io.Source
import scala.util.Using

class SsoTokenDecoderTests extends FunSuite {
  private def valid[A](value: Either[ValueError, A]): A = value.fold(e => fail(e.expected), identity)

  private val session = SsoSession(
    valid(SessionName.from("Work")),
    valid(SsoStartUrl.from("https://example.awsapps.com/start")),
    valid(Region.from("eu-central-1"))
  )
  private val fixture = Using.resource(Source.fromResource("sso-token.json"))(_.mkString)

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
    val text  = fixture.replace("2030-05-01T12:00:00UTC", "2030-05-01T12:00:00Z")
    val token = SsoTokenDecoder.decode(text, session).toOption.getOrElse(fail("Expected cached token"))
    assertEquals(token.expiresAt, Instant.parse("2030-05-01T12:00:00Z"))
  }

  test("incomplete refresh material does not invalidate a usable access token") {
    val text  = fixture.replace("  \"refreshToken\": \"synthetic-refresh-token\",\n", "")
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
    val wrongUrl = fixture.replace("https://example.awsapps.com/start", "https://other.awsapps.com/start")
    assertEquals(errors(SsoTokenDecoder.decode(wrongUrl, session)), List(SsoTokenError.SessionMismatch))
  }

  test("report malformed JSON, missing fields, malformed expiry, and missing token") {
    assertEquals(errors(SsoTokenDecoder.decode("{", session)), List(SsoTokenError.MalformedJson))
    assertEquals(errors(SsoTokenDecoder.decode("[]", session)), List(SsoTokenError.MalformedJson))
    assertEquals(
      errors(SsoTokenDecoder.decode("{}", session)).toSet,
      Set(
        SsoTokenError.MissingField("startUrl"),
        SsoTokenError.MissingField("region"),
        SsoTokenError.MissingField("accessToken"),
        SsoTokenError.MissingField("expiresAt")
      )
    )
    val text = fixture
      .replace("  \"accessToken\": \"synthetic-access-token\",\n", "")
      .replace("2030-05-01T12:00:00UTC", "not-a-date")
    assertEquals(
      errors(SsoTokenDecoder.decode(text, session)).toSet,
      Set(SsoTokenError.MissingField("accessToken"), SsoTokenError.InvalidField("expiresAt"))
    )
    assertEquals(
      errors(SsoTokenDecoder.decode(fixture.replace("synthetic-access-token", ""), session)),
      List(SsoTokenError.InvalidField("accessToken"))
    )
  }

  test("validate present but malformed optional refresh material") {
    val text = fixture
      .replace("2030-06-01T12:00:00+00:00", "bad-date")
      .replace("\"clientSecret\": \"synthetic-client-secret\"", "\"clientSecret\": null")
    assertEquals(
      errors(SsoTokenDecoder.decode(text, session)).toSet,
      Set(SsoTokenError.InvalidField("clientSecret"), SsoTokenError.InvalidField("registrationExpiresAt"))
    )
  }

  test("secret material and raw JSON stay out of diagnostic representations") {
    val token       = SsoTokenDecoder.decode(fixture, session).toOption.getOrElse(fail("Expected cached token"))
    val diagnostics =
      List(token.toString, token.accessToken.toString, token.refresh.toString, token.refresh.get.toString)
    List("synthetic-access-token", "synthetic-client-id", "synthetic-client-secret", "synthetic-refresh-token")
      .foreach(secret => assert(!diagnostics.exists(_.contains(secret))))
    val bad         = fixture.replace("synthetic-access-token", "")
    errors(SsoTokenDecoder.decode(bad, session)).foreach { error =>
      assert(!error.message.contains("synthetic-client-secret"))
      assert(!error.toString.contains("synthetic-refresh-token"))
      assert(!error.message.contains("unknownField"))
    }
  }
}
