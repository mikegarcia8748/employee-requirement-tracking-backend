package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.Attestation
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.Department
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.EmploymentType
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.model.LinkScope
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.PortalAccessLog
import com.pgsystem.employee.requirement.tracker.domain.model.PortalAction
import com.pgsystem.employee.requirement.tracker.domain.model.PortalOutcome
import com.pgsystem.employee.requirement.tracker.domain.model.PortalSession
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementSet
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementTemplate
import com.pgsystem.employee.requirement.tracker.domain.model.Submission
import com.pgsystem.employee.requirement.tracker.domain.model.TemplateAssignment
import com.pgsystem.employee.requirement.tracker.domain.model.UploadLink
import java.time.Duration
import java.time.Instant

/**
 * Domain objects with every field defaulted, so a test states only the field it is about.
 *
 * [Employee] has 18 fields and [UploadLink] 15. Built inline, a test about one rule reads as a wall
 * of irrelevant values and the field that matters disappears into it. `anEmployee(packetStatus =
 * COMPLETE)` reads as the rule being tested.
 *
 * ### Two properties these builders hold, beyond convenience
 *
 * **Timestamps come from [FixedClock.DEFAULT], never from `Instant.now()`.** A fixture anchored to
 * wall-clock time makes an expiry test pass today and fail in ninety days, and the failure will look
 * like a domain bug. Every default below is an offset from that one constant.
 *
 * **The named shortcuts are internally consistent, not merely status-stamped.** [anExpiredLink] has
 * status `EXPIRED` *and* an `expiresAt` in the past; [aLockedOutLink] carries a `failedPinCount` at
 * the policy threshold *and* a `lockedUntil` still in the future. A shortcut that set only the enum
 * would let a use case pass its test by reading the field the fixture happened to get right — which
 * is the failure mode PRD 6.1-6.3 tables exist to prevent.
 *
 * Credential fields (`tokenHash`, `pinHash`) default to recognisable placeholders rather than real
 * digests. A test that actually verifies a PIN builds its own with `BcryptHasher(cost = 4)`; a test
 * that only needs a link to exist should not pay for bcrypt.
 */

// ── Identifiers and values ──────────────────────────────────────────────────────────────────────

/** Defaults are per-kind and stable, so two objects of different kinds never share an id. */
object Fixtures {
    val EMPLOYEE_ID: PersonId = personId("EMP00001")
    val DEPARTMENT_ID: EntityId = entityId("DPT000000001")
    val EMPLOYMENT_TYPE_ID: EntityId = entityId("EMT000000001")
    val TEMPLATE_ID: EntityId = entityId("TPL000000001")
    val REQUIREMENT_ID: EntityId = entityId("REQ000000001")
    val SUBMISSION_ID: EntityId = entityId("SUB000000001")
    val LINK_ID: EntityId = entityId("LNK000000001")
    val SESSION_ID: EntityId = entityId("SES000000001")
    val ACCESS_LOG_ID: EntityId = entityId("LOG000000001")
    val AUDIT_ID: EntityId = entityId("AUD000000001")

    /**
     * The acting HR user (ERT-190).
     *
     * A [PersonId] like [EMPLOYEE_ID], because `users` and `employees` share the 8-character width —
     * but a *different* value, so a test that crosses the two wires cannot pass by coincidence.
     */
    val HR_USER_ID: PersonId = personId("HRU00001")

    const val IP = "203.0.113.10"
    const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)"
    const val HR_ACTOR = "hr.officer@example.com"
}

/**
 * `AccessPin.of` unwrapped.
 *
 * Goes through the real value object rather than holding a raw string, so a test that mistypes a PIN
 * fails in its own arrange step. The default is a valid six-digit PIN; pass a different one to
 * contrast a right PIN with a wrong one.
 */
fun anAccessPin(raw: String = "123456"): AccessPin =
    when (val result = AccessPin.of(raw)) {
        is DomainResult.Ok -> result.value
        is DomainResult.Err -> throw IllegalArgumentException("'$raw' is not a valid PIN: ${result.error.code}")
    }

/** `EmailAddress.of` unwrapped — a malformed literal is a mistake in the test, not a branch. */
fun anEmail(raw: String = "jose.delacruz@example.com"): EmailAddress =
    when (val result = EmailAddress.of(raw)) {
        is DomainResult.Ok -> result.value
        is DomainResult.Err -> throw IllegalArgumentException("'$raw' is not a valid email: ${result.error.code}")
    }

// ── Reference data ──────────────────────────────────────────────────────────────────────────────

fun aDepartment(
    id: EntityId = Fixtures.DEPARTMENT_ID,
    name: String = "Store Operations",
): Department = Department(id = id, name = name)

fun anEmploymentType(
    id: EntityId = Fixtures.EMPLOYMENT_TYPE_ID,
    name: String = "Regular",
): EmploymentType = EmploymentType(id = id, name = name)

fun aRequirementTemplate(
    id: EntityId = Fixtures.TEMPLATE_ID,
    name: String = "NBI Clearance",
    instructions: String = "Upload a clear photo or scan of the full document.",
    isRequired: Boolean = true,
    expires: Boolean = false,
    validityMonths: Int? = null,
    renewalLeadDays: Int? = null,
    isActive: Boolean = true,
    sortOrder: Int = 0,
): RequirementTemplate = RequirementTemplate(
    id = id,
    name = name,
    instructions = instructions,
    isRequired = isRequired,
    expires = expires,
    validityMonths = validityMonths,
    renewalLeadDays = renewalLeadDays,
    isActive = isActive,
    sortOrder = sortOrder,
)

fun aTemplateAssignment(
    employmentTypeId: EntityId = Fixtures.EMPLOYMENT_TYPE_ID,
    requirementTemplateId: EntityId = Fixtures.TEMPLATE_ID,
): TemplateAssignment = TemplateAssignment(employmentTypeId, requirementTemplateId)

// ── Employee ────────────────────────────────────────────────────────────────────────────────────

fun anEmployee(
    id: PersonId = Fixtures.EMPLOYEE_ID,
    firstName: String = "Jose",
    middleInitial: String? = "P",
    lastName: String = "Dela Cruz",
    departmentId: EntityId = Fixtures.DEPARTMENT_ID,
    position: String = "Store Associate",
    employmentTypeId: EntityId = Fixtures.EMPLOYMENT_TYPE_ID,
    email: EmailAddress = anEmail(),
    packetStatus: PacketStatus = PacketStatus.DRAFT_COLLECTING,
    submittedAt: Instant? = null,
    submittedByHr: Boolean = false,
    attestation: Attestation? = null,
    originalsSightedAt: Instant? = null,
    originalsSightedBy: PersonId? = null,
    anomalyFlags: Set<AnomalyFlag> = emptySet(),
    completedAt: Instant? = null,
    createdAt: Instant = FixedClock.DEFAULT,
    createdBy: PersonId = Fixtures.HR_USER_ID,
): Employee = Employee(
    id = id,
    firstName = firstName,
    middleInitial = middleInitial,
    lastName = lastName,
    departmentId = departmentId,
    position = position,
    employmentTypeId = employmentTypeId,
    email = email,
    packetStatus = packetStatus,
    submittedAt = submittedAt,
    submittedByHr = submittedByHr,
    attestation = attestation,
    originalsSightedAt = originalsSightedAt,
    originalsSightedBy = originalsSightedBy,
    anomalyFlags = anomalyFlags,
    completedAt = completedAt,
    createdAt = createdAt,
    createdBy = createdBy,
)

fun anAttestation(
    textVersion: String = "v1",
    attestedAt: Instant = FixedClock.DEFAULT,
    attestedIp: String = Fixtures.IP,
): Attestation = Attestation(textVersion = textVersion, attestedAt = attestedAt, attestedIp = attestedIp)

/**
 * A hire who has reviewed, attested and submitted (PRD 7.2).
 *
 * The attestation is present because that is what distinguishes an employee submission from an HR
 * force-submit, and `submittedByHr` stays false for the same reason. A packet in `UNDER_REVIEW` with
 * no attestation is a different record with different consequences.
 */
fun aSubmittedPacket(
    id: PersonId = Fixtures.EMPLOYEE_ID,
    attestation: Attestation = anAttestation(),
    submittedAt: Instant = FixedClock.DEFAULT,
): Employee = anEmployee(
    id = id,
    packetStatus = PacketStatus.UNDER_REVIEW,
    submittedAt = submittedAt,
    attestation = attestation,
)

/** A hire whose paperwork is in and approved. Not identity assurance — see `originalsSightedAt`. */
fun aCompletedPacket(
    id: PersonId = Fixtures.EMPLOYEE_ID,
    completedAt: Instant = FixedClock.DEFAULT,
): Employee = anEmployee(
    id = id,
    packetStatus = PacketStatus.COMPLETE,
    submittedAt = completedAt.minus(Duration.ofDays(2)),
    attestation = anAttestation(attestedAt = completedAt.minus(Duration.ofDays(2))),
    completedAt = completedAt,
)

/** A hire with an open anomaly flag, so `retentionFrozen` is true (PRD 7.1, SEC-13). */
fun aFlaggedEmployee(
    id: PersonId = Fixtures.EMPLOYEE_ID,
    flag: AnomalyFlag = AnomalyFlag.SUSPECTED_FRAUD,
): Employee = anEmployee(id = id, anomalyFlags = setOf(flag))

// ── Requirements ────────────────────────────────────────────────────────────────────────────────

fun anEmployeeRequirement(
    id: EntityId = Fixtures.REQUIREMENT_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    templateId: EntityId = Fixtures.TEMPLATE_ID,
    nameSnapshot: String = "NBI Clearance",
    isRequiredSnapshot: Boolean = true,
    status: RequirementStatus = RequirementStatus.PENDING,
    rejectionCount: Int = 0,
): EmployeeRequirement = EmployeeRequirement(
    id = id,
    employeeId = employeeId,
    templateId = templateId,
    nameSnapshot = nameSnapshot,
    isRequiredSnapshot = isRequiredSnapshot,
    status = status,
    rejectionCount = rejectionCount,
)

/**
 * A requirement set stated as the progress figures it should report (PRD 6.5).
 *
 * `aRequirementSet(required = 5, approved = 2)` builds five required requirements of which two are
 * `APPROVED` and three `PENDING`. `optional` adds requirements outside the denominator entirely,
 * which is the rule most easily broken by a `count()` that forgets to filter.
 *
 * The `require` is a construction precondition, not a domain rule: asking for more approved than
 * required describes no reachable state, and a builder that silently clamped it would make a
 * nonsense test pass.
 *
 * `firstId` offsets the generated requirement ids. Two sets built for two employees would otherwise
 * share ids, and a repository keyed by id would silently merge them.
 */
fun aRequirementSet(
    required: Int = 3,
    approved: Int = 0,
    underReview: Int = 0,
    uploaded: Int = 0,
    optional: Int = 0,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    firstId: Int = 0,
): RequirementSet {
    require(required >= 0 && optional >= 0) { "A requirement set cannot have a negative count" }
    require(approved >= 0 && underReview >= 0 && uploaded >= 0) { "Status counts cannot be negative" }
    require(approved + underReview + uploaded <= required) {
        "Asked for $approved approved, $underReview under review and $uploaded uploaded out of only $required required"
    }

    val statuses = buildList {
        repeat(approved) { add(RequirementStatus.APPROVED) }
        repeat(underReview) { add(RequirementStatus.UNDER_REVIEW) }
        repeat(uploaded) { add(RequirementStatus.UPLOADED) }
        repeat(required - approved - underReview - uploaded) { add(RequirementStatus.PENDING) }
    }

    val requirements = statuses.mapIndexed { index, status ->
        anEmployeeRequirement(
            id = requirementId(firstId + index),
            employeeId = employeeId,
            nameSnapshot = "Required document ${index + 1}",
            isRequiredSnapshot = true,
            status = status,
        )
    } + List(optional) { index ->
        anEmployeeRequirement(
            id = requirementId(firstId + statuses.size + index),
            employeeId = employeeId,
            nameSnapshot = "Optional document ${index + 1}",
            isRequiredSnapshot = false,
            status = RequirementStatus.PENDING,
        )
    }

    return RequirementSet(requirements)
}

/** `REQ000000001`, `REQ000000002`, … — stable across runs so a failure quotes the same id twice. */
fun requirementId(index: Int): EntityId = entityId("REQ" + (index + 1).toString().padStart(9, '0'))

// ── Upload links ────────────────────────────────────────────────────────────────────────────────

fun anUploadLink(
    id: EntityId = Fixtures.LINK_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    tokenHash: String = "token-hash-0000000001",
    pinHash: String = "pin-hash-0000000001",
    scope: LinkScope = LinkScope.All,
    status: LinkStatus = LinkStatus.ACTIVE,
    issuedAt: Instant = FixedClock.DEFAULT,
    expiresAt: Instant = issuedAt.plus(Duration.ofDays(LinkPolicy().absoluteExpiryDays.toLong())),
    idleExpiresAt: Instant? = issuedAt.plus(Duration.ofDays(LinkPolicy().idleExpiryDays.toLong())),
    extendedCount: Int = 0,
    failedPinCount: Int = 0,
    lockedUntil: Instant? = null,
    warnedAt: Instant? = null,
    revokedAt: Instant? = null,
    revokedReason: String? = null,
): UploadLink = UploadLink(
    id = id,
    employeeId = employeeId,
    tokenHash = tokenHash,
    pinHash = pinHash,
    scope = scope,
    status = status,
    issuedAt = issuedAt,
    expiresAt = expiresAt,
    idleExpiresAt = idleExpiresAt,
    extendedCount = extendedCount,
    failedPinCount = failedPinCount,
    lockedUntil = lockedUntil,
    warnedAt = warnedAt,
    revokedAt = revokedAt,
    revokedReason = revokedReason,
)

/** The working link: issued now, both clocks ahead of it, no failures recorded. */
fun anActiveLink(
    id: EntityId = Fixtures.LINK_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    tokenHash: String = "token-hash-0000000001",
): UploadLink = anUploadLink(id = id, employeeId = employeeId, tokenHash = tokenHash)

/**
 * A lapsed link.
 *
 * Both halves matter: the status says `EXPIRED` and `expiresAt` really is behind the fixed clock. A
 * use case that decides expiry by comparing instants and one that reads the enum must both see an
 * expired link here, or whichever one is wrong passes by accident.
 */
fun anExpiredLink(
    id: EntityId = Fixtures.LINK_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
): UploadLink = anUploadLink(
    id = id,
    employeeId = employeeId,
    status = LinkStatus.EXPIRED,
    issuedAt = FixedClock.DEFAULT.minus(Duration.ofDays(100)),
    expiresAt = FixedClock.DEFAULT.minus(Duration.ofDays(10)),
    idleExpiresAt = FixedClock.DEFAULT.minus(Duration.ofDays(70)),
)

/**
 * A link in temporary lockout (PRD 6.6).
 *
 * Still `ACTIVE` — lockout is a timed gate on PIN entry, not a link state. Conflating the two is
 * exactly the mistake `LinkStatus` exists to prevent, so the fixture must not make it either.
 */
fun aLockedOutLink(
    id: EntityId = Fixtures.LINK_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    policy: LinkPolicy = LinkPolicy(),
): UploadLink = anUploadLink(
    id = id,
    employeeId = employeeId,
    status = LinkStatus.ACTIVE,
    failedPinCount = policy.pinAttemptsBeforeLockout,
    lockedUntil = FixedClock.DEFAULT.plus(Duration.ofMinutes(policy.lockoutMinutes.toLong())),
)

/** Auto-suspended by a burst of PIN failures, or by the employee reporting a problem. */
fun aSuspendedLink(
    id: EntityId = Fixtures.LINK_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    policy: LinkPolicy = LinkPolicy(),
): UploadLink = anUploadLink(
    id = id,
    employeeId = employeeId,
    status = LinkStatus.SUSPENDED,
    failedPinCount = policy.pinFailuresBeforeSuspend,
)

/** Revoked by HR or by an email change — immediate and unconditional, with a recorded reason. */
fun aRevokedLink(
    id: EntityId = Fixtures.LINK_ID,
    employeeId: PersonId = Fixtures.EMPLOYEE_ID,
    reason: String = "Email address corrected",
): UploadLink = anUploadLink(
    id = id,
    employeeId = employeeId,
    status = LinkStatus.REVOKED,
    revokedAt = FixedClock.DEFAULT,
    revokedReason = reason,
)

// ── Submissions ─────────────────────────────────────────────────────────────────────────────────

fun aSubmission(
    id: EntityId = Fixtures.SUBMISSION_ID,
    employeeRequirementId: EntityId = Fixtures.REQUIREMENT_ID,
    version: Int = 1,
    fileKey: String = "documents/EMP00001/REQ000000001/v1",
    originalFilename: String = "nbi-clearance.pdf",
    mimeType: String = "application/pdf",
    sizeBytes: Long = 512L * 1024,
    uploadedAt: Instant = FixedClock.DEFAULT,
    status: RequirementStatus = RequirementStatus.UPLOADED,
    validFrom: Instant? = null,
    validUntil: Instant? = null,
    reviewedBy: PersonId? = null,
    reviewedAt: Instant? = null,
    rejectionReason: String? = null,
    isCurrent: Boolean = true,
): Submission = Submission(
    id = id,
    employeeRequirementId = employeeRequirementId,
    version = version,
    fileKey = fileKey,
    originalFilename = originalFilename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    uploadedAt = uploadedAt,
    status = status,
    validFrom = validFrom,
    validUntil = validUntil,
    reviewedBy = reviewedBy,
    reviewedAt = reviewedAt,
    rejectionReason = rejectionReason,
    isCurrent = isCurrent,
)

/**
 * `count` versions of one requirement, oldest first, only the newest current.
 *
 * The fixture the retention rule needs (PRD 7.1: current plus the last four). Each version is a
 * minute later than the last, so "the oldest" is unambiguous however a purge chooses to order.
 */
fun aVersionChain(
    employeeRequirementId: EntityId = Fixtures.REQUIREMENT_ID,
    count: Int = 3,
    sizeBytes: Long = 512L * 1024,
): List<Submission> {
    require(count >= 1) { "A version chain needs at least one version" }

    return (1..count).map { version ->
        aSubmission(
            id = entityId("SUB" + version.toString().padStart(9, '0')),
            employeeRequirementId = employeeRequirementId,
            version = version,
            fileKey = "documents/${employeeRequirementId.value}/v$version",
            sizeBytes = sizeBytes,
            uploadedAt = FixedClock.DEFAULT.plus(Duration.ofMinutes(version.toLong())),
            isCurrent = version == count,
        )
    }
}

// ── Portal sessions and the access trail ────────────────────────────────────────────────────────

fun aPortalSession(
    id: EntityId = Fixtures.SESSION_ID,
    uploadLinkId: EntityId = Fixtures.LINK_ID,
    startedAt: Instant = FixedClock.DEFAULT,
    expiresAt: Instant = startedAt.plus(Duration.ofMinutes(LinkPolicy().sessionMinutes.toLong())),
    ip: String = Fixtures.IP,
    userAgent: String = Fixtures.USER_AGENT,
    endedAt: Instant? = null,
): PortalSession = PortalSession(
    id = id,
    uploadLinkId = uploadLinkId,
    startedAt = startedAt,
    expiresAt = expiresAt,
    ip = ip,
    userAgent = userAgent,
    endedAt = endedAt,
)

/** Ended before its expiry — signed out, or terminated by HR. */
fun anEndedSession(
    id: EntityId = Fixtures.SESSION_ID,
    uploadLinkId: EntityId = Fixtures.LINK_ID,
): PortalSession = aPortalSession(
    id = id,
    uploadLinkId = uploadLinkId,
    endedAt = FixedClock.DEFAULT.plus(Duration.ofMinutes(5)),
)

/** Ran out rather than being ended: `endedAt` is null and `expiresAt` is behind the fixed clock. */
fun anExpiredSession(
    id: EntityId = Fixtures.SESSION_ID,
    uploadLinkId: EntityId = Fixtures.LINK_ID,
): PortalSession = aPortalSession(
    id = id,
    uploadLinkId = uploadLinkId,
    startedAt = FixedClock.DEFAULT.minus(Duration.ofHours(2)),
    expiresAt = FixedClock.DEFAULT.minus(Duration.ofMinutes(75)),
)

fun aPortalAccessLog(
    id: EntityId = Fixtures.ACCESS_LOG_ID,
    uploadLinkId: EntityId = Fixtures.LINK_ID,
    sessionId: EntityId? = null,
    timestamp: Instant = FixedClock.DEFAULT,
    ip: String = Fixtures.IP,
    userAgent: String = Fixtures.USER_AGENT,
    action: PortalAction = PortalAction.OPEN_LINK,
    outcome: PortalOutcome = PortalOutcome.SUCCESS,
): PortalAccessLog = PortalAccessLog(
    id = id,
    uploadLinkId = uploadLinkId,
    sessionId = sessionId,
    timestamp = timestamp,
    ip = ip,
    userAgent = userAgent,
    action = action,
    outcome = outcome,
)

/**
 * A failed PIN entry — or an unknown token. The trail does not distinguish them, deliberately
 * (PRD 6.6): it is shown to HR, and recording which would make it an oracle.
 */
fun aDeniedAttempt(
    id: EntityId = Fixtures.ACCESS_LOG_ID,
    uploadLinkId: EntityId = Fixtures.LINK_ID,
    ip: String = Fixtures.IP,
    timestamp: Instant = FixedClock.DEFAULT,
): PortalAccessLog = aPortalAccessLog(
    id = id,
    uploadLinkId = uploadLinkId,
    ip = ip,
    timestamp = timestamp,
    action = PortalAction.VERIFY_PIN,
    outcome = PortalOutcome.DENIED,
)

fun aSuccessfulAccess(
    id: EntityId = Fixtures.ACCESS_LOG_ID,
    uploadLinkId: EntityId = Fixtures.LINK_ID,
    sessionId: EntityId? = Fixtures.SESSION_ID,
    ip: String = Fixtures.IP,
    timestamp: Instant = FixedClock.DEFAULT,
): PortalAccessLog = aPortalAccessLog(
    id = id,
    uploadLinkId = uploadLinkId,
    sessionId = sessionId,
    ip = ip,
    timestamp = timestamp,
    action = PortalAction.VERIFY_PIN,
    outcome = PortalOutcome.SUCCESS,
)

/** `LOG000000001`, `LOG000000002`, … — for a trail built several entries at a time. */
fun accessLogId(index: Int): EntityId = entityId("LOG" + (index + 1).toString().padStart(9, '0'))

// ── Audit ───────────────────────────────────────────────────────────────────────────────────────

fun anAuditEntry(
    id: EntityId = Fixtures.AUDIT_ID,
    actor: String = Fixtures.HR_ACTOR,
    /**
     * Defaults to **null**, not to [Fixtures.HR_USER_ID] (ERT-190).
     *
     * The column is nullable precisely so the trail can record actors who are not users — the seed,
     * the expiry sweep, an import job. Defaulting it to a user would make every fixture look
     * attributable and would let a rule that forgot to set it pass its own test.
     */
    actorUserId: PersonId? = null,
    action: AuditAction = AuditAction.HIRE_CREATED,
    entity: String = "employee",
    entityId: Identifier = Fixtures.EMPLOYEE_ID,
    timestamp: Instant = FixedClock.DEFAULT,
    metadata: Map<String, String> = emptyMap(),
): AuditEntry = AuditEntry(
    id = id,
    actor = actor,
    actorUserId = actorUserId,
    action = action,
    entity = entity,
    entityId = entityId,
    timestamp = timestamp,
    metadata = metadata,
)

/**
 * An HR account (ERT-190).
 *
 * [passwordHash] is a recognisable placeholder rather than a real digest, for the reason the credential
 * fields on [anUploadLink] are: a test that only needs an account to exist should not pay ~100ms of
 * bcrypt. A test that actually verifies a password builds its own with `BcryptHasher(cost = 4)`.
 *
 * [passwordChangeRequired] defaults to **false** so the ordinary fixture is a usable account; the
 * gate has its own tests that set it.
 */
fun anHrUser(
    id: PersonId = Fixtures.HR_USER_ID,
    email: EmailAddress = anEmail("hr.officer@example.com"),
    fullName: String = "Ana Reyes",
    passwordHash: String = "bcrypt-placeholder",
    role: HrRole = HrRole.HR_OFFICER,
    isActive: Boolean = true,
    passwordChangeRequired: Boolean = false,
    createdAt: Instant = FixedClock.DEFAULT,
): HrUser = HrUser(
    id = id,
    email = email,
    fullName = fullName,
    passwordHash = passwordHash,
    role = role,
    isActive = isActive,
    passwordChangeRequired = passwordChangeRequired,
    createdAt = createdAt,
)

/** An `HR_ADMIN`, for the routes an officer may not reach. */
fun anHrAdmin(
    id: PersonId = personId("HRA00001"),
    email: EmailAddress = anEmail("hr.admin@example.com"),
    passwordHash: String = "bcrypt-placeholder",
    isActive: Boolean = true,
    passwordChangeRequired: Boolean = false,
): HrUser = anHrUser(
    id = id,
    email = email,
    fullName = "Marisol Tan",
    passwordHash = passwordHash,
    role = HrRole.HR_ADMIN,
    isActive = isActive,
    passwordChangeRequired = passwordChangeRequired,
)
