package synnks.credenza.config.sso

import cats.data.EitherNec
import cats.syntax.all.*
import synnks.credenza.config.model.SsoSession
import synnks.credenza.config.model.ConfigNames.SessionName
import SsoCachedToken.Error

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, NoSuchFileException, Path }
import java.security.MessageDigest

object SsoTokenCache {
  final case class Snapshot private[sso] (path: Path, sha256: String) {
    override def toString: String = "Snapshot(<redacted>)"
  }

  final case class Stored private[sso] (token: SsoCachedToken, snapshot: Snapshot) {
    override def toString: String = "Stored(<redacted>)"
  }

  private def hex(bytes: Array[Byte]): String = bytes.iterator.map(b => f"${b & 0xff}%02x").mkString

  def pathFor(directory: Path, name: SessionName): Path = {
    val bytes = MessageDigest.getInstance("SHA-1").digest(name.value.getBytes(StandardCharsets.UTF_8))
    directory.resolve(hex(bytes) + ".json")
  }

  def load(directory: Path, session: SsoSession): EitherNec[Error, Stored] = {
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
        val snapshot = Snapshot(path, hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        (text, snapshot)
      }
      .leftMap {
        case _: NoSuchFileException => Error.NotFound
        case _                      => Error.Unreadable
      }
      .toEitherNec
      .flatMap { (text, snapshot) =>
        SsoTokenDecoder.decode(text, session).map(Stored(_, snapshot))
      }
  }
}
