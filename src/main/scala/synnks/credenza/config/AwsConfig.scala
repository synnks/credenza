package synnks.credenza.config

import cats.data.EitherNec
import cats.syntax.all.*
import synnks.credenza.config.decoding.{ SectionDecoder, SsoDecoders }
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.reader.AwsProfileReader

object AwsConfig {
  import AwsConfigError.*
  import AwsConfigError.Section
  import SectionDecoder.{ Input, Validation }

  def resolveSsoProfile(text: String, name: ProfileName): EitherNec[AwsConfigError, SsoProfile] =
    for {
      config     <- AwsProfileReader.read(text).toEitherNec
      properties <- config.profiles.get(name.value).toRight(ProfileNotFound(name)).toEitherNec
      profile    <- SsoDecoders
                      .profile(name, resolveSession(config, name, _))
                      .run(Input(Section.Profile(name), properties))
                      .toEither
    } yield profile

  private def resolveSession(
    config: AwsProfileReader.Sections,
    profile: ProfileName,
    name: SessionName
  ): Validation[SsoSession] =
    config.sessions
      .get(name.value)
      .toValidNec(SessionNotFound(profile, name))
      .andThen { properties =>
        SsoDecoders.session(name).run(Input(Section.Session(name), properties))
      }
}
