package com.pgsystem.employee.requirement.tracker.plugin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
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
 */
@Serializable
data class ErrorResponse(val code: String, val message: String)

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(code = "internal_error", message = "An unexpected error occurred."),
            )
        }
    }
}
