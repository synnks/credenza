package synnks.credenza.config.model

opaque type AccountId = String

object AccountId {
  def from(value: String): Either[ValueError, AccountId] =
    Either.cond(value.matches("[0-9]{12}"), value, ValueError.InvalidAccountId)

  extension (id: AccountId) def value: String = id
}
