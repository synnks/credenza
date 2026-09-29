package synnks.credenza.config.sso

import munit.FunSuite
import synnks.credenza.config.model.*
import synnks.credenza.config.model.ConfigNames.SessionName
import SsoCachedToken.Error as SsoTokenError

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class SsoTokenCacheTests extends FunSuite {
  private def valid[A](result: Either[ValueError, A]): A = result.fold(e => fail(e.expected), identity)

  private val session = SsoSession(
    valid(SessionName.from("Work")),
    valid(SsoStartUrl.from("https://example.awsapps.com/start")),
    valid(Region.from("eu-central-1"))
  )
  private val fixture = Using.resource(Source.fromResource("sso-token.json"))(_.mkString)

  private def withCache[A](f: Path => A): A = {
    val directory = Files.createTempDirectory("credenza-sso-cache-")
    try f(directory)
    finally {
      Using.resource(Files.list(directory)) { stream =>
        stream.iterator().asScala.foreach(Files.delete)
      }
      Files.delete(directory)
    }
  }

  test("derive the exact CLI filename by SHA-1 of the session name") {
    withCache { directory =>
      val name = valid(SessionName.from("abc"))
      assertEquals(
        SsoTokenCache.pathFor(directory, name).getFileName.toString,
        "a9993e364706816aba3e25717850c26c9cd0d89d.json"
      )
      assertNotEquals(
        SsoTokenCache.pathFor(directory, session.name),
        SsoTokenCache.pathFor(directory, valid(SessionName.from("work")))
      )
    }
  }

  test("load only the selected session; do not scan other tokens with the same start URL") {
    withCache { directory =>
      val otherSession = session.copy(name = valid(SessionName.from("work")))
      Files.writeString(SsoTokenCache.pathFor(directory, otherSession.name), fixture)
      assertEquals(
        SsoTokenCache.load(directory, session).left.map(_.toNonEmptyList.toList),
        Left(List(SsoTokenError.NotFound))
      )
      Files.writeString(SsoTokenCache.pathFor(directory, session.name), fixture)
      val stored       = SsoTokenCache.load(directory, session).toOption.getOrElse(fail("Expected cached token"))
      assertEquals(stored.token.accessToken.value, "synthetic-access-token")
      assertEquals(stored.snapshot.path, SsoTokenCache.pathFor(directory, session.name))
      assertEquals(stored.snapshot.sha256.length, 64)
      assert(!stored.toString.contains("synthetic-access-token"))
      assert(!stored.snapshot.toString.contains(directory.toString))
    }
  }

  test("reject the selected file if its metadata belongs to another session") {
    withCache { directory =>
      Files.writeString(SsoTokenCache.pathFor(directory, session.name), fixture.replace("eu-central-1", "us-east-1"))
      assertEquals(
        SsoTokenCache.load(directory, session).left.map(_.toNonEmptyList.toList),
        Left(List(SsoTokenError.SessionMismatch))
      )
    }
  }

  test("do not fall back to another session when the selected file is malformed") {
    withCache { directory =>
      Files.writeString(SsoTokenCache.pathFor(directory, valid(SessionName.from("work"))), fixture)
      Files.writeString(SsoTokenCache.pathFor(directory, session.name), "{")
      assertEquals(
        SsoTokenCache.load(directory, session).left.map(_.toNonEmptyList.toList),
        Left(List(SsoTokenError.MalformedJson))
      )
    }
  }

  test("retain a fingerprint to detect an externally replaced cache record") {
    withCache { directory =>
      val path   = SsoTokenCache.pathFor(directory, session.name)
      Files.writeString(path, fixture)
      val first  = SsoTokenCache.load(directory, session).toOption.getOrElse(fail("Expected cached token"))
      Files.writeString(path, fixture.replace("synthetic-access-token", "synthetic-new-access-token"))
      val second = SsoTokenCache.load(directory, session).toOption.getOrElse(fail("Expected cached token"))
      assertNotEquals(first.snapshot.sha256, second.snapshot.sha256)
    }
  }

  test("distinguish missing cache file, unreadable file, and invalid UTF-8") {
    withCache { directory =>
      val path = SsoTokenCache.pathFor(directory, session.name)
      assertEquals(
        SsoTokenCache.load(directory, session).left.map(_.toNonEmptyList.toList),
        Left(List(SsoTokenError.NotFound))
      )
      Files.write(path, Array(0xc3.toByte, 0x28.toByte))
      assertEquals(
        SsoTokenCache.load(directory, session).left.map(_.toNonEmptyList.toList),
        Left(List(SsoTokenError.Unreadable))
      )
      Files.delete(path)
      Files.createDirectory(path)
      assertEquals(
        SsoTokenCache.load(directory, session).left.map(_.toNonEmptyList.toList),
        Left(List(SsoTokenError.Unreadable))
      )
    }
  }

  test("do not include cached secrets or raw cache text in errors") {
    withCache { directory =>
      val secret = "synthetic-secret-value"
      Files.writeString(
        SsoTokenCache.pathFor(directory, session.name),
        s"{\"accessToken\":\"$secret\",broken}",
        StandardCharsets.UTF_8
      )
      val errors = SsoTokenCache.load(directory, session).left.toOption.getOrElse(fail("Expected error"))
      assert(errors.toNonEmptyList.toList.forall(e => !e.toString.contains(secret) && !e.message.contains(secret)))
    }
  }
}
