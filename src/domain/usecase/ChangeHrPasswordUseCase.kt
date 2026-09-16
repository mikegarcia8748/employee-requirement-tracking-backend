package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
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
import com.pgsystem.employee.requirement.tracker.domain.model.PasswordPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

/** [userId] comes from the verified token, never from the request body. */
data class ChangePassword(val userId: PersonId, val currentPassword: String, val newPassword: String)

/**
 * A user changes their own password (ERT-190).
 *
 * This is the **only** route a user with `passwordChangeRequired` may reach, which is what makes the
 * bootstrap account and every HR-issued reset safe to hand over: the credential works exactly once,
 * for exactly this.
 *
 * ### Order of checks, and why it is this way round
 *
 * The current password is verified **before** the new one is validated. Reversed, a caller who does
 * not know the current password could still learn the length rule by watching 422 turn into 401 —
 * minor, but free to avoid. It also means a wrong current password costs the same whatever was
 * typed in the new field.
 *
 * ### `Denied` is deliberately not used
 *
 * A wrong current password returns [AppError.AuthenticationFailed], not [AppError.Denied]. The
 * caller is already authenticated and the account is known to exist — there is nothing to conceal
 * here, and a 404 would say the endpoint was absent. The indistinguishability requirement belongs to
 * sign-in and to the portal; borrowing it here would only make a real failure unreadable.
 *
 * A user who cannot be found is [AppError.AuthenticationFailed] too, which is not a case a live
 * token should reach: it means the row was deleted inside the TTL. There is no delete path for
 * `users` — accounts are deactivated — so this is a corrupt-state branch, and refusing is right.
 */
class ChangeHrPasswordUseCase(
    private val users: HrUserRepository,
    private val hasher: Hasher,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val tracer: UseCaseTracer,
) {
    suspend operator fun invoke(command: ChangePassword): DomainResult<Unit> =
        tracer.trace("ChangeHrPasswordUseCase") { execute(command) }

    private suspend fun execute(command: ChangePassword): DomainResult<Unit> {
        val user = users.findById(command.userId)

        if (user == null || !hasher.verify(command.currentPassword, user.passwordHash)) {
            audit.record(
                entry(
                    action = AuditAction.PASSWORD_CHANGED,
                    actor = user?.email?.value ?: command.userId.value,
                    actorUserId = user?.id,
                    subject = user?.id ?: HrUser.NO_SUBJECT,
                    metadata = mapOf("outcome" to "refused"),
                )
            )
            return AppError.AuthenticationFailed.asErr()
        }

        val validated = PasswordPolicy.validate(command.newPassword)
        if (validated is DomainResult.Err) return validated

        // Deliberately no "must differ from the current password" rule. It reads as obvious, but
        // enforcing it means comparing the new plaintext against the stored hash and reporting the
        // result -- which tells anyone holding a session whether a guessed string is the current
        // password, from an endpoint that is not rate-limited. The check protects nothing a forced
        // reset needs, and costs a credential oracle.
        users.save(
            user.copy(
                passwordHash = hasher.hash(command.newPassword),
                passwordChangeRequired = false,
            )
        )

        audit.record(
            entry(
                action = AuditAction.PASSWORD_CHANGED,
                actor = user.email.value,
                actorUserId = user.id,
                subject = user.id,
                metadata = mapOf("outcome" to "changed"),
            )
        )

        return Unit.asOk()
    }

    private fun entry(
        action: AuditAction,
        actor: String,
        actorUserId: PersonId?,
        subject: PersonId,
        metadata: Map<String, String>,
    ) = AuditEntry(
        id = ids.newEntityId(),
        actor = actor,
        actorUserId = actorUserId,
        action = action,
        entity = HrUser.AUDIT_ENTITY,
        entityId = subject,
        timestamp = clock.now(),
        metadata = metadata,
    )
}
