package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.table.EmployeeRequirements
import com.pgsystem.employee.requirement.tracker.data.db.table.Employees
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.Attestation
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

/**
 * `employees` and `employee_requirements` rows ↔ their domain models (ERT-410).
 *
 * Both halves of one aggregate live in this file because they are written together: a hire and its
 * snapshotted requirement set are created in the same action (PRD 8.1) and nothing reads one
 * without the other.
 *
 * **A stored value that will not parse is a corrupt row, not a case to branch on**, so this throws
 * rather than returning a `DomainResult` — the choice `HrUserMapper` and
 * `ExposedReferenceDataRepository` already make. Every value here was written from a validated value
 * object or an enum name, so the only way one fails is that something outside this application wrote
 * the row.
 *
 * Three places are not mechanical, and each has its own named test:
 *
 *  - **[toAnomalyFlags] and the empty string.** The column defaults to `''`, and `"".split(",")`
 *    yields `[""]` rather than an empty list. Get this wrong and a hire with no flags either throws
 *    on a blank flag name or — far worse — comes back carrying one, which makes
 *    [Employee.retentionFrozen] true for every hire in the system and silently disables the PRD 7.1
 *    version purge. The freeze failing *open* is invisible in a green suite.
 *  - **[attestationIn] is all-or-nothing.** Three nullable columns collapse into one nullable
 *    [Attestation]. All three present is an attestation, all three absent is `null`, and any other
 *    combination is corrupt: a half-populated attestation would claim the employee agreed to
 *    something without recording which wording (PRD 7.2), which is precisely the evidence the
 *    versioning exists to preserve.
 *  - **The actor columns are [PersonId]s, not names** (ERT-190). `created_by` is non-null and
 *    `originals_sighted_by` is nullable; both reference `users(id)`.
 */
internal fun ResultRow.toEmployee(): Employee = Employee(
    id = PersonId.of(this[Employees.id].value).orCorrupt("employees.id"),
    firstName = this[Employees.firstName],
    middleInitial = this[Employees.middleInitial],
    lastName = this[Employees.lastName],
    departmentId = EntityId.of(this[Employees.departmentId].value).orCorrupt("employees.department_id"),
    position = this[Employees.position],
    employmentTypeId = EntityId.of(this[Employees.employmentTypeId].value)
        .orCorrupt("employees.employment_type_id"),
    email = EmailAddress.of(this[Employees.email]).orCorrupt("employees.email"),
    packetStatus = this[Employees.packetStatus].toPacketStatus(),
    submittedAt = this[Employees.submittedAt],
    submittedByHr = this[Employees.submittedByHr],
    attestation = attestationIn(this),
    originalsSightedAt = this[Employees.originalsSightedAt],
    originalsSightedBy = this[Employees.originalsSightedBy]
        ?.let { PersonId.of(it.value).orCorrupt("employees.originals_sighted_by") },
    anomalyFlags = this[Employees.anomalyFlags].toAnomalyFlags(),
    completedAt = this[Employees.completedAt],
    createdAt = this[Employees.createdAt],
    createdBy = PersonId.of(this[Employees.createdBy].value).orCorrupt("employees.created_by"),
)

/**
 * Every non-key column, in one place, for both insert and update.
 *
 * `id` is excluded deliberately: it is what an update matches on, and it is what `create` redraws.
 * Two hand-written column lists drift — the realistic failure is a field added to [Employee] and
 * wired into the insert only, so an edit silently keeps the old value — which is why
 * `ExposedEmployeeRepositoryTest` round-trips a *modified* hire through `save` rather than only a
 * fresh one through `create`.
 */
internal fun UpdateBuilder<*>.writeTo(employee: Employee) {
    this[Employees.firstName] = employee.firstName
    this[Employees.middleInitial] = employee.middleInitial
    this[Employees.lastName] = employee.lastName
    this[Employees.departmentId] = employee.departmentId.value
    this[Employees.position] = employee.position
    this[Employees.employmentTypeId] = employee.employmentTypeId.value
    this[Employees.email] = employee.email.value
    this[Employees.packetStatus] = employee.packetStatus.name
    this[Employees.submittedAt] = employee.submittedAt
    this[Employees.submittedByHr] = employee.submittedByHr
    this[Employees.attestationVersion] = employee.attestation?.textVersion
    this[Employees.attestedAt] = employee.attestation?.attestedAt
    this[Employees.attestedIp] = employee.attestation?.attestedIp
    this[Employees.originalsSightedAt] = employee.originalsSightedAt
    this[Employees.originalsSightedBy] = employee.originalsSightedBy?.value
    this[Employees.anomalyFlags] = employee.anomalyFlags.toStoredFlags()
    this[Employees.completedAt] = employee.completedAt
    this[Employees.createdAt] = employee.createdAt
    this[Employees.createdBy] = employee.createdBy.value
}

internal fun ResultRow.toEmployeeRequirement(): EmployeeRequirement = EmployeeRequirement(
    id = EntityId.of(this[EmployeeRequirements.id].value).orCorrupt("employee_requirements.id"),
    employeeId = PersonId.of(this[EmployeeRequirements.employeeId].value)
        .orCorrupt("employee_requirements.employee_id"),
    templateId = EntityId.of(this[EmployeeRequirements.templateId].value)
        .orCorrupt("employee_requirements.template_id"),
    nameSnapshot = this[EmployeeRequirements.nameSnapshot],
    isRequiredSnapshot = this[EmployeeRequirements.isRequiredSnapshot],
    status = this[EmployeeRequirements.status].toRequirementStatus(),
    rejectionCount = this[EmployeeRequirements.rejectionCount],
)

/** See [writeTo] above; `id` is excluded for the same reason. */
internal fun UpdateBuilder<*>.writeTo(requirement: EmployeeRequirement) {
    this[EmployeeRequirements.employeeId] = requirement.employeeId.value
    this[EmployeeRequirements.templateId] = requirement.templateId.value
    this[EmployeeRequirements.nameSnapshot] = requirement.nameSnapshot
    this[EmployeeRequirements.isRequiredSnapshot] = requirement.isRequiredSnapshot
    this[EmployeeRequirements.status] = requirement.status.name
    this[EmployeeRequirements.rejectionCount] = requirement.rejectionCount
}

/**
 * The three attestation columns as one nullable object.
 *
 * Stated as a `when` over the triple rather than three `?.let`s so the corrupt case has somewhere to
 * live. A row with a timestamp but no text version is not "an attestation missing its version" — it
 * is a row nobody can interpret, and PRD 7.2 makes the version the point.
 */
private fun attestationIn(row: ResultRow): Attestation? {
    val version = row[Employees.attestationVersion]
    val at = row[Employees.attestedAt]
    val ip = row[Employees.attestedIp]

    return when {
        version != null && at != null && ip != null -> Attestation(version, at, ip)
        version == null && at == null && ip == null -> null
        else -> error(
            "employees holds a partial attestation (version=${version != null}, at=${at != null}, " +
                "ip=${ip != null}). All three columns are written together or not at all."
        )
    }
}

/**
 * The comma-separated flag column back to a set.
 *
 * [String.isNotBlank] rather than [String.isNotEmpty], and a filter rather than a guard on the whole
 * string, so `""`, `"  "` and a stray trailing comma all yield an empty set instead of a flag named
 * nothing. See the note on [toEmployee] for why the empty case is the dangerous one.
 */
private fun String.toAnomalyFlags(): Set<AnomalyFlag> =
    split(SEPARATOR)
        .filter { it.isNotBlank() }
        .map { name -> name.trim().toAnomalyFlag() }
        .toSet()

/**
 * Written in enum declaration order rather than set-iteration order.
 *
 * The column is compared as text by the exception report, and a set that reordered itself between
 * writes would make two identical flag sets look like a change.
 */
private fun Set<AnomalyFlag>.toStoredFlags(): String =
    AnomalyFlag.entries.filter { it in this }.joinToString(SEPARATOR) { it.name }

private fun String.toAnomalyFlag(): AnomalyFlag =
    AnomalyFlag.entries.firstOrNull { it.name == this }
        ?: error("employees.anomaly_flags holds '$this', which is not an AnomalyFlag. A flag was removed from the enum without a migration.")

private fun String.toPacketStatus(): PacketStatus =
    PacketStatus.entries.firstOrNull { it.name == this }
        ?: error("employees.packet_status holds '$this', which is not a PacketStatus. A status was removed from the enum without a migration.")

private fun String.toRequirementStatus(): RequirementStatus =
    RequirementStatus.entries.firstOrNull { it.name == this }
        ?: error("employee_requirements.status holds '$this', which is not a RequirementStatus. A status was removed from the enum without a migration.")

private const val SEPARATOR = ","

private fun <T> DomainResult<T>.orCorrupt(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value the domain rejects: ${error.code}")
}
