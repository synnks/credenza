package synnks.credenza.config

import cats.data.EitherNec
import munit.FunSuite
import synnks.credenza.config.model.*
import synnks.credenza.config.model.AwsConfigError.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.model.AwsConfigError.Section as ConfigSection

import scala.io.Source
import scala.util.Using

class AwsConfigTests extends FunSuite {
  private val fixture = Using.resource(Source.fromResource("aws-config"))(_.mkString)

  private def valid[A](value: Either[ValueError, A]): A = value.fold(error => fail(error.expected), identity)

  private val name           = valid(ProfileName.from("staging"))
  private val sessionName    = valid(SessionName.from("Work"))
  private val profileSection = ConfigSection.Profile(name)
  private val sessionSection = ConfigSection.Session(sessionName)

  private val staging = SsoProfile(
    name,
    SsoSession(
      sessionName,
      valid(SsoStartUrl.from("https://example.awsapps.com/start")),
      valid(Region.from("eu-central-1"))
    ),
    valid(AccountId.from("000011112222")),
    valid(RoleName.from("DeveloperAccess")),
    Some(valid(Region.from("eu-west-1")))
  )

  private val profileSettings = """sso_session = Work
                                |sso_account_id = 000011112222
                                |sso_role_name = DeveloperAccess
                                |region = eu-west-1""".stripMargin

  private val sessionSettings = """sso_start_url = https://example.awsapps.com/start
                                |sso_region = eu-central-1""".stripMargin

  private def config(profile: String = profileSettings, session: String = sessionSettings): String =
    s"[profile staging]\n$profile\n[sso-session Work]\n$session\n"

  private def resolve(text: String, profile: String = "staging"): EitherNec[AwsConfigError, SsoProfile] =
    AwsConfig.resolveSsoProfile(text, valid(ProfileName.from(profile)))

  private def errors(result: EitherNec[AwsConfigError, SsoProfile]): List[AwsConfigError] = result match {
    case Left(values) => values.toNonEmptyList.toList
    case Right(_)     => fail("Expected configuration errors")
  }

  test("resolve a typed SSO profile while ignoring unrelated profiles, settings, and nested entries") {
    assertEquals(resolve(fixture), Right(staging))
  }

  test("resolve the default profile") {
    assertEquals(
      resolve(fixture, "default"),
      Right(
        staging.copy(
          name = valid(ProfileName.from("default")),
          accountId = valid(AccountId.from("111122223333")),
          roleName = valid(RoleName.from("ReadOnlyAccess")),
          region = Some(valid(Region.from("us-west-2")))
        )
      )
    )
  }

  test("select the exact session name when sessions share a start URL") {
    assertEquals(
      resolve(fixture, "production"),
      Right(
        SsoProfile(
          valid(ProfileName.from("production")),
          SsoSession(valid(SessionName.from("work")), staging.session.startUrl, valid(Region.from("us-east-1"))),
          valid(AccountId.from("444455556666")),
          valid(RoleName.from("ReadOnlyAccess")),
          None
        )
      )
    )
  }

  test("profile region is optional and does not inherit from default or the SSO region") {
    val text = "[default]\nregion = us-west-2\n" + config(profileSettings.replace("region = eu-west-1", ""))
    assertEquals(resolve(text), Right(staging.copy(region = None)))
  }

  test("named profiles do not inherit missing credential settings from default") {
    val text = "[default]\nsso_account_id = 111122223333\n" + config(
      profileSettings.replace("sso_account_id = 000011112222", "")
    )
    assertEquals(errors(resolve(text)), List(MissingSetting(profileSection, ConfigField.SsoAccountId)))
  }

  test("accept AWS whitespace, CRLF, full-line comments, and inline comments") {
    val text = """# comment
                 |[ profile   staging ] ; comment
                 |sso_session = Work # shared session
                 |
                 |sso_account_id=000011112222
                 |sso_role_name = DeveloperAccess
                 |region = eu-west-1 ; service region
                 |[ sso-session Work ]
                 |sso_start_url = https://example.awsapps.com/start
                 |sso_region = eu-central-1
                 |; comment
                 |""".stripMargin.replace("\n", "\r\n")
    assertEquals(resolve(text), Right(staging))
  }

  test("preserve URL characters that are not whitespace-prefixed comments") {
    val url    = "https://example.awsapps.com/start?view=roles#landing"
    val result = resolve(config(session = sessionSettings.replace(staging.session.startUrl.value, url)))
    assertEquals(result.map(_.session.startUrl.value), Right(url))
  }

  test("use case-sensitive setting names as the AWS reader does") {
    val text = config(profileSettings.replace("sso_session", "SSO_SESSION"))
    assertEquals(errors(resolve(text)), List(MissingSetting(profileSection, ConfigField.SsoSession)))
  }

  test("profile default takes precedence over default regardless of section order") {
    val preferred = config().replace("[profile staging]", "[profile default]")
    val other     = "[default]\ncredential_process = another-provider\n"
    for (text <- List(other + preferred, preferred + other))
      assertEquals(resolve(text, "default"), Right(staging.copy(name = valid(ProfileName.from("default")))))
  }

  test("report a missing profile without falling back to default or changing case") {
    for (profile <- List("missing", "Staging"))
      assertEquals(errors(resolve(fixture, profile)), List(ProfileNotFound(valid(ProfileName.from(profile)))))
    assertEquals(errors(resolve("", "default")), List(ProfileNotFound(valid(ProfileName.from("default")))))
  }

  test("ignore named profiles without the profile prefix") {
    assertEquals(
      errors(resolve(config().replace("[profile staging]", "[staging]"))),
      List(ProfileNotFound(name))
    )
  }

  test("report a missing session and continue validating independent profile fields") {
    val text =
      config(profileSettings.replace("sso_session = Work", "sso_session = missing").replace("000011112222", "123"))
    assertEquals(
      errors(resolve(text)),
      List(
        SessionNotFound(name, valid(SessionName.from("missing"))),
        InvalidSetting(profileSection, ConfigField.SsoAccountId, ValueError.InvalidAccountId)
      )
    )
  }

  test("accumulate independent errors from the profile and referenced session") {
    val text = config(
      profile = "sso_session = Work\nsso_account_id = 123\nsso_role_name =\nregion = bad region",
      session = "sso_start_url = not-a-url\nsso_region ="
    )
    assertEquals(
      errors(resolve(text)),
      List(
        InvalidSetting(sessionSection, ConfigField.SsoStartUrl, ValueError.InvalidStartUrl),
        MissingSetting(sessionSection, ConfigField.SsoRegion),
        InvalidSetting(profileSection, ConfigField.SsoAccountId, ValueError.InvalidAccountId),
        MissingSetting(profileSection, ConfigField.SsoRoleName),
        InvalidSetting(profileSection, ConfigField.Region, ValueError.InvalidRegion)
      )
    )
  }

  List(
    "sso_session"    -> ConfigField.SsoSession,
    "sso_account_id" -> ConfigField.SsoAccountId,
    "sso_role_name"  -> ConfigField.SsoRoleName
  ).foreach { (key, field) =>
    test(s"require profile $key") {
      val without = profileSettings.linesIterator.filterNot(_.startsWith(s"$key =")).mkString("\n")
      assertEquals(errors(resolve(config(without))), List(MissingSetting(profileSection, field)))
    }
  }

  List("sso_start_url" -> ConfigField.SsoStartUrl, "sso_region" -> ConfigField.SsoRegion).foreach { (key, field) =>
    test(s"require session $key") {
      val without = sessionSettings.linesIterator.filterNot(_.startsWith(s"$key =")).mkString("\n")
      assertEquals(errors(resolve(config(session = without))), List(MissingSetting(sessionSection, field)))
    }
  }

  test("an explicitly empty service region is invalid") {
    assertEquals(
      errors(resolve(config(profileSettings.replace("region = eu-west-1", "region =")))),
      List(InvalidSetting(profileSection, ConfigField.Region, ValueError.InvalidRegion))
    )
  }

  test("reject inline SSO configuration before attempting named-session resolution") {
    val legacy = valid(ProfileName.from("legacy"))
    assertEquals(
      errors(resolve(fixture, "legacy")),
      List(UnsupportedProfile(legacy, ConfigField.SsoStartUrl), UnsupportedProfile(legacy, ConfigField.SsoRegion))
    )
  }

  test("reject an external process profile explicitly") {
    assertEquals(
      errors(resolve(fixture, "external")),
      List(UnsupportedProfile(valid(ProfileName.from("external")), ConfigField.CredentialProcess))
    )
  }

  List(
    "aws_access_key_id"       -> ConfigField.AccessKeyId,
    "aws_secret_access_key"   -> ConfigField.SecretAccessKey,
    "aws_session_token"       -> ConfigField.SessionToken,
    "aws_security_token"      -> ConfigField.SecurityToken,
    "credential_process"      -> ConfigField.CredentialProcess,
    "credential_source"       -> ConfigField.CredentialSource,
    "source_profile"          -> ConfigField.SourceProfile,
    "role_arn"                -> ConfigField.RoleArn,
    "web_identity_token_file" -> ConfigField.WebIdentityTokenFile,
    "login_session"           -> ConfigField.LoginSession
  ).foreach { (key, field) =>
    test(s"reject $key even alongside a named SSO session") {
      assertEquals(
        errors(resolve(config(s"$profileSettings\n$key = sensitive-value"))),
        List(UnsupportedProfile(name, field))
      )
    }
  }

  test("merge repeated sections and use the last duplicate setting") {
    val text = s"""[profile staging]
                 |sso_session = Work
                 |sso_account_id = 999999999999
                 |[profile staging]
                 |sso_account_id = 000011112222
                 |sso_role_name = ReadOnlyAccess
                 |sso_role_name = DeveloperAccess
                 |region = eu-west-1
                 |[sso-session Work]
                 |$sessionSettings
                 |""".stripMargin
    assertEquals(resolve(text), Right(staging))
  }

  test("translate malformed syntax to a redacted error") {
    for (
      text <- List(
                "sso_session = Work",
                "[profile staging",
                "[profile staging]\nbroken entry",
                "[profile staging]\n  sso_session = Work"
              )
    )
      assertEquals(errors(resolve(text)), List(InvalidSyntax))
  }

  test("reject continued SSO values instead of interpreting them as separate settings") {
    val text = config(profileSettings.replace("sso_session = Work", "sso_session = Work\n    extra-text"))
    assertEquals(
      errors(resolve(text)),
      List(InvalidSetting(profileSection, ConfigField.SsoSession, ValueError.InvalidIdentifier))
    )
  }

  test("errors do not echo invalid values or raw input") {
    val secret = "synthetic-secret-value"
    val inputs = List(
      config(profileSettings.replace("000011112222", secret)),
      config(session = sessionSettings.replace(staging.session.startUrl.value, secret)),
      s"[profile staging]\n$secret",
      config(s"$profileSettings\ncredential_process = $secret")
    )
    inputs.flatMap(text => errors(resolve(text))).foreach { error =>
      assert(!error.message.contains(secret))
      assert(!error.toString.contains(secret))
    }
  }
}
