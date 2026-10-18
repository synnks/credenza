package synnks.credenza.config.sso

import org.http4s.Uri

final private[sso] case class HttpsTestConnection(
  baseUri: Uri,
  wrongHostUri: Uri,
  adminUri: Uri,
  caPem: String
) {
  override def toString: String = "HttpsTestConnection(<redacted>)"
}
