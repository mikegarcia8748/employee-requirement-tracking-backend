package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.error.flatMap
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.model.PasswordPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

/** [actingUserId] is the admin doing the creating, taken from the verified token. */
data class CreateHrUser(
    val email: String,
    val fullName: String,
    val role: HrRole,
    val initialPassword: String,
    val actingUserId: PersonId,
)

/**
 * An admin creates an HR account (ERT-190).
 *
 * ### The initial password is supplied, not generated
 *
 * A generated one would have to be returned in the response, which puts a live credential in a body
 * that gets logged by proxies, copied into tickets and left in browser history. Supplied, it travels
 * once in a request the admin composed and is never echoed — `HrUserDto` has no password field at
 * all. The account carries `passwordChangeRequired`, so whatever the admin chose stops working the
 * moment its owner signs in, which is what keeps "the admin knows the password" from mattering.
 *
 * ### Why a duplicate is a `Conflict` and not the uniform failure
 *
 * Sign-in must not disclose whether an address has an account; **this** endpoint must, because an
 * admin who cannot be told is left retrying a creation that will never work. The difference is the
 * caller: this route is behind `HR_ADMIN`, and someone who can list every user learns nothing from
 * being told one exists.
 */
class CreateHrUserUseCase(
    private val users: HrUserRepository,
    private val hasher: Hasher,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val personIds: PersonIdGenerator,
    private val tracer: UseCaseTracer,
) {
    suspend operator fun invoke(command: CreateHrUser): DomainResult<HrUser> =
        tracer.trace("CreateHrUserUseCase") { execute(command) }

    private suspend fun execute(command: CreateHrUser): DomainResult<HrUser> =
        EmailAddress.of(command.email).flatMap { email ->
            PasswordPolicy.validate(command.initialPassword, field = "initialPassword").flatMap { password ->
                create(command, email, password)
            }
        }

    private suspend fun create(
        command: CreateHrUser,
        email: EmailAddress,
        password: String,
    ): DomainResult<HrUser> {
        if (command.fullName.isBlank()) {
            return AppError.Validation(
                code = "full_name.required",
                field = "fullName",
                detail = "A full name is required",
            ).asErr()
        }

        // Checked here as well as by the unique index, so the caller gets a code it can act on
        // rather than a 500 from a constraint violation. The index remains the authority -- this
        // check and the insert are not atomic, and losing that race is correct: the row that landed
        // first wins and the second caller sees the constraint.
        if (users.findByEmail(email) != null) {
            return AppError.Conflict(
                code = "user.email_taken",
                detail = "An account already exists for that email address",
            ).asErr()
        }

        val now = clock.now()
        val user = users.save(
            HrUser(
                id = personIds.newPersonId(),
                email = email,
                fullName = command.fullName.trim(),
                passwordHash = hasher.hash(password),
                role = command.role,
                isActive = true,
                passwordChangeRequired = true,
                createdAt = now,
            )
        )

        audit.record(
            AuditEntry(
                id = ids.newEntityId(),
                actor = command.actingUserId.value,
                actorUserId = command.actingUserId,
                action = AuditAction.USER_CREATED,
                entity = HrUser.AUDIT_ENTITY,
                entityId = user.id,
                timestamp = now,
                // The role is the whole of what was decided here and is the field a reviewer asks
                // about. The email is already the subject row's own; the password is nowhere.
                metadata = mapOf("role" to user.role.name, "email" to user.email.value),
            )
        )

        return user.asOk()
    }
}
