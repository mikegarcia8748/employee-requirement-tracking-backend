package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrSession
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

/** What the caller presents. [ip] is recorded on the audit row, never on the token. */
data class SignIn(val email: String, val password: String, val ip: String)

/**
 * HR sign-in (ERT-190, PRD §14 Q4).
 *
 * ### One failure, four ways to reach it
 *
 * A malformed address, an unknown address, a wrong password and a deactivated account all return
 * [AppError.AuthenticationFailed] — a `data object`, so they are not merely *documented* as identical
 * but *unrepresentably* different: there is one instance and it carries no fields, so no branch below
 * can render something a little more helpful. Anything else turns `POST /api/auth/login` into an
 * oracle for who works in HR, which §6.6 argues at length for the portal and which applies here
 * unchanged.
 *
 * ### Timing is part of the response
 *
 * Byte-identical bodies are worth nothing if the unknown-address branch returns in 1ms and the
 * wrong-password branch in 100ms — bcrypt at cost 12 makes that difference trivially measurable over
 * the network. So **every** path verifies a password against *some* hash: an absent or deactivated
 * user is checked against [decoyHash], which is produced by the injected [Hasher] rather than by a
 * literal, so it carries whatever work factor is actually bound. A hard-coded digest would fix the
 * cost at whatever it was the day it was pasted and re-open the gap the moment the real cost changed.
 *
 * `by lazy` rather than a constructor body: hashing costs ~100ms and would otherwise be paid while
 * building the Koin graph, for a value most deployments never need. It is computed on the first
 * failed sign-in and reused.
 *
 * ### The audit row does not say which branch ran
 *
 * Every attempt writes a row; a failure writes [AuditAction.SIGN_IN_FAILED] pointing at
 * [HrUser.NO_SUBJECT] with a null `actorUserId`, **even when a user was found**. Recording the real
 * id on a wrong-password failure would rebuild the oracle inside the audit table, where it is
 * readable by anyone who can read the trail. The attempted address goes in `actor`, which discloses
 * nothing — the attacker typed it — and is what makes "failures for this address" a query. Until
 * ERT-660 rate-limits this endpoint, that query is the detection.
 */
class AuthenticateHrUserUseCase(
    private val users: HrUserRepository,
    private val hasher: Hasher,
    private val tokens: AccessTokenIssuer,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val tracer: UseCaseTracer,
) {
    private val decoyHash: String by lazy { hasher.hash(DECOY_PASSWORD) }

    suspend operator fun invoke(command: SignIn): DomainResult<HrSession> =
        tracer.trace("AuthenticateHrUserUseCase") { execute(command) }

    private suspend fun execute(command: SignIn): DomainResult<HrSession> {
        val now = clock.now()

        // A malformed address is a failed sign-in, not a validation error. Answering 422 here would
        // separate "not an address" from "an address nobody has", and while that leaks nothing about
        // any account it is a second response shape on the one endpoint whose whole design is having
        // exactly one. The decoy verify below keeps the timing uniform with it too.
        val email = (EmailAddress.of(command.email) as? DomainResult.Ok)?.value

        val user = email?.let { users.findByEmail(it) }

        // Runs on every path, including both branches that have already lost, so the elapsed time
        // does not separate them. `verified` is read only when there is a user to read it for.
        val verified = hasher.verify(command.password, user?.passwordHash ?: decoyHash)

        if (user == null || !user.isActive || !verified) {
            audit.record(
                AuditEntry(
                    id = ids.newEntityId(),
                    actor = command.email,
                    actorUserId = null,
                    action = AuditAction.SIGN_IN_FAILED,
                    entity = HrUser.AUDIT_ENTITY,
                    entityId = HrUser.NO_SUBJECT,
                    timestamp = now,
                    metadata = mapOf("ip" to command.ip),
                )
            )
            return AppError.AuthenticationFailed.asErr()
        }

        val grant = tokens.issue(user, now)

        audit.record(
            AuditEntry(
                id = ids.newEntityId(),
                actor = user.email.value,
                actorUserId = user.id,
                action = AuditAction.SIGN_IN_SUCCEEDED,
                entity = HrUser.AUDIT_ENTITY,
                entityId = user.id,
                timestamp = now,
                metadata = mapOf("ip" to command.ip),
            )
        )

        return HrSession(token = grant.token, expiresAt = grant.expiresAt, user = user).asOk()
    }

    private companion object {
        /**
         * The plaintext behind [decoyHash]. Not a secret and not a credential: no account holds it,
         * and bcrypt salts per call, so its digest matches nothing. It exists only to be slow.
         */
        const val DECOY_PASSWORD = "decoy.password.for.uniform.timing"
    }
}
