package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.MigratedDatabase
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.model.Department
import com.pgsystem.employee.requirement.tracker.domain.model.EmploymentType
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeReferenceDataRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * One reference set, two implementations, one set of rules (ERT-250).
 *
 * **The adapter side clears the V2 seed first, and that is the point rather than an inconvenience.**
 * ERT-350 recorded that `findDepartments` with **no `ORDER BY` at all** failed only the test that
 * inserted three more departments, because the seed holds exactly one — so "in name order" was
 * vacuous against seeded data. A contract suite that let the two sides start from different states
 * would prove less than either adapter test does alone.
 *
 * **The two existence checks read different tables, and only a test says so.** Both take an
 * [com.pgsystem.employee.requirement.tracker.core.value.EntityId] and their bodies differ by one
 * identifier, so a body copied from the other compiles and answers every listing test correctly
 * while accepting a department id where an employment type was required — §8.2's exact defect,
 * reached through the validator meant to prevent it. The cross case is asserted in both directions.
 */
abstract class ReferenceDataRepositoryContract {

    protected abstract val reference: ReferenceDataRepository

    protected abstract suspend fun given(
        departments: List<Department> = emptyList(),
        employmentTypes: List<EmploymentType> = emptyList(),
    )

    // ── Listing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reference contract - several departments - both implementations list them in name order`() =
        runTest {
            // Inserted in an order that is neither the asserted one nor its reverse, with ids that
            // ascend in a third order.
            given(
                departments = listOf(
                    Department(entityId("d00000000002"), "Merchandising"),
                    Department(entityId("d00000000001"), "Warehouse"),
                    Department(entityId("d00000000003"), "Accounting"),
                )
            )

            reference.findDepartments().map { it.name } shouldBe
                listOf("Accounting", "Merchandising", "Warehouse")
        }

    @Test
    fun `reference contract - several employment types - both implementations list them in name order`() =
        runTest {
            // `employment_types` carries no `sort_order` column, so name order is the only
            // deterministic choice that is not insertion order wearing a disguise (ERT-350).
            given(
                employmentTypes = listOf(
                    EmploymentType(entityId("e00000000002"), "Probationary"),
                    EmploymentType(entityId("e00000000001"), "Regular"),
                    EmploymentType(entityId("e00000000003"), "Contractual"),
                )
            )

            reference.findEmploymentTypes().map { it.name } shouldBe
                listOf("Contractual", "Probationary", "Regular")
        }

    @Test
    fun `reference contract - nothing seeded - both implementations list nothing`() = runTest {
        reference.findDepartments().shouldBeEmpty()
        reference.findEmploymentTypes().shouldBeEmpty()
    }

    // ── Existence ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reference contract - a stored department id - both implementations confirm it exists`() =
        runTest {
            given(departments = listOf(Department(entityId("d00000000001"), "Warehouse")))

            reference.departmentExists(entityId("d00000000001")) shouldBe true
            reference.departmentExists(entityId("d00000000099")) shouldBe false
        }

    @Test
    fun `reference contract - a department id in the employment type slot - both implementations refuse it`() =
        runTest {
            // The cross question, and the reason the port carries two checks rather than one
            // `exists(id)`: both ids are 12-character EntityIds and structurally indistinguishable,
            // so a single scan over both tables would answer true for either in either slot.
            given(
                departments = listOf(Department(entityId("d00000000001"), "Warehouse")),
                employmentTypes = listOf(EmploymentType(entityId("e00000000001"), "Regular")),
            )

            reference.employmentTypeExists(entityId("d00000000001")) shouldBe false
            reference.departmentExists(entityId("e00000000001")) shouldBe false

            // Paired, so a check that answered false to everything cannot satisfy this.
            reference.departmentExists(entityId("d00000000001")) shouldBe true
            reference.employmentTypeExists(entityId("e00000000001")) shouldBe true
        }
}

class FakeReferenceDataRepositoryContractTest : ReferenceDataRepositoryContract() {

    private val fake = FakeReferenceDataRepository()

    override val reference: ReferenceDataRepository = fake

    override suspend fun given(departments: List<Department>, employmentTypes: List<EmploymentType>) {
        fake.givenDepartments(*departments.toTypedArray())
        fake.givenEmploymentTypes(*employmentTypes.toTypedArray())
    }
}

class ExposedReferenceDataRepositoryContractTest : ReferenceDataRepositoryContract() {

    private val database = MigratedDatabase()

    override val reference: ReferenceDataRepository by lazy {
        ExposedReferenceDataRepository(database.factory)
    }

    override suspend fun given(departments: List<Department>, employmentTypes: List<EmploymentType>) {
        departments.forEach {
            execute("""insert into departments (id, "name") values ('${it.id.value}', '${it.name}')""")
        }
        employmentTypes.forEach {
            execute("""insert into employment_types (id, "name") values ('${it.id.value}', '${it.name}')""")
        }
    }

    @BeforeTest
    fun openDatabaseAndClearTheSeed() {
        database.open()
        runBlocking {
            // The V2 seed holds one department and four employment types. Left in place, the
            // ordering tests would assert over rows this contract never arranged — and ERT-350
            // already found "in name order" vacuous against exactly one seeded row.
            execute("delete from template_assignments")
            execute("delete from departments")
            execute("delete from employment_types")
        }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
    }

    private suspend fun execute(sql: String) {
        database.factory.transaction { exec(sql) }
    }
}
