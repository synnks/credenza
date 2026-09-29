package synnks.credenza.config.reader

import cats.syntax.all.*
import software.amazon.awssdk.profiles.ProfileFile
import synnks.credenza.config.model.{ AwsConfigError, ConfigField }

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

private[config] object AwsProfileReader {
  final case class Sections(profiles: Map[String, Map[String, String]], sessions: Map[String, Map[String, String]]) {
    override def toString: String = "Sections(<redacted>)"
  }

  def read(text: String): Either[AwsConfigError, Sections] =
    Either
      .catchNonFatal {
        ProfileFile.builder().content(text).`type`(ProfileFile.Type.CONFIGURATION).build()
      }
      .leftMap {
        case _: IllegalArgumentException => AwsConfigError.InvalidSyntax
        case _                           => AwsConfigError.ReaderFailure
      }
      .map(snapshot)

  private def snapshot(file: ProfileFile): Sections = {
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

    Sections(profiles, sessions)
  }
}
