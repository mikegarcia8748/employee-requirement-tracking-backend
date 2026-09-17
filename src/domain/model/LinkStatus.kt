package com.pgsystem.employee.requirement.tracker.domain.model

/**
 * Lifecycle of an upload link, per PRD 6.3.
 *
 * Distinct from [PacketStatus] on purpose: the link is a credential with its own expiry and
 * revocation rules, and conflating the two makes the correction loop in PRD 7.3 intractable — a
 * rejection must not revoke the link, since correction is exactly what the employee needs to do.
 */
enum class LinkStatus(val opensPortal: Boolean) {
    /** Created with the hire. The working checklist, which the link alone reaches (PRD 6.6). */
    ACTIVE(opensPortal = true),

    /** Expiry lapsed (PRD 6.4). Recoverable — the employee can request a fresh link. */
    EXPIRED(opensPortal = false),

    /** Too many failed PIN attempts, or the employee reported a problem (PRD 6.6, 8.6). */
    SUSPENDED(opensPortal = false),

    /** Email changed, or HR revoked manually. Immediate and unconditional (PRD 7.4). */
    REVOKED(opensPortal = false),

    /** Packet reached COMPLETE; a read-only confirmation of outcomes, never of content. */
    COMPLETED(opensPortal = true),

    /** Grace window after COMPLETED elapsed. Resolves to a generic message with no personal data. */
    CLOSED(opensPortal = false),
}
