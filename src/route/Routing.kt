package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.route.hr.referenceRoutes
import com.pgsystem.employee.requirement.tracker.route.hr.requirementTemplateRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

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
 *
 * **[authName] arrives as a parameter rather than being read here (ERT-340).** `HR_AUTH` lives in
 * `plugin/Security.kt`, and the dependency between the two packages runs `plugin` → `route`, never
 * the reverse — `ArchitectureTest` fails the build on a route importing `plugin`, and importing the
 * constant is the obvious thing to reach for. `Application.kt` supplies it, being outside `route/`
 * and already assembling the plugins. `MetricsRoutes` takes its gate the same way for the same
 * reason.
 *
 * **The gate is applied here, once, rather than inside each route file.** That keeps every file
 * under `route/hr` auth-agnostic, so a route test can mount a handler against a fake with no
 * security plugin at all — which matters while the scheme is still the Q4 placeholder and no test
 * can mint a token the application would accept.
 */
fun Application.configureRouting(authName: String) {
    val requirementTemplates by inject<RequirementTemplateRepository>()
    val reference by inject<ReferenceDataRepository>()

    routing {
        healthRoutes()

        authenticate(authName) {
            requirementTemplateRoutes(requirementTemplates)
            referenceRoutes(reference)
        }

        // portal routes -> route/portal, added with their use cases
    }
}
