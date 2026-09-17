package com.pgsystem.employee.requirement.tracker.plugin

import com.pgsystem.employee.requirement.tracker.route.dto.ApiError
import com.pgsystem.employee.requirement.tracker.route.mapper.MappedErrorKey
import com.pgsystem.employee.requirement.tracker.route.mapper.NOT_FOUND_BODY
import com.pgsystem.employee.requirement.tracker.route.mapper.errorEnvelope
import com.pgsystem.employee.requirement.tracker.route.mapper.messageFor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

/**
 * Uniform error responses.
 *
 * The generator's handler echoed the exception back to the caller (`"500: $cause"`). That is a
 * disclosure problem in a system holding government IDs and medical records: stack traces and
 * driver messages name tables, file paths and library versions. The cause is logged server-side;
 * the client gets a code and nothing else (PRD 12).
 *
 * Bodies are the `/api` envelope (ERT-145), built by `route/mapper`. `Serialization.kt` sets
 * `explicitNulls = false`, so a null field is **omitted from the JSON entirely** rather than
 * rendered as `null` — that is what lets a denied response serialise to one constant body with
 * nothing in it that could differ between two causes.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // A malformed or unconvertible request body. Before ERT-140 this fell through to the
        // handler below and told the client the *server* had failed, which is both wrong and a
        // nuisance to debug — the same class of trap as the 500 a mistyped path id used to return.
        exception<BadRequestException> { call, cause ->
            call.application.log.info("Malformed request on ${call.request.local.uri}: ${cause.message}")
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                errorEnvelope(
                    HttpStatusCode.UnprocessableEntity,
                    ApiError(code = "request_malformed", message = messageFor("request_malformed")),
                ),
            )
        }

        // A body the server never got to read, because the request's `Content-Type` matched no
        // registered converter -- most often because the request carried none. ContentNegotiation
        // does not fail here: `convertRequestBody` skips every converter whose type does not match
        // (an absent header parses as `*/*`, which matches nothing), returns the raw bytes, and
        // `receive<T>()` throws on finding them. That exception is `ContentTransformationException :
        // IOException`, **not** a `BadRequestException`, so the arm above never saw it and it landed
        // in the 500 below -- which is what Swagger UI's "Try it out" produced on
        // `POST /api/auth/login`, having sent no body and no header because the route published no
        // `requestBody` schema for it to fill (ERT-146).
        //
        // **415 rather than 422**, which is also what Ktor's own `defaultExceptionStatusCode` gives
        // this exception before `StatusPages` displaces it. Nothing was parsed, so the payload is
        // not "well-formed but unprocessable" -- there may be no payload. The split is the one thing
        // a client can act on: 422 means fix the body, 415 means fix the header. It discloses
        // nothing, because it is decided from caller-supplied headers before any lookup happens.
        //
        // **The parent class, not `CannotTransformContentToTypeException`.** Its sibling
        // `UnsupportedMediaTypeException` is the identical mistake against a `receiveMultipart()`
        // handler, which ERT-710 writes; registering the narrow class here would hand the upload
        // route this same 500. Register a class and never an interface -- `selectNearestParentClass`
        // measures distance by walking `superclass`, and an interface has none.
        //
        // `cause.message` names the Kotlin type that could not be built. It stays in the log.
        exception<ContentTransformationException> { call, cause ->
            call.application.log.info(
                "Unreadable request content type on ${call.request.local.uri}: ${cause.message}"
            )
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                errorEnvelope(
                    HttpStatusCode.UnsupportedMediaType,
                    ApiError(
                        code = "unsupported_media_type",
                        message = messageFor("unsupported_media_type"),
                    ),
                ),
            )
        }

        // The only producer of `result: "error"`. It must never populate `details`: a 5xx names
        // nothing about the cause, which is logged here instead (PRD 12). Note that most 5xx a
        // client sees never reach this handler at all — a proxy 502 or a container OOM is answered
        // above the application — so a client cannot treat this envelope as the contract for 5xx.
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                errorEnvelope(
                    HttpStatusCode.InternalServerError,
                    ApiError(code = "internal_error", message = messageFor("internal_error")),
                ),
            )
        }

        // Ktor answers an unmatched route with a bare 404 and no body. Give it the same body a
        // denied response carries, so the two are indistinguishable.
        //
        // `status` handlers fire on the response status, including one a route has already
        // answered with a body of its own. Verified, not assumed: with the guard below removed,
        // `error mapping - a mapped 404 carrying a body - is not re-wrapped` fails, because every
        // mapped NotFound collapses into this body and the HR side loses the code naming which
        // entity was missing. `respondError` marks those calls so this handler leaves them alone.
        status(HttpStatusCode.NotFound) { call, _ ->
            if (!call.attributes.contains(MappedErrorKey)) call.respond(HttpStatusCode.NotFound, NOT_FOUND_BODY)
        }
    }
}
