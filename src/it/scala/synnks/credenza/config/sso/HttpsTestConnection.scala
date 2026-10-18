package synnks.credenza.config.sso

import sttp.model.Uri

final private[sso] case class HttpsTestConnection(
  baseUri: Uri,
  wrongHostUri: Uri,
  adminHost: String,
  adminPort: Int,
  caPem: String
) {
  override def toString: String = "HttpsTestConnection(<redacted>)"
}
