package synnks.credenza.config.sso

import cats.data.EitherNec
import cats.effect.{ IO, Resource }
import munit.CatsEffectSuite
import synnks.credenza.config.model.{ AwsConfigError, Region, ValueError }
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.Instant
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class SsoSourceTests extends CatsEffectSuite {
  private def valid[A](result: Either[ValueError, A]): A = result.fold(error => fail(error.expected), identity)

  private val name        = valid(ProfileName.from("staging"))
  private val sessionName = valid(SessionName.from("Work"))
  private val now         = Instant.parse("2029-01-01T00:00:00Z")
  private val config      =
    Using.resource(Source.fromInputStream(getClass.getResourceAsStream("/aws-config"), "UTF-8"))(_.mkString)
  private val token       =
    Using.resource(Source.fromInputStream(getClass.getResourceAsStream("/sso-token.json"), "UTF-8"))(_.mkString)

  private def withHome[A](use: Path => IO[A]): IO[A] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("credenza-home-"))) { home =>
        IO.blocking {
          Using.resource(Files.walk(home)) { stream =>
            stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
          }
        }
      }
      .use(use)

  private def write(path: Path, content: String): IO[Unit] =
    IO.blocking {
      Files.createDirectories(path.getParent)
      Files.writeString(path, content, StandardCharsets.UTF_8)
      ()
    }

  private def selected(home: Path): SsoSource.Locations = selected(home, home.resolve(".aws/config"))

  private def selected(home: Path, configFile: Path): SsoSource.Locations =
    SsoSource.Locations(configFile, SsoTokenCache.defaultDirectory(home))

  private def errors(result: EitherNec[SsoSource.Error, SsoSource.Loaded]): List[SsoSource.Error] = result match {
    case Left(values) => values.toNonEmptyList.toList
    case Right(_)     => fail("Expected source error")
  }

  test("load the selected source from disk with a usable access token") {
    withHome { home =>
      val location = selected(home)
      for {
        _      <- write(location.configFile, config)
        _      <- write(SsoTokenCache.pathFor(location.cacheDirectory, sessionName), token)
        result <- SsoSource.load(location, name, now)
      } yield {
        val loaded = result.toOption.getOrElse(fail("Expected source"))
        assertEquals(loaded.profile.name.value, "staging")
        assertEquals(loaded.profile.session.region, valid(Region.from("eu-central-1")))
        assertEquals(loaded.cached.token.accessToken.value, "synthetic-access-token")
        assert(!loaded.toString.contains("synthetic-access-token"))
      }
    }
  }

  test("an explicitly selected config file does not change the cache directory") {
    withHome { home =>
      val defaults     = selected(home)
      val overrideFile = home.resolve("alternate-config")
      val overridden   = selected(home, overrideFile)
      val missing      = selected(home, home.resolve("missing-config"))
      for {
        _      <- write(defaults.configFile, "invalid AWS config")
        _      <- write(overrideFile, config)
        _      <- write(SsoTokenCache.pathFor(defaults.cacheDirectory, sessionName), token)
        result <- SsoSource.load(overridden, name, now)
        absent <- SsoSource.load(missing, name, now)
      } yield {
        assertEquals(overridden.cacheDirectory, defaults.cacheDirectory)
        assertEquals(result.toOption.map(_.profile.name.value), Some("staging"))
        assertEquals(errors(absent), List(SsoSource.Error.ConfigMissing))
      }
    }
  }

  test("distinguish config, profile, cache, and expired login failures") {
    withHome { home =>
      val location  = selected(home)
      val cacheFile = SsoTokenCache.pathFor(location.cacheDirectory, sessionName)
      for {
        absent     <- SsoSource.load(location, name, now)
        _          <- IO(assertEquals(errors(absent), List(SsoSource.Error.ConfigMissing)))
        _          <- write(location.configFile, "[default]\nregion = eu-west-1\n")
        noProfile  <- SsoSource.load(location, name, now)
        _          <- IO(assertEquals(errors(noProfile), List(SsoSource.Error.Profile(AwsConfigError.ProfileNotFound(name)))))
        _          <- write(location.configFile, config)
        noCache    <- SsoSource.load(location, name, now)
        _          <- IO(assertEquals(errors(noCache), List(SsoSource.Error.Cache(SsoCachedToken.Error.NotFound))))
        _          <- write(cacheFile, token.replace("eu-central-1", "us-east-1"))
        mismatched <- SsoSource.load(location, name, now)
        _          <- IO(assertEquals(errors(mismatched), List(SsoSource.Error.Cache(SsoCachedToken.Error.SessionMismatch))))
        _          <- write(cacheFile, token)
        expired    <- SsoSource.load(location, name, Instant.parse("2030-05-01T12:00:00Z"))
      } yield assertEquals(errors(expired), List(SsoSource.Error.Cache(SsoCachedToken.Error.ExpiredToken)))
    }
  }

  test("redact invalid configuration content in diagnostics") {
    withHome { home =>
      val location = selected(home)
      val secret   = "synthetic-secret-value"
      for {
        _      <- write(location.configFile, config.replace("000011112222", secret))
        result <- SsoSource.load(location, name, now)
      } yield errors(result).foreach { error =>
        assert(!error.toString.contains(secret))
        assert(!error.message.contains(secret))
        assert(!error.toString.contains(location.configFile.toString))
      }
    }
  }

  test("report unreadable config content as an I/O error") {
    withHome { home =>
      val location = selected(home)
      for {
        _      <- IO.blocking {
                    Files.createDirectories(location.configFile.getParent)
                    Files.write(location.configFile, Array(0xc3.toByte, 0x28.toByte))
                  }
        result <- SsoSource.load(location, name, now)
      } yield assertEquals(errors(result), List(SsoSource.Error.ConfigUnreadable))
    }
  }
}
