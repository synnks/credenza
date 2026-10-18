package synnks.credenza.config.sso

import cats.effect.{ IO, Resource }
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.interop.reactivestreams.{ fromPublisher, StreamUnicastPublisher }
import org.reactivestreams.Publisher
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.interceptor.{
  Context,
  ExecutionAttribute,
  ExecutionAttributes,
  ExecutionInterceptor
}

import java.nio.ByteBuffer
import java.util.Optional

/** Bounds success and error bodies before the SDK buffers and unmarshals them. */
final private[sso] class SsoResponseLimit private (dispatcher: Dispatcher[IO]) extends ExecutionInterceptor {
  override def modifyAsyncHttpResponseContent(
    context: Context.ModifyHttpResponse,
    attributes: ExecutionAttributes
  ): Optional[Publisher[ByteBuffer]] = context.responsePublisher().map(source => limit(source, attributes))

  override def modifyException(context: Context.FailedExecution, attributes: ExecutionAttributes): Throwable =
    Option(attributes.getAttribute(SsoResponseLimit.exceeded))
      .fold(context.exception())(error => SdkClientException.create("SSO response exceeded the byte limit", error))

  def limit(
    source: Publisher[ByteBuffer],
    attributes: ExecutionAttributes = new ExecutionAttributes
  ): Publisher[ByteBuffer] =
    new StreamUnicastPublisher(
      fromPublisher[IO, ByteBuffer](source, bufferSize = 1)
        .mapAccumulate(0L)((total, bytes) => (total + bytes.remaining(), bytes))
        .evalMap { case (total, bytes) =>
          IO.raiseWhen(total > SsoApiClient.MaxResponseBytes)(new SsoResponseLimit.Exceeded)
            .onError { case error: SsoResponseLimit.Exceeded =>
              IO.delay(attributes.putAttribute(SsoResponseLimit.exceeded, error))
            }
            .as(bytes)
        },
      dispatcher
    )
}

private[sso] object SsoResponseLimit {
  final class Exceeded extends RuntimeException("SSO response exceeded the byte limit")

  // Netty completes its request future on upstream cancellation, before FS2 publishes the stream failure.
  private val exceeded = new ExecutionAttribute[Exceeded]("credenza-sso-response-limit-exceeded")

  def resource: Resource[IO, SsoResponseLimit] =
    Dispatcher.parallel[IO](await = false).map(new SsoResponseLimit(_))
}
