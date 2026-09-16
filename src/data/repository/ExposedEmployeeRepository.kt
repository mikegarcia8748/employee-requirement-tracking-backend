package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
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
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Hires and their snapshotted requirement sets, against `employees` and `employee_requirements`
 * (ERT-410).
 *
 * **[create] and [save] are separate operations, and neither can do the other's job.** With a single
 * upsert the adapter could not tell a brand-new hire whose id collided from an edit of the hire
 * already at that id, and would overwrite a stranger's record rather than redraw. See
 * [EmployeeRepository.create] for why the redraw belongs here and not in the generator.
 *
 * **Neither path uses Exposed's `upsert`.** It compiles to `on conflict`, which
 * `MigrationTest.migration portability` bans outright and which H2 and PostgreSQL spell differently.
 * Read-then-write inside one transaction is the pattern `ExposedHrUserRepository` already set, and
 * both branches share that transaction so the check and the write do not race each other.
 *
 * **[findActiveByEmail] derives its scope from [PacketStatus.isTerminal] rather than listing
 * statuses.** The rule already lives on the type and `FakeEmployeeRepository` already reads it there;
 * restating it here would give it two definitions that could drift. Deriving it also decides the
 * open question in the right direction — a status added later counts as *active* until someone marks
 * it terminal, so a new state errs toward warning HR about a duplicate rather than staying silent.
 */
class ExposedEmployeeRepository(
    private val factory: DatabaseFactory,
    private val ids: PersonIdGenerator,
) : EmployeeRepository {

    override suspend fun findById(id: PersonId): Employee? = factory.transaction {
        Employees.selectAll().where { Employees.id eq id.value }.singleOrNull()?.toEmployee()
    }

    override suspend fun findActiveByEmail(email: EmailAddress): List<Employee> = factory.transaction {
        Employees.selectAll()
            .where { (Employees.email eq email.value) and (Employees.packetStatus inList ACTIVE_STATUSES) }
            .orderBy(Employees.createdAt to SortOrder.ASC, Employees.id to SortOrder.ASC)
            .map { it.toEmployee() }
    }

    /**
     * Insert, drawing a fresh id for as long as the candidate is taken.
     *
     * The collision is detected by *reading*, not by catching a driver exception: the constraint
     * violation H2 raises and the one PostgreSQL raises are different types with different messages,
     * and a repository that branched on either would be pinned to whichever database the tests use.
     * Reading inside the transaction that then inserts is the same shape `ExposedHrUserRepository`
     * uses, and it leaves the genuinely concurrent case — two instances drawing the same id between
     * this read and this insert — to the primary key, which is the correct outcome and is loud.
     *
     * The redraw is bounded. An unbounded loop against a saturated keyspace is a hung request rather
     * than an error, and at 62^8 the only realistic way to reach the bound is a broken generator
     * handing back a constant, which must be a visible failure and not a spin.
     */
    override suspend fun create(employee: Employee): Employee = factory.transaction {
        val stored = generateSequence(employee) { employee.copy(id = ids.newPersonId()) }
            .take(ID_ATTEMPTS)
            .firstOrNull { candidate -> !exists(candidate.id) }
            ?: error(
                "Could not place a new hire after $ID_ATTEMPTS identifier draws. Every candidate was " +
                    "already taken, which at 62^8 means the identifier generator is not drawing freely."
            )

        Employees.insert {
            it[id] = stored.id.value
            it.writeTo(stored)
        }

        stored
    }

    /**
     * Update in place. Never inserts, and never moves a hire to a different id.
     *
     * A missing row is a programming error rather than a new hire — [create] is how a hire begins —
     * so this fails loudly instead of quietly creating one whose `createdAt` and `createdBy` would be
     * whatever the caller happened to be holding.
     */
    override suspend fun save(employee: Employee): Employee = factory.transaction {
        val updated = Employees.update({ Employees.id eq employee.id.value }) { it.writeTo(employee) }

        check(updated == 1) {
            "Updating employee ${employee.id.value} matched $updated rows, expected exactly 1. " +
                "A hire that does not exist yet is created through create(), not save()."
        }

        employee
    }

    /**
     * The requirement set, ordered for determinism only.
     *
     * The PRD 6.5 arithmetic lives on [RequirementSet] and is deliberately not recomputed here — a
     * second implementation of a rule is a second thing to keep right. The display order HR and the
     * employee actually see comes from the template's `sort_order` and is ERT-510's concern.
     */
    override suspend fun requirementsOf(employeeId: PersonId): RequirementSet = factory.transaction {
        RequirementSet(
            EmployeeRequirements.selectAll()
                .where { EmployeeRequirements.employeeId eq employeeId.value }
                .orderBy(EmployeeRequirements.id to SortOrder.ASC)
                .map { it.toEmployeeRequirement() }
        )
    }

    /**
     * Insert or update each requirement, in one transaction.
     *
     * No redraw here: these are [EntityId]s drawing from 62^12, where a collision is negligible and
     * the primary key is an adequate backstop on its own.
     */
    override suspend fun saveRequirements(requirements: List<EmployeeRequirement>) {
        factory.transaction {
            requirements.forEach { requirement ->
                val updated = EmployeeRequirements
                    .update({ EmployeeRequirements.id eq requirement.id.value }) { it.writeTo(requirement) }

                if (updated == 0) {
                    EmployeeRequirements.insert {
                        it[id] = requirement.id.value
                        it.writeTo(requirement)
                    }
                }
            }
        }
    }

    private fun JdbcTransaction.exists(id: PersonId): Boolean =
        Employees.selectAll().where { Employees.id eq id.value }.empty().not()

    private companion object {
        /** One original plus four redraws. See [create] for why this is bounded at all. */
        const val ID_ATTEMPTS = 5

        /** Derived from the domain rule, never restated. See the class note on [findActiveByEmail]. */
        val ACTIVE_STATUSES: List<String> =
            PacketStatus.entries.filterNot { it.isTerminal }.map { it.name }
    }
}
