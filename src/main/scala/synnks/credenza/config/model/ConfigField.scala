package synnks.credenza.config.model

enum ConfigField(val key: String) {
  case SsoSession           extends ConfigField("sso_session")
  case SsoAccountId         extends ConfigField("sso_account_id")
  case SsoRoleName          extends ConfigField("sso_role_name")
  case Region               extends ConfigField("region")
  case SsoStartUrl          extends ConfigField("sso_start_url")
  case SsoRegion            extends ConfigField("sso_region")
  case AccessKeyId          extends ConfigField("aws_access_key_id")
  case SecretAccessKey      extends ConfigField("aws_secret_access_key")
  case SessionToken         extends ConfigField("aws_session_token")
  case SecurityToken        extends ConfigField("aws_security_token")
  case CredentialProcess    extends ConfigField("credential_process")
  case CredentialSource     extends ConfigField("credential_source")
  case SourceProfile        extends ConfigField("source_profile")
  case RoleArn              extends ConfigField("role_arn")
  case WebIdentityTokenFile extends ConfigField("web_identity_token_file")
  case LoginSession         extends ConfigField("login_session")
}
