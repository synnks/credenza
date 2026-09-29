package synnks.credenza.config.model

enum ConfigSection {
  case Profile(name: ProfileName)
  case Session(name: SessionName)

  def label: String = this match {
    case Profile(name) if name.value == "default" => "[default]"
    case Profile(name)                            => s"[profile ${name.value}]"
    case Session(name)                            => s"[sso-session ${name.value}]"
  }
}

enum AwsConfigError {
  case InvalidSyntax
  case ReaderFailure
  case ProfileNotFound(name: ProfileName)
  case SessionNotFound(profile: ProfileName, session: SessionName)
  case MissingSetting(section: ConfigSection, setting: ConfigField)
  case InvalidSetting(section: ConfigSection, setting: ConfigField, reason: ValueError)
  case UnsupportedProfile(profile: ProfileName, setting: ConfigField)

  def message: String = this match {
    case InvalidSyntax                            => "Invalid AWS config syntax; check section headers and key=value entries."
    case ReaderFailure                            => "The AWS config reader failed unexpectedly."
    case ProfileNotFound(name)                    => s"AWS profile '${name.value}' was not found."
    case SessionNotFound(profile, session)        =>
      s"AWS profile '${profile.value}' references missing SSO session '${session.value}'."
    case MissingSetting(section, setting)         => s"Missing '${setting.key}' in ${section.label}."
    case InvalidSetting(section, setting, reason) =>
      s"Invalid '${setting.key}' in ${section.label}: expected ${reason.expected}."
    case UnsupportedProfile(profile, setting)     =>
      s"AWS profile '${profile.value}' uses unsupported setting '${setting.key}'; use a named SSO session without other credential providers."
  }
}
