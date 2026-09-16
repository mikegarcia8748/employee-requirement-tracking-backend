package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.error.flatMap
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.model.PasswordPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

data class BootstrapHrUser(val email: String, val password: String)

/**
 * The first `HR_ADMIN`, created once (ERT-190).
 *
 * With no SSO and no self-registration the first account has to come from somewhere, and every
 * alternative is worse: a seeded row in a migration ships a known password in version control, and a
 * CLI is a second entry point to secure.
 *
 * ### "Only when the table is empty" is the whole safety property
 *
 * [HrUserRepository.countAll] deciding rather than "does this email exist" is deliberate. The latter
 * would re-create the bootstrap admin on every boot after someone deactivated it — which is exactly
 * what an operator does when they finish setting up and want that account gone — silently handing
 * back an account with a password that lives in the deployment environment.
 *
 * It also means changing `HR_BOOTSTRAP_PASSWORD` and restarting does **not** reset anything. That is
 * intentional: a startup path that can rewrite a live credential from an environment variable is a
 * backdoor with a nice name. Password recovery is [ResetHrPasswordUseCase].
 *
 * ### Refusing to boot is not this class's job
 *
 * "Outside dev, startup fails if the table is empty and the variables are unset" lives in
 * `plugin/HrBootstrap.kt`, beside the `JWT_SECRET` and `TOKEN_PEPPER` checks it is modelled on. That
 * is a configuration rule; **this** is the business rule, and keeping them apart is what lets this be
 * tested against fakes with no environment at all.
 *
 * Returns the created user, or null when one already existed — a value the caller logs, not an error.
 */
class EnsureBootstrapHrUserUseCase(
    private val users: HrUserRepository,
    private val hasher: Hasher,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val personIds: PersonIdGenerator,
    private val tracer: UseCaseTracer,
) {
    suspend operator fun invoke(command: BootstrapHrUser): DomainResult<HrUser?> =
        tracer.trace("EnsureBootstrapHrUserUseCase") { execute(command) }

    private suspend fun execute(command: BootstrapHrUser): DomainResult<HrUser?> {
        if (users.countAll() > 0L) return null.asOk()

        return EmailAddress.of(command.email).flatMap { email ->
            PasswordPolicy.validate(command.password, field = "HR_BOOTSTRAP_PASSWORD").flatMap { password ->
                create(email, password)
            }
        }
    }

    private suspend fun create(email: EmailAddress, password: String): DomainResult<HrUser?> {
        val now = clock.now()

        val user = users.save(
            HrUser(
                id = personIds.newPersonId(),
                email = email,
                fullName = "Bootstrap Administrator",
                passwordHash = hasher.hash(password),
                role = HrRole.HR_ADMIN,
                isActive = true,
                // Non-negotiable. The password is sitting in a deployment environment, a shell
                // history and probably a chat message; it must stop working at first use.
                passwordChangeRequired = true,
                createdAt = now,
            )
        )

        audit.record(
            AuditEntry(
                id = ids.newEntityId(),
                // No actorUserId: nothing created this but the application itself, which is the case
                // audit_logs.actor stayed free text for.
                actor = "system.bootstrap",
                actorUserId = null,
                action = AuditAction.USER_CREATED,
                entity = HrUser.AUDIT_ENTITY,
                entityId = user.id,
                timestamp = now,
                metadata = mapOf("role" to user.role.name, "email" to user.email.value, "source" to "bootstrap"),
            )
        )

        return user.asOk()
    }
}
