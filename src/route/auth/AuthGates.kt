package com.pgsystem.employee.requirement.tracker.route.auth

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.route.mapper.respondError
import io.ktor.server.auth.principal
import io.ktor.server.routing.RoutingContext

/**
 * The two gates that sit between `authenticate` and a handler (ERT-190).
 *
 * `authenticate(HR_AUTH)` proves the caller holds a token this application signed. It says nothing
 * about whether they may do **this**, and the two questions that remain are asked here:
 *
 *  - **Has the password been changed yet?** A bootstrap account and every HR-issued reset carry
 *    `passwordChangeRequired`, and the credential is sitting in a deployment environment or a chat
 *    message. Every route but change-password refuses until it is replaced.
 *  - **Does the role carry this action?** `HR_ADMIN` only, for the administration surface.
 *
 * ### Why these are functions a handler calls rather than a pipeline interceptor
 *
 * An interceptor around the whole `authenticate` block would be one line in `Routing.kt` and would
 * be impossible to forget — genuinely the stronger property. It is not used because the exception
 * has to be expressible: change-password must run *while* the gate would refuse, and carving one
 * route out of an interceptor means either a path allowlist (which a rename silently breaks) or a
 * second `authenticate` block that no longer shares the gate at all. An explicit call per handler is
 * visible in the file it protects, and `AuthRoutesTest` covers the refusal on a real route rather
 * than on a mock pipeline.
 *
 * **The decision itself is not here.** [com.pgsystem.employee.requirement.tracker.domain.model.HrRole.canAdminister]
 * is the rule; this maps its answer onto a status code, which is all a route may do.
 */

/**
 * The caller, or null having already answered 401.
 *
 * Null is only reachable when a handler is mounted outside `authenticate`, which is a wiring mistake
 * rather than a request the client can make — but it must not become a null-pointer 500, and a
 * handler that ignored the null would run unauthenticated.
 */
suspend fun RoutingContext.hrPrincipalOrRefuse(): HrPrincipal? {
    val principal = call.principal<HrPrincipal>()
    if (principal == null) call.respondError(AppError.AuthenticationFailed)
    return principal
}

/**
 * The caller, once past the password gate — or null having already answered.
 *
 * Every HR route calls this. Change-password calls [hrPrincipalOrRefuse] instead, and is the only
 * thing in the codebase that may.
 */
suspend fun RoutingContext.hrUserOrRefuse(): HrPrincipal? {
    val principal = hrPrincipalOrRefuse() ?: return null

    if (principal.passwordChangeRequired) {
        // Conflict, not Forbidden: the caller is entitled to this route and will be again the moment
        // they act. 403 would read as "not for you" and send them to an administrator; 409 with this
        // code names the one thing that unblocks it.
        call.respondError(
            AppError.Conflict(
                code = "password_change_required",
                detail = "Change your password before using this application",
            )
        )
        return null
    }

    return principal
}

/**
 * The caller, once past the password gate **and** holding `HR_ADMIN` — or null having answered 403.
 *
 * The refusal is audited, which the plain gates above are not. A signed-in user reaching for
 * something their role does not carry is a §8.13 signal; an unauthenticated 401 is background noise
 * on any public endpoint.
 */
suspend fun RoutingContext.hrAdminOrRefuse(
    audit: AuditLog,
    clock: Clock,
    ids: EntityIdGenerator,
    action: String,
): HrPrincipal? {
    val principal = hrUserOrRefuse() ?: return null
    if (principal.role.canAdminister) return principal

    audit.record(
        AuditEntry(
            id = ids.newEntityId(),
            actor = principal.userId.value,
            actorUserId = principal.userId,
            action = AuditAction.ACCESS_DENIED,
            entity = HrUser.AUDIT_ENTITY,
            entityId = principal.userId,
            timestamp = clock.now(),
            // The attempted action and the role held. Not the path: a path can carry an id, and
            // this row is written from wherever a gate is placed in future, portal paths included.
            metadata = mapOf("attempted" to action, "role" to principal.role.name),
        )
    )

    call.respondError(AppError.Forbidden)
    return null
}
