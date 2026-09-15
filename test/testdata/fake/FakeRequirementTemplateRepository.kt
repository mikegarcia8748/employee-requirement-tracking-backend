package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementTemplate
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository

/**
 * The document-type catalogue, in memory.
 *
 * [assign] stands in for the `template_assignments` table. Keeping assignment separate from the
 * template itself matters: a template belongs to the catalogue, not to one employment type, which is
 * what lets Phase 4 renewal reuse the catalogue without restructuring it (PRD 9.3).
 *
 * [findActiveForEmploymentType] orders by `sortOrder`, because the order a hire sees their checklist
 * in comes from here and nowhere else. An unordered fake would let a use case that forgot to sort
 * pass, and the defect would first appear as a shuffled checklist in front of an employee.
 */
class FakeRequirementTemplateRepository(vararg seed: RequirementTemplate) : RequirementTemplateRepository {

    val failure = FakeFailure()

    private val templates = seed.associateBy { it.id }.toMutableMap()
    private val assignments = mutableMapOf<EntityId, MutableSet<EntityId>>()

    /** Records each read, so the PRD 5 "read once at creation" rule can be asserted. */
    private val reads = mutableListOf<EntityId>()

    /** Every [findActiveForEmploymentType] call, in order. */
    val employmentTypeReads: List<EntityId> get() = reads.toList()

    override suspend fun findById(id: EntityId): RequirementTemplate? {
        failure.check()
        return templates[id]
    }

    override suspend fun findActiveForEmploymentType(employmentTypeId: EntityId): List<RequirementTemplate> {
        failure.check()
        reads += employmentTypeId
        val assigned = assignments[employmentTypeId].orEmpty()
        return templates.values
            .filter { it.id in assigned && it.isActive }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    override suspend fun findAll(includeInactive: Boolean): List<RequirementTemplate> {
        failure.check()
        return templates.values
            .filter { includeInactive || it.isActive }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    fun given(vararg templates: RequirementTemplate): FakeRequirementTemplateRepository = apply {
        templates.forEach { this.templates[it.id] = it }
    }

    /**
     * The `template_assignments` rows for one employment type.
     *
     * A list rather than a vararg because [EntityId] is a value class, which Kotlin will not accept
     * as a vararg parameter type.
     */
    fun assign(employmentTypeId: EntityId, templateIds: List<EntityId>): FakeRequirementTemplateRepository = apply {
        assignments.getOrPut(employmentTypeId) { mutableSetOf() } += templateIds
    }

    /** Seed and assign in one step, for the common case of one employment type. */
    fun givenAssigned(
        employmentTypeId: EntityId,
        vararg templates: RequirementTemplate,
    ): FakeRequirementTemplateRepository = apply {
        given(*templates)
        assign(employmentTypeId, templates.map { it.id })
    }
}
