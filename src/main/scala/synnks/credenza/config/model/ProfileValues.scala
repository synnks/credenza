package synnks.credenza.config.model

import java.net.URI
import scala.util.Try

enum ValueError(val expected: String) {
  case InvalidIdentifier extends ValueError("a nonempty AWS profile or session identifier")
  case InvalidAccountId  extends ValueError("a 12-digit AWS account ID")
  case InvalidRoleName   extends ValueError("a nonempty role name without whitespace or control characters")
  case InvalidRegion     extends ValueError("an AWS region name")
  case InvalidStartUrl   extends ValueError("an absolute HTTPS URL without user information")
}

private def isIdentifier(value: String): Boolean = value.matches("[A-Za-z0-9_/.%@:+-]+")

opaque type ProfileName = String

object ProfileName {
  def from(value: String): Either[ValueError, ProfileName] =
    Either.cond(isIdentifier(value), value, ValueError.InvalidIdentifier)

  extension (name: ProfileName) def value: String = name
}

opaque type SessionName = String

object SessionName {
  def from(value: String): Either[ValueError, SessionName] =
    Either.cond(isIdentifier(value), value, ValueError.InvalidIdentifier)

  extension (name: SessionName) def value: String = name
}

opaque type AccountId = String

object AccountId {
  def from(value: String): Either[ValueError, AccountId] =
    Either.cond(value.matches("[0-9]{12}"), value, ValueError.InvalidAccountId)

  extension (id: AccountId) def value: String = id
}

opaque type RoleName = String

object RoleName {
  def from(value: String): Either[ValueError, RoleName] =
    Either.cond(value.nonEmpty && !value.exists(c => c.isWhitespace || c.isControl), value, ValueError.InvalidRoleName)

  extension (name: RoleName) def value: String = name
}

opaque type Region = String

object Region {
  def from(value: String): Either[ValueError, Region] =
    Either.cond(value.matches("[a-z]+(?:-[a-z]+)+-[0-9]+"), value, ValueError.InvalidRegion)

  extension (region: Region) def value: String = region
}

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
