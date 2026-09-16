package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.EmployeeRequirements
import com.pgsystem.employee.requirement.tracker.data.db.table.Employees
import com.pgsystem.employee.requirement.tracker.data.mapper.toEmployee
import com.pgsystem.employee.requirement.tracker.data.mapper.toEmployeeRequirement
import com.pgsystem.employee.requirement.tracker.data.mapper.writeTo
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementSet
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Hires and their snapshotted requirement sets, against `employees` and `employee_requirements`
 * (ERT-410, PRD 8.1, 11, 7.1).
 *
 * **[create] and [save] are separate methods, and this is the only adapter where they are.** The
 * port says why at length; the consequence here is that neither is `save`'s usual
 * read-then-insert-or-update. [create] inserts and never updates, [save] updates and never inserts.
 *
 * **[create] redraws a taken [PersonId] rather than surfacing the conflict.** An identifier draws
 * from 62^8, so a duplicate is unlikely but real at the volume PRD 8.2's CSV import reaches, and the
 * import reports created, skipped and failed counts — a collision reported to HR as a failed row
 * would be an identifier problem wearing a data problem's clothes. `SecurePersonIdGenerator` cannot
 * do this itself: a value object cannot know what the database already holds.
 *
 * The check-then-insert is **not** atomic against another transaction under `READ_COMMITTED`, and
 * that is accepted rather than overlooked. A concurrent writer taking the id between the two
 * statements loses to the primary key, and Exposed responds by re-running the whole block — which
 * draws a fresh id, because the generator call is not transactional. That behaviour is recorded in
 * the roadmap as a hazard for tests, and it is a second line of defence here. `forUpdate()` would
 * not help: there is no row to lock.
 *
 * **[requirementsOf] orders by `name_snapshot`, not by the catalogue's `sort_order`.**
 * `employee_requirements` carries no sort-order snapshot, and joining `requirement_templates` to get
 * one would be a live read of the catalogue for a hire already in flight — reordering a template
 * would reorder someone's checklist mid-onboarding, which is the shape PRD 5 forbids. Name order is
 * deterministic and comes entirely from the snapshot. A `sort_order_snapshot` column is the real
 * fix and belongs with ERT-432, which is what writes these rows.
 *
 * **[saveRequirements] *is* read-then-insert-or-update**, because an [com.pgsystem.employee.requirement.tracker.core.value.EntityId]
 * draws from 62^12 where a collision is negligible, and because ERT-432 writes the snapshot and
 * ERT-730/ERT-910 mutate its statuses through the same call. Exposed's `upsert` is not used, for the
 * reason `ExposedHrUserRepository` gives: it compiles to `on conflict`, which
 * `MigrationTest.migration portability` bans outright and which H2 and PostgreSQL spell differently.
 */
class ExposedEmployeeRepository(
    private val factory: DatabaseFactory,
    private val ids: PersonIdGenerator,
) : EmployeeRepository {

    override suspend fun findById(id: PersonId): Employee? = factory.transaction {
        Employees.selectAll().where { Employees.id eq id.value }.singleOrNull()?.toEmployee()
    }

    /**
     * The PRD 8.1 duplicate check.
     *
     * **Active is read off [PacketStatus.isTerminal] rather than by listing statuses here.** The
     * rule already lives on the type and `FakeEmployeeRepository` reads it from the same place;
     * restating it in SQL would give one rule two definitions that could drift, and the drift would
     * show up as a duplicate-email warning that stopped firing.
     *
     * Compares `lower(email)`, mirroring `ExposedHrUserRepository.findByEmail` — but note the
     * difference: `users` carries a `email = lower(email)` check constraint that makes the
     * comparison belt-and-braces, and **`employees` carries no such constraint**. Here the predicate
     * is the only thing that catches an address a migration or an import wrote in mixed case, and
     * missing one is how two hires quietly share a mailbox (SEC-11). The cost is that the plain
     * `employees_email` index cannot serve a `lower()` predicate, so this is a scan; at the volume
     * PRD 14 Q8 expects that is not worth designing around, and the note is here so the trade is
     * visible if it ever is.
     */
    override suspend fun findActiveByEmail(email: EmailAddress): List<Employee> = factory.transaction {
        Employees.selectAll()
            .where {
                (Employees.email.lowerCase() eq email.value) and
                    (Employees.packetStatus inList ACTIVE_STATUSES)
            }
            .orderBy(Employees.createdAt to SortOrder.ASC, Employees.id to SortOrder.ASC)
            .map { it.toEmployee() }
    }

    override suspend fun create(employee: Employee): Employee = factory.transaction {
        val stored = withFreeIdentity(employee)

        // The id comes from the hire. `Employees` is a PersonIdTable, which installs no
        // autoGenerate() default, so an insert that forgot its id fails rather than silently taking
        // one nobody chose.
        Employees.insert {
            it[id] = stored.id.value
            it.writeTo(stored)
        }

        stored
    }

    override suspend fun save(employee: Employee): Employee = factory.transaction {
        val rows = Employees.update({ Employees.id eq employee.id.value }) { it.writeTo(employee) }

        check(rows == 1) {
            "Updating employee '${employee.id.value}' matched $rows rows, expected exactly 1. " +
                "A hire that has never been stored is written with create(), not save()."
        }

        employee
    }

    override suspend fun requirementsOf(employeeId: PersonId): RequirementSet = factory.transaction {
        RequirementSet(
            EmployeeRequirements.selectAll()
                .where { EmployeeRequirements.employeeId eq employeeId.value }
                .orderBy(
                    EmployeeRequirements.nameSnapshot to SortOrder.ASC,
                    EmployeeRequirements.id to SortOrder.ASC,
                )
                .map { it.toEmployeeRequirement() }
        )
    }

    override suspend fun saveRequirements(requirements: List<EmployeeRequirement>) {
        factory.transaction {
            requirements.forEach { requirement ->
                val exists = EmployeeRequirements.selectAll()
                    .where { EmployeeRequirements.id eq requirement.id.value }
                    .empty()
                    .not()

                if (exists) {
                    EmployeeRequirements.update({ EmployeeRequirements.id eq requirement.id.value }) {
                        it.writeTo(requirement)
                    }
                } else {
                    EmployeeRequirements.insert {
                        it[id] = requirement.id.value
                        it.writeTo(requirement)
                    }
                }
            }
        }
    }

    /**
     * [employee] with an id no row holds, redrawing until one is free.
     *
     * Bounded rather than a `while (taken)`: a generator that has stopped varying — a fake scripted
     * with one value, a `SecureRandom` that failed to seed — would otherwise spin forever holding a
     * transaction open, which is the worst of the available failures. Giving up loudly names the
     * cause instead.
     */
    private fun JdbcTransaction.withFreeIdentity(employee: Employee): Employee {
        var candidate = employee

        repeat(MAX_ID_ATTEMPTS) {
            val taken = Employees.selectAll().where { Employees.id eq candidate.id.value }.empty().not()
            if (!taken) return candidate
            candidate = candidate.copy(id = ids.newPersonId())
        }

        error(
            "Could not find a free employee id in $MAX_ID_ATTEMPTS attempts. At 62^8 this is not " +
                "chance: the PersonIdGenerator is returning values that are already taken."
        )
    }

    private companion object {
        /**
         * How many identifiers to try before giving up.
         *
         * Five is generous against the real risk. With even a million hires stored, one draw
         * collides with probability under 1 in 200,000, so five consecutive collisions is not a
         * number this reaches by luck — it is a broken generator, and the message says so.
         */
        const val MAX_ID_ATTEMPTS = 5

        /** PRD 8.1's "active", read off the type rather than restated. See [findActiveByEmail]. */
        val ACTIVE_STATUSES: List<String> =
            PacketStatus.entries.filterNot { it.isTerminal }.map { it.name }
    }
}
