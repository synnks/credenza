package fs2.io.net.tls

import cats.effect.IO
import fs2.io.net.Socket

import scala.annotation.nowarn

/** Test-only, server-only adaptation for FS2 3.14.0 / s2n 1.7.10.
  * On a live connection, s2n_get_application_protocol returns a protocol or NULL for absence.
  * It performs no handshake or I/O. FS2's pointer guard misinterprets NULL using stale s2n_errno.
  * See https://github.com/http4s/http4s/issues/7917.
  */
object NativeAlpnCompat {
  @nowarn("cat=deprecation") // Socket still requires these legacy accessors.
  def serverContext(underlying: TLSContext[IO]): TLSContext[IO] = new TLSContext.UnsealedTLSContext[IO] {
    def clientBuilder(socket: Socket[IO]): TLSContext.SocketBuilder[IO, TLSSocket] =
      underlying.clientBuilder(socket)

    def serverBuilder(socket: Socket[IO]): TLSContext.SocketBuilder[IO, TLSSocket] =
      TLSContext.SocketBuilder[IO, TLSSocket] { (parameters, logger) =>
        underlying.serverBuilder(socket).withParameters(parameters).withLogger(logger).build.map { delegate =>
          new TLSSocket.UnsealedTLSSocket[IO] {
            export delegate.{
              address,
              endOfInput,
              endOfOutput,
              getOption,
              isOpen,
              localAddress,
              peerAddress,
              read,
              readN,
              reads,
              remoteAddress,
              session,
              setOption,
              supportedOptions,
              write,
              writes
            }

            // Deliberately scoped to this getter; acquisition, handshake, and socket I/O are not recovered.
            def applicationProtocol: IO[String] = delegate.applicationProtocol.adaptError { case _: S2nException =>
              new NoSuchElementException("No ALPN protocol was negotiated")
            }
          }
        }
      }
  }
}
