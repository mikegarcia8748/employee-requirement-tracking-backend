package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementSet
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository

/**
 * Hires and their requirement sets, in memory.
 *
 * Seeding and saving are separate on purpose. The constructor and [given] arrange state without
 * recording anything; [save] records into [saved], so "the use case saved the hire twice" and "the
 * use case never saved it at all" are both assertable. A fake that recorded its own setup would make
 * the first of those impossible to state.
 */
class FakeEmployeeRepository(
    vararg seed: Employee,
    private val owners: RequirementOwners = RequirementOwners(),
) : EmployeeRepository {

    val failure = FakeFailure()

    private val employees = seed.associateBy { it.id }.toMutableMap()
    private val requirements = mutableMapOf<EntityId, EmployeeRequirement>()

    private val savedEmployees = mutableListOf<Employee>()
    private val savedRequirements = mutableListOf<List<EmployeeRequirement>>()

    /** Every [save] call, in order. Seeded employees do not appear here. */
    val saved: List<Employee> get() = savedEmployees.toList()

    /** Every [saveRequirements] call, in order — one entry per call, not per requirement. */
    val savedRequirementBatches: List<List<EmployeeRequirement>> get() = savedRequirements.toList()

    /** Everything currently held, seeded or saved. */
    val all: List<Employee> get() = employees.values.toList()

    override suspend fun findById(id: PersonId): Employee? {
        failure.check()
        return employees[id]
    }

    /**
     * The PRD 8.1 duplicate check.
     *
     * Scoped through [com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus.isTerminal]
     * rather than by listing statuses here: a completed or cancelled hire sharing an address is not
     * a collision worth warning about, and that rule already lives on the type. Restating it would
     * give it two definitions that could drift.
     */
    override suspend fun findActiveByEmail(email: EmailAddress): List<Employee> {
        failure.check()
        return employees.values.filter { it.email == email && !it.packetStatus.isTerminal }
    }

    override suspend fun save(employee: Employee): Employee {
        failure.check()
        employees[employee.id] = employee
        savedEmployees += employee
        return employee
    }

    override suspend fun requirementsOf(employeeId: PersonId): RequirementSet {
        failure.check()
        return RequirementSet(requirements.values.filter { it.employeeId == employeeId })
    }

    override suspend fun saveRequirements(requirements: List<EmployeeRequirement>) {
        failure.check()
        requirements.forEach { this.requirements[it.id] = it }
        owners.register(requirements)
        savedRequirements += requirements
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** Seed without recording a save. */
    fun given(vararg employees: Employee): FakeEmployeeRepository = apply {
        employees.forEach { this.employees[it.id] = it }
    }

    /** Seed requirements without recording a save. Registers ownership, as a real insert would. */
    fun givenRequirements(vararg requirements: EmployeeRequirement): FakeEmployeeRepository = apply {
        requirements.forEach { this.requirements[it.id] = it }
        owners.register(requirements.asIterable())
    }

    fun givenRequirements(set: RequirementSet): FakeEmployeeRepository =
        givenRequirements(*set.requirements.toTypedArray())
}
