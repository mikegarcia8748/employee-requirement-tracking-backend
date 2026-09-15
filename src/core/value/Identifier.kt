package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * An entity identifier: either an 8-character [PersonId] or a 12-character [EntityId].
 *
 * The two widths are deliberately distinct, which is what lets [of] recover the kind of a stored id
 * from the value alone. That matters for `audit_logs.entity_id`, the one column that can hold
 * either, because an audit row points at any of ten tables and carries no foreign key.
 *
 * **Not a credential.** An [EntityId] is roughly 71 bits and a [PersonId] roughly 48, against 256
 * for an upload-link token. Nothing that grants access may be derived from, or compared against, an
 * identifier.
 */
sealed interface Identifier {
    val value: String

    companion object {
        /**
         * The only characters an identifier may contain. Generators draw from exactly this set, and
         * both value classes validate against it.
         */
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /**
         * Resolve a stored identifier to its kind by length, then validate it against that kind.
         *
         * **Adding a third identifier width makes this ambiguous.** If one is ever needed, this must
         * switch to reading the `entity` discriminator column instead, and this paragraph is the
         * warning. Reusing [PersonId] for HR user accounts is safe: an 8-character value then means
         * "an employee or a user", and `AuditEntry.entity` already says which.
         */
        fun of(raw: String): DomainResult<Identifier> = when (raw.length) {
            PersonId.LENGTH -> PersonId.of(raw)
            EntityId.LENGTH -> EntityId.of(raw)
            else -> DomainResult.Err(
                AppError.Validation(
                    code = "id.unknown_kind",
                    field = "id",
                    detail = "No identifier kind is ${raw.length} characters",
                )
            )
        }
    }
}
