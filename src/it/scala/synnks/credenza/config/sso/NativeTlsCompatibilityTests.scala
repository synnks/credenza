package synnks.credenza.config.sso

import cats.data.Kleisli
import cats.effect.{ Deferred, IO, Ref, Resource }
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Chunk
import fs2.io.net.Network
import fs2.io.net.tls.{ NativeAlpnCompat, S2nConfig, SSLException, TLSContext, TLSParameters }
import munit.{ Assertions, CatsEffectSuite }
import org.http4s.{ HttpApp, HttpVersion, Response, Status, Uri }
import synnks.credenza.config.sso.NativeHttpsFixture.{ Identity, Trust }
import synnks.credenza.config.sso.NativeTlsCompatibilityTests.*

import java.io.IOException
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class NativeTlsCompatibilityTests extends CatsEffectSuite {
  override val munitIOTimeout: FiniteDuration = 90.seconds

  test("absent ALPN and encrypted I/O work across 32 fresh TLS connections") {
    (1 to 32).toList.traverse_(_ => exchange(None).map(protocol => assertEquals(protocol, None)))
  }

  test("negotiated HTTP/1.1, HTTP/2, and opaque ALPN values are preserved") {
    List("http/1.1", "h2", "synthetic-protocol").traverse_ { expected =>
      exchange(Some(expected)).map(protocol => assertEquals(protocol, Some(expected)))
    }
  }

  test("32 HTTP/1.1 HTTPS server/client lifecycles close their listeners") {
    (1 to 32).toList.traverse_ { _ =>
      NativeHttpsFixture
        .server(app)
        .use { server =>
          NativeHttpsFixture.client().use(_.expect[String](server.baseUri)).map { body =>
            assertEquals(body, "synthetic response")
            server.address
          }
        }
        .flatMap(assertClosed)
    }
  }

  test("32 concurrent HTTPS requests share a server and release all connections") {
    NativeHttpsFixture
      .server(app)
      .use { server =>
        NativeHttpsFixture
          .client()
          .use { client =>
            (1 to 32).toList.parTraverse_ { _ =>
              client.expect[String](server.baseUri).map(body => assertEquals(body, "synthetic response"))
            }
          }
          .as(server.address)
      }
      .flatMap(assertClosed)
  }

  test("an untrusted CA fails before the handler and a subsequent trusted request succeeds") {
    for {
      received            <- Ref.of[IO, Int](0)
      handler: HttpApp[IO] =
        Kleisli(_ => received.update(_ + 1).as(Response[IO](Status.Ok).withEntity("synthetic response")))
      _                   <- NativeHttpsFixture.server(handler).use { server =>
                               for {
                                 rejected <- NativeHttpsFixture.client(Trust.Untrusted).use(_.expect[String](server.baseUri)).attempt
                                 count    <- received.get
                                 _        <- IO {
                                               assert(rejected.swap.toOption.exists(_.isInstanceOf[SSLException]))
                                               assertEquals(count, 0)
                                             }
                                 accepted <- NativeHttpsFixture.client().use(_.expect[String](server.baseUri))
                                 _        <- IO(assertEquals(accepted, "synthetic response"))
                               } yield ()
                             }
    } yield ()
  }

  test("a trusted certificate for the wrong hostname is rejected") {
    NativeHttpsFixture.server(app, Identity.WrongHost).use { server =>
      NativeHttpsFixture.client().use(_.expect[String](server.baseUri)).attempt.map { result =>
        assert(result.swap.toOption.exists(_.isInstanceOf[SSLException]))
      }
    }
  }

  test("real Native TLS configuration errors still fail resource acquisition") {
    S2nConfig.builder
      .withCipherPreferences("synthetic-invalid-policy")
      .build[IO]
      .map(config => NativeAlpnCompat.serverContext(TLSContext.Builder.forAsync[IO].fromS2nConfig(config)))
      .use(_ => IO.unit)
      .attempt
      .map(result => assert(result.swap.toOption.exists(_.isInstanceOf[SSLException])))
  }

  test("canceling eight servers with active HTTPS requests releases handlers and listeners") {
    (1 to 8).toList.traverse_ { _ =>
      for {
        ready               <- Deferred[IO, (Uri, SocketAddress[IpAddress])]
        started             <- Deferred[IO, Unit]
        stopped             <- Deferred[IO, Unit]
        handler: HttpApp[IO] =
          Kleisli(_ => (started.complete(()) *> IO.never[Response[IO]]).guarantee(stopped.complete(()).void))
        serve                = NativeHttpsFixture
                                 .server(handler)
                                 .use(server => ready.complete((server.baseUri, server.address)) *> IO.never[Unit])
        address             <- Resource.make(serve.start)(_.cancel).use { serverFiber =>
                                 for {
                                   (base, address) <- ready.get
                                   _               <- NativeHttpsFixture.client().use { client =>
                                                        client.expect[String](base).attempt.background.use { _ =>
                                                          started.get *> serverFiber.cancel
                                                        }
                                                      }
                                 } yield address
                               }
        _                   <- stopped.get.timeout(5.seconds)
        _                   <- assertClosed(address)
      } yield ()
    }
  }
}

private object NativeTlsCompatibilityTests extends Assertions {
  private val app: HttpApp[IO] = Kleisli { request =>
    IO(assertEquals(request.httpVersion, HttpVersion.`HTTP/1.1`))
      .as(Response[IO](Status.Ok).withEntity("synthetic response"))
  }

  private def assertClosed(address: SocketAddress[IpAddress]): IO[Unit] =
    Network[IO].connect(address).use(_ => IO.unit).attempt.timeout(5.seconds).map { result =>
      assert(result.swap.toOption.exists(_.isInstanceOf[IOException]), "Expected a closed listener")
    }

  private def exchange(protocol: Option[String]): IO[Option[String]] = {
    val ping       = Chunk.array("ping".getBytes(StandardCharsets.US_ASCII))
    val pong       = Chunk.array("pong".getBytes(StandardCharsets.US_ASCII))
    val parameters = TLSParameters(protocolPreferences = protocol.map(List(_)))
    val resource   = for {
      serverContext <- NativeHttpsFixture.serverContext()
      clientContext <- NativeHttpsFixture.clientContext()
      result        <- Resource.eval(Deferred[IO, Either[Throwable, Option[String]]])
      listener      <- Network[IO].bind(SocketAddress(ipv4"127.0.0.1", port"0"))
      _             <- listener.accept
                         .take(1)
                         .evalMap { socket =>
                           serverContext.serverBuilder(socket).withParameters(parameters).build.use { tls =>
                             for {
                               selected <- tls.applicationProtocol.map(value => Some(value): Option[String]).recover {
                                             case _: NoSuchElementException => None
                                           }
                               request  <- tls.readN(ping.size)
                               _        <- IO(assertEquals(request, ping))
                               _        <- tls.write(pong)
                               _        <- result.complete(Right(selected))
                             } yield ()
                           }
                         }
                         .compile
                         .drain
                         .onError { case error => result.complete(Left(error)).void }
                         .background
      socket        <- Network[IO].connect(listener.address)
      tls           <-
        clientContext
          .clientBuilder(socket)
          .withParameters(TLSParameters(protocolPreferences = protocol.map(List(_)), serverName = Some("localhost")))
          .build
      _             <- Resource.eval(tls.write(ping))
      response      <- Resource.eval(tls.readN(pong.size))
      _             <- Resource.eval(IO(assertEquals(response, pong)))
      selected      <- Resource.eval(result.get.rethrow)
    } yield selected
    resource.use(IO.pure)
  }
}
