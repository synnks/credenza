package synnks.credenza.config.sso

import cats.data.EitherNec
import cats.effect.{ IO, Resource }
import munit.CatsEffectSuite
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.SessionName
import SsoCachedToken.Error as SsoTokenError

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class SsoTokenCacheTests extends CatsEffectSuite {
  private def valid[A](result: Either[ValueError, A]): A = result.fold(e => fail(e.expected), identity)

  private val session = SsoSession(
    valid(SessionName.from("Work")),
    valid(SsoStartUrl.from("https://example.awsapps.com/start")),
    valid(Region.from("eu-central-1"))
  )
  private val fixture = Using.resource(Source.fromResource("sso-token.json"))(_.mkString)

  private def withCache[A](use: Path => IO[A]): IO[A] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("credenza-sso-cache-"))) { directory =>
        IO.blocking {
          Using.resource(Files.list(directory)) { stream =>
            stream.iterator().asScala.foreach(Files.delete)
          }
          Files.delete(directory)
        }
      }
      .use(use)

  private def write(directory: Path, name: SessionName, text: String): IO[Unit] =
    IO.blocking(Files.writeString(SsoTokenCache.pathFor(directory, name), text, StandardCharsets.UTF_8)).void

  private def errors(result: EitherNec[SsoTokenError, SsoTokenCache.Stored]): List[SsoTokenError] = result match {
    case Left(values) => values.toNonEmptyList.toList
    case Right(_)     => fail("Expected cache error")
  }

  test("derive the exact CLI filename by SHA-1 of the session name") {
    val directory = Path.of("cache")
    val name      = valid(SessionName.from("abc"))
    assertEquals(
      SsoTokenCache.pathFor(directory, name).getFileName.toString,
      "a9993e364706816aba3e25717850c26c9cd0d89d.json"
    )
    assertNotEquals(
      SsoTokenCache.pathFor(directory, session.name),
      SsoTokenCache.pathFor(directory, valid(SessionName.from("work")))
    )
  }

  test("a deferred load reads only the selected session, not another token with the same start URL") {
    withCache { directory =>
      val otherSession = session.copy(name = valid(SessionName.from("work")))
      val load         = SsoTokenCache.load(directory, session)
      for {
        _       <- write(directory, otherSession.name, fixture)
        missing <- load
        _       <- IO(assertEquals(errors(missing), List(SsoTokenError.NotFound)))
        _       <- write(directory, session.name, fixture)
        stored  <- load.map(_.toOption.getOrElse(fail("Expected cached token")))
      } yield {
        assertEquals(stored.token.accessToken.value, "synthetic-access-token")
        assertEquals(stored.snapshot.path, SsoTokenCache.pathFor(directory, session.name))
        assert(!stored.toString.contains("synthetic-access-token"))
        assert(!stored.snapshot.toString.contains(directory.toString))
      }
    }
  }

  test("reject the selected file if its metadata belongs to another session") {
    withCache { directory =>
      for {
        _      <- write(directory, session.name, fixture.replace("eu-central-1", "us-east-1"))
        result <- SsoTokenCache.load(directory, session)
      } yield assertEquals(errors(result), List(SsoTokenError.SessionMismatch))
    }
  }

  test("do not fall back to another session when the selected file is malformed") {
    withCache { directory =>
      for {
        _      <- write(directory, valid(SessionName.from("work")), fixture)
        _      <- write(directory, session.name, "{")
        result <- SsoTokenCache.load(directory, session)
      } yield assertEquals(errors(result), List(SsoTokenError.MalformedJson))
    }
  }

  test("retain a fingerprint to detect an externally replaced cache record") {
    withCache { directory =>
      for {
        _      <- write(directory, session.name, fixture)
        first  <- SsoTokenCache.load(directory, session).map(_.toOption.getOrElse(fail("Expected cached token")))
        _      <- write(directory, session.name, fixture.replace("synthetic-access-token", "synthetic-new-access-token"))
        second <- SsoTokenCache.load(directory, session).map(_.toOption.getOrElse(fail("Expected cached token")))
      } yield assertNotEquals(first.snapshot.sha256, second.snapshot.sha256)
    }
  }

  test("distinguish missing cache file, unreadable file, and invalid UTF-8") {
    withCache { directory =>
      val path = SsoTokenCache.pathFor(directory, session.name)
      for {
        missing    <- SsoTokenCache.load(directory, session)
        _          <- IO(assertEquals(errors(missing), List(SsoTokenError.NotFound)))
        _          <- IO.blocking(Files.write(path, Array(0xc3.toByte, 0x28.toByte)))
        malformed  <- SsoTokenCache.load(directory, session)
        _          <- IO(assertEquals(errors(malformed), List(SsoTokenError.Unreadable)))
        _          <- IO.blocking { Files.delete(path); Files.createDirectory(path) }
        unreadable <- SsoTokenCache.load(directory, session)
      } yield assertEquals(errors(unreadable), List(SsoTokenError.Unreadable))
    }
  }

  test("do not include cached secrets or raw cache text in errors") {
    withCache { directory =>
      val secret = "synthetic-secret-value"
      for {
        _      <- write(directory, session.name, s"{\"accessToken\":\"$secret\",broken}")
        result <- SsoTokenCache.load(directory, session)
      } yield assert(errors(result).forall(e => !e.toString.contains(secret) && !e.message.contains(secret)))
    }
  }
}
