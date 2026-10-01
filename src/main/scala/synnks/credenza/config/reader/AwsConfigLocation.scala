package synnks.credenza.config.reader

import cats.syntax.all.*

import java.nio.file.{ InvalidPathException, Path }

private[config] object AwsConfigLocation {
  def resolve(home: Path, property: Option[String], environment: Option[String]): Option[Path] =
    val location = property.orElse(environment).map(_.trim) match {
      case Some("")    => None
      case Some(value) =>
        val expanded = if (value.startsWith("~/")) home.toString + value.drop(1) else value
        Some(expanded)
      case None        => Some(home.resolve(".aws").resolve("config").toString)
    }
    location
      .filterNot(_.contains('\u0000'))
      .flatMap(value => Either.catchOnly[InvalidPathException](Path.of(value)).toOption)
}
