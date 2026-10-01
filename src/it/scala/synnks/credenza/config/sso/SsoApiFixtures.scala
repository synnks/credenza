package synnks.credenza.config.sso

import io.circe.Encoder

private[sso] object SsoApiFixtures {
  final case class RoleCredentials(
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: String,
    expiration: Long
  ) derives Encoder.AsObject {
    override def toString: String = "RoleCredentials(<redacted>)"
  }

  final case class RoleResponse(roleCredentials: RoleCredentials) derives Encoder.AsObject

  final case class RefreshGrant(clientId: String, clientSecret: String, refreshToken: String, grantType: String)
      derives Encoder.AsObject {
    override def toString: String = "RefreshGrant(<redacted>)"
  }

  final case class TokenResponse(accessToken: String, expiresIn: Int) derives Encoder.AsObject {
    override def toString: String = "TokenResponse(<redacted>)"
  }

  final case class RotatedTokenResponse(accessToken: String, expiresIn: Int, refreshToken: String)
      derives Encoder.AsObject {
    override def toString: String = "RotatedTokenResponse(<redacted>)"
  }

  final case class ErrorResponse(error: String, error_description: String) derives Encoder.AsObject {
    override def toString: String = "ErrorResponse(<redacted>)"
  }

  val role          = RoleResponse(RoleCredentials("synthetic-key-id", "synthetic-key", "synthetic-session", 1893456000000L))
  val grant         = RefreshGrant("synthetic-client-id", "synthetic-client-secret", "synthetic-refresh-token", "refresh_token")
  val rotatedToken  = RotatedTokenResponse("synthetic-new-token", 3600, "synthetic-new-refresh")
  val token         = TokenResponse("synthetic-token", 60)
  val invalidGrant  = ErrorResponse("invalid_grant", "synthetic-secret")
  val invalidClient = ErrorResponse("invalid_client", "synthetic-secret")
}
