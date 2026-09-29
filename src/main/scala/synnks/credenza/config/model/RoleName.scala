package synnks.credenza.config.model

opaque type RoleName = String

object RoleName {
  def from(value: String): Either[ValueError, RoleName] =
    Either.cond(value.nonEmpty && !value.exists(c => c.isWhitespace || c.isControl), value, ValueError.InvalidRoleName)

  extension (name: RoleName) def value: String = name
}
