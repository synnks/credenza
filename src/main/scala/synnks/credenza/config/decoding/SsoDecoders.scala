package synnks.credenza.config.decoding

import cats.data.{ Kleisli, Validated }
import cats.syntax.all.*
import synnks.credenza.config.model.*

private[config] object SsoDecoders {
  import SectionDecoder.*

  def profile(name: ProfileName, resolveSession: SessionName => Validation[SsoSession]): Decoder[SsoProfile] = {
    val session = required(Field.sessionName).mapF(_.andThen(resolveSession))
    val decoded = (
      session,
      required(Field.accountId),
      required(Field.roleName),
      optional(Field.region)
    ).mapN(SsoProfile(name, _, _, _, _))

    Kleisli { input =>
      validateProvider(name, input).andThen(_ => decoded.run(input))
    }
  }

  def session(name: SessionName): Decoder[SsoSession] =
    (
      required(Field.startUrl),
      required(Field.ssoRegion)
    ).mapN(SsoSession(name, _, _))

  private val incompatibleProfileFields = List(
    // SSO settings belong to the referenced session for this provider.
    ConfigField.SsoStartUrl,
    ConfigField.SsoRegion,
    // Competing credential sources are outside named-session resolution.
    ConfigField.AccessKeyId,
    ConfigField.SecretAccessKey,
    ConfigField.SessionToken,
    ConfigField.SecurityToken,
    ConfigField.CredentialProcess,
    ConfigField.CredentialSource,
    ConfigField.SourceProfile,
    ConfigField.RoleArn,
    ConfigField.WebIdentityTokenFile,
    ConfigField.LoginSession
  )

  private def validateProvider(name: ProfileName, input: SectionInput): Validation[Unit] =
    incompatibleProfileFields.traverse_ { field =>
      Validated.condNec(!input.properties.contains(field.key), (), AwsConfigError.UnsupportedProfile(name, field))
    }
}
