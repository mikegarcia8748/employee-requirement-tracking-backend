package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.domain.model.Department
import com.pgsystem.employee.requirement.tracker.domain.model.EmploymentType
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository

/**
 * The seeded reference data a hire is created against, in memory (ERT-431).
 *
 * **The eleventh port, and the last one to get a fake.** ERT-210 built ten;
 * `ReferenceDataRepository` arrived later with ERT-350, which needed only a private fake inside its
 * own route test, and nothing recorded a shared one as anyone's job until C9 did. `CreateHireUseCase`
 * is the first use case to need it, which is what the local fake's own KDoc predicted.
 *
 * ### Two maps, never one
 *
 * The port splits `departmentExists` from `employmentTypeExists` because both ids are 12-character
 * [EntityId]s and structurally indistinguishable, so a single `exists(id)` scanning both tables
 * would answer `true` for a department id handed in the `employmentTypeId` slot — PRD 8.2's exact
 * defect, reached through the validator meant to prevent it. **A fake holding one combined
 * collection would reintroduce it**, and the use-case test written to prove the two checks are
 * separate would pass against a use case that called the same one twice. The split is mirrored into
 * the arrange surface too: [givenDepartments] and [givenEmploymentTypes] are separately named rather
 * than two `given` overloads, so a test cannot seed a department into the employment-type
 * collection — and two overloads taking `List` would clash on the JVM anyway.
 *
 * ### Both reads sort by name
 *
 * The port says so for both, and `ExposedReferenceDataRepository` does it. A fake returning
 * insertion order would let a caller that lost the ordering pass here and reorder a picker in
 * production. `employment_types` has no `sort_order` column, so name order is not HR's order — that
 * is the missing column speaking (ERT-350), not a choice this fake gets to make.
 *
 * There are deliberately **no read counters**. `FakeRequirementTemplateRepository` records its reads
 * because PRD 5 has a read-once-at-creation rule to assert; reference data has no snapshot rule, so
 * a recorder nothing asserts would be a throwing placeholder in test clothing.
 */
class FakeReferenceDataRepository(
    departments: List<Department> = emptyList(),
    employmentTypes: List<EmploymentType> = emptyList(),
) : ReferenceDataRepository {

    val failure = FakeFailure()

    private val departmentsById = departments.associateBy { it.id }.toMutableMap()
    private val employmentTypesById = employmentTypes.associateBy { it.id }.toMutableMap()

    // ── The port ────────────────────────────────────────────────────────────────────────────────

    override suspend fun findDepartments(): List<Department> {
        failure.check()
        return departmentsById.values.sortedBy { it.name }
    }

    override suspend fun findEmploymentTypes(): List<EmploymentType> {
        failure.check()
        return employmentTypesById.values.sortedBy { it.name }
    }

    override suspend fun departmentExists(id: EntityId): Boolean {
        failure.check()
        return departmentsById.containsKey(id)
    }

    override suspend fun employmentTypeExists(id: EntityId): Boolean {
        failure.check()
        return employmentTypesById.containsKey(id)
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    fun givenDepartments(vararg departments: Department): FakeReferenceDataRepository = apply {
        departments.forEach { departmentsById[it.id] = it }
    }

    fun givenEmploymentTypes(vararg employmentTypes: EmploymentType): FakeReferenceDataRepository = apply {
        employmentTypes.forEach { employmentTypesById[it.id] = it }
    }
}
