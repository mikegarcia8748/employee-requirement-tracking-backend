package com.pgsystem.employee.requirement.tracker.route.dto

import kotlinx.serialization.Serializable

/**
 * The add-hire form's payload (ERT-450, PRD 8.1).
 *
 * **Every field is a `String`, and that is the command's decision rather than this type's.**
 * [com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHire] takes the reference ids and
 * the address raw, because `EntityId.of` reports `field = "id"` while the API contract requires a
 * body field's failure to name *that* field. A DTO that parsed them here would leave the route
 * re-mapping `id` onto `departmentId`, which is a business decision in the one layer that must make
 * none.
 *
 * `actingUserId` is deliberately absent. It comes from the verified token, and a body that could
 * carry it would let any officer create a hire in another officer's name — §8.13's exception report
 * asks who created a record, and an answer the caller supplied is not an answer.
 */
@Serializable
data class CreateHireRequest(
    val firstName: String,
    val middleInitial: String? = null,
    val lastName: String,
    val departmentId: String,
    val position: String,
    val employmentTypeId: String,
    val email: String,
    /**
     * Required only when the address already belongs to an **active** hire (§8.1, SEC-11).
     *
     * Absent on the first attempt by design: the flow is submit, get a `422` naming this field, then
     * resubmit with a justification. A boolean `force` would satisfy the flow and destroy the point
     * — the reason is the artefact that reaches the audit trail and the exception report.
     */
    val duplicateReason: String? = null,
)

/**
 * What HR gets back from a successful creation (ERT-450).
 *
 * **The omissions are the control, as in [RequirementTemplateDto].** The one that matters is the
 * link: `HireCreated.link` is an `UploadLink` carrying `tokenHash`, `pinHash`, `failedPinCount` and
 * nine other fields, and none of them belongs in a response body. Rather than a nested link object
 * that would invite the next field, the only thing published is [linkExpiresAt] — a flat `String`,
 * so there is no type here for a token to be added to.
 *
 * The plaintext token is not merely withheld, it is **absent from the domain result entirely**: it
 * is generated, digested and handed to the notifier inside the use case, and never stored on
 * anything the route can see. `EmployeeRoutesTest` pins that against a future change rather than
 * trusting it.
 */
@Serializable
data class HireCreatedDto(
    val id: String,
    val firstName: String,
    val middleInitial: String? = null,
    val lastName: String,
    val departmentId: String,
    val position: String,
    val employmentTypeId: String,
    val email: String,
    val packetStatus: String,
    /** `SHARED_EMAIL` when a duplicate was overridden — the visible half of §8.13's flagging. */
    val anomalyFlags: List<String>,
    val createdAt: String,
    /** The set copied from the catalogue at this moment, in catalogue order (ERT-432). */
    val requirements: List<HireRequirementDto>,
    /** The link's absolute ceiling, snapshotted at issue (§6.4). */
    val linkExpiresAt: String,
    val invitation: InvitationDto,
)

/**
 * One requirement as it was copied onto the hire (ERT-432).
 *
 * The three `*Snapshot` fields lose their suffix on the wire: a client has nothing to compare them
 * against, and "snapshot" describes why the column exists rather than what the value is. `templateId`
 * stays behind — the catalogue row it came from is an internal join key, and a client that had it
 * would be tempted to read the live template, which is the exact coupling the snapshot removes.
 */
@Serializable
data class HireRequirementDto(
    val id: String,
    val name: String,
    val isRequired: Boolean,
    val sortOrder: Int,
    val status: String,
)

/**
 * Whether the invitation reached the outbox (§8.1, ERT-434).
 *
 * **`QUEUED` rather than `SENT`, and the wording is the point.** `DeliveryResult.Sent` means the
 * outbox row was written durably; nothing transmits until ERT-1010 drains the table. A body saying
 * `SENT` would tell an officer the employee has the link, which is false for every hire created
 * today, and it is the kind of false reassurance that turns into "they never got it" a week later.
 *
 * Two states rather than a nullable reason, because [DeliveryResult] is a two-case sealed type and
 * the mapper branches it exhaustively — a third case added to the domain fails to compile here
 * rather than serialising as an absent key.
 *
 * The retry this failure implies is ERT-1030's `resend-link`, which reissues the credential. It is
 * **not** `OutboxNotifier.retry`, which refuses an invitation because its body was never stored.
 */
@Serializable
data class InvitationDto(
    /** `QUEUED` or `FAILED`. */
    val status: String,
    /** Present only on `FAILED`, and absent rather than null — `explicitNulls` is off. */
    val reason: String? = null,
)
