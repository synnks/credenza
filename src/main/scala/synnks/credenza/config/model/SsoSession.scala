package synnks.credenza.config.model

import ConfigNames.SessionName

final case class SsoSession(name: SessionName, startUrl: SsoStartUrl, region: Region)
