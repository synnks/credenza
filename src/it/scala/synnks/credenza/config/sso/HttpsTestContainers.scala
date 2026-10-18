package synnks.credenza.config.sso

import cats.effect.{ IO, Resource }
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import sttp.model.Uri
import synnks.credenza.config.sso.HttpsTestCertificates.Identity

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

private[sso] object HttpsTestContainers {
  private val image          = DockerImageName.parse(
    "wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3"
  )
  private val adminPort      = 8080
  private val httpsPort      = 8443
  private val keyStorePath   = "/home/wiremock/server.p12"
  private val startupTimeout = 60.seconds

  final private case class Server(host: String, httpsPort: Int, adminPort: Int) {
    def baseUri: Uri = Uri("https", host, httpsPort)
  }

  val resource: Resource[IO, HttpsTestConnection] = for {
    valid <- server(Identity.Valid)
    wrong <- server(Identity.WrongHost)
    ca    <- Resource.eval(HttpsTestCertificates.caPem)
  } yield HttpsTestConnection(valid.baseUri, wrong.baseUri, valid.host, valid.adminPort, ca)

  private def server(identity: Identity): Resource[IO, Server] =
    Resource.eval(HttpsTestCertificates.pkcs12(identity)).flatMap { keyStore =>
      Resource
        .make(IO.blocking {
          val container: GenericContainer[?] = new GenericContainer(image)
          container
            .withExposedPorts(adminPort, httpsPort)
            .withLabel("credenza.fixture", "https")
            .withCopyToContainer(Transferable.of(keyStore.toArray), keyStorePath)
            .withCommand(
              "--https-port",
              httpsPort.toString,
              "--https-keystore",
              keyStorePath,
              "--keystore-type",
              "PKCS12",
              "--keystore-password",
              HttpsTestCertificates.keyStorePassword,
              "--key-manager-password",
              HttpsTestCertificates.keyStorePassword,
              "--disable-http2-tls",
              "--disable-request-logging",
              "--disable-banner"
            )
            .waitingFor(Wait.forHttp("/__admin/mappings").forPort(adminPort).forStatusCode(200))
            .withStartupTimeout(startupTimeout.toJava)
          container
        })(container => IO.blocking(container.stop()))
        .evalTap(container => IO.blocking(container.start()))
        .evalMap(container =>
          IO.blocking(Server(container.getHost, container.getMappedPort(httpsPort), container.getMappedPort(adminPort)))
        )
    }
}
