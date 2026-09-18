package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import java.time.Instant

/** A hired person being onboarded. Created by HR, individually or via CSV import (PRD 5). */
data class Employee(
    val id: PersonId,
    val firstName: String,
    val middleInitial: String?,
    val lastName: String,
    val departmentId: EntityId,
    val position: String,
    val employmentTypeId: EntityId,
    val email: EmailAddress,
    val packetStatus: PacketStatus,
    val submittedAt: Instant?,
    /** True when HR force-submitted on the employee's behalf. Such a packet carries no attestation. */
    val submittedByHr: Boolean,
    val attestation: Attestation?,
    /** The PRD 8.5 physical checkpoint. Absent means COMPLETE is not identity assurance. */
    val originalsSightedAt: Instant?,
    /** The [HrUser] who sighted the originals. A [PersonId] since ERT-190, not a typed-in name. */
    val originalsSightedBy: PersonId?,
    val anomalyFlags: Set<AnomalyFlag>,
    val completedAt: Instant?,
    val createdAt: Instant,
    /**
     * The [HrUser] who created this hire.
     *
     * **A [PersonId] rather than free text since ERT-190, and non-null.** §8.13's exception report
     * asks whether one officer created a hire, altered its email and approved every document — a
     * question about a person, which two rows spelling a name differently cannot answer. The column
     * references `users(id)` `on delete restrict`, so a user who acted cannot be deleted out from
     * under the record.
     */
    val createdBy: PersonId,
) {
    /**
     * PRD 7.1: no version may be purged while a flag is open. The superseded version is the
     * evidence — an attacker with portal access could otherwise erase a forgery by uploading five
     * innocuous replacements.
     */
    val retentionFrozen: Boolean get() = anomalyFlags.isNotEmpty()

    companion object {
        /**
         * The `entity` discriminator for every audit row about a hire.
         *
         * A constant for the reason [com.pgsystem.employee.requirement.tracker.domain.model.HrUser.AUDIT_ENTITY]
         * is one. ERT-431 writes the first employee audit row; before it the string `"employee"` was
         * a bare literal in three test files, which is how a discriminator ends up spelled two ways
         * and an exception report silently misses half its rows.
         */
        const val AUDIT_ENTITY = "employee"
    }
}

/**
 * What [com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHireUseCase] returns, and the
 * type ERT-432, ERT-433 and ERT-434 extend.
 *
 * **A wrapper rather than a bare [Employee], decided by ERT-431 so the other three sub-tasks do not
 * re-argue it.** Creating a hire produces four things — the hire, its snapshotted requirement set,
 * its link, and whether the invitation actually went out — and only the first is a column on
 * `employees`. The delivery indicator in particular **must not** become one: E4 settled that it is
 * derived from the latest outbox row, and that "a column would be a second copy of a fact the outbox
 * already owns, and the two would drift the first time a retry succeeded".
 *
 * **E4's derivation has one hole, and [delivery] is half of the answer (HAR-02).** When the outbox
 * *insert* is what failed, `OutboxNotifier` writes nothing — so there is no latest row to derive
 * from, and the `DeliveryResult` value is the only evidence in existence. It reaches HR twice:
 * synchronously through this field, which ERT-450 renders into the 201 body, and durably through an
 * [AuditAction.INVITATION_DELIVERY_FAILED] row the use case writes. The outbox still records what
 * was *queued*; the audit row records what *failed*. Neither is a copy of the other.
 *
 * The alternative — return [Employee] now and widen the signature at ERT-434 — is what ERT-430 split
 * itself into sub-tasks to avoid, and it would additionally force ERT-450's route to make a second
 * repository call for the requirement set, which the dependency rule forbids.
 *
 * Growth is compile-checked in the right place: adding a field without a default breaks only the one
 * construction site inside the use case, while every test reading `.employee` keeps compiling. That
 * is the `NotificationKind.storesBody` device — a new field is a decision someone has to make rather
 * than one they can inherit in silence.
 *
 * | Sub-task | Field |
 * |---|---|
 * | ERT-431 | `employee` |
 * | ERT-432 | `requirements` — **landed** |
 * | ERT-433 | `link` — **landed**; safe to expose, since it holds a digest and never a plaintext token |
 * | ERT-434 | `delivery` — **landed** |
 */
data class HireCreated(
    val employee: Employee,
    /**
     * The set copied from the catalogue at this moment, in the order it was copied (ERT-432).
     *
     * **The list that was written, not a re-read.** `EmployeeRepository.saveRequirements` returns
     * nothing, so the alternative is a second round trip through `requirementsOf` — which would
     * also make the result depend on the adapter's `ORDER BY` rather than on what this use case
     * decided. The two agree by construction, because the adapter's ordering is a copy of the
     * catalogue's; **no test distinguishes them**, and this says so rather than claiming a
     * behavioural difference it cannot demonstrate.
     */
    val requirements: RequirementSet,
    val link: UploadLink,
    /**
     * Whether the invitation reached the outbox (ERT-434, §8.1).
     *
     * [DeliveryResult] rather than a `Boolean`, because §8.1 asks for a failure indicator **and** a
     * retry action, and the reason is what HR is shown. Rather than a nullable `String`, because a
     * two-case sealed type is what lets ERT-450 branch exhaustively instead of testing a null.
     *
     * `Sent` means **durably queued**, not delivered — `OutboxNotifier`'s own KDoc is explicit, and
     * nothing transmits until ERT-1010 drains the table. The retry for a failed *invitation* is
     * ERT-1030's `resend-link`, never `OutboxNotifier.retry`, which refuses an invitation loudly
     * because its body was never stored.
     *
     * **This is the first reference from `domain/model` to `domain/port`, and it is deliberate.**
     * Both are the same layer so no dependency rule is touched, and `HireCreated` is a use-case
     * result living here on the `HrSession` precedent rather than a persisted model — a result may
     * speak a port's vocabulary. Moving [DeliveryResult] into `domain/model` was the alternative and
     * was declined: it describes what a `Notifier` did, and relocating it would churn six files for
     * no behavioural gain.
     */
    val delivery: DeliveryResult,
)

/**
 * The employee's declaration at submission (PRD 7.2).
 *
 * The text is versioned because the wording will change and you will need to know which version a
 * given employee accepted; without that, a surfacing forgery meets no record of the employee having
 * claimed anything.
 */
data class Attestation(
    val textVersion: String,
    val attestedAt: Instant,
    val attestedIp: String,
)

/** Signals that route a record into the PRD 8.13 exception report and freeze retention. */
enum class AnomalyFlag {
    /** Same requirement rejected 3 times — usually unclear instructions, an HR problem to fix. */
    REPEATED_REJECTIONS,

    /** Portal accessed from more than one country or more than N distinct IPs (PRD 8.12). */
    ACCESS_ANOMALY,

    /** A burst of failed PIN entries suspended the link (PRD 6.6). */
    PIN_FAILURE_SUSPENSION,

    /** Another active hire shares this email address (PRD 8.1, SEC-11). */
    SHARED_EMAIL,

    /** One officer created, altered the email, and approved throughout (PRD 8.13, SEC-10). */
    SEPARATION_OF_DUTIES,

    /** Fraud suspected; set by HR. */
    SUSPECTED_FRAUD,
}
