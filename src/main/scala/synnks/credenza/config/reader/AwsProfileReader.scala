package synnks.credenza.config.reader

import cats.syntax.all.*
import software.amazon.awssdk.profiles.ProfileFile
import synnks.credenza.config.model.{ AwsConfigError, ConfigField }

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

final private[config] case class AwsConfigSections(
  profiles: Map[String, Map[String, String]],
  sessions: Map[String, Map[String, String]]
) {
  override def toString: String = "AwsConfigSections(<redacted>)"
}

private[config] object AwsProfileReader {
  def read(text: String): Either[AwsConfigError, AwsConfigSections] =
    Either
      .catchNonFatal {
        ProfileFile.builder().content(text).`type`(ProfileFile.Type.CONFIGURATION).build()
      }
      .leftMap {
        case _: IllegalArgumentException => AwsConfigError.InvalidSyntax
        case _                           => AwsConfigError.ReaderFailure
      }
      .map(snapshot)

  private def snapshot(file: ProfileFile): AwsConfigSections = {
    val profiles = file
      .profiles()
      .asScala
      .iterator
      .map { (name, profile) =>
        name -> profile.properties().asScala.toMap
      }
      .toMap

    // The SDK exposes named section lookup, so snapshot sessions referenced by the profiles.
    val sessionNames = profiles.valuesIterator.flatMap(_.get(ConfigField.SsoSession.key)).toSet
    val sessions     = sessionNames.iterator.flatMap { name =>
      file.getSection("sso-session", name).toScala.map { session =>
        name -> session.properties().asScala.toMap
      }
    }.toMap

    AwsConfigSections(profiles, sessions)
  }
}
