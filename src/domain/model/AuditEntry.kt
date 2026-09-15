package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import java.time.Instant

/**
 * An HR-side action record (PRD 11, 12).
 *
 * Covers every view, approve, reject, download, email change and reopen. An unread log is not a
 * control, so PRD 8.13 pairs this with an exception report that surfaces the combinations worth
 * looking at.
 */
data class AuditEntry(
    val id: EntityId,
    val actor: String,
    val action: AuditAction,
    val entity: String,
    /**
     * The identifier of whatever was acted on, of either width — [entity] names its kind.
     *
     * Typed as [Identifier] rather than a concrete id because an audit row points at any of ten
     * tables. It deliberately carries no foreign key: an audit row must outlive the row it
     * describes, and a trail that cascades away with its subject is not a trail.
     */
    val entityId: Identifier,
    val timestamp: Instant,
    /** Free-form context: old and new values, reason, verification method. Never credentials. */
    val metadata: Map<String, String>,
)

enum class AuditAction {
    HIRE_CREATED,
    HIRE_EDITED,

    /**
     * The highest-risk operation in the system. Never actionable from an inbound email alone; the
     * recorded [VerificationMethod] is what makes the log show *how* a change was verified, not
     * merely that it happened (PRD 7.4).
     */
    EMAIL_CHANGED,

    DUPLICATE_EMAIL_OVERRIDDEN,
    LINK_ISSUED,
    LINK_EXTENDED,
    LINK_REVOKED,
    LINK_SUSPENDED,
    DOCUMENT_VIEWED,
    DOCUMENT_DOWNLOADED,
    SUBMISSION_APPROVED,
    SUBMISSION_REJECTED,
    IDENTITY_CONFIRMED,
    ORIGINALS_SIGHTED,
    PACKET_FORCE_SUBMITTED,
    RECORD_REOPENED,
    SETTING_CHANGED,
}

/**
 * How HR confirmed an email-change request was genuine (PRD 7.4).
 *
 * Required before the change commits. The attack this closes is procedural, not technical: someone
 * emails HR claiming to be the hire and asks for the link to be resent elsewhere, and following the
 * documented process correctly hands a valid credential to the attacker (SEC-03).
 */
enum class VerificationMethod {
    /** Phone call to the number on the recruitment record. */
    PHONE_CALL_TO_RECRUITMENT_RECORD,

    /** Confirmed through the recruiter or hiring manager who has met the person. */
    RECRUITER_CONFIRMATION,

    /** Confirmed in person. */
    IN_PERSON,
}
