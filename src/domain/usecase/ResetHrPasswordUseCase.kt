package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.error.flatMap
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.model.PasswordPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

data class ResetHrPassword(val userId: PersonId, val newPassword: String, val actingUserId: PersonId)

/**
 * An admin resets someone's password (ERT-190).
 *
 * **This is the whole of password recovery in v1, and that is a scope decision.** Self-service reset
 * needs a mail transport (ERT-1010) and introduces a second bearer credential with its own expiry
 * and threat model. For a handful of people who share an office, an admin resetting and the user
 * changing at next sign-in is enough — and it has the property a mailed reset link does not: the
 * admin knows who they handed it to.
 *
 * [HrUser.passwordChangeRequired] is set unconditionally, so the credential the admin chose works
 * exactly once and only for [ChangeHrPasswordUseCase]. Without that, an admin would be left holding
 * a working password for someone else's account indefinitely.
 *
 * Resetting your own password through here is allowed: it is the same outcome as changing it, minus
 * knowing the current one, and an admin already has that power over every other account. Denying it
 * would only push an admin who forgot their password towards asking another admin — which works
 * right up until there is one admin.
 */
class ResetHrPasswordUseCase(
    private val users: HrUserRepository,
    private val hasher: Hasher,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val tracer: UseCaseTracer,
) {
    suspend operator fun invoke(command: ResetHrPassword): DomainResult<Unit> =
        tracer.trace("ResetHrPasswordUseCase") { execute(command) }

    private suspend fun execute(command: ResetHrPassword): DomainResult<Unit> =
        PasswordPolicy.validate(command.newPassword).flatMap { password -> reset(command, password) }

    private suspend fun reset(command: ResetHrPassword, password: String): DomainResult<Unit> {
        val existing = users.findById(command.userId)
            ?: return AppError.NotFound(code = "user_not_found", entity = HrUser.AUDIT_ENTITY).asErr()

        val now = clock.now()

        users.save(
            existing.copy(
                passwordHash = hasher.hash(password),
                passwordChangeRequired = true,
            )
        )

        audit.record(
            AuditEntry(
                id = ids.newEntityId(),
                actor = command.actingUserId.value,
                actorUserId = command.actingUserId,
                action = AuditAction.PASSWORD_RESET,
                entity = HrUser.AUDIT_ENTITY,
                entityId = existing.id,
                timestamp = now,
                metadata = mapOf("email" to existing.email.value),
            )
        )

        return Unit.asOk()
    }
}
