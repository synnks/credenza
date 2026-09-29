package synnks.credenza.config.model

enum ValueError(val expected: String) {
  case InvalidIdentifier extends ValueError("a nonempty AWS profile or session identifier")
  case InvalidAccountId  extends ValueError("a 12-digit AWS account ID")
  case InvalidRoleName   extends ValueError("a nonempty role name without whitespace or control characters")
  case InvalidRegion     extends ValueError("an AWS region name")
  case InvalidStartUrl   extends ValueError("an absolute HTTPS URL without user information")
}
