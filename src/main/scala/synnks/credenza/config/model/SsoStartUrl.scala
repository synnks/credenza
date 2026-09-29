package synnks.credenza.config.model

import java.net.URI
import scala.util.Try

opaque type SsoStartUrl = URI

object SsoStartUrl {
  def from(value: String): Either[ValueError, SsoStartUrl] =
    Try(new URI(value)).toEither.left.map(_ => ValueError.InvalidStartUrl).flatMap { uri =>
      Either.cond(
        "https".equalsIgnoreCase(uri.getScheme) && uri.getHost != null && uri.getUserInfo == null,
        uri,
        ValueError.InvalidStartUrl
      )
    }

  extension (url: SsoStartUrl) def value: String = url.toString
}
