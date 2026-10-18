package synnks.credenza.config.sso

import cats.effect.IO
import com.typesafe.sslconfig.ssl.{ SimpleSSLContextBuilder, StringBasedKeyStoreBuilder }
import fs2.io.net.tls.TLSContext
import fs2.{ text, Chunk }

import javax.net.ssl.TrustManagerFactory

private[sso] object HttpsTestCertificates {
  enum Identity(val file: String) {
    case Valid     extends Identity("valid.p12")
    case WrongHost extends Identity("wrong-host.p12")
  }
  val keyStorePassword: String = "synthetic-key-password"

  def caPem: IO[String] = resource("ca.pem").through(text.utf8.decode).compile.string

  def pkcs12(identity: Identity): IO[Chunk[Byte]] = resource(identity.file).compile.to(Chunk)

  def trustCertificate(pem: String): IO[TLSContext[IO]] = IO.blocking {
    val store   = new StringBasedKeyStoreBuilder(pem).build()
    val trust   = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    val context = new SimpleSSLContextBuilder("TLS", Nil, trust.getTrustManagers.toList, None).build()
    TLSContext.Builder.forAsync[IO].fromSSLContext(context)
  }

  private def resource(name: String): fs2.Stream[IO, Byte] =
    fs2.io.readClassResource[IO, HttpsTestCertificates.type](s"/tls/$name")
}
