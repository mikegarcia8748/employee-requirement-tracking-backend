package com.pgsystem.employee.requirement.tracker.domain.port

import com.pgsystem.employee.requirement.tracker.domain.model.PortalAccessLog
import java.util.UUID

/**
 * Append-only trail of portal access attempts (PRD 8.12).
 *
 * Every attempt is recorded, success or failure. A burst of failures is one of the few signals of
 * an attack while it is still happening, so it must reach a person and not only a log file — hence
 * [countRecentFailures], which the notification path reads.
 */
interface PortalAccessTrail {
    suspend fun record(entry: PortalAccessLog)
    suspend fun findFor(uploadLinkId: UUID): List<PortalAccessLog>

    /** Distinct source addresses seen for a link — the input to the multi-IP anomaly flag. */
    suspend fun distinctIpsFor(uploadLinkId: UUID): Set<String>

    suspend fun countRecentFailures(uploadLinkId: UUID, since: java.time.Instant): Int
}
