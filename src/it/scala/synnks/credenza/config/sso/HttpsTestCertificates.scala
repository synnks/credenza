package synnks.credenza.config.sso

import cats.effect.IO
import fs2.io.net.tls.TLSContext

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.nio.charset.StandardCharsets
import java.security.{ KeyFactory, KeyStore }
import java.security.cert.{ Certificate, CertificateFactory }
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.{ SSLContext, TrustManagerFactory }
import scala.io.Source
import scala.util.Using

private[sso] object HttpsTestCertificates {
  enum Identity(val file: String) {
    case Valid     extends Identity("valid.pem")
    case WrongHost extends Identity("wrong-host.pem")
  }
  val keyStorePassword: String = "synthetic-key-password"

  def caPem: IO[String] = pem("ca.pem")

  private def keyStore(identity: Identity): IO[KeyStore] = for {
    certificatePem <- pem(identity.file)
    keyPem         <- pem("server-key.pem")
    store          <- IO.blocking {
                        val keyBytes = Base64.getMimeDecoder.decode(
                          keyPem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                        )
                        val key      = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes))
                        val store    = emptyKeyStore()
                        store.setKeyEntry("server", key, keyStorePassword.toCharArray, Array(certificate(certificatePem)))
                        store
                      }
  } yield store

  def pkcs12(identity: Identity): IO[Array[Byte]] = keyStore(identity).flatMap { store =>
    IO.blocking {
      Using.resource(new ByteArrayOutputStream) { output =>
        store.store(output, keyStorePassword.toCharArray)
        output.toByteArray
      }
    }
  }

  def trustCertificate(pem: String): IO[TLSContext[IO]] = IO.blocking {
    val store   = emptyKeyStore()
    store.setCertificateEntry("ca", certificate(pem))
    val trust   = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    val context = SSLContext.getInstance("TLS")
    context.init(null, trust.getTrustManagers, null)
    TLSContext.Builder.forAsync[IO].fromSSLContext(context)
  }

  private def emptyKeyStore(): KeyStore = {
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, null)
    store
  }

  private def certificate(pem: String): Certificate =
    Using.resource(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))) {
      CertificateFactory.getInstance("X.509").generateCertificate
    }

  private def pem(name: String): IO[String] = IO.blocking {
    val input = Option(getClass.getResourceAsStream(s"/tls/$name"))
      .getOrElse(throw new IllegalStateException(s"Missing synthetic TLS fixture: $name"))
    Using.resource(Source.fromInputStream(input, "UTF-8"))(_.mkString)
  }
}
