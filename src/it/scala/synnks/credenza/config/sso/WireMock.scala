package synnks.credenza.config.sso

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{ MediaType, Method, Request, Status, Uri }
import org.typelevel.ci.CIString
import synnks.credenza.config.sso.WireMock.*

import scala.concurrent.duration.{ Duration, FiniteDuration }

final private[sso] class WireMock(http: Client[IO], admin: Uri) {
  def stub(request: RequestPattern, response: StubResponse): IO[Unit] =
    http.status(post(admin.path / "__admin" / "mappings", Mapping(request, response))).flatMap { status =>
      IO.raiseUnless(status == Status.Created)(
        new IllegalStateException(s"WireMock rejected the stub: HTTP ${status.code}")
      )
    }

  def recordedRequests(request: RequestPattern): IO[List[RecordedRequest]] =
    http
      .expect[String](post(admin.path / "__admin" / "requests" / "find", request))
      .flatMap(body =>
        IO.fromEither(
          decode[RequestJournal](body).left.map(_ => new IllegalStateException("Invalid WireMock request journal"))
        )
      )
      .map(_.requests)

  private def post[A: Encoder](path: Uri.Path, value: A): Request[IO] =
    Request[IO](Method.POST, admin.withPath(path))
      .withEntity(value.asJson.noSpaces)
      .putHeaders(`Content-Type`(MediaType.application.json))
}

private[sso] object WireMock {
  private given Codec[Method]        = Codec.from(
    Decoder.decodeString.emap(value => Method.fromString(value).left.map(_ => "Invalid HTTP method")),
    Encoder.encodeString.contramap(_.name)
  )
  private given Encoder[Status]      = Encoder.encodeInt.contramap(_.code)
  private given Encoder[Uri.Path]    = Encoder.encodeString.contramap(_.renderString)
  private given Decoder[Uri]         =
    Decoder.decodeString.emap(value => Uri.fromString(value).left.map(_ => "Invalid request URI"))
  private given KeyEncoder[CIString] = KeyEncoder.instance(_.toString)

  sealed trait StringMatch {
    final override def toString: String = "StringMatch(<redacted>)"
  }
  object StringMatch       {
    final case class EqualTo(equalTo: String) extends StringMatch derives Encoder.AsObject
    case object Absent                        extends StringMatch

    final private case class Absence(absent: Boolean) derives Encoder.AsObject

    // WireMock uses untagged matcher objects, rather than Circe's tagged sum representation.
    given Encoder[StringMatch] = Encoder.instance {
      case value: EqualTo => value.asJson
      case Absent         => Absence(true).asJson
    }
  }

  final case class JsonMatch(equalToJson: Json) derives Encoder.AsObject {
    override def toString: String = "JsonMatch(<redacted>)"
  }
  object JsonMatch                                                       {
    def equalTo[A: Encoder](value: A): JsonMatch = JsonMatch(value.asJson)
  }

  final case class RequestPattern(
    method: Method,
    urlPath: Uri.Path,
    headers: Map[CIString, StringMatch] = Map.empty,
    queryParameters: Map[String, StringMatch] = Map.empty,
    bodyPatterns: List[JsonMatch] = Nil
  ) derives Encoder.AsObject {
    override def toString: String = "RequestPattern(<redacted>)"
  }

  sealed trait StubResponse {
    final override def toString: String = "StubResponse(<redacted>)"
  }
  object StubResponse       {
    final private case class JsonResponse(
      status: Status,
      jsonBody: Json,
      headers: Map[CIString, String],
      fixedDelayMilliseconds: Long
    ) extends StubResponse derives Encoder.AsObject
    final private case class RawResponse(status: Status, body: String, headers: Map[CIString, String])
        extends StubResponse derives Encoder.AsObject

    private val headers = Map(CIString("Content-Type") -> "application/json")

    def json[A: Encoder](status: Status, body: A, delay: FiniteDuration = Duration.Zero): StubResponse =
      JsonResponse(status, body.asJson, headers, delay.toMillis)

    def raw(status: Status, body: String): StubResponse = RawResponse(status, body, headers)

    given Encoder[StubResponse] = Encoder.instance {
      case value: JsonResponse => value.asJson
      case value: RawResponse  => value.asJson
    }
  }

  final case class RecordedRequest(method: Method, url: Uri, body: Option[String]) derives Decoder {
    override def toString: String = "RecordedRequest(<redacted>)"
  }
  final private case class RequestJournal(requests: List[RecordedRequest]) derives Decoder
  final private case class Mapping(request: RequestPattern, response: StubResponse) derives Encoder.AsObject
}
