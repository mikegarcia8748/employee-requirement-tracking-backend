package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * The identifier of a hired person — exactly 8 characters from [Identifier.ALPHABET].
 *
 * Shorter than an [EntityId] because it is the identifier a person is most likely to be quoted.
 *
 * Three properties are deliberate and each has cost a decision:
 *
 *  - **Input is not trimmed**, unlike [EmailAddress]. A trimmed identifier turns a malformed path
 *    segment into a successful lookup.
 *  - **[toString] is not redacted**, unlike [AccessPin]. An identifier is not a credential; it
 *    belongs in logs and error messages.
 *  - **The alphabet is mixed case**, so `0`/`O` and `1`/`l`/`I` are indistinguishable in many fonts.
 *    Nothing in the system asks anyone to retype an identifier — the employee receives a link and a
 *    PIN, and HR follows a link rather than typing one — so this is a constraint on future UI, not a
 *    defect. An unambiguous 32-symbol alphabet would cut the keyspace from roughly 2.2e14 to 1.1e12.
 *
 * At 62^8 the primary key is the collision backstop, and a duplicate draw fails the insert loudly.
 * **The retry belongs with the insert** (hire creation, ERT-400), not here — a value object cannot
 * know what the database already holds.
 */
@JvmInline
value class PersonId private constructor(override val value: String) : Identifier {
    override fun toString(): String = value

    companion object {
        const val LENGTH = 8
        private val PATTERN = Regex("^[A-Za-z0-9]{$LENGTH}$")

        fun of(raw: String): DomainResult<PersonId> =
            if (PATTERN.matches(raw)) {
                DomainResult.Ok(PersonId(raw))
            } else {
                DomainResult.Err(
                    AppError.Validation(
                        code = "person_id.invalid_format",
                        field = "id",
                        detail = "An employee id is exactly $LENGTH characters, A-Z, a-z or 0-9",
                    )
                )
            }
    }
}
