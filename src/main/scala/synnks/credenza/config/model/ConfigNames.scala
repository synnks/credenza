package synnks.credenza.config.model

object ConfigNames {
  private def isIdentifier(value: String): Boolean = value.matches("[A-Za-z0-9_/.%@:+-]+")

  opaque type ProfileName = String

  object ProfileName {
    def from(value: String): Either[ValueError, ProfileName] =
      Either.cond(isIdentifier(value), value, ValueError.InvalidIdentifier)

    extension (name: ProfileName) def value: String = name
  }

  opaque type SessionName = String

  object SessionName {
    def from(value: String): Either[ValueError, SessionName] =
      Either.cond(isIdentifier(value), value, ValueError.InvalidIdentifier)

    extension (name: SessionName) def value: String = name
  }
}
