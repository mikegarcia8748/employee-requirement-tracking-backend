package com.pgsystem.employee.requirement.tracker.domain.model

import java.util.UUID

/**
 * One requirement as it applies to one employee — a **snapshot** of the template taken at hire
 * creation, not a live reference to it (PRD 5).
 *
 * `nameSnapshot` and `isRequiredSnapshot` are copies for exactly this reason: editing a template
 * later must not change the progress of anyone already in flight, and must never make a completed
 * hire retroactively incomplete. Template edits apply only to hires created after the edit.
 */
data class EmployeeRequirement(
    val id: UUID,
    val employeeId: UUID,
    val templateId: UUID,
    val nameSnapshot: String,
    val isRequiredSnapshot: Boolean,
    val status: RequirementStatus,
    /** Three rejections flags the record for HR attention — a signal, not a block (PRD 7.1). */
    val rejectionCount: Int,
)

/**
 * The set of requirements for one employee, and the progress arithmetic of PRD 6.5.
 *
 * Two figures, not one: submission progress is what the employee has done, approval progress is
 * what HR has actually validated. Showing only the former lets a hire look finished when nothing
 * has been checked.
 */
data class RequirementSet(val requirements: List<EmployeeRequirement>) {

    /** Optional requirements are excluded from the denominator entirely (PRD 6.5). */
    private val required: List<EmployeeRequirement> get() = requirements.filter { it.isRequiredSnapshot }

    val total: Int get() = required.size

    val submitted: Int
        get() = required.count {
            it.status in setOf(
                RequirementStatus.UPLOADED,
                RequirementStatus.UNDER_REVIEW,
                RequirementStatus.APPROVED,
            )
        }

    val approved: Int get() = required.count { it.status == RequirementStatus.APPROVED }

    val awaitingReview: Int get() = required.count { it.status == RequirementStatus.UNDER_REVIEW }
}
