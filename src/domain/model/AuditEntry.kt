package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
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
    /**
     * Who acted, as free text — an email, or a name like `system` for an unattended job.
     *
     * **Kept, and deliberately not replaced by [actorUserId].** ERT-190 turned four of the five actor
     * columns in the schema into foreign keys; this is the fifth and it stays a string, because the
     * trail must record actors who are **not** users: the ERT-130 seed, the ERT-1020 expiry sweep, a
     * future import job. An append-only trail that can refuse a write because it cannot name a user
     * is worse than one carrying a string.
     */
    val actor: String,
    /**
     * The acting [HrUser], when there was one.
     *
     * Nullable for the reason above. §8.13's exception report joins on this — "one officer created,
     * altered the email and approved throughout" is a question about a person, not about a string
     * two rows spelled differently — and everything else reads [actor].
     */
    val actorUserId: PersonId? = null,
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

    /**
     * The invitation did not reach the outbox (ERT-434, HAR-02, PRD §8.1).
     *
     * **A separate action rather than an outcome in [HIRE_CREATED]'s metadata**, for the reason
     * [SIGN_IN_FAILED] is separate from [SIGN_IN_SUCCEEDED] and [DUPLICATE_EMAIL_OVERRIDDEN] is its
     * own row: "which hires did the invitation never reach" is a filter on `action`, not a
     * substring scan of every creation row's unindexed JSON.
     *
     * It exists because `OutboxNotifier` writes **nothing** when the outbox insert is what failed,
     * so §8.1's delivery-failure indicator — which `NotificationOutbox`'s KDoc derives from the
     * latest row for a hire — cannot see that case at all. The `DeliveryResult.Failed` value
     * reaching `CreateHireUseCase` is the only evidence that exists, and this row is where it is
     * kept. A column on `employees` was the alternative and E4 forbids it: it would be a second
     * copy of a fact the outbox already owns, and the two would drift the first time a retry
     * succeeded.
     */
    INVITATION_DELIVERY_FAILED,

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

    // ── HR accounts (ERT-190) ───────────────────────────────────────────────────────────────────

    /**
     * A sign-in succeeded. Paired with [SIGN_IN_FAILED] rather than folded into one action with an
     * outcome in the metadata, so "failures for this address" is a filter rather than a scan.
     */
    SIGN_IN_SUCCEEDED,

    /**
     * A sign-in failed — and the row does **not** say why.
     *
     * An unknown email, a wrong password and a deactivated account all write this, with the same
     * metadata. Recording which of the three occurred would rebuild, in the audit table, precisely
     * the oracle the identical 401 exists to deny; anyone who can read the trail could then enumerate
     * who works in HR. Until ERT-660 rate-limits `/api/auth/login`, this row is the detection.
     */
    SIGN_IN_FAILED,

    /** A user changed their own password. */
    PASSWORD_CHANGED,

    /** An admin reset someone's password, forcing a change at next sign-in. */
    PASSWORD_RESET,

    USER_CREATED,
    USER_ACTIVATED,
    USER_DEACTIVATED,

    /** A signed-in user was refused an action their role does not carry (§8.13). */
    ACCESS_DENIED,
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
