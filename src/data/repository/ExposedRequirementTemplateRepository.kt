package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.RequirementTemplates
import com.pgsystem.employee.requirement.tracker.data.db.table.TemplateAssignments
import com.pgsystem.employee.requirement.tracker.data.mapper.toRequirementTemplate
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementTemplate
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The requirement catalogue, read from `requirement_templates` (ERT-320, PRD 5, 8.11).
 *
 * **There is no path from an employee to this catalogue, and that absence is the feature.** The
 * requirement set is read once at hire creation and copied onto the employee (ERT-432); nothing
 * downstream may consult it again for an in-flight hire. That is what stops an HR template edit
 * from moving someone's progress under them, or making a completed hire retroactively incomplete.
 * `ExposedRequirementTemplateRepositoryTest` reads this file as text and fails the build on
 * `Employees`, `EmployeeRequirements` or `PersonId` appearing in it — a convenience method taking
 * an employee id is exactly the well-meant addition that would reopen 8.11, and it would look
 * reasonable in review.
 *
 * Returns raw domain types rather than `DomainResult`: an employment type with no assignments is a
 * legitimate state, not a read failure. `AppSettingsRepository` is the only port here that needs the
 * other treatment, for the reason its own KDoc gives.
 */
class ExposedRequirementTemplateRepository(private val factory: DatabaseFactory) :
    RequirementTemplateRepository {

    override suspend fun findById(id: EntityId): RequirementTemplate? = factory.transaction {
        RequirementTemplates.selectAll()
            .where { RequirementTemplates.id eq id.value }
            .singleOrNull()
            ?.toRequirementTemplate()
    }

    /**
     * Does **not** filter by `is_active` — only [findActiveForEmploymentType] does.
     *
     * The Phase 2 admin screen has to be able to open a template it has just deactivated, and a
     * `findById` that hid one would make that impossible for the one caller that needs it.
     */
    override suspend fun findActiveForEmploymentType(
        employmentTypeId: EntityId,
    ): List<RequirementTemplate> = factory.transaction {
        TemplateAssignments
            .innerJoin(
                RequirementTemplates,
                onColumn = { TemplateAssignments.requirementTemplateId },
                otherColumn = { RequirementTemplates.id },
            )
            .selectAll()
            .where {
                (TemplateAssignments.employmentTypeId eq employmentTypeId.value) and
                    (RequirementTemplates.isActive eq true)
            }
            .inCatalogueOrder()
            .map { it.toRequirementTemplate() }
    }

    /**
     * No `= false` default here: Kotlin forbids one in an override, so the default lives on the port
     * and applies only to calls made through the interface. Koin hands out the interface, so every
     * production caller gets it.
     */
    override suspend fun findAll(includeInactive: Boolean): List<RequirementTemplate> =
        factory.transaction {
            RequirementTemplates.selectAll()
                .where { if (includeInactive) Op.TRUE else RequirementTemplates.isActive eq true }
                .inCatalogueOrder()
                .map { it.toRequirementTemplate() }
        }
}

/**
 * `sort_order`, then name.
 *
 * The order is an HR decision, not a consequence of insertion — this is the sequence a new hire
 * reads the checklist in on a phone (PRD 5). The name tiebreaker is not decoration: `sort_order`
 * carries a database default of 0, so a Phase 2 admin insert that omits it produces ties, and ties
 * resolved by whatever the engine returns are a checklist that reorders itself between requests.
 *
 * **Known and accepted:** a name tiebreaker is collation-sensitive, and H2 in PostgreSQL mode is not
 * PostgreSQL. It can only fire on a `sort_order` tie, which the seeded catalogue never produces —
 * sort_order runs 1..14, each used once.
 */
private fun Query.inCatalogueOrder(): Query = orderBy(
    RequirementTemplates.sortOrder to SortOrder.ASC,
    RequirementTemplates.name to SortOrder.ASC,
)
