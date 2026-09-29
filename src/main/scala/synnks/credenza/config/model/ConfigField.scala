package synnks.credenza.config.model

import software.amazon.awssdk.profiles.ProfileProperty

enum ConfigField(val key: String) {
  case SsoSession           extends ConfigField("sso_session")
  case SsoAccountId         extends ConfigField(ProfileProperty.SSO_ACCOUNT_ID)
  case SsoRoleName          extends ConfigField(ProfileProperty.SSO_ROLE_NAME)
  case Region               extends ConfigField(ProfileProperty.REGION)
  case SsoStartUrl          extends ConfigField(ProfileProperty.SSO_START_URL)
  case SsoRegion            extends ConfigField(ProfileProperty.SSO_REGION)
  case AccessKeyId          extends ConfigField(ProfileProperty.AWS_ACCESS_KEY_ID)
  case SecretAccessKey      extends ConfigField(ProfileProperty.AWS_SECRET_ACCESS_KEY)
  case SessionToken         extends ConfigField(ProfileProperty.AWS_SESSION_TOKEN)
  case SecurityToken        extends ConfigField("aws_security_token")
  case CredentialProcess    extends ConfigField(ProfileProperty.CREDENTIAL_PROCESS)
  case CredentialSource     extends ConfigField(ProfileProperty.CREDENTIAL_SOURCE)
  case SourceProfile        extends ConfigField(ProfileProperty.SOURCE_PROFILE)
  case RoleArn              extends ConfigField(ProfileProperty.ROLE_ARN)
  case WebIdentityTokenFile extends ConfigField(ProfileProperty.WEB_IDENTITY_TOKEN_FILE)
  case LoginSession         extends ConfigField(ProfileProperty.LOGIN_SESSION)
}
