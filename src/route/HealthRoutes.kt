package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.route.dto.HealthResponse
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe

/**
 * Unauthenticated liveness probe. Reports no personal data and no configuration.
 *
 * **Deliberately outside the `/api` response envelope (ERT-145).** The base path of the API is
 * `/api`; this is an operational probe whose consumer is monitoring, not the front-end. Wrapping it
 * would change a body that deployment checks already read.
 *
 * The `describe { }` block is the pattern every route must follow, and the `responses` block in it
 * is not optional: the generator does **not** infer a response schema from `call.respond`, so a
 * route without one publishes an operation with no body type at all and nothing a client can
 * generate from. Routes that must not appear in the public spec use `hide()` instead.
 */
fun Route.healthRoutes() {
    get("/health") {
        call.respond(
            HealthResponse(
                status = "UP",
                service = "employee-requirements-tracker",
                version = BuildInfo.VERSION,
            )
        )
    }.describe {
        summary = "Liveness probe"
        description = "Returns UP when the service is accepting requests. Carries no personal data."
        operationId = "health"
        tag("Operations")
        responses {
            response(200) {
                description = "The service is accepting requests."
                schema = jsonSchema<HealthResponse>()
            }
        }
    }
}

object BuildInfo {
    const val VERSION = "0.1.0-SNAPSHOT"
}
