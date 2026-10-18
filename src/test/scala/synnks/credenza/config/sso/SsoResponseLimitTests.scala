package synnks.credenza.config.sso

import cats.effect.{ Deferred, IO }
import cats.syntax.all.*
import fs2.Stream
import fs2.interop.reactivestreams.*
import munit.CatsEffectSuite

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class SsoResponseLimitTests extends CatsEffectSuite {
  private def withLimited[A](source: Stream[IO, ByteBuffer])(use: Stream[IO, ByteBuffer] => IO[A]): IO[A] =
    (for {
      publisher <- source.toUnicastPublisher
      limiter   <- SsoResponseLimit.resource
    } yield fromPublisher[IO, ByteBuffer](limiter.limit(publisher), bufferSize = 1)).use(use)

  test("accept a response exactly at the byte limit across multiple chunks") {
    val first  = ByteBuffer.allocate(SsoApiClient.MaxResponseBytes - 1)
    val second = ByteBuffer.allocate(1)
    withLimited(Stream(first, second).covary[IO]) { limited =>
      limited.compile.toList.map(result => assertEquals(result, List(first, second)))
    }
  }

  test("cancel upstream without pulling the tail when a response crosses the limit") {
    for {
      canceled <- Deferred[IO, Unit]
      source    = (Stream(ByteBuffer.allocate(SsoApiClient.MaxResponseBytes), ByteBuffer.allocate(1)).covary[IO] ++
                    Stream.eval(IO(fail("Response tail must not be pulled"))))
                    .onFinalize(canceled.complete(()).void)
      _        <- withLimited(source) { limited =>
                    for {
                      result <- limited.compile.drain.attempt
                      _      <- canceled.get.timeout(3.seconds)
                    } yield assert(result.swap.toOption.exists(_.isInstanceOf[SsoResponseLimit.Exceeded]))
                  }
    } yield ()
  }

  test("measure bytes rather than decoded characters, including the first chunk") {
    val multibyte = ("é" * (SsoApiClient.MaxResponseBytes / 2 + 1)).getBytes(StandardCharsets.UTF_8)
    withLimited(Stream.emit(ByteBuffer.wrap(multibyte))) { limited =>
      limited.compile.drain.attempt.map(result =>
        assert(result.swap.toOption.exists(_.isInstanceOf[SsoResponseLimit.Exceeded]))
      )
    }
  }

  test("respect a read-only buffer's position and limit without changing them") {
    val buffer = ByteBuffer
      .wrap(new Array[Byte](SsoApiClient.MaxResponseBytes + 20), 10, SsoApiClient.MaxResponseBytes)
      .asReadOnlyBuffer()
    withLimited(Stream.emit(buffer)) { limited =>
      limited.compile.toList.map { result =>
        assertEquals(result.size, 1)
        assert(result.head eq buffer)
        assertEquals(buffer.position(), 10)
        assertEquals(buffer.limit(), SsoApiClient.MaxResponseBytes + 10)
      }
    }
  }

  test("propagate downstream cancellation without reading ahead") {
    for {
      canceled <- Deferred[IO, Unit]
      first     = ByteBuffer.allocate(1)
      source    = (Stream.emit(first) ++ Stream.eval(IO(fail("Response tail must not be pulled"))))
                    .onFinalize(canceled.complete(()).void)
      _        <- withLimited(source) { limited =>
                    for {
                      result <- limited.take(1).compile.toList
                      _      <- canceled.get.timeout(3.seconds)
                    } yield assertEquals(result, List(first))
                  }
    } yield ()
  }

  test("count each subscription independently") {
    val buffer = ByteBuffer.allocate(SsoApiClient.MaxResponseBytes)
    withLimited(Stream.emit(buffer)) { limited =>
      (1 to 2).toList.traverse_ { _ =>
        limited.compile.toList.map(result => assertEquals(result, List(buffer)))
      }
    }
  }

  test("complete an empty response and propagate upstream failures unchanged") {
    val failure = new IOException("synthetic transport failure")
    withLimited(Stream.empty) { limited =>
      limited.compile.toList.map(result => assertEquals(result, List.empty[ByteBuffer]))
    } *> withLimited(Stream.raiseError[IO](failure)) { limited =>
      limited.compile.drain.attempt.map(result => assertEquals(result, Left(failure)))
    }
  }
}
