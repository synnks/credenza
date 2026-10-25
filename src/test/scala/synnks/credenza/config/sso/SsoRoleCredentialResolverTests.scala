package synnks.credenza.config.sso

import cats.data.EitherNec
import cats.effect.{ IO, Ref, Resource }
import cats.syntax.all.*
import munit.CatsEffectSuite
import software.amazon.awssdk.services.sso.SsoAsyncClient
import software.amazon.awssdk.services.sso.model.{
  GetRoleCredentialsRequest,
  GetRoleCredentialsResponse,
  RoleCredentials as SdkRoleCredentials,
  SsoException
}
import software.amazon.awssdk.services.ssooidc.SsoOidcAsyncClient
import software.amazon.awssdk.services.ssooidc.model.{ CreateTokenRequest, CreateTokenResponse }
import synnks.credenza.config.model.{ Region, ValueError }
import synnks.credenza.config.model.ConfigNames.{ ProfileName, SessionName }
import synnks.credenza.config.sso.SsoApiClient.RoleCredentials
import synnks.credenza.config.sso.SsoRoleCredentialResolver.Error

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.concurrent.{ CompletableFuture, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class SsoRoleCredentialResolverTests extends CatsEffectSuite {
  private def valid[A](result: Either[ValueError, A]): A = result.fold(error => fail(error.expected), identity)

  private val name        = valid(ProfileName.from("staging"))
  private val sessionName = valid(SessionName.from("Work"))
  private val now         = Instant.parse("2029-01-01T00:00:00Z")
  private val expiration  = now.plusSeconds(3600)
  private val config      = Using.resource(Source.fromResource("aws-config"))(_.mkString)
  private val token       = Using.resource(Source.fromResource("sso-token.json"))(_.mkString)

  private def response(expiresAt: Instant = expiration): GetRoleCredentialsResponse =
    GetRoleCredentialsResponse
      .builder()
      .roleCredentials(
        SdkRoleCredentials
          .builder()
          .accessKeyId("synthetic-key-id")
          .secretAccessKey("synthetic-secret-key")
          .sessionToken("synthetic-session-token")
          .expiration(expiresAt.toEpochMilli)
          .build()
      )
      .build()

  private def client(
    getRole: GetRoleCredentialsRequest => CompletableFuture[GetRoleCredentialsResponse] = _ =>
      CompletableFuture.completedFuture(response())
  ): SsoApiClient = new SsoApiClient(
    new SsoAsyncClient     {
      override def serviceName(): String               = "sso"
      override def close(): Unit                       = ()
      override def getRoleCredentials(
        request: GetRoleCredentialsRequest
      ): CompletableFuture[GetRoleCredentialsResponse] =
        getRole(request)
    },
    new SsoOidcAsyncClient {
      override def serviceName(): String                                                            = "ssooidc"
      override def close(): Unit                                                                    = ()
      override def createToken(request: CreateTokenRequest): CompletableFuture[CreateTokenResponse] =
        fail("Resolution must not refresh the SSO token")
    }
  )

  private def withSource[A](use: SsoSource.Locations => IO[A]): IO[A] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("credenza-resolver-"))) { root =>
        IO.blocking {
          Using.resource(Files.walk(root)) { files =>
            files.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
          }
        }
      }
      .use { root =>
        val location = SsoSource.Locations(root.resolve("config"), SsoTokenCache.defaultDirectory(root))
        write(location.configFile, config) *>
          write(SsoTokenCache.pathFor(location.cacheDirectory, sessionName), token) *>
          use(location)
      }

  private def write(path: Path, content: String): IO[Unit] = IO.blocking {
    Files.createDirectories(path.getParent)
    Files.writeString(path, content, StandardCharsets.UTF_8)
    ()
  }

  private def errors(result: EitherNec[Error, RoleCredentials]): List[Error] =
    result.swap.toOption.getOrElse(fail("Expected resolution errors")).toNonEmptyList.toList

  private def unusedClient: Region => Resource[IO, SsoApiClient] =
    _ => Resource.eval(IO(fail("Invalid sources must not acquire an API client")))

  test("resolve the selected source through its SSO region and retain actual credential expiry") {
    withSource { location =>
      for {
        events   <- Ref.of[IO, List[String]](Nil)
        api       = client { request =>
                      assertEquals(request.accountId(), "000011112222")
                      assertEquals(request.roleName(), "DeveloperAccess")
                      assertEquals(request.accessToken(), "synthetic-access-token")
                      CompletableFuture.completedFuture(response())
                    }
        acquire   = (region: Region) =>
                      Resource.make(
                        events.update(_ :+ s"open:${region.value}").as(api)
                      )(_ => events.update(_ :+ "close"))
        result   <- SsoRoleCredentialResolver.resolveSource(SsoSource.load(location, name, now), acquire, IO.pure(now))
        recorded <- events.get
      } yield {
        val credentials = result.toOption.getOrElse(fail("Expected role credentials"))
        assertEquals(credentials.accessKeyId.value, "synthetic-key-id")
        assertEquals(credentials.secretAccessKey.value, "synthetic-secret-key")
        assertEquals(credentials.sessionToken.value, "synthetic-session-token")
        assertEquals(credentials.expiration, expiration)
        assertEquals(credentials.toString, "RoleCredentials(<redacted>)")
        assertEquals(recorded, List("open:eu-central-1", "close"))
      }
    }
  }

  test("preserve accumulated source validation errors without acquiring a client") {
    withSource { location =>
      for {
        _      <-
          write(location.configFile, config.replace("000011112222", "synthetic-secret").replace("DeveloperAccess", ""))
        source <- SsoSource.load(location, name, now)
        result <- SsoRoleCredentialResolver.resolveSource(IO.pure(source), unusedClient, IO.pure(now))
      } yield {
        val expected = source.swap.toOption.getOrElse(fail("Expected invalid source"))
        assert(expected.length >= 2)
        assertEquals(result, Left(expected.map(error => Error.Source(error): Error)))
        errors(result).foreach { error =>
          assert(!error.toString.contains("synthetic-secret"))
          assert(!error.message.contains("synthetic-secret"))
        }
      }
    }
  }

  test("the explicit-location entry point reports a missing configuration file") {
    withSource { location =>
      val missing = location.copy(configFile = location.configFile.resolveSibling("missing"))
      SsoRoleCredentialResolver.resolve(missing, name).map { result =>
        assertEquals(errors(result), List(Error.Source(SsoSource.Error.ConfigMissing)))
      }
    }
  }

  test("an expired cached login remains a source failure without acquiring a client") {
    withSource { location =>
      val expired = Instant.parse("2030-05-01T12:00:00Z")
      SsoRoleCredentialResolver
        .resolveSource(SsoSource.load(location, name, expired), unusedClient, IO.pure(expired))
        .map { result =>
          assertEquals(errors(result), List(Error.Source(SsoSource.Error.Cache(SsoCachedToken.Error.ExpiredToken))))
        }
    }
  }

  test("recheck token expiry after client acquisition and release the client without making a request") {
    withSource { location =>
      for {
        clock    <- Ref.of[IO, Instant](now)
        closed   <- Ref.of[IO, Boolean](false)
        api       = client(_ => fail("An expired access token must not be sent"))
        acquire   = (_: Region) =>
                      Resource.make(
                        clock.set(Instant.parse("2030-05-01T12:00:00Z")).as(api)
                      )(_ => closed.set(true))
        result   <- SsoRoleCredentialResolver.resolveSource(SsoSource.load(location, name, now), acquire, clock.get)
        released <- closed.get
      } yield {
        assertEquals(errors(result), List(Error.Source(SsoSource.Error.Cache(SsoCachedToken.Error.ExpiredToken))))
        assert(released)
      }
    }
  }

  test("preserve typed API failures and release the client without exposing remote diagnostics") {
    withSource { location =>
      List(401 -> SsoApiClient.Error.AuthRequired, 429 -> SsoApiClient.Error.Throttled).traverse_ {
        (status, expected) =>
          val failure = SsoException.builder().statusCode(status).message("synthetic-secret").build()
          for {
            closed   <- Ref.of[IO, Boolean](false)
            acquire   = (_: Region) =>
                          Resource.make(IO(client(_ => CompletableFuture.failedFuture(failure))))(_ => closed.set(true))
            result   <-
              SsoRoleCredentialResolver.resolveSource(SsoSource.load(location, name, now), acquire, IO.pure(now))
            released <- closed.get
          } yield {
            assertEquals(errors(result), List(Error.Api(expected)))
            assertEquals(errors(result).map(_.message), List(expected.message))
            assert(!result.toString.contains("synthetic-secret"))
            assert(released)
          }
      }
    }
  }

  test("reject credentials expiring at or before the current time") {
    withSource { location =>
      List(now.minusSeconds(1), now).traverse_ { expiresAt =>
        SsoRoleCredentialResolver
          .resolveSource(
            SsoSource.load(location, name, now),
            _ => Resource.pure(client(_ => CompletableFuture.completedFuture(response(expiresAt)))),
            IO.pure(now)
          )
          .map(result => assertEquals(errors(result), List(Error.ExpiredCredentials)))
      }
    }
  }

  test("evaluate credential expiry after the API response") {
    withSource { location =>
      for {
        reads  <- Ref.of[IO, Int](0)
        clock   = reads.getAndUpdate(_ + 1).map(index => if (index == 0) now else expiration)
        result <- SsoRoleCredentialResolver.resolveSource(
                    SsoSource.load(location, name, now),
                    _ => Resource.pure(client()),
                    clock
                  )
      } yield assertEquals(errors(result), List(Error.ExpiredCredentials))
    }
  }

  test("reject credentials that expire while the client is released") {
    withSource { location =>
      for {
        clock  <- Ref.of[IO, Instant](now)
        acquire = (_: Region) => Resource.make(IO(client()))(_ => clock.set(expiration))
        result <- SsoRoleCredentialResolver.resolveSource(SsoSource.load(location, name, now), acquire, clock.get)
      } yield assertEquals(errors(result), List(Error.ExpiredCredentials))
    }
  }

  test("cancellation reaches the SDK request and releases the client") {
    withSource { location =>
      val started = new CountDownLatch(1)
      val pending = new CompletableFuture[GetRoleCredentialsResponse]
      for {
        closed <- Ref.of[IO, Boolean](false)
        acquire = (_: Region) => Resource.make(IO(client { _ => started.countDown(); pending }))(_ => closed.set(true))
        _      <-
          Resource
            .make(
              SsoRoleCredentialResolver.resolveSource(SsoSource.load(location, name, now), acquire, IO.pure(now)).start
            )(_.cancel)
            .use { fiber =>
              for {
                _        <- IO.blocking(assert(started.await(3, TimeUnit.SECONDS), "SDK request did not start"))
                _        <- fiber.cancel.timeout(3.seconds)
                outcome  <- fiber.join
                released <- closed.get
              } yield {
                assert(outcome.isCanceled)
                assert(pending.isCancelled)
                assert(released)
              }
            }
      } yield ()
    }
  }
}
