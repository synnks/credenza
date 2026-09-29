package synnks.credenza.config.sso

import cats.data.{ EitherNec, NonEmptyChain }
import cats.effect.IO
import cats.syntax.all.*
import synnks.credenza.config.model.SsoSession
import synnks.credenza.config.model.ConfigNames.SessionName
import SsoCachedToken.Error

import java.nio.ByteBuffer
import java.nio.charset.{ CharacterCodingException, CodingErrorAction }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, NoSuchFileException, Path }
import java.io.IOException
import java.security.MessageDigest

object SsoTokenCache {
  final case class Snapshot private[sso] (path: Path, sha256: String) {
    override def toString: String = "Snapshot(<redacted>)"
  }

  final case class Stored private[sso] (token: SsoCachedToken, snapshot: Snapshot) {
    override def toString: String = "Stored(<redacted>)"
  }

  private def hex(bytes: Array[Byte]): String = bytes.iterator.map(b => f"${b & 0xff}%02x").mkString

  def defaultDirectory(home: Path): Path = home.resolve(".aws").resolve("sso").resolve("cache")

  def pathFor(directory: Path, name: SessionName): Path = {
    val bytes = MessageDigest.getInstance("SHA-1").digest(name.value.getBytes(StandardCharsets.UTF_8))
    directory.resolve(hex(bytes) + ".json")
  }

  def load(directory: Path, session: SsoSession): IO[EitherNec[Error, Stored]] =
    IO.defer {
      val path = pathFor(directory, session.name)
      readBytes(path).map(_.flatMap(bytes => decode(bytes, session, path)))
    }

  private def readBytes(path: Path): IO[EitherNec[Error, Array[Byte]]] =
    IO.blocking(Files.readAllBytes(path))
      .map(_.asRight[NonEmptyChain[Error]])
      .recover {
        case _: NoSuchFileException => Left(NonEmptyChain.one(Error.NotFound))
        case _: IOException         => Left(NonEmptyChain.one(Error.Unreadable))
      }

  private def decode(bytes: Array[Byte], session: SsoSession, path: Path): EitherNec[Error, Stored] =
    for {
      text  <- Either
                 .catchOnly[CharacterCodingException] {
                   StandardCharsets.UTF_8
                     .newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT)
                     .onUnmappableCharacter(CodingErrorAction.REPORT)
                     .decode(ByteBuffer.wrap(bytes))
                     .toString
                 }
                 .leftMap(_ => Error.Unreadable)
                 .toEitherNec
      token <- SsoTokenDecoder.decode(text, session)
    } yield Stored(token, Snapshot(path, hex(MessageDigest.getInstance("SHA-256").digest(bytes))))
}
