package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

data class SetHrUserActive(val userId: PersonId, val isActive: Boolean, val actingUserId: PersonId)

/**
 * An admin enables or disables an account (ERT-190).
 *
 * **Deactivation, never deletion.** The four actor columns reference `users(id)` `on delete
 * restrict`, so a user who has created a hire or approved a document cannot be removed — and that is
 * the point rather than an obstacle: an audit trail naming a row that no longer exists is not a
 * trail. `is_active` is the off switch.
 *
 * **An admin may not deactivate themselves.** Not a courtesy: with a handful of staff and no
 * self-registration, the last admin switching themselves off leaves a system with no way to
 * administer it and no path back that does not involve a psql prompt. The check is here rather than
 * in the route because it is a rule, and a rule reachable only through a handler is a bug.
 *
 * This does **not** revoke a live token. The verifier reads claims and does not resolve the subject
 * per request, so a deactivated user keeps working for up to `JWT_TTL_MINUTES` — see [com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer]
 * for why that trade was taken and what the immediate control is instead.
 */
class SetHrUserActiveUseCase(
    private val users: HrUserRepository,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val tracer: UseCaseTracer,
) {
    suspend operator fun invoke(command: SetHrUserActive): DomainResult<HrUser> =
        tracer.trace("SetHrUserActiveUseCase") { execute(command) }

    private suspend fun execute(command: SetHrUserActive): DomainResult<HrUser> {
        if (command.userId == command.actingUserId && !command.isActive) {
            return AppError.Conflict(
                code = "user.cannot_deactivate_self",
                detail = "You cannot deactivate your own account",
            ).asErr()
        }

        val existing = users.findById(command.userId)
            ?: return AppError.NotFound(code = "user_not_found", entity = HrUser.AUDIT_ENTITY).asErr()

        if (existing.isActive == command.isActive) return existing.asOk()

        val now = clock.now()
        val updated = users.save(existing.copy(isActive = command.isActive))

        audit.record(
            AuditEntry(
                id = ids.newEntityId(),
                actor = command.actingUserId.value,
                actorUserId = command.actingUserId,
                action = if (command.isActive) AuditAction.USER_ACTIVATED else AuditAction.USER_DEACTIVATED,
                entity = HrUser.AUDIT_ENTITY,
                entityId = updated.id,
                timestamp = now,
                metadata = mapOf("email" to updated.email.value),
            )
        )

        return updated.asOk()
    }
}
