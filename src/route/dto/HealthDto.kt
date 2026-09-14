package com.pgsystem.employee.requirement.tracker.route.dto

import kotlinx.serialization.Serializable

/**
 * Wire types live here, never domain models.
 *
 * Two reasons, and the second is easy to overlook. First, the wire format is an external contract
 * that should not move every time a domain model is refactored. Second, and more important for this
 * system: a domain model serialised straight to JSON leaks whatever fields it happens to carry, and
 * PRD 8.6 forbids the portal returning an original filename or a storage key. Keeping the mapping
 * explicit means adding such a field to a response is a visible edit, not an accident.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
)
