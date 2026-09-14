package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.route.dto.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe

/**
 * Unauthenticated liveness probe. Reports no personal data and no configuration.
 *
 * The `describe { }` block is the pattern every route should follow: documentation lives beside the
 * handler, so the published spec cannot drift from the code. Routes that must not appear in the
 * public spec use `hide()` instead.
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
    }
}

object BuildInfo {
    const val VERSION = "0.1.0-SNAPSHOT"
}
