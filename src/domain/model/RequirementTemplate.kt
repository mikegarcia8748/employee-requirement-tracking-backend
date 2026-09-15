package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId

/**
 * A document type in the catalogue, defined once and reused (PRD 5, 8.11).
 *
 * Deliberately independent of the onboarding flow: this is a catalogue of document types, not a
 * checklist only onboarding uses, so Phase 4 renewal can reuse it without restructuring (PRD 9.3).
 */
data class RequirementTemplate(
    val id: EntityId,
    val name: String,
    val instructions: String,
    /** Optional requirements are excluded from both numerator and denominator of progress (PRD 6.5). */
    val isRequired: Boolean,
    /** Phase 4: whether documents of this type carry a validity window. */
    val expires: Boolean,
    val validityMonths: Int?,
    val renewalLeadDays: Int?,
    val isActive: Boolean,
    val sortOrder: Int,
)

/** Which templates make up the requirement set for an employment type (PRD 11). */
data class TemplateAssignment(
    val employmentTypeId: EntityId,
    val requirementTemplateId: EntityId,
)

data class Department(val id: EntityId, val name: String)

data class EmploymentType(val id: EntityId, val name: String)
