package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement

/**
 * The `employee_requirements -> employees` join, in memory.
 *
 * `SubmissionRepository.totalBytesFor` takes a [PersonId], but a [com.pgsystem.employee.requirement.tracker.domain.model.Submission]
 * only knows its `employeeRequirementId` — the real adapter closes that gap with a join, and the
 * fakes need somewhere equivalent to look. [FakeEmployeeRepository] registers into this whenever it
 * is given requirements; [FakeSubmissionRepository] reads it.
 *
 * Each fake defaults to its own instance, so either is constructible alone. A test that needs the
 * join passes one instance to both:
 *
 * ```
 * val owners = RequirementOwners()
 * val employees = FakeEmployeeRepository(anEmployee(), owners = owners)
 * val submissions = FakeSubmissionRepository(owners = owners)
 * ```
 *
 * Or registers directly, when no employee repository is in play:
 * `owners.register(requirementId, ownedBy = employeeId)`.
 */
class RequirementOwners {
    private val owners = mutableMapOf<EntityId, PersonId>()

    fun register(employeeRequirementId: EntityId, ownedBy: PersonId) {
        owners[employeeRequirementId] = ownedBy
    }

    fun register(requirements: Iterable<EmployeeRequirement>) {
        requirements.forEach { owners[it.id] = it.employeeId }
    }

    /**
     * Resolve or fail loudly.
     *
     * A missing owner must not read as "zero bytes". The PRD 7.1 storage cap is asserted by
     * comparing a total against 100 MB, and a silent zero makes that assertion pass for a reason
     * that has nothing to do with the rule.
     */
    fun ownerOrFail(employeeRequirementId: EntityId): PersonId =
        owners[employeeRequirementId] ?: error(
            "No employee owns requirement ${employeeRequirementId.value}. Register it with " +
                "RequirementOwners.register(...), or pass the same RequirementOwners instance to " +
                "FakeEmployeeRepository and FakeSubmissionRepository."
        )
}
