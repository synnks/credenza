package synnks.credenza.config.sso

import cats.effect.IO
import fs2.{ text, Chunk }

private[sso] object HttpsTestCertificates {
  enum Identity(val file: String) {
    case Valid     extends Identity("valid.p12")
    case WrongHost extends Identity("wrong-host.p12")
  }
  val keyStorePassword: String = "synthetic-key-password"

  def caPem: IO[String] = resource("ca.pem").through(text.utf8.decode).compile.string

  def pkcs12(identity: Identity): IO[Chunk[Byte]] = resource(identity.file).compile.to(Chunk)

  private def resource(name: String): fs2.Stream[IO, Byte] =
    fs2.io.readClassResource[IO, HttpsTestCertificates.type](s"/tls/$name")
}
