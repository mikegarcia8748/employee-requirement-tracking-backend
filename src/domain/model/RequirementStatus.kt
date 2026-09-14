package com.pgsystem.employee.requirement.tracker.domain.model

/**
 * Lifecycle of a single requirement, per PRD 6.1.
 *
 * [employeeCanUpload] is the 6.1 table transcribed onto the type rather than re-derived at each
 * call site. It is the authority for the server-side lock check in PRD 8.7 — "given a locked
 * requirement, then upload is rejected server-side, not merely hidden in the UI".
 */
enum class RequirementStatus(val employeeCanUpload: Boolean) {
    /** Nothing uploaded yet. */
    PENDING(employeeCanUpload = true),

    /** File present, packet not yet submitted. Freely replaceable. */
    UPLOADED(employeeCanUpload = true),

    /** Packet submitted, awaiting HR validation. Locked so the document under review cannot change. */
    UNDER_REVIEW(employeeCanUpload = false),

    /** HR validated and accepted. Locked so cleared work is never silently replaced. */
    APPROVED(employeeCanUpload = false),

    /** HR found it invalid; a reason is attached. Must be replaced. */
    REJECTED(employeeCanUpload = true),

    /** Validity window lapsed (Phase 4). */
    EXPIRED(employeeCanUpload = true),
    ;

    val isLocked: Boolean get() = !employeeCanUpload
}
