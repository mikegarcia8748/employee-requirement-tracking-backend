package com.pgsystem.employee.requirement.tracker.domain.model

/**
 * Lifecycle of the employee's packet as a unit, per PRD 6.2.
 *
 * COMPLETE means the paperwork is in and looks right. It does **not** mean the person has been
 * verified, and no downstream process may read it that way (PRD 1). Identity binding is HR's
 * manual judgement at validation (PRD 8.5), recorded separately via `originalsSightedAt`.
 */
enum class PacketStatus {
    /** HR created the hire and the invite was sent; the employee is uploading. */
    DRAFT_COLLECTING,

    /** The employee reviewed, attested and submitted. Every requirement is locked. */
    UNDER_REVIEW,

    /** HR rejected at least one document; only the rejected requirements unlock. */
    CHANGES_REQUESTED,

    /** Every required document is approved. The handoff signal for account provisioning. */
    COMPLETE,

    /** The hire fell through. */
    CANCELLED,

    /** Paused — deferred start date, pending medical. Reachable from any non-terminal state. */
    ON_HOLD,
    ;

    val isTerminal: Boolean get() = this == COMPLETE || this == CANCELLED
}
