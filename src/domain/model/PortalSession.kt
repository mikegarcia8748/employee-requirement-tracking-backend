package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import java.time.Instant

/**
 * A short-lived window opened by link + PIN.
 *
 * **Access is carried by the session, not by the URL** (PRD 6.6). This is what stops a link in a
 * shared computer's history from remaining a standing credential for the life of the link.
 */
data class PortalSession(
    val id: EntityId,
    val uploadLinkId: EntityId,
    val startedAt: Instant,
    val expiresAt: Instant,
    val ip: String,
    val userAgent: String,
    val endedAt: Instant?,
)
