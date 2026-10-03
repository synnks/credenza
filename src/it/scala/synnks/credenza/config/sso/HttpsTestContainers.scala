package synnks.credenza.config.sso

import cats.effect.{ IO, Resource }
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import synnks.credenza.config.sso.HttpsTestCertificates.Identity

import java.time.Duration

final private[sso] case class HttpsTestConnection(baseUri: Uri, wrongHostUri: Uri, adminUri: Uri, caPem: String) {
  override def toString: String = "HttpsTestConnection(<redacted>)"
}

private[sso] object HttpsTestContainers {
  private val image        = DockerImageName.parse(
    "wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3"
  )
  private val adminPort    = 8080
  private val httpsPort    = 8443
  private val keyStorePath = "/home/wiremock/server.p12"

  val resource: Resource[IO, HttpsTestConnection] = for {
    valid      <- server(Identity.Valid)
    wrong      <- server(Identity.WrongHost)
    ca         <- Resource.eval(HttpsTestCertificates.caPem)
    connection <- Resource.eval(IO.blocking {
                    HttpsTestConnection(
                      endpoint(valid, Uri.Scheme.https, httpsPort),
                      endpoint(wrong, Uri.Scheme.https, httpsPort),
                      endpoint(valid, Uri.Scheme.http, adminPort),
                      ca
                    )
                  })
  } yield connection

  private def server(identity: Identity): Resource[IO, GenericContainer[?]] =
    Resource.eval(HttpsTestCertificates.pkcs12(identity)).flatMap { keyStore =>
      Resource
        .make(IO.blocking {
          val container: GenericContainer[?] = new GenericContainer(image)
          container
            .withExposedPorts(adminPort, httpsPort)
            .withLabel("credenza.fixture", "https")
            .withCopyToContainer(Transferable.of(keyStore), keyStorePath)
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
            .withStartupTimeout(Duration.ofSeconds(60))
          container
        })(container => IO.blocking(container.stop()))
        .evalTap(container => IO.blocking(container.start()))
    }

  private def endpoint(container: GenericContainer[?], scheme: Uri.Scheme, port: Int): Uri =
    Uri(
      scheme = Some(scheme),
      authority = Some(
        Uri.Authority(
          host = Uri.Host
            .fromString(container.getHost)
            .fold(_ => throw new IllegalStateException("Invalid Docker host"), identity),
          port = Some(container.getMappedPort(port).intValue)
        )
      )
    )
}
