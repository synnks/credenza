package synnks.credenza.config.sso

import cats.data.{ EitherNec, EitherT }
import cats.effect.{ Clock, IO, Resource }
import cats.syntax.all.*
import synnks.credenza.config.model.Region
import synnks.credenza.config.model.ConfigNames.ProfileName
import synnks.credenza.config.sso.SsoApiClient.RoleCredentials

import java.time.Instant
import scala.concurrent.duration.*

object SsoRoleCredentialResolver {
  enum Error {
    case Source(error: SsoSource.Error)
    case Api(error: SsoApiClient.Error)
    case ExpiredCredentials

    def message: String = this match {
      case Source(error)      => error.message
      case Api(error)         => error.message
      case ExpiredCredentials => "The resolved role credentials have expired."
    }
  }

  def resolveHost(
    name: ProfileName,
    timeout: FiniteDuration = 30.seconds
  ): IO[EitherNec[Error, RoleCredentials]] =
    resolveSource(SsoSource.loadHost(name), region => SsoApiClient.resource(region, timeout))

  def resolve(
    location: SsoSource.Locations,
    name: ProfileName,
    timeout: FiniteDuration = 30.seconds
  ): IO[EitherNec[Error, RoleCredentials]] =
    resolveSource(
      Clock[IO].realTimeInstant.flatMap(now => SsoSource.load(location, name, now)),
      region => SsoApiClient.resource(region, timeout)
    )

  private[sso] def resolveSource(
    source: IO[EitherNec[SsoSource.Error, SsoSource.Loaded]],
    client: Region => Resource[IO, SsoApiClient],
    now: IO[Instant] = Clock[IO].realTimeInstant
  ): IO[EitherNec[Error, RoleCredentials]] =
    source.flatMap {
      case Left(errors)  => IO.pure(Left(errors.map(error => Error.Source(error): Error)))
      case Right(loaded) =>
        val result = for {
          credentials <- EitherT(client(loaded.profile.session.region).use { api =>
                           val request = for {
                             before <- EitherT.liftF[IO, Error, Instant](now)
                             token  <- EitherT.fromEither[IO](
                                         loaded.cached.token
                                           .validAccessToken(before)
                                           .leftMap(error => Error.Source(SsoSource.Error.Cache(error)): Error)
                                       )
                             value  <- EitherT(api.getRoleCredentials(loaded.profile, token))
                                         .leftMap(error => Error.Api(error): Error)
                           } yield value
                           request.value
                         })
          after       <- EitherT.liftF[IO, Error, Instant](now)
          _           <- EitherT.cond[IO](after.isBefore(credentials.expiration), (), Error.ExpiredCredentials: Error)
        } yield credentials
        result.value.map(_.toEitherNec)
    }
}
