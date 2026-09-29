package synnks.credenza.config.sso

import cats.data.{ EitherNec, EitherT, NonEmptyChain }
import cats.effect.{ Clock, IO }
import cats.effect.std.{ Env, SystemProperties }
import cats.syntax.all.*
import software.amazon.awssdk.profiles.ProfileFileLocation
import synnks.credenza.config.AwsConfig
import synnks.credenza.config.model.{ AwsConfigError, SsoProfile }
import synnks.credenza.config.model.ConfigNames.ProfileName

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, InvalidPathException, NoSuchFileException, Path }
import java.time.Instant

object SsoSource {
  final case class Locations(configFile: Path, cacheDirectory: Path) {
    override def toString: String = "Locations(<redacted>)"
  }

  final case class Loaded(profile: SsoProfile, cached: SsoTokenCache.Stored) {
    override def toString: String = "Loaded(<redacted>)"
  }

  enum Error {
    case InvalidLocation
    case ConfigMissing
    case ConfigUnreadable
    case Profile(error: AwsConfigError)
    case Cache(error: SsoCachedToken.Error)

    def message: String = this match {
      case InvalidLocation  => "The AWS configuration location is invalid."
      case ConfigMissing    => "The AWS configuration file was not found."
      case ConfigUnreadable => "Could not read the AWS configuration file."
      case Profile(error)   => error.message
      case Cache(error)     => error.message
    }
  }

  def load(location: Locations, name: ProfileName, now: Instant): IO[EitherNec[Error, Loaded]] = {
    val result = for {
      text    <- EitherT(readConfig(location.configFile))
      profile <- EitherT.fromEither[IO](
                   AwsConfig.resolveSsoProfile(text, name).leftMap(_.map(e => Error.Profile(e): Error))
                 )
      cached  <- EitherT(
                   SsoTokenCache
                     .load(location.cacheDirectory, profile.session)
                     .map(_.leftMap(_.map(e => Error.Cache(e): Error)))
                 )
      _       <- EitherT.fromEither[IO](
                   cached.token.validAccessToken(now).leftMap(e => NonEmptyChain.one[Error](Error.Cache(e)))
                 )
    } yield Loaded(profile, cached)
    result.value
  }

  def loadHost(name: ProfileName): IO[EitherNec[Error, Loaded]] =
    hostLocations.flatMap {
      case Left(error)     => IO.pure(Left(NonEmptyChain.one(error)))
      case Right(location) =>
        Clock[IO].realTime.flatMap(time => load(location, name, Instant.ofEpochMilli(time.toMillis)))
    }

  private def hostLocations: IO[Either[Error, Locations]] =
    Env[IO]
      .get("HOME")
      .flatMap {
        case Some(home) if home.nonEmpty => IO.pure(Some(home))
        case _                           => SystemProperties[IO].get("user.home")
      }
      .map {
        case Some(home) if home.nonEmpty =>
          Either.catchOnly[InvalidPathException](Path.of(home)).leftMap(_ => Error.InvalidLocation)
        case _                           => Left(Error.InvalidLocation)
      }
      .flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(home) =>
          IO.delay(ProfileFileLocation.configurationFilePath())
            .map(configFile => Right(Locations(configFile, SsoTokenCache.defaultDirectory(home))))
            .recover { case _: InvalidPathException => Left(Error.InvalidLocation) }
      }

  private def readConfig(path: Path): IO[EitherNec[Error, String]] =
    IO.blocking(Files.readString(path, StandardCharsets.UTF_8))
      .map(_.asRight[NonEmptyChain[Error]])
      .recover {
        case _: NoSuchFileException => Left(NonEmptyChain.one(Error.ConfigMissing))
        case _: IOException         => Left(NonEmptyChain.one(Error.ConfigUnreadable))
      }
}
