package synnks.credenza.config.model

opaque type Region = String

object Region {
  def from(value: String): Either[ValueError, Region] =
    Either.cond(value.matches("[a-z]+(?:-[a-z]+)+-[0-9]+"), value, ValueError.InvalidRegion)

  extension (region: Region) def value: String = region
}
