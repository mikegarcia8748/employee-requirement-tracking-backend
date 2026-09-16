package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.db.table.RequirementTemplates
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementTemplate
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * `requirement_templates` row → [RequirementTemplate] (ERT-320).
 *
 * One direction only. Phase 1 reads the catalogue and never writes it — template CRUD is the Phase 2
 * admin screen (8.11) — and a `toRow` nobody calls would be an invitation to write one from a use
 * case that should be snapshotting instead.
 *
 * [validityMonths] and [renewalLeadDays] are adjacent nullable integers, which is the shape a
 * transposition hides in: swap them and every seeded row but two still reads correctly. The
 * repository test pins the two rows that differ.
 */
fun ResultRow.toRequirementTemplate(): RequirementTemplate = RequirementTemplate(
    id = EntityId.of(this[RequirementTemplates.id].value).orFail("requirement_templates.id"),
    name = this[RequirementTemplates.name],
    instructions = this[RequirementTemplates.instructions],
    isRequired = this[RequirementTemplates.isRequired],
    expires = this[RequirementTemplates.expires],
    validityMonths = this[RequirementTemplates.validityMonths],
    renewalLeadDays = this[RequirementTemplates.renewalLeadDays],
    isActive = this[RequirementTemplates.isActive],
    sortOrder = this[RequirementTemplates.sortOrder],
)

/**
 * A stored id that does not parse is a corrupt row, not a case to branch on.
 *
 * The same shape as `AuditEntryMapper`'s and `AppSettingMapper`'s: the column is `varchar(12)` and
 * the only writer is this application, so an unparseable value means something bypassed it.
 */
private fun <T> DomainResult<T>.orFail(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value that is not a valid identifier: ${error.code}")
}
