package synnks.credenza.config.model

final case class SsoSession(name: SessionName, startUrl: SsoStartUrl, region: Region)

final case class SsoProfile(
  name: ProfileName,
  session: SsoSession,
  accountId: AccountId,
  roleName: RoleName,
  region: Option[Region]
)
