package synnks.credenza.config.reader

import munit.FunSuite
import synnks.credenza.config.model.AwsConfigError

class AwsProfileReaderTests extends FunSuite {
  private def sections(text: String): AwsProfileReader.Sections =
    AwsProfileReader.read(text).toOption.getOrElse(fail("Expected valid configuration"))

  test("raw config snapshots redact property values") {
    val secret = "synthetic-secret-value"

    assert(!AwsProfileReader.read(s"[profile staging]\naws_secret_access_key = $secret").toString.contains(secret))
  }

  test("nested properties stay beneath their parent and continuation comments remain data") {
    val text       = """[profile staging]
                 |s3 =
                 |    addressing_style = path
                 |    sso_session = other ; data
                 |sso_session = Work
                 |    # continuation data
                 |""".stripMargin
    val properties = sections(text).profiles("staging")
    assertEquals(properties("s3.addressing_style"), "path")
    assertEquals(properties("s3.sso_session"), "other ; data")
    assertEquals(properties("sso_session"), "Work\n# continuation data")
  }

  test("invalid identifiers and their continuations are ignored without hiding later valid properties") {
    val text   = """[profile invalid name]
                 |malformed ignored entry
                 |[profile staging]
                 |bad key = ignored
                 |    malformed ignored continuation
                 |sso_session = Work
                 |[sso-session Work]
                 |sso_region = eu-central-1
                 |[sso-session Unreferenced]
                 |sso_region = us-east-1
                 |""".stripMargin
    val result = sections(text)
    assertEquals(result.profiles, Map("staging" -> Map("sso_session" -> "Work")))
    assertEquals(result.sessions, Map("Work" -> Map("sso_region" -> "eu-central-1")))
  }

  test("an empty parent requires assignment-shaped nested continuations") {
    assertEquals(
      AwsProfileReader.read("[profile staging]\ns3 =\n    malformed nested entry"),
      Left(AwsConfigError.InvalidSyntax)
    )
  }
}
