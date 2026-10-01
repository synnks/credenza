package synnks.credenza.config.sso

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import org.http4s.Uri

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.util.Using

final private[sso] case class HttpsTestConnection(baseUri: Uri, wrongHostUri: Uri, adminUri: Uri, caPem: String) {
  override def toString: String = "HttpsTestConnection(<redacted>)"
}

private[sso] object HttpsTestConnection {
  val resource: Resource[IO, HttpsTestConnection] = for {
    directory <- fs2.io.file.Files[IO].tempDirectory(None, "credenza-https-", None).map(_.toNioPath)
    _         <- Resource.eval(IO.blocking {
                   val input = Option(getClass.getResourceAsStream("/https/compose.yaml"))
                     .getOrElse(throw new IllegalStateException("Missing HTTPS Compose fixture"))
                   Using.resource(input)(Files.copy(_, directory.resolve("compose.yaml")))
                 })
    compose   <- Resource
                   .make(IO.pure(new Compose(directory)))(_.run("down", "--volumes", "--timeout", "10").void)
                   .evalTap(_.run("up", "--wait", "--wait-timeout", "60", "wiremock", "wrong-host"))
    base      <- Resource.eval(compose.endpoint("wiremock", 8443, "https"))
    wrong     <- Resource.eval(compose.endpoint("wrong-host", 8443, "https"))
    admin     <- Resource.eval(compose.endpoint("wiremock", 8080, "http"))
    ca        <- Resource.eval(compose.run("exec", "-T", "wiremock", "cat", "/certificates/ca.pem"))
  } yield HttpsTestConnection(base, wrong, admin, ca)

  final private class Compose(directory: Path) {
    private val command = List(
      "docker",
      "compose",
      "--progress",
      "quiet",
      "--project-name",
      directory.getFileName.toString.toLowerCase(Locale.ROOT),
      "--file",
      directory.resolve("compose.yaml").toString
    )
    private val output  = directory.resolve("output")

    def endpoint(service: String, port: Int, scheme: String): IO[Uri] =
      run("port", service, port.toString).flatMap { value =>
        val port = value.trim.stripPrefix("127.0.0.1:").toIntOption.filter(p => p > 0 && p <= 65535)
        IO.fromOption(port.map(p => Uri.unsafeFromString(s"$scheme://127.0.0.1:$p")))(
          new IllegalStateException("Invalid Docker Compose port mapping")
        )
      }

    def run(args: String*): IO[String] = Resource
      .make(IO.blocking {
        new ProcessBuilder((command ++ args)*)
          .redirectOutput(output.toFile)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
      })(process =>
        IO.blocking {
          if (process.isAlive()) {
            process.destroyForcibly()
            if (!process.waitFor(5, TimeUnit.SECONDS))
              throw new IllegalStateException("Could not stop Docker Compose")
          }
        }
      )
      .use { process =>
        IO.interruptible {
          if (!process.waitFor(90, TimeUnit.SECONDS))
            throw new IllegalStateException(s"Docker Compose ${args.head} timed out")
          if (process.exitValue() != 0)
            throw new IllegalStateException(s"Docker Compose ${args.head} exited with status ${process.exitValue()}")
          val bytes = Using.resource(Files.newInputStream(output))(_.readNBytes(64 * 1024 + 1))
          if (bytes.length > 64 * 1024)
            throw new IllegalStateException("Docker Compose output exceeded 64 KiB")
          new String(bytes, StandardCharsets.UTF_8)
        }
      }
  }
}
