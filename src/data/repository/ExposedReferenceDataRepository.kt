package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.Departments
import com.pgsystem.employee.requirement.tracker.data.db.table.EmploymentTypes
import com.pgsystem.employee.requirement.tracker.domain.model.Department
import com.pgsystem.employee.requirement.tracker.domain.model.EmploymentType
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Departments and employment types, read from their seeded tables (ERT-350, PRD 8.1, 8.2, 11).
 *
 * **The two existence checks read different tables, and nothing but a test says so.** Both take an
 * [EntityId] and their bodies differ by one identifier, so a body copied from the other compiles,
 * answers every listing test correctly, and quietly accepts a department id where an employment type
 * was required. `ExposedReferenceDataRepositoryTest` asserts the cross case in both directions.
 *
 * Existence is `.empty().not()` rather than a `count(*)`: the question is whether a row exists, and
 * the engine can stop at the first one.
 *
 * Returns raw domain types. An absent id is `false` and an empty table is an empty list — neither is
 * "this row cannot be read", which is the narrow case `AppSettingsRepository` returns `DomainResult`
 * for.
 */
class ExposedReferenceDataRepository(private val factory: DatabaseFactory) : ReferenceDataRepository {

    override suspend fun findDepartments(): List<Department> = factory.transaction {
        Departments.selectAll()
            .orderBy(Departments.name to SortOrder.ASC)
            .map {
                Department(
                    id = EntityId.of(it[Departments.id].value).orFail("departments.id"),
                    name = it[Departments.name],
                )
            }
    }

    override suspend fun findEmploymentTypes(): List<EmploymentType> = factory.transaction {
        EmploymentTypes.selectAll()
            .orderBy(EmploymentTypes.name to SortOrder.ASC)
            .map {
                EmploymentType(
                    id = EntityId.of(it[EmploymentTypes.id].value).orFail("employment_types.id"),
                    name = it[EmploymentTypes.name],
                )
            }
    }

    override suspend fun departmentExists(id: EntityId): Boolean = factory.transaction {
        Departments.selectAll().where { Departments.id eq id.value }.empty().not()
    }

    override suspend fun employmentTypeExists(id: EntityId): Boolean = factory.transaction {
        EmploymentTypes.selectAll().where { EmploymentTypes.id eq id.value }.empty().not()
    }
}

/**
 * A stored id that does not parse is a corrupt row, not a case to branch on.
 *
 * Mapped inline rather than through `data/mapper/`, because a two-column row has no mapping decision
 * in it. When ERT-410 joins these tables for the 8.3 list view, that is when a `ReferenceMapper`
 * earns a file.
 */
private fun <T> DomainResult<T>.orFail(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value that is not a valid identifier: ${error.code}")
}
