package com.pgsystem.employee.requirement.tracker.plugin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/**
 * Uniform error responses.
 *
 * The generator's handler echoed the exception back to the caller (`"500: $cause"`). That is a
 * disclosure problem in a system holding government IDs and medical records: stack traces and
 * driver messages name tables, file paths and library versions. The cause is logged server-side;
 * the client gets a code and nothing else (PRD 12).
 *
 * `detail` and `field` are nullable and `Serialization.kt` sets `explicitNulls = false`, so a null
 * is **omitted from the JSON entirely** rather than rendered as `null`. That is what lets
 * [com.pgsystem.employee.requirement.tracker.core.error.AppError.Denied] serialise to exactly
 * `{"code":"not_found"}` — a body with nothing in it that could differ between two causes.
 */
@Serializable
data class ErrorResponse(
    val code: String,
    val detail: String? = null,
    val field: String? = null,
)

/**
 * The body every unmatched route and every `Denied` produces, to the byte.
 *
 * A mistyped portal sub-path and a denied one must look the same. If the framework's 404 were empty
 * while a denied 404 carried a body, the difference would answer "is this path real" and, one step
 * later, "is this link real" (PRD 6.6, SEC-01).
 */
internal val NOT_FOUND_BODY = ErrorResponse(code = "not_found")

fun Application.configureStatusPages() {
    install(StatusPages) {
        // A malformed or unconvertible request body. Before ERT-140 this fell through to the
        // handler below and told the client the *server* had failed, which is both wrong and a
        // nuisance to debug — the same class of trap as the 500 a mistyped path id used to return.
        exception<BadRequestException> { call, cause ->
            call.application.log.info("Malformed request on ${call.request.local.uri}: ${cause.message}")
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(code = "request.malformed", detail = "The request body could not be read."),
            )
        }

        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(code = "internal_error", detail = "An unexpected error occurred."),
            )
        }

        // Ktor answers an unmatched route with a bare 404 and no body. Given it the same body a
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

/**
 * Marks a call whose failure body was written by the `AppError` mapper.
 *
 * 401 and 405 deliberately keep Ktor's own handling. A `status(Unauthorized)` handler risks
 * dropping the `WWW-Authenticate` challenge the JWT plugin sets, and neither status discloses
 * anything about whether a link exists, so there is nothing to gain.
 */
internal val MappedErrorKey: io.ktor.util.AttributeKey<Unit> =
    io.ktor.util.AttributeKey("com.pgsystem.ert.mapped-error")
