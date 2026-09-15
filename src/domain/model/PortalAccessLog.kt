package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import java.time.Instant

/**
 * One append-only record of a portal access attempt (PRD 8.12).
 *
 * Append-only is the point. PRD v0.3 carried a single `upload_link.last_accessed_at` column, and a
 * timestamp overwritten on every visit cannot answer who, from where, or how often — which is the
 * first question asked when a fraudulent submission surfaces (SEC-05). This record replaces it.
 */
data class PortalAccessLog(
    val id: EntityId,
    val uploadLinkId: EntityId,
    val sessionId: EntityId?,
    val timestamp: Instant,
    val ip: String,
    val userAgent: String,
    val action: PortalAction,
    val outcome: PortalOutcome,
)

enum class PortalAction {
    OPEN_LINK,
    VERIFY_PIN,
    VIEW_CHECKLIST,
    UPLOAD,
    DELETE_FILE,
    SUBMIT_PACKET,
    REPORT_PROBLEM,
    REQUEST_NEW_LINK,
}

enum class PortalOutcome {
    SUCCESS,

    /**
     * Wrong PIN **or** unknown token. Deliberately one value: the two must be indistinguishable to
     * the caller (PRD 6.6), so the endpoint cannot be used to test whether a link is real. The
     * distinction is not recorded here either, because this trail is shown to HR.
     */
    DENIED,

    LOCKED_OUT,
    SUSPENDED,
    EXPIRED,
    RATE_LIMITED,
}
