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
