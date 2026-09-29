package synnks.credenza.config.sso

import cats.data.{ EitherNec, ValidatedNec }
import cats.syntax.all.*
import io.circe.{ Json, JsonObject }
import io.circe.parser.parse
import synnks.credenza.config.model.{ Region, SsoSession, SsoStartUrl }
import SsoCachedToken.{ Error as SsoTokenError, RefreshMaterial, Secret }

import java.time.{ Instant, OffsetDateTime }
import scala.util.Try

private[config] object SsoTokenDecoder {
  final private class Field[A](val key: String, val decode: String => Option[A])

  private object Fields {
    val startUrl              = new Field("startUrl", value => SsoStartUrl.from(value).toOption)
    val region                = new Field("region", value => Region.from(value).toOption)
    val accessToken           = new Field("accessToken", Secret.from)
    val expiresAt             = new Field("expiresAt", timestamp)
    val clientId              = new Field("clientId", Secret.from)
    val clientSecret          = new Field("clientSecret", Secret.from)
    val registrationExpiresAt = new Field("registrationExpiresAt", timestamp)
    val refreshToken          = new Field("refreshToken", Secret.from)
  }

  def decode(text: String, session: SsoSession): EitherNec[SsoTokenError, SsoCachedToken] =
    for {
      json     <- parse(text).leftMap(_ => SsoTokenError.MalformedJson).toEitherNec
      fields   <- json.asObject.toRight(SsoTokenError.MalformedJson).toEitherNec
      token    <- (
                    required(fields, Fields.startUrl),
                    required(fields, Fields.region),
                    required(fields, Fields.accessToken),
                    required(fields, Fields.expiresAt),
                    refresh(fields)
                  ).mapN(SsoCachedToken.apply).toEither
      selected <- Either
                    .cond(
                      token.startUrl.value == session.startUrl.value && token.region == session.region,
                      token,
                      SsoTokenError.SessionMismatch
                    )
                    .toEitherNec
    } yield selected

  private def refresh(fields: JsonObject): ValidatedNec[SsoTokenError, Option[RefreshMaterial]] =
    (
      optional(fields, Fields.clientId),
      optional(fields, Fields.clientSecret),
      optional(fields, Fields.refreshToken),
      optional(fields, Fields.registrationExpiresAt)
    ).mapN { (clientId, clientSecret, refreshToken, expiresAt) =>
      for {
        id     <- clientId
        secret <- clientSecret
        token  <- refreshToken
        expiry <- expiresAt
      } yield RefreshMaterial(id, secret, token, expiry)
    }

  private def required[A](fields: JsonObject, field: Field[A]): ValidatedNec[SsoTokenError, A] =
    fields(field.key)
      .toValidNec(SsoTokenError.MissingField(field.key))
      .andThen(value => decode(value, field))

  private def optional[A](fields: JsonObject, field: Field[A]): ValidatedNec[SsoTokenError, Option[A]] =
    fields(field.key).traverse(value => decode(value, field))

  private def decode[A](json: Json, field: Field[A]): ValidatedNec[SsoTokenError, A] =
    json.asString.flatMap(field.decode).toValidNec(SsoTokenError.InvalidField(field.key))

  private def timestamp(value: String): Option[Instant] = {
    val iso = if (value.endsWith("UTC")) value.stripSuffix("UTC") + "Z" else value
    Try(OffsetDateTime.parse(iso).toInstant).toOption
  }
}
