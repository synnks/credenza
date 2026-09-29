package synnks.credenza.config.model

import java.time.Instant

final class Secret private (val value: String) {
  override def toString: String = "<redacted>"
}

object Secret {
  def from(value: String): Option[Secret] = Option.when(value.nonEmpty)(new Secret(value))
}

final case class RefreshMaterial private[config] (
  clientId: Secret,
  clientSecret: Secret,
  refreshToken: Secret,
  registrationExpiresAt: Instant
) {
  override def toString: String = "RefreshMaterial(<redacted>)"
}

final case class SsoCachedToken private[config] (
  startUrl: SsoStartUrl,
  region: Region,
  accessToken: Secret,
  expiresAt: Instant,
  refresh: Option[RefreshMaterial]
) {
  def validAccessToken(now: Instant): Either[SsoTokenError, Secret] =
    Either.cond(now.isBefore(expiresAt), accessToken, SsoTokenError.ExpiredToken)

  override def toString: String = "SsoCachedToken(<redacted>)"
}

enum SsoTokenError {
  case NotFound
  case Unreadable
  case MalformedJson
  case MissingField(key: String)
  case InvalidField(key: String)
  case SessionMismatch
  case ExpiredToken

  def message: String = this match {
    case NotFound          => "No cached SSO login was found for this session."
    case Unreadable        => "Could not read the cached SSO login."
    case MalformedJson     => "The cached SSO login is not a valid SSO cache JSON object."
    case MissingField(key) => s"The cached SSO login is missing '$key'."
    case InvalidField(key) => s"The cached SSO login has an invalid '$key'."
    case SessionMismatch   => "The cached SSO login does not match the selected session."
    case ExpiredToken      => "The cached SSO login has expired; sign in again."
  }
}
