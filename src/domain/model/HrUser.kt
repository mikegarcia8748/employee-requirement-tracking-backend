package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.AccessToken
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import java.time.Instant

/**
 * An HR staff account (PRD §2, §8.13, §14 Q4).
 *
 * Q4 was answered on 2026-09-16: a handful of HR staff, local accounts held here, two roles, bcrypt,
 * no SSO. What this type buys is attribution — before it, `employees.created_by` was free text, so
 * the audit trail named whatever someone typed rather than a row that has to exist.
 *
 * **[id] is a [PersonId], reusing the employee width rather than introducing a third.**
 * `Identifier.of` dispatches on length, and a third width would make that ambiguous — its KDoc says
 * so and names this case. An 8-character `audit_logs.entity_id` therefore means "an employee **or** a
 * user", which is correct because `AuditEntry.entity` already says which.
 *
 * **There is no `lastLoginAt`, and the absence is the control.** Same reasoning that kept
 * `last_accessed_at` off `upload_links` (§11, SEC-05): one overwritten timestamp cannot answer who,
 * from where, or how often. A sign-in is an audit row.
 */
data class HrUser(
    val id: PersonId,
    val email: EmailAddress,
    val fullName: String,
    /**
     * The bcrypt output from [com.pgsystem.employee.requirement.tracker.core.crypto.Hasher.hash].
     * Never the password, and never recoverable from it.
     */
    val passwordHash: String,
    val role: HrRole,
    /** False disables sign-in. Accounts are deactivated, never deleted — their audit rows outlive them. */
    val isActive: Boolean,
    /** Set on the bootstrap account and on every HR-issued reset. Gates every route but change-password. */
    val passwordChangeRequired: Boolean,
    val createdAt: Instant,
) {
    /**
     * Redacted, which is a deliberate divergence from [UploadLink] — that one lets `pinHash` render.
     *
     * The difference is reach. An `UploadLink` is handled by portal use cases that were written
     * around §12; an `HrUser` is the argument of a sign-in path and the natural thing to interpolate
     * into a diagnostic while debugging one. A bcrypt digest is not a password, but it is the input
     * to an offline attempt, and the acceptance criterion is that it reaches no log, response, trace
     * line or audit row. Costing nothing, this closes the cheapest way to break that.
     *
     * It is not the only control: the tracer is handed no arguments at all, and `HrUserDto` carries
     * no hash field.
     */
    override fun toString(): String =
        "HrUser(id=$id, email=$email, role=$role, isActive=$isActive, " +
            "passwordChangeRequired=$passwordChangeRequired)"

    companion object {
        /**
         * The subject a **failed** sign-in row points at.
         *
         * `audit_logs.entity_id` is not nullable and carries no foreign key, and a failed sign-in has
         * no subject to name: the address may belong to nobody. Pointing the row at the real user
         * when one exists and at a sentinel when it does not would rebuild, inside the audit table,
         * exactly the oracle the identical 401 denies — anyone who can read the trail could then
         * separate "wrong password" from "no such account". So **every** failure points here,
         * including the ones where a user was found, and `actorUserId` stays null on all of them.
         *
         * Eight zeroes is a well-formed [PersonId] that no generator will draw:
         * `SecurePersonIdGenerator` draws from a 62-character alphabet, so the chance is 62^-8.
         */
        val NO_SUBJECT: PersonId = personIdOrDie("00000000")

        /** The `entity` discriminator for every row in this file's area. */
        const val AUDIT_ENTITY = "user"
    }
}

/**
 * What a password must satisfy before it is hashed.
 *
 * **Length only, and that is a decision rather than an omission.** Composition rules — a digit, a
 * symbol, mixed case — measurably push people towards `Password1!` and towards reuse, and NIST
 * SP 800-63B withdrew them for that reason. Length is the property that actually costs an attacker
 * work, and bcrypt at cost 12 is what makes the offline attempt expensive.
 *
 * The maximum is not a policy choice: **bcrypt truncates at 72 bytes**, silently. Without this, a
 * 100-character passphrase and its first 72 characters would be the same password and nothing would
 * say so. Refusing is better than accepting a credential that is not the one the user chose.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12

    /** bcrypt's hard limit, in bytes rather than characters — a non-ASCII passphrase reaches it sooner. */
    const val MAX_BYTES = 72

    fun validate(raw: String, field: String = "newPassword"): DomainResult<String> = when {
        raw.length < MIN_LENGTH -> DomainResult.Err(
            AppError.Validation(
                code = "password.too_short",
                field = field,
                detail = "A password must be at least $MIN_LENGTH characters",
            )
        )

        raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> DomainResult.Err(
            AppError.Validation(
                code = "password.too_long",
                field = field,
                detail = "A password must be at most $MAX_BYTES bytes",
            )
        )

        else -> DomainResult.Ok(raw)
    }
}

/**
 * [PersonId.of] for a literal the compiler cannot check.
 *
 * Only ever called with a constant in this file, so a failure is a typo in that constant and a
 * startup crash is the right response — there is no runtime input that can reach it.
 */
private fun personIdOrDie(raw: String): PersonId = when (val result = PersonId.of(raw)) {
    is DomainResult.Ok -> result.value
    is DomainResult.Err -> error("'$raw' is not a valid PersonId: ${result.error.code}")
}

/**
 * Two roles, not four (Q4).
 *
 * `SYSTEM_ADMIN` and `RECRUITMENT` are dropped: nothing in §8 asks for either, and a role with no
 * requirement behind it becomes a place to put permissions nobody has thought about. Operator access
 * is database access, not an application role.
 *
 * **The roles differ in configuration rights, not validation rights, and that is not an oversight.**
 * §8.13 retains one effective role for v1 and mitigates it with the exception report. An `HR_OFFICER`
 * can still create a hire, change its email and approve every document unaided — **do not read the
 * second role as having closed SEC-10.**
 */
enum class HrRole {
    /** Creates hires, validates documents, manages links, issues recovery PINs. */
    HR_OFFICER,

    /** All of the above, plus the §6.4 settings, the requirement catalogue and user administration. */
    HR_ADMIN;

    /**
     * Whether this role may reach the administration surface.
     *
     * A property rather than `role == HR_ADMIN` at each call site: when a third role arrives it has
     * to answer this question here, once, instead of being silently excluded by every comparison
     * already written. Same device as `AnomalyFlag.freezesRetention` (§12 invariant 8).
     */
    val canAdminister: Boolean get() = this == HR_ADMIN
}

/**
 * What a successful sign-in hands back.
 *
 * [expiresAt] travels beside the token rather than being left for the client to decode: the token is
 * opaque at this layer by construction ([AccessToken] has no format rule), so a client that needed
 * the expiry would otherwise have to parse a JWT the domain refuses to admit is a JWT.
 *
 * Carries the whole [HrUser] rather than an id, so the route can answer with the profile the client
 * needs — role and `passwordChangeRequired` — without a second round trip on the one call that
 * happens before the client knows anything.
 */
data class HrSession(
    val token: AccessToken,
    val expiresAt: Instant,
    val user: HrUser,
)
