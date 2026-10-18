package synnks.credenza.config.sso

import cats.effect.{ Deferred, IO, Resource }
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.typesafe.sslconfig.ssl.StringBasedKeyStoreBuilder
import io.netty.handler.ssl.SslProvider
import software.amazon.awssdk.http.async.{ AsyncExecuteRequest, SdkAsyncHttpClient }
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import sttp.model.Uri
import synnks.credenza.config.model.Region

import java.util.concurrent.CompletableFuture
import javax.net.ssl.{ SSLException, TrustManager, TrustManagerFactory }
import scala.concurrent.duration.FiniteDuration

private[sso] object HttpsTestTls {
  enum Trust {
    case TestCa, System
  }

  enum TransportFailure {
    case Tls, Other
  }

  def client(
    connection: HttpsTestConnection,
    region: Region,
    base: Uri,
    timeout: FiniteDuration,
    trust: Trust = Trust.TestCa
  ): Resource[IO, (SsoApiClient, IO[TransportFailure])] =
    for {
      failure    <- Resource.eval(Deferred[IO, TransportFailure])
      dispatcher <- Dispatcher.sequential[IO]
      api        <- SsoApiClient.resourceWithTransport(
                      region,
                      http(
                        connection.caPem,
                        trust,
                        error => dispatcher.unsafeRunAndForget(failure.complete(transportFailure(error)).void)
                      ),
                      timeout,
                      Some(base.toJavaUri),
                      Some(base.toJavaUri)
                    )
    } yield (api, failure.get)

  private def http(caPem: String, trust: Trust, onFailure: Throwable => Unit): Resource[IO, SdkAsyncHttpClient] =
    for {
      managers   <- Resource.eval(trust match {
                      case Trust.TestCa => trustManagers(caPem).map(Some(_))
                      case Trust.System => IO.pure(None)
                    })
      underlying <- Resource.fromAutoCloseable(IO.blocking {
                      val builder = NettyNioAsyncHttpClient.builder().sslProvider(SslProvider.JDK).maxConcurrency(1)
                      managers.foreach(values => builder.tlsTrustManagersProvider(() => values))
                      builder.build()
                    })
    } yield new SdkAsyncHttpClient {
      override def execute(request: AsyncExecuteRequest): CompletableFuture[Void] = {
        val future = underlying.execute(request)
        future.whenComplete((_, error) => Option(error).foreach(onFailure))
        future
      }
      override def close(): Unit                                                  = ()
    }

  private def transportFailure(error: Throwable): TransportFailure =
    if (
      SsoSdkErrors.causes(error).exists {
        case _: SSLException => true
        case _               => false
      }
    ) TransportFailure.Tls
    else TransportFailure.Other

  private def trustManagers(pem: String): IO[Array[TrustManager]] = IO.blocking {
    val store = new StringBasedKeyStoreBuilder(pem).build()
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    trust.getTrustManagers
  }
}
