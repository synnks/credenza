package synnks.credenza.config.decoding

import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.SessionName

final private[config] class Field[A] private (
  val id: ConfigField,
  val decode: String => Either[ValueError, A]
)

private[config] object Field {
  val sessionName: Field[SessionName] = new Field(ConfigField.SsoSession, SessionName.from)
  val accountId: Field[AccountId]     = new Field(ConfigField.SsoAccountId, AccountId.from)
  val roleName: Field[RoleName]       = new Field(ConfigField.SsoRoleName, RoleName.from)
  val region: Field[Region]           = new Field(ConfigField.Region, Region.from)
  val startUrl: Field[SsoStartUrl]    = new Field(ConfigField.SsoStartUrl, SsoStartUrl.from)
  val ssoRegion: Field[Region]        = new Field(ConfigField.SsoRegion, Region.from)
}
