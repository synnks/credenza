package synnks.credenza.config.sso

import cats.effect.{ IO, Resource }
import com.comcast.ip4s.*
import fs2.io.net.tls.{ CertChainAndKey, NativeAlpnCompat, S2nConfig, TLSContext }
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

private[sso] object NativeHttpsFixture {
  enum Identity(val file: String) {
    case Valid     extends Identity("valid.pem")
    case WrongHost extends Identity("wrong-host.pem")
  }
  enum Trust                      {
    case TestCa, Untrusted
  }

  def serverContext(identity: Identity = Identity.Valid): Resource[IO, TLSContext[IO]] = for {
    certificate <- Resource.eval(pem(identity.file))
    key         <- Resource.eval(pem("server-key.pem"))
    config      <- S2nConfig.builder.withCertChainAndKeysToStore(List(CertChainAndKey(certificate, key))).build[IO]
  } yield NativeAlpnCompat.serverContext(TLSContext.Builder.forAsync[IO].fromS2nConfig(config))

  def clientContext(trust: Trust = Trust.TestCa): Resource[IO, TLSContext[IO]] = for {
    roots  <- Resource.eval(if (trust == Trust.TestCa) pem("ca.pem").map(List(_)) else IO.pure(Nil))
    config <- S2nConfig.builder.withWipedTrustStore.withPemsToTrustStore(roots).build[IO]
  } yield TLSContext.Builder.forAsync[IO].fromS2nConfig(config)

  def server(app: HttpApp[IO], identity: Identity = Identity.Valid): Resource[IO, Server] =
    serverContext(identity).flatMap { context =>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port"0")
        .withTLS(context)
        .withHttpApp(app)
        .withShutdownTimeout(2.seconds)
        .build
    }

  def client(trust: Trust = Trust.TestCa): Resource[IO, Client[IO]] = clientContext(trust).flatMap { context =>
    EmberClientBuilder.default[IO].withTLSContext(context).withTimeout(5.seconds).build
  }

  private def pem(name: String): IO[String] = IO.blocking {
    val input = Option(getClass.getResourceAsStream(s"/native-tls/$name"))
      .getOrElse(throw new IllegalStateException(s"Missing synthetic TLS fixture: $name"))
    Using.resource(Source.fromInputStream(input, "UTF-8"))(_.mkString)
  }
}
