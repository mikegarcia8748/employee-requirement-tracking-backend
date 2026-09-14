package com.pgsystem.employee.requirement.tracker.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A short-lived window opened by link + PIN.
 *
 * **Access is carried by the session, not by the URL** (PRD 6.6). This is what stops a link in a
 * shared computer's history from remaining a standing credential for the life of the link.
 */
data class PortalSession(
    val id: UUID,
    val uploadLinkId: UUID,
    val startedAt: Instant,
    val expiresAt: Instant,
    val ip: String,
    val userAgent: String,
    val endedAt: Instant?,
)
