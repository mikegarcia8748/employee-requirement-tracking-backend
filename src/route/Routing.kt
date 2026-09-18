package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.usecase.AuthenticateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ChangeHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHireUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ResetHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.SetHrUserActiveUseCase
import com.pgsystem.employee.requirement.tracker.route.hr.accountRoutes
import com.pgsystem.employee.requirement.tracker.route.hr.employeeRoutes
import com.pgsystem.employee.requirement.tracker.route.hr.referenceRoutes
import com.pgsystem.employee.requirement.tracker.route.hr.requirementTemplateRoutes
import com.pgsystem.employee.requirement.tracker.route.hr.signInRoutes
import com.pgsystem.employee.requirement.tracker.route.hr.userAdminRoutes
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
 * security plugin at all.
 *
 * **`signInRoutes` is the one HR route outside `authenticate`, and has to be (ERT-190).** It is how
 * a caller *obtains* a token; requiring one would make the application unreachable. Everything else
 * under `/api` is inside, and the second gate — role, and whether a password change is owed — is
 * applied per handler by `route/auth/AuthGates.kt`, which explains why it is not an interceptor here.
 */
fun Application.configureRouting(authName: String) {
    val requirementTemplates by inject<RequirementTemplateRepository>()
    val reference by inject<ReferenceDataRepository>()
    val users by inject<HrUserRepository>()
    val audit by inject<AuditLog>()
    val clock by inject<Clock>()
    val ids by inject<EntityIdGenerator>()

    val authenticateHrUser by inject<AuthenticateHrUserUseCase>()
    val changeHrPassword by inject<ChangeHrPasswordUseCase>()
    val createHrUser by inject<CreateHrUserUseCase>()
    val setHrUserActive by inject<SetHrUserActiveUseCase>()
    val resetHrPassword by inject<ResetHrPasswordUseCase>()
    val createHire by inject<CreateHireUseCase>()

    routing {
        healthRoutes()

        signInRoutes(authenticateHrUser)

        authenticate(authName) {
            accountRoutes(changeHrPassword, users)
            userAdminRoutes(users, createHrUser, setHrUserActive, resetHrPassword, audit, clock, ids)
            requirementTemplateRoutes(requirementTemplates)
            referenceRoutes(reference)
            employeeRoutes(createHire)
        }

        // portal routes -> route/portal, added with their use cases
    }
}
