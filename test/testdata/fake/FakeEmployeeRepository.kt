package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementSet
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator

/**
 * Hires and their requirement sets, in memory.
 *
 * Seeding and writing are separate on purpose. The constructor and [given] arrange state without
 * recording anything; [create] records into [created] and [save] into [saved], so "the use case
 * saved the hire twice" and "the use case never saved it at all" are both assertable. A fake that
 * recorded its own setup would make the first of those impossible to state.
 *
 * **[create] inserts and [save] updates, and neither does the other** — the split the port
 * describes. Held here too rather than collapsing both to a map write, because a fake that accepted
 * a creation through [save] would let a use case call the wrong method and still pass: the whole
 * point of the split is that a new hire cannot silently overwrite an existing one.
 *
 * [ids] supplies the redraw when [create] meets a taken identifier. It defaults to an unscripted
 * [FixedPersonIdGenerator], so a test that does not care never mentions it; a test about the retry
 * scripts one — `FixedPersonIdGenerator("EMP00001", "EMP00002")`.
 */
class FakeEmployeeRepository(
    vararg seed: Employee,
    private val owners: RequirementOwners = RequirementOwners(),
    private val ids: PersonIdGenerator = FixedPersonIdGenerator(),
) : EmployeeRepository {

    val failure = FakeFailure()

    private val employees = seed.associateBy { it.id }.toMutableMap()
    private val requirements = mutableMapOf<EntityId, EmployeeRequirement>()

    private val createdEmployees = mutableListOf<Employee>()
    private val savedEmployees = mutableListOf<Employee>()
    private val savedRequirements = mutableListOf<List<EmployeeRequirement>>()

    /** Every [create] call, in order, each as stored. Seeded employees do not appear here. */
    val created: List<Employee> get() = createdEmployees.toList()

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
     *
     * **Ordered by creation, then id — the order `ExposedEmployeeRepository` promises (ERT-250).**
     * This returned map insertion order until the contract suite compared the two (HAR-01 a). The
     * adapter's `ORDER BY` was added in ERT-410's review step because SEC-11's duplicate list must
     * not reshuffle between two reads of the same page; a fake without it would let ERT-450 render
     * that list from an order no database guarantees. The id tiebreak is not decoration: every
     * builder creates at `FixedClock.DEFAULT`, so two hires sharing an instant is this harness's
     * default case.
     */
    override suspend fun findActiveByEmail(email: EmailAddress): List<Employee> {
        failure.check()
        return employees.values
            .filter { it.email == email && !it.packetStatus.isTerminal }
            .sortedWith(compareBy({ it.createdAt }, { it.id.value }))
    }

    /**
     * Insert, redrawing a taken identifier from [ids].
     *
     * Bounded for the reason the adapter is: a generator scripted with one value would otherwise
     * spin forever, and a test that hangs says less than one that fails.
     */
    override suspend fun create(employee: Employee): Employee {
        failure.check()

        var candidate = employee
        repeat(MAX_ID_ATTEMPTS) {
            if (!employees.containsKey(candidate.id)) {
                employees[candidate.id] = candidate
                createdEmployees += candidate
                return candidate
            }
            candidate = candidate.copy(id = ids.newPersonId())
        }

        error("Could not find a free employee id in $MAX_ID_ATTEMPTS attempts.")
    }

    override suspend fun save(employee: Employee): Employee {
        failure.check()

        check(employees.containsKey(employee.id)) {
            "No hire '${employee.id.value}' to update. A hire that has never been stored is " +
                "written with create(), not save()."
        }

        employees[employee.id] = employee
        savedEmployees += employee
        return employee
    }

    /**
     * The snapshotted set, in the order the adapter returns it (ERT-432).
     *
     * Sorted here rather than returned in map order, because an unsorted fake would let a use case
     * that wrote the wrong `sortOrderSnapshot` — or none at all — pass every use-case test, and the
     * defect would first appear as a shuffled checklist on a hire's phone. The three keys match
     * `ExposedEmployeeRepository.requirementsOf` exactly: a fake that agrees with the port's
     * signature and disagrees with the adapter is the failure fakes exist to prevent.
     */
    override suspend fun requirementsOf(employeeId: PersonId): RequirementSet {
        failure.check()
        return RequirementSet(
            requirements.values
                .filter { it.employeeId == employeeId }
                .sortedWith(
                    compareBy({ it.sortOrderSnapshot }, { it.nameSnapshot }, { it.id.value }),
                )
        )
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

    private companion object {
        /** Matches `ExposedEmployeeRepository`, so the two fail at the same point. */
        const val MAX_ID_ATTEMPTS = 5
    }
}
