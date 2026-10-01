package synnks.credenza.config.reader

import cats.syntax.all.*
import synnks.credenza.config.model.{ AwsConfigError, ConfigField }

private[config] object AwsProfileReader {
  final case class Sections(profiles: Map[String, Map[String, String]], sessions: Map[String, Map[String, String]]) {
    override def toString: String = "Sections(<redacted>)"
  }

  private enum Section {
    case Profile(name: String)
    case Session(name: String)
    case Services(name: String)
  }

  private enum Property {
    case Current(key: String, nested: Boolean)
    case Ignored
  }

  private enum Position {
    case Start, Ignored
    case Selected(section: Section, property: Option[Property])
  }

  private case class State(
    sections: Map[Section, Map[String, String]] = Map.empty,
    position: Position = Position.Start,
    prefixedDefault: Boolean = false
  ) {
    override def toString: String = "State(<redacted>)"

    def put(section: Section, key: String, value: String): State =
      copy(sections = sections.updated(section, sections(section).updated(key, value)))
  }

  def read(text: String): Either[AwsConfigError, Sections] =
    text.linesIterator
      .foldLeft[Either[AwsConfigError, State]](Right(State())) { (result, line) =>
        result.flatMap(readLine(_, line))
      }
      .map { state =>
        val profiles = state.sections.collect { case (Section.Profile(name), properties) => name -> properties }
        val names    = profiles.valuesIterator.flatMap(_.get(ConfigField.SsoSession.key)).toSet
        val sessions = state.sections.collect {
          case (Section.Session(name), properties) if names(name) => name -> properties
        }
        Sections(profiles, sessions)
      }

  private def readLine(state: State, line: String): Either[AwsConfigError, State] =
    if (line.forall(c => c == ' ' || c == '\t') || line.startsWith("#") || line.startsWith(";")) Right(state)
    else if (line.startsWith("[")) readSection(state, line)
    else if (line.startsWith(" ") || line.startsWith("\t")) readContinuation(state, line.trim)
    else readProperty(state, uncomment(line, requireWhitespace = true).trim)

  private def readSection(state: State, line: String): Either[AwsConfigError, State] = {
    val clean = uncomment(line, requireWhitespace = false).trim
    if (!clean.endsWith("]")) Left(AwsConfigError.InvalidSyntax)
    else {
      val raw      = clean.substring(1, clean.length - 1).trim
      val prefixed = hasPrefix(raw, "profile")
      val section  =
        if (prefixed) Some(Section.Profile(raw.drop("profile".length).trim))
        else if (raw == "default") Some(Section.Profile("default"))
        else if (hasPrefix(raw, "sso-session")) Some(Section.Session(raw.drop("sso-session".length).trim))
        else if (hasPrefix(raw, "services")) Some(Section.Services(raw.drop("services".length).trim))
        else None

      val selected = section
        .filter {
          case Section.Profile(name)  => identifier(name)
          case Section.Session(name)  => identifier(name)
          case Section.Services(name) => identifier(name)
        }
        .filterNot(_ == Section.Profile("default") && !prefixed && state.prefixedDefault)

      Right(selected match {
        case None        => state.copy(position = Position.Ignored)
        case Some(value) =>
          val preferred = value == Section.Profile("default") && prefixed
          val sections  = if (preferred && !state.prefixedDefault) state.sections - value else state.sections
          state.copy(
            sections = sections.updated(value, sections.getOrElse(value, Map.empty)),
            position = Position.Selected(value, None),
            prefixedDefault = state.prefixedDefault || preferred
          )
      })
    }
  }

  private def readProperty(state: State, line: String): Either[AwsConfigError, State] = state.position match {
    case Position.Ignored              => Right(state)
    case Position.Start                => Left(AwsConfigError.InvalidSyntax)
    case Position.Selected(section, _) =>
      assignment(line).map {
        case None               => state.copy(position = Position.Selected(section, Some(Property.Ignored)))
        case Some((key, value)) =>
          state
            .put(section, key, value)
            .copy(position = Position.Selected(section, Some(Property.Current(key, value.isEmpty))))
      }
  }

  private def readContinuation(state: State, line: String): Either[AwsConfigError, State] = state.position match {
    case Position.Ignored | Position.Selected(_, Some(Property.Ignored)) => Right(state)
    case Position.Selected(section, Some(Property.Current(key, nested))) =>
      val appended = state.put(section, key, state.sections(section)(key) + "\n" + line)
      if (nested) assignment(line).map {
        case Some((subKey, value)) => appended.put(section, s"$key.$subKey", value)
        case None                  => appended
      }
      else Right(appended)
    case _                                                               => Left(AwsConfigError.InvalidSyntax)
  }

  private def assignment(line: String): Either[AwsConfigError, Option[(String, String)]] = {
    val equals = line.indexOf('=')
    if (equals < 0) Left(AwsConfigError.InvalidSyntax)
    else {
      val key = line.take(equals).trim
      if (key.isEmpty) Left(AwsConfigError.InvalidSyntax)
      else Right(Option.when(identifier(key))(key -> line.drop(equals + 1).trim))
    }
  }

  private def hasPrefix(value: String, prefix: String): Boolean =
    value.startsWith(prefix + " ") || value.startsWith(prefix + "\t")

  private def identifier(value: String): Boolean =
    value.forall(c => c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "_-/.%@:+".contains(c))

  private def uncomment(line: String, requireWhitespace: Boolean): String = {
    val comment = line.indices.find { index =>
      (line(index) == '#' || line(index) == ';') &&
      (!requireWhitespace || index > 0 && (line(index - 1) == ' ' || line(index - 1) == '\t'))
    }
    comment.fold(line)(line.take)
  }
}
