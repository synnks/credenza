package synnks.credenza.config.sso

import io.circe.Json
import munit.FunSuite
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.ssooidc.model.SsoOidcException
import software.amazon.awssdk.thirdparty.jackson.core.JsonParseException
import synnks.credenza.config.sso.SsoApiClient.Error

import java.util.concurrent.TimeoutException

class SsoSdkErrorsTests extends FunSuite {
  test("response validation failures outrank timeouts and service status anywhere in the cause chain") {
    List(new SsoResponseLimit.Exceeded, new JsonParseException(null, "synthetic-secret-value")).foreach { invalid =>
      val timeout = new TimeoutException("synthetic-secret-value")
      timeout.initCause(invalid)
      val error   = SsoOidcException.builder().statusCode(429).message("synthetic-secret-value").cause(timeout).build()
      assertEquals(SsoSdkErrors.fromSdk(error), Error.InvalidResponse)
    }
  }

  test("a timeout behind an unrecognized wrapper outranks the service status") {
    val cause = new IllegalStateException("synthetic-secret-value", new TimeoutException("synthetic-secret-value"))
    val error = SsoOidcException.builder().statusCode(429).message("synthetic-secret-value").cause(cause).build()
    assertEquals(SsoSdkErrors.fromSdk(error), Error.TimedOut)
  }

  test("prefer the SDK error code and use the OAuth body only when SDK metadata lacks a code") {
    val body = Json.obj(
      "error"             -> Json.fromString("invalid_grant"),
      "error_description" -> Json.fromString("synthetic-secret-value")
    )
    List(
      Some("InvalidClientException") -> Error.AuthRequired,
      None                           -> Error.InvalidGrant
    ).foreach { (code, expected) =>
      val details = AwsErrorDetails.builder().rawResponse(SdkBytes.fromUtf8String(body.noSpaces))
      code.foreach(details.errorCode)
      val error   = SsoOidcException
        .builder()
        .statusCode(400)
        .message("synthetic-secret-value")
        .awsErrorDetails(details.build())
        .build()
      val result  = SsoSdkErrors.fromSdk(error)
      assertEquals(result, expected)
      assert(!result.toString.contains("synthetic-secret-value"))
      assert(!result.message.contains("synthetic-secret-value"))
    }
  }
}
