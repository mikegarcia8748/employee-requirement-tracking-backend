package com.pgsystem.employee.requirement.tracker.data.db.table

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The PRD 11 schema.
 *
 * Two things here are load-bearing rather than incidental:
 *
 *  - **Snapshot columns.** `EmployeeRequirements.nameSnapshot` / `isRequiredSnapshot` and
 *    `UploadLinks.expiresAt` are copies, not joins. Editing a template or a policy later must not
 *    change the progress of anyone in flight, nor make a completed hire retroactively incomplete
 *    (PRD 5, 6.4). A foreign key read live would quietly break both.
 *  - **`PortalAccessLogs` is append-only, and there is no `lastAccessedAt` column anywhere.** A
 *    single overwritten timestamp cannot answer who, from where, or how often — the first question
 *    asked when a fraudulent submission surfaces (SEC-05). Restoring such a column would undo the
 *    control.
 */

/**
 * HR staff accounts (ERT-190, PRD §14 Q4).
 *
 * A [PersonIdTable], so a user id and an employee id are the same 8-character width. That is what
 * `Identifier.of`'s length dispatch needs — a third width would make it ambiguous — and it is why an
 * 8-character `audit_logs.entity_id` means "an employee or a user", with `entity` saying which.
 *
 * **The unique index is on `lower(email)` and it is declared in SQL, not here.** Exposed models a
 * functional index poorly, and `uniqueIndex()` on the raw column would let `A@x.com` and `a@x.com`
 * both exist. `EmailAddress.of` lower-cases on construction, so this application cannot create that
 * pair — but a migration, an import or a psql prompt can, and then "which account signs in" is a
 * race. The index is in `V4__hr_users.sql`; `ExposedHrUserRepositoryTest` asserts it bites.
 *
 * There is deliberately **no `last_login_at`**, for the reason §11 and SEC-05 give for keeping
 * `last_accessed_at` off `upload_links`: one overwritten timestamp cannot answer who, from where, or
 * how often. A sign-in is an `audit_logs` row.
 */
object Users : PersonIdTable("users") {
    val email = varchar("email", 320)
    val fullName = varchar("full_name", 256)

    /** bcrypt output. Never a password, and never returned by any route. */
    val passwordHash = varchar("password_hash", 256)

    val role = varchar("role", 32)

    /** Accounts are deactivated, never deleted — their audit rows and actor references outlive them. */
    val isActive = bool("is_active").default(true)

    val passwordChangeRequired = bool("password_change_required").default(false)
    val createdAt = timestamp("created_at")
}

object Departments : EntityIdTable("departments") {
    val name = varchar("name", 128).uniqueIndex()
}

object EmploymentTypes : EntityIdTable("employment_types") {
    val name = varchar("name", 128).uniqueIndex()
}

object RequirementTemplates : EntityIdTable("requirement_templates") {
    val name = varchar("name", 256)
    val instructions = text("instructions")
    val isRequired = bool("is_required")
    val expires = bool("expires").default(false)
    val validityMonths = integer("validity_months").nullable()
    val renewalLeadDays = integer("renewal_lead_days").nullable()
    val isActive = bool("is_active").default(true)
    val sortOrder = integer("sort_order").default(0)
}

object TemplateAssignments : Table("template_assignments") {
    val employmentTypeId = reference("employment_type_id", EmploymentTypes)
    val requirementTemplateId = reference("requirement_template_id", RequirementTemplates)
    override val primaryKey = PrimaryKey(employmentTypeId, requirementTemplateId)
}

object Employees : PersonIdTable("employees") {
    val firstName = varchar("first_name", 128)
    val middleInitial = varchar("middle_initial", 8).nullable()
    val lastName = varchar("last_name", 128)
    val departmentId = reference("department_id", Departments)
    val position = varchar("position", 256)
    val employmentTypeId = reference("employment_type_id", EmploymentTypes)
    val email = varchar("email", 320).index()
    val packetStatus = varchar("packet_status", 32)
    val submittedAt = timestamp("submitted_at").nullable()

    /** True when HR force-submitted. Such a packet carries no attestation and must look different. */
    val submittedByHr = bool("submitted_by_hr").default(false)

    /** Versioned: the wording will change, and which version was agreed to is the evidence (PRD 7.2). */
    val attestationVersion = varchar("attestation_version", 32).nullable()
    val attestedAt = timestamp("attested_at").nullable()
    val attestedIp = varchar("attested_ip", 64).nullable()

    /** The physical checkpoint. Absent means COMPLETE is not identity assurance (PRD 1, 8.5). */
    val originalsSightedAt = timestamp("originals_sighted_at").nullable()
    val originalsSightedBy = reference("originals_sighted_by", Users).nullable()

    /** Comma-separated AnomalyFlag names. Any open flag freezes version purging (PRD 7.1). */
    val anomalyFlags = varchar("anomaly_flags", 512).default("")
    val completedAt = timestamp("completed_at").nullable()
    val createdAt = timestamp("created_at")
    val createdBy = reference("created_by", Users)
}

object EmployeeRequirements : EntityIdTable("employee_requirements") {
    val employeeId = reference("employee_id", Employees)
    val templateId = reference("template_id", RequirementTemplates)

    /** Snapshots, not joins — see the note at the top of this file. */
    val nameSnapshot = varchar("name_snapshot", 256)
    val isRequiredSnapshot = bool("is_required_snapshot")

    val status = varchar("status", 32)
    val rejectionCount = integer("rejection_count").default(0)
}

object Submissions : EntityIdTable("submissions") {
    val employeeRequirementId = reference("employee_requirement_id", EmployeeRequirements)
    val version = integer("version")

    /** Object-storage key. The database never holds document bytes (PRD 11). */
    val fileKey = varchar("file_key", 512)

    /** Never returned by any portal response — filenames leak content (SEC-02). */
    val originalFilename = varchar("original_filename", 512)

    val mimeType = varchar("mime_type", 128)
    val sizeBytes = long("size_bytes")
    val uploadedAt = timestamp("uploaded_at")
    val status = varchar("status", 32)

    /** Nullable from the start so Phase 4 validity tracking is a migration, not a redesign (9.3). */
    val validFrom = timestamp("valid_from").nullable()
    val validUntil = timestamp("valid_until").nullable()

    val reviewedBy = reference("reviewed_by", Users).nullable()
    val reviewedAt = timestamp("reviewed_at").nullable()
    val rejectionReason = text("rejection_reason").nullable()
    val isCurrent = bool("is_current").default(true)
}

object UploadLinks : EntityIdTable("upload_links") {
    val employeeId = reference("employee_id", Employees)

    /** Both credentials stored hashed; neither plaintext is recoverable (PRD 12). */
    val tokenHash = varchar("token_hash", 256).uniqueIndex()

    /**
     * The recovery PIN, hashed. **Nullable since V5, and null on almost every row.**
     *
     * V1 wrote it NOT NULL under the access model that preceded 2026-09-16, where the URL opened
     * nothing without the PIN and both were issued with the hire. The link alone now opens the
     * portal (PRD 6.6) and the PIN is minted on demand by HR only when an invitation fails to
     * arrive, so a link is created without one. NOT NULL would force a bcrypt hash of a value
     * nobody was told -- a credential-shaped digest for a credential that does not exist, which the
     * column could not be distinguished from a live one.
     */
    val pinHash = varchar("pin_hash", 256).nullable()

    /** Serialised LinkScope. Always "ALL" in v1; the Phase 4 renewal seam (PRD 9.3). */
    val scope = varchar("scope", 512).default("ALL")

    val status = varchar("status", 32)
    val issuedAt = timestamp("issued_at")

    /** Computed at issue from the policy then in force — never recomputed (PRD 6.4). */
    val expiresAt = timestamp("expires_at")

    /** Null when the idle clock is disabled. The earlier of the two ceilings wins. */
    val idleExpiresAt = timestamp("idle_expires_at").nullable()

    val extendedCount = integer("extended_count").default(0)
    val failedPinCount = integer("failed_pin_count").default(0)
    val lockedUntil = timestamp("locked_until").nullable()
    val warnedAt = timestamp("warned_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    val revokedReason = text("revoked_reason").nullable()
}

object PortalSessions : EntityIdTable("portal_sessions") {
    val uploadLinkId = reference("upload_link_id", UploadLinks)

    /**
     * The session cookie's value, hashed. Not in PRD 11: without it the cookie would have to carry
     * the primary key, which makes the row id a live bearer token in plaintext. That argument is
     * sharper than it looks -- a 12-character id carries roughly 71 bits against this token's 256,
     * so the id is not merely the wrong thing to present, it is a weak one. Hashed like the link
     * token and the PIN -- only the digest is ever stored.
     */
    val tokenHash = varchar("token_hash", 256).uniqueIndex()

    val startedAt = timestamp("started_at")
    val expiresAt = timestamp("expires_at")
    val ip = varchar("ip", 64)
    val userAgent = varchar("user_agent", 512)
    val endedAt = timestamp("ended_at").nullable()
}

/** Append-only. Nothing updates or deletes rows here. */
object PortalAccessLogs : EntityIdTable("portal_access_logs") {
    val uploadLinkId = reference("upload_link_id", UploadLinks)
    val sessionId = reference("session_id", PortalSessions).nullable()
    val timestamp = timestamp("timestamp")
    val ip = varchar("ip", 64)
    val userAgent = varchar("user_agent", 512)
    val action = varchar("action", 32)

    /** DENIED covers both a wrong PIN and an unknown token; the two are not distinguished (6.6). */
    val outcome = varchar("outcome", 32)
}

object AppSettings : Table("app_settings") {
    val key = varchar("key", 128)
    val value = varchar("value", 512)
    val valueType = varchar("value_type", 32)
    val minValue = varchar("min_value", 64).nullable()
    val maxValue = varchar("max_value", 64).nullable()
    val updatedBy = reference("updated_by", Users).nullable()
    val updatedAt = timestamp("updated_at").nullable()
    override val primaryKey = PrimaryKey(key)
}

object AuditLogs : EntityIdTable("audit_logs") {
    val actor = varchar("actor", 128)
    val action = varchar("action", 64)
    val entity = varchar("entity", 64)
    /**
     * Polymorphic: an 8-character person id or a 12-character entity id, sized to the wider of
     * the two. No foreign key, deliberately -- it points at ten different tables, and an audit
     * row must outlive the row it describes.
     */
    val entityId = varchar("entity_id", EntityId.LENGTH).index()

    /**
     * The acting user, when there was one — the **fifth** actor column and the only one that did not
     * become a non-null foreign key (ERT-190).
     *
     * Nullable because the trail must record actors who are not users: the ERT-130 seed, the ERT-1020
     * expiry sweep, a future import job. An append-only trail that can refuse a write because it
     * cannot name a user is worse than one carrying a string, so [actor] keeps its free text and this
     * sits beside it. §8.13's exception report joins on this; everything else reads [actor].
     */
    val actorUserId = reference("actor_user_id", Users).nullable()

    val timestamp = timestamp("timestamp")

    /** JSON. Carries reasons and verification methods — never credentials. */
    val metadata = text("metadata").default("{}")
}

val allTables = arrayOf(
    Users,
    Departments, EmploymentTypes, RequirementTemplates, TemplateAssignments,
    Employees, EmployeeRequirements, Submissions, UploadLinks,
    PortalSessions, PortalAccessLogs, AppSettings, AuditLogs,
)
