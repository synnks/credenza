package synnks.credenza.config.model

opaque type Region = String

object Region {
  def from(value: String): Either[ValueError, Region] =
    Either.cond(value.nonEmpty && !value.exists(c => c.isWhitespace || c.isControl), value, ValueError.InvalidRegion)

  extension (region: Region) def value: String = region
}
