package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * The identifier of everything that is not a person — exactly 12 characters from
 * [Identifier.ALPHABET].
 *
 * Twelve rather than eight because these are machine-handled and numerous: submissions, access log
 * rows and audit entries accumulate per action rather than per hire. At 62^12 a collision is
 * negligible at any plausible row count, so no retry is needed on these inserts.
 *
 * Shares [PersonId]'s no-trimming and no-redaction rules; see that class for why.
 */
@JvmInline
value class EntityId private constructor(override val value: String) : Identifier {
    override fun toString(): String = value

    companion object {
        const val LENGTH = 12
        private val PATTERN = Regex("^[A-Za-z0-9]{$LENGTH}$")

        fun of(raw: String): DomainResult<EntityId> =
            if (PATTERN.matches(raw)) {
                DomainResult.Ok(EntityId(raw))
            } else {
                DomainResult.Err(
                    AppError.Validation(
                        code = "entity_id.invalid_format",
                        field = "id",
                        detail = "An id is exactly $LENGTH characters, A-Z, a-z or 0-9",
                    )
                )
            }
    }
}
