package synnks.credenza.config.reader

import cats.data.EitherNec
import cats.syntax.all.*
import synnks.credenza.config.decoding.SsoTokenDecoder
import synnks.credenza.config.model.{ SessionName, SsoCachedToken, SsoSession, SsoTokenError }

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, NoSuchFileException, Path }
import java.security.MessageDigest

final case class CacheSnapshot private[reader] (path: Path, sha256: String) {
  override def toString: String = "CacheSnapshot(<redacted>)"
}

final case class StoredSsoToken private[reader] (token: SsoCachedToken, snapshot: CacheSnapshot) {
  override def toString: String = "StoredSsoToken(<redacted>)"
}

object SsoTokenCache {
  private def hex(bytes: Array[Byte]): String = bytes.iterator.map(b => f"${b & 0xff}%02x").mkString

  def pathFor(directory: Path, name: SessionName): Path = {
    val bytes = MessageDigest.getInstance("SHA-1").digest(name.value.getBytes(StandardCharsets.UTF_8))
    directory.resolve(hex(bytes) + ".json")
  }

  def load(directory: Path, session: SsoSession): EitherNec[SsoTokenError, StoredSsoToken] = {
    val path = pathFor(directory, session.name)
    Either
      .catchNonFatal {
        val bytes    = Files.readAllBytes(path)
        val text     = StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
        val snapshot = CacheSnapshot(path, hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        (text, snapshot)
      }
      .leftMap {
        case _: NoSuchFileException => SsoTokenError.NotFound
        case _                      => SsoTokenError.Unreadable
      }
      .toEitherNec
      .flatMap { (text, snapshot) =>
        SsoTokenDecoder.decode(text, session).map(StoredSsoToken(_, snapshot))
      }
  }
}
