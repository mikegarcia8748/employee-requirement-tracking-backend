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
 * `employees` and `employee_requirements` rows ↔ [Employee] and [EmployeeRequirement] (ERT-410).
 *
 * **A stored value that will not parse is a corrupt row, not a case to branch on**, so this throws
 * rather than returning a `DomainResult` — the same choice `HrUserMapper` and
 * `ExposedReferenceDataRepository` make. Every value here was written from an already-validated
 * value object or an enum name, so the only way one fails is that something outside this application
 * wrote the row.
 *
 * Three mappings are not mechanical, and each has a named test in
 * `ExposedEmployeeRepositoryTest`:
 *
 *  - **The attestation triple.** `attestation_version`, `attested_at` and `attested_ip` are three
 *    nullable columns holding one nullable [Attestation]. [writeTo] writes all three from the single
 *    `employee.attestation?`, so a half-populated attestation is unrepresentable on the way in;
 *    [toAttestation] refuses one on the way out. There is **no database CHECK** tying them together,
 *    so these two functions are the entire enforcement of "all three or none" (PRD 7.2).
 *  - **The anomaly-flag set.** `anomaly_flags` is one delimited string, and it drives
 *    [Employee.retentionFrozen] — get the round trip wrong and PRD 7.1's retention freeze stops
 *    working in silence, which is SEC-13. The empty set is the empty string, which is also the
 *    column's own default.
 *  - **The actor references.** `created_by` is a non-null `users(id)` and `originals_sighted_by` a
 *    nullable one, so they read as `EntityID<String>` and `EntityID<String>?` rather than as text.
 *
 * The write side is shared by insert and update through [writeTo], for the reason `HrUserMapper`
 * gives: two hand-written column lists drift, and the realistic failure is a field added to
 * [Employee] and wired into the insert only.
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
    attestation = toAttestation(),
    originalsSightedAt = this[Employees.originalsSightedAt],
    originalsSightedBy = this[Employees.originalsSightedBy]
        ?.let { PersonId.of(it.value).orCorrupt("employees.originals_sighted_by") },
    anomalyFlags = this[Employees.anomalyFlags].toAnomalyFlags(),
    completedAt = this[Employees.completedAt],
    createdAt = this[Employees.createdAt],
    createdBy = PersonId.of(this[Employees.createdBy].value).orCorrupt("employees.created_by"),
)

/**
 * Every non-key column of `employees`, in one place, for both insert and update.
 *
 * `id` is excluded on purpose: it is the key an update matches on, and Exposed rejects writing it in
 * an update statement. `ExposedEmployeeRepository` supplies it on the insert path only.
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

    // All three from one nullable source, so a half-populated attestation cannot be written.
    this[Employees.attestationVersion] = employee.attestation?.textVersion
    this[Employees.attestedAt] = employee.attestation?.attestedAt
    this[Employees.attestedIp] = employee.attestation?.attestedIp

    this[Employees.originalsSightedAt] = employee.originalsSightedAt
    this[Employees.originalsSightedBy] = employee.originalsSightedBy?.value
    this[Employees.anomalyFlags] = employee.anomalyFlags.toColumn()
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
    sortOrderSnapshot = this[EmployeeRequirements.sortOrderSnapshot],
    status = this[EmployeeRequirements.status].toRequirementStatus(),
    rejectionCount = this[EmployeeRequirements.rejectionCount],
)

/** Every non-key column of `employee_requirements`. See the [Employee] overload for why `id` is out. */
internal fun UpdateBuilder<*>.writeTo(requirement: EmployeeRequirement) {
    this[EmployeeRequirements.employeeId] = requirement.employeeId.value
    this[EmployeeRequirements.templateId] = requirement.templateId.value
    this[EmployeeRequirements.nameSnapshot] = requirement.nameSnapshot
    this[EmployeeRequirements.isRequiredSnapshot] = requirement.isRequiredSnapshot
    this[EmployeeRequirements.sortOrderSnapshot] = requirement.sortOrderSnapshot
    this[EmployeeRequirements.status] = requirement.status.name
    this[EmployeeRequirements.rejectionCount] = requirement.rejectionCount
}

/**
 * The three attestation columns as one nullable [Attestation].
 *
 * A row with some but not all three present describes no state this application can produce — PRD
 * 7.2 attests to a text version at a time from an address, and the three are written together — so
 * it is corruption rather than a partial attestation worth modelling. Returning `null` for it would
 * silently discard evidence that someone attested; returning a half-built object is not
 * representable, since every field of [Attestation] is non-null.
 */
private fun ResultRow.toAttestation(): Attestation? {
    val textVersion = this[Employees.attestationVersion]
    val attestedAt = this[Employees.attestedAt]
    val attestedIp = this[Employees.attestedIp]

    return when {
        textVersion == null && attestedAt == null && attestedIp == null -> null

        textVersion != null && attestedAt != null && attestedIp != null ->
            Attestation(textVersion = textVersion, attestedAt = attestedAt, attestedIp = attestedIp)

        else -> error(
            "employees holds a half-populated attestation for '${this[Employees.id].value}': " +
                "version=${textVersion != null}, at=${attestedAt != null}, ip=${attestedIp != null}. " +
                "All three columns are written together or not at all."
        )
    }
}

/**
 * The flag set as one column value.
 *
 * Sorted by ordinal so the stored text is stable: an unsorted `Set` would write the same flags in
 * different orders on two saves, which makes a column diff noise and an assertion on the raw text
 * flaky. No [AnomalyFlag] name contains a comma, which is what makes the delimiter safe — a name
 * that did would have to change the encoding, not just the enum.
 */
private fun Set<AnomalyFlag>.toColumn(): String =
    sortedBy { it.ordinal }.joinToString(FLAG_SEPARATOR) { it.name }

private fun String.toAnomalyFlags(): Set<AnomalyFlag> =
    split(FLAG_SEPARATOR)
        .filter { it.isNotBlank() }
        .map { name ->
            AnomalyFlag.entries.firstOrNull { it.name == name }
                ?: error(
                    "employees.anomaly_flags holds '$name', which is not an AnomalyFlag. " +
                        "A flag was removed from the enum without a migration."
                )
        }
        .toSet()

private const val FLAG_SEPARATOR = ","

private fun String.toPacketStatus(): PacketStatus =
    PacketStatus.entries.firstOrNull { it.name == this }
        ?: error(
            "employees.packet_status holds '$this', which is not a PacketStatus. " +
                "A status was removed from the enum without a migration."
        )

private fun String.toRequirementStatus(): RequirementStatus =
    RequirementStatus.entries.firstOrNull { it.name == this }
        ?: error(
            "employee_requirements.status holds '$this', which is not a RequirementStatus. " +
                "A status was removed from the enum without a migration."
        )

private fun <T> DomainResult<T>.orCorrupt(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value the domain rejects: ${error.code}")
}
