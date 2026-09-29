package synnks.credenza.config

import cats.data.EitherNec
import cats.syntax.all.*
import synnks.credenza.config.decoding.{ SectionDecoder, SectionInput, SsoDecoders }
import synnks.credenza.config.model.*
import synnks.credenza.config.reader.{ AwsConfigSections, AwsProfileReader }

object AwsConfig {
  import AwsConfigError.*
  import SectionDecoder.Validation

  def resolveSsoProfile(text: String, name: ProfileName): EitherNec[AwsConfigError, SsoProfile] =
    for {
      config     <- AwsProfileReader.read(text).toEitherNec
      properties <- config.profiles.get(name.value).toRight(ProfileNotFound(name)).toEitherNec
      profile    <- SsoDecoders
                      .profile(name, resolveSession(config, name, _))
                      .run(SectionInput(ConfigSection.Profile(name), properties))
                      .toEither
    } yield profile

  private def resolveSession(
    config: AwsConfigSections,
    profile: ProfileName,
    name: SessionName
  ): Validation[SsoSession] =
    config.sessions
      .get(name.value)
      .toValidNec(SessionNotFound(profile, name))
      .andThen { properties =>
        SsoDecoders.session(name).run(SectionInput(ConfigSection.Session(name), properties))
      }
}
