package com.pgsystem.employee.requirement.tracker.route

import io.ktor.server.application.Application
import io.ktor.server.routing.routing

/**
 * The single place routes are mounted.
 *
 * Route files are thin adapters: they parse a request, call one use case, and map the result onto a
 * status code. A business decision must never be reachable only through a handler — it belongs in a
 * use case where it can be tested without a server.
 *
 * HR routes mount under `/api`, portal routes under `/api/portal`, and the two are kept separate
 * because they authenticate differently and, more importantly, because the portal may never return
 * document content (PRD 8.6).
 */
fun Application.configureRouting() {
    routing {
        healthRoutes()
        // hr routes    -> route/hr,     added with their use cases
        // portal routes -> route/portal, added with their use cases
    }
}
