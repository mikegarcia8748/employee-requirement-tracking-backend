package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.domain.model.RequirementTemplate
import com.pgsystem.employee.requirement.tracker.route.dto.RequirementTemplateDto

/**
 * [RequirementTemplate] → [RequirementTemplateDto] (ERT-340).
 *
 * One direction only: nothing in Phase 1 accepts a template over the wire. The id is rendered as its
 * string value rather than the value class, because `EntityId` has a private constructor and a
 * client sends back whatever it was given — validation on the way in is `EntityId.of`'s job, at the
 * boundary that actually receives one.
 */
fun RequirementTemplate.toDto(): RequirementTemplateDto = RequirementTemplateDto(
    id = id.value,
    name = name,
    instructions = instructions,
    isRequired = isRequired,
    sortOrder = sortOrder,
)
