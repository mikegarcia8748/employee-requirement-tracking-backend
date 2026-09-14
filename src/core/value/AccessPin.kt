package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * A plaintext 6-digit portal access PIN.
 *
 * Exists only in the window between generation and delivery in the invitation email — it is
 * persisted as a hash and never restated in any later notification, so that a forwarded rejection
 * notice or expiry warning carries nothing useful (PRD 6.6, 8.9).
 *
 * [toString] is overridden to keep the value out of logs and stack traces.
 */
@JvmInline
value class AccessPin private constructor(val value: String) {

    /** Never render the PIN. Accidental interpolation into a log line must not leak it. */
    override fun toString(): String = "AccessPin(******)"

    companion object {
        const val LENGTH = 6
        private val PATTERN = Regex("^\\d{$LENGTH}$")

        fun of(raw: String): DomainResult<AccessPin> =
            if (PATTERN.matches(raw)) {
                DomainResult.Ok(AccessPin(raw))
            } else {
                DomainResult.Err(
                    AppError.Validation(
                        code = "pin.invalid_format",
                        field = "pin",
                        detail = "PIN must be exactly $LENGTH digits",
                    )
                )
            }
    }
}
