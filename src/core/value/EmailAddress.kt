package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * A syntactically valid email address, normalised to lower case.
 *
 * Constructed only through [of], so an invalid address cannot exist as a value. This is the address
 * the invitation — carrying both halves of the portal credential — is sent to, which is why
 * changing it is the highest-risk operation in the system (PRD 7.4).
 */
@JvmInline
value class EmailAddress private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

        fun of(raw: String): DomainResult<EmailAddress> {
            val trimmed = raw.trim()
            return if (PATTERN.matches(trimmed)) {
                DomainResult.Ok(EmailAddress(trimmed.lowercase()))
            } else {
                DomainResult.Err(
                    AppError.Validation(
                        code = "email.invalid_format",
                        field = "email",
                        detail = "Not a valid email address",
                    )
                )
            }
        }
    }
}
