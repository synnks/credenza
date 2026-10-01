package synnks.credenza.config.reader

import munit.FunSuite
import synnks.credenza.config.model.AwsConfigError

import scala.io.Source
import scala.util.Using

class AwsProfileReaderCompatibilityTests extends FunSuite {
  private val fixture =
    Using.resource(Source.fromInputStream(getClass.getResourceAsStream("/aws-config"), "UTF-8"))(_.mkString)

  private type Result = Either[AwsConfigError, AwsProfileReader.Sections]

  private def profile(name: String, properties: Map[String, String]): Result =
    Right(AwsProfileReader.Sections(Map(name -> properties), Map.empty))

  private val sessionProfile: Result = Right(
    AwsProfileReader.Sections(
      Map("staging" -> Map("sso_session" -> "Work")),
      Map("Work"    -> Map("sso_region" -> "eu-central-1"))
    )
  )

  private val fixtureSections: Result = Right(
    AwsProfileReader.Sections(
      Map(
        "default"    -> Map(
          "sso_session"    -> "Work",
          "sso_account_id" -> "111122223333",
          "sso_role_name"  -> "ReadOnlyAccess",
          "region"         -> "us-west-2"
        ),
        "staging"    -> Map(
          "sso_session"         -> "Work",
          "sso_account_id"      -> "000011112222",
          "sso_role_name"       -> "DeveloperAccess",
          "region"              -> "eu-west-1",
          "output"              -> "json",
          "cli_pager"           -> "",
          "s3"                  -> "\naddressing_style = path\nsso_account_id = 999999999999",
          "s3.addressing_style" -> "path",
          "s3.sso_account_id"   -> "999999999999"
        ),
        "production" -> Map(
          "sso_session"    -> "work",
          "sso_account_id" -> "444455556666",
          "sso_role_name"  -> "ReadOnlyAccess"
        ),
        "external"   -> Map("credential_process" -> "example-provider --option=value"),
        "legacy"     -> Map(
          "sso_start_url"  -> "https://example.awsapps.com/start",
          "sso_region"     -> "eu-central-1",
          "sso_account_id" -> "111122223333",
          "sso_role_name"  -> "ReadOnlyAccess"
        )
      ),
      Map(
        "Work"       -> Map(
          "sso_start_url"           -> "https://example.awsapps.com/start",
          "sso_region"              -> "eu-central-1",
          "sso_registration_scopes" -> "sso:account:access"
        ),
        "work"       -> Map("sso_start_url" -> "https://example.awsapps.com/start", "sso_region" -> "us-east-1")
      )
    )
  )

  private val invalid: Result = Left(AwsConfigError.InvalidSyntax)

  private val cases: List[(String, String, Result)] = List(
    ("complete fixture", fixture, fixtureSections),
    ("empty configuration", "", Right(AwsProfileReader.Sections(Map.empty, Map.empty))),
    (
      "duplicate sections and keys",
      "[profile staging]\nsso_session=other\n[profile staging]\nsso_session=Work\n[sso-session Work]\nsso_region=eu-central-1",
      sessionProfile
    ),
    (
      "prefixed default first",
      "[profile default]\nsso_session=Work\n[default]\nsso_session=other",
      profile("default", Map("sso_session" -> "Work"))
    ),
    (
      "prefixed default last",
      "[default]\nsso_session=other\n[profile default]\nsso_session=Work",
      profile("default", Map("sso_session" -> "Work"))
    ),
    (
      "comments and tabs",
      "[ profile\tstaging ]#comment\nsso_session = Work\t; comment\n[sso-session\tWork];comment\nsso_region=eu-central-1",
      sessionProfile
    ),
    (
      "URL fragments and equals",
      "[profile staging]\nurl=https://example.test/start?view=roles#landing\ncommand=example --option=value",
      profile(
        "staging",
        Map("url" -> "https://example.test/start?view=roles#landing", "command" -> "example --option=value")
      )
    ),
    (
      "ignored section",
      "[staging]\nmalformed entry\n[profile valid]\nregion=eu-central-1",
      profile("valid", Map("region" -> "eu-central-1"))
    ),
    (
      "ignored identifiers",
      "[profile bad name]\nmalformed entry\n[profile valid]\nbad key=value\n    malformed continuation\nregion=eu-central-1",
      profile("valid", Map("region" -> "eu-central-1"))
    ),
    (
      "allowed identifier punctuation",
      "[profile a_-/ .%@:+]\nignored=value\n[profile a_-/.%@:+]\nsetting_-/.%@:+=value",
      profile("a_-/.%@:+", Map("setting_-/.%@:+" -> "value"))
    ),
    (
      "ordinary continuation",
      "[profile staging]\ncommand=first\n    second # data\n\tthird ; data",
      profile("staging", Map("command" -> "first\nsecond # data\nthird ; data"))
    ),
    (
      "nested continuations",
      "[profile staging]\ns3=\n    endpoint_url=https://example.test\n    addressing_style=path\n    bad key=ignored",
      profile(
        "staging",
        Map(
          "s3"                  -> "\nendpoint_url=https://example.test\naddressing_style=path\nbad key=ignored",
          "s3.endpoint_url"     -> "https://example.test",
          "s3.addressing_style" -> "path"
        )
      )
    ),
    ("invalid nested continuation", "[profile staging]\ns3=\n    broken", invalid),
    ("property before section", "region=eu-central-1", invalid),
    ("continuation before property", "[profile staging]\n    region=eu-central-1", invalid),
    ("malformed header", "[profile staging", invalid),
    ("malformed property", "[profile staging]\nbroken", invalid),
    ("missing property name", "[profile staging]\n=value", invalid),
    ("invalid services property", "[services local]\nbroken", invalid)
  )

  cases.foreach { (name, text, expected) =>
    test(s"AWS SDK 2.55.7 reference configuration: $name") {
      assertEquals(AwsProfileReader.read(text), expected)
      assertEquals(AwsProfileReader.read(text.replace("\n", "\r\n")), expected)
    }
  }
}
