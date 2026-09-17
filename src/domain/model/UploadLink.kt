package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import java.time.Instant

/**
 * A tokenized, expiring pointer at one employee's checklist.
 *
 * **The token is the whole of authentication on the normal path** (PRD 6.6, reversed 2026-09-16).
 * Holding the URL opens the portal; there is no second factor. That trade is accepted in writing in
 * PRD 12, and what makes it survivable is that the portal returns status and never content — so a
 * leaked link costs a fraudulent upload rather than bulk disclosure.
 *
 * [pinHash] is therefore **not** a second factor and is null on almost every link. The 6-digit PIN
 * is a *recovery* credential, minted on demand by HR for the ordinary failure — an invitation that
 * never arrived — and passed to the hire out of band. Null means no PIN has been issued, which is
 * the state a link is created in (ERT-433) and the state most links stay in. Both credentials are
 * stored hashed when present; neither plaintext is recoverable from this record.
 *
 * `expiresAt` is computed and stored **when the link is issued**, exactly like the requirement-set
 * snapshot: changing the policy later must not silently extend or kill links already in the wild
 * (PRD 6.4).
 *
 * [scope] exists so Phase 4 can issue a link covering a single requirement for a renewal, without
 * reworking the model — v1 always scopes to all of them (PRD 9.3).
 */
data class UploadLink(
    val id: EntityId,
    val employeeId: PersonId,
    val tokenHash: String,
    /** The recovery PIN, hashed. Null until HR issues one — see this class's own note. */
    val pinHash: String?,
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
    data class Only(val requirementTemplateIds: Set<EntityId>) : LinkScope
}
