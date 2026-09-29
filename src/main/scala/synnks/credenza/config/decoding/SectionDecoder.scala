package synnks.credenza.config.decoding

import cats.data.{ Kleisli, ValidatedNec }
import cats.syntax.all.*
import synnks.credenza.config.model.*
import AwsConfigError.Section

private[config] object SectionDecoder {
  import AwsConfigError.*

  final case class Input(section: Section, properties: Map[String, String]) {
    override def toString: String = s"Input(${section.label}, <redacted>)"
  }

  type Validation[A] = ValidatedNec[AwsConfigError, A]
  type Decoder[A]    = Kleisli[Validation, Input, A]

  def required[A](field: Field[A]): Decoder[A] = Kleisli { input =>
    input.properties
      .get(field.id.key)
      .filter(_.nonEmpty)
      .toValidNec(MissingSetting(input.section, field.id))
      .andThen(value => decode(field, input.section, value))
  }

  def optional[A](field: Field[A]): Decoder[Option[A]] = Kleisli { input =>
    input.properties.get(field.id.key).traverse(value => decode(field, input.section, value))
  }

  private def decode[A](field: Field[A], section: Section, value: String): Validation[A] =
    field.decode(value).leftMap(InvalidSetting(section, field.id, _)).toValidatedNec
}
