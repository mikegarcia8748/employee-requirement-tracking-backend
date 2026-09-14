package com.pgsystem.employee.requirement.tracker.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A tokenized, expiring pointer at one employee's checklist.
 *
 * **On its own it grants nothing.** The URL must be paired with the access PIN to open a session
 * (PRD 6.6). Both are stored hashed; neither plaintext is recoverable from this record.
 *
 * `expiresAt` is computed and stored **when the link is issued**, exactly like the requirement-set
 * snapshot: changing the policy later must not silently extend or kill links already in the wild
 * (PRD 6.4).
 *
 * [scope] exists so Phase 4 can issue a link covering a single requirement for a renewal, without
 * reworking the model — v1 always scopes to all of them (PRD 9.3).
 */
data class UploadLink(
    val id: UUID,
    val employeeId: UUID,
    val tokenHash: String,
    val pinHash: String,
    val scope: LinkScope,
    val status: LinkStatus,
    val issuedAt: Instant,
    /** Absolute ceiling, snapshotted at issue. */
    val expiresAt: Instant,
    /** Idle ceiling. Null when the idle clock is disabled (`idleExpiryDays = 0`). */
    val idleExpiresAt: Instant?,
    val extendedCount: Int,
    val failedPinCount: Int,
    val lockedUntil: Instant?,
    val warnedAt: Instant?,
    val revokedAt: Instant?,
    val revokedReason: String?,
)

/**
 * Which requirements a link may touch.
 *
 * v1 only ever issues [All]; [Only] is the Phase 4 renewal seam kept open from the start.
 */
sealed interface LinkScope {
    data object All : LinkScope
    data class Only(val requirementTemplateIds: Set<UUID>) : LinkScope
}
