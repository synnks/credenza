package synnks.credenza.config.model

import munit.FunSuite
import ConfigNames.{ ProfileName, SessionName }

class ProfileValuesTests extends FunSuite {
  test("account IDs preserve leading zeroes and require twelve ASCII digits") {
    assertEquals(AccountId.from("000011112222").map(_.value), Right("000011112222"))
    for (value <- List("", "123", "1234567890123", "abcdefghijkl", "0000 11112222"))
      assertEquals(AccountId.from(value), Left(ValueError.InvalidAccountId))
  }

  test("profile and session names preserve case and AWS identifier characters") {
    val value = "Work/admin@example.com:read+only"
    assertEquals(ProfileName.from(value).map(_.value), Right(value))
    assertEquals(SessionName.from(value).map(_.value), Right(value))
    for (invalid <- List("", " Work", "Work ", "Work Team", "Work\nother")) {
      assertEquals(ProfileName.from(invalid), Left(ValueError.InvalidIdentifier))
      assertEquals(SessionName.from(invalid), Left(ValueError.InvalidIdentifier))
    }
  }

  test("role names must be nonempty and contain no whitespace or control characters") {
    assertEquals(RoleName.from("ReadOnlyAccess").map(_.value), Right("ReadOnlyAccess"))
    for (value <- List("", " ", "Read Only", "ReadOnly\nother", "ReadOnly\u0000"))
      assertEquals(RoleName.from(value), Left(ValueError.InvalidRoleName))
  }

  test("region values preserve future naming conventions without trying to verify AWS availability") {
    for (
      value <- List(
                 "eu-central-1",
                 "us-gov-west-1",
                 "cn-north-1",
                 "us-isob-east-1",
                 "eusc-de-east-1",
                 "us-east",
                 "future_region"
               )
    )
      assertEquals(Region.from(value).map(_.value), Right(value))
    for (value <- List("", " ", "not a region", "us-east-1\nother", "us-east-1\u0000"))
      assertEquals(Region.from(value), Left(ValueError.InvalidRegion))
  }

  test("start URLs require HTTPS without user information and preserve their original spelling") {
    for (value <- List("https://aws.example.com", "https://Example.awsapps.com/a%2fb?view=roles#landing"))
      assertEquals(SsoStartUrl.from(value).map(_.value), Right(value))
    for (
      value <- List(
                 "http://example.com/start",
                 "/start",
                 "https:///start",
                 "https://user:password@example.com/start",
                 "not a URL"
               )
    )
      assertEquals(SsoStartUrl.from(value), Left(ValueError.InvalidStartUrl))
  }

  test("domain values cannot be substituted for other identifiers or constructed from raw strings") {
    assert(compileErrors("val id: AccountId = Region.from(\"us-east-1\").toOption.get").nonEmpty)
    assert(compileErrors("val name: ProfileName = SessionName.from(\"Work\").toOption.get").nonEmpty)
    assert(compileErrors("val id: AccountId = \"000011112222\"").nonEmpty)
  }
}
