package synnks.credenza.config.model

import ConfigNames.ProfileName

final case class SsoProfile(
  name: ProfileName,
  session: SsoSession,
  accountId: AccountId,
  roleName: RoleName,
  region: Option[Region]
)
