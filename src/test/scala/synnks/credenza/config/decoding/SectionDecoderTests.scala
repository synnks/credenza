package synnks.credenza.config.decoding

import cats.syntax.all.*
import munit.FunSuite
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.ProfileName
import synnks.credenza.config.model.AwsConfigError.Section as ConfigSection

class SectionDecoderTests extends FunSuite {
  import SectionDecoder.*

  private val name    = ProfileName.from("staging").fold(error => fail(error.expected), identity)
  private val section = ConfigSection.Profile(name)

  test("the account field determines the decoder's result type") {
    val decoder: Decoder[AccountId] = required(Field.accountId)
    val input                       = Input(section, Map("sso_account_id" -> "000011112222"))

    assertEquals(decoder.run(input).map(_.value).toEither, Right("000011112222"))
    assert(compileErrors("val wrong: Decoder[Region] = required(Field.accountId)").nonEmpty)
  }

  test("required fields treat both missing and empty values as missing") {
    for (properties <- List(Map.empty[String, String], Map("sso_account_id" -> ""))) {
      val errors = required(Field.accountId).run(Input(section, properties)).toEither.left.map(_.toNonEmptyList.toList)
      assertEquals(errors, Left(List(AwsConfigError.MissingSetting(section, ConfigField.SsoAccountId))))
    }
  }

  test("section decoders compose over immutable input and accumulate errors without the SDK") {
    val decoder = (required(Field.accountId), optional(Field.region)).tupled
    val input   = Input(section, Map("sso_account_id" -> "123", "region" -> "bad region"))

    val result = decoder.run(input).toEither.left.map(_.toNonEmptyList.toList)
    assertEquals(
      result,
      Left(
        List(
          AwsConfigError.InvalidSetting(section, ConfigField.SsoAccountId, ValueError.InvalidAccountId),
          AwsConfigError.InvalidSetting(section, ConfigField.Region, ValueError.InvalidRegion)
        )
      )
    )
  }

  test("raw section representations redact property values") {
    val secret = "synthetic-secret-value"
    val input  = Input(section, Map("aws_secret_access_key" -> secret))

    assert(!input.toString.contains(secret))
  }
}
