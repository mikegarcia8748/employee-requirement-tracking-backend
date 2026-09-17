package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId

/**
 * One requirement as it applies to one employee — a **snapshot** of the template taken at hire
 * creation, not a live reference to it (PRD 5).
 *
 * `nameSnapshot`, `isRequiredSnapshot` and `sortOrderSnapshot` are copies for exactly this reason:
 * editing a template later must not change the progress of anyone already in flight, and must never
 * make a completed hire retroactively incomplete. Template edits apply only to hires created after
 * the edit.
 */
data class EmployeeRequirement(
    val id: EntityId,
    val employeeId: PersonId,
    val templateId: EntityId,
    val nameSnapshot: String,
    val isRequiredSnapshot: Boolean,
    /**
     * The catalogue's `sortOrder`, copied at creation (ERT-432).
     *
     * **The order is a snapshot for the same reason the names are.** Reading it live would mean
     * joining `requirement_templates`, so a template an admin reorders tomorrow would reshuffle a
     * checklist on a phone today, mid-onboarding. Copied verbatim rather than re-derived from the
     * position in the catalogue list: a snapshot copies, and copying is what makes the stored order
     * reproduce `RequirementTemplateRepository.findActiveForEmploymentType`'s exactly.
     *
     * **No default value, deliberately.** The column carries `default 0` so the migration applies
     * to a table that may hold rows, and a Kotlin default here would let a new construction site
     * inherit it in silence — putting every row at 0 and collapsing the order back to name, which
     * is the defect ERT-432 exists to remove. Without one, a site that forgets does not compile.
     */
    val sortOrderSnapshot: Int,
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
