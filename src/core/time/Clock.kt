package com.pgsystem.employee.requirement.tracker.core.time

import java.time.Instant

/**
 * Time as an injected dependency.
 *
 * Use cases never call [Instant.now]. Link expiry, idle windows, lockouts and attestation
 * timestamps are all business rules with real boundaries (PRD 6.4, 6.6), and a rule anchored to
 * wall-clock time cannot be tested at its boundary.
 */
fun interface Clock {
    fun now(): Instant
}
