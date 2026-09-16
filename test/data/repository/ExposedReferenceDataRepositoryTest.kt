package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Departments and employment types, against real SQL (ERT-350).
 *
 * **The ticket's named test is vacuous for ordering and the tests below say so.** The seed holds
 * exactly one department, so "returns departments in name order" passes against a query with no
 * `ORDER BY` at all — the same class of coincidence ERT-320 found in `sort_order`. The ordering
 * tests here insert rows whose name order differs from their insertion order.
 *
 * The other theme is that the two existence checks must read different tables. They are one
 * copy-paste apart, and both take an [com.pgsystem.employee.requirement.tracker.core.value.EntityId],
 * so nothing but a test distinguishes a working pair from one that answers the same question twice.
 */
class ExposedReferenceDataRepositoryTest : RepositoryTestBase() {

    private val reference by lazy { ExposedReferenceDataRepository(factory) }

    // ── Listing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reference data - the seeded catalogue - returns departments and employment types`() = runTest {
        reference.findDepartments().map { it.name } shouldContainExactly listOf("Unassigned")
        reference.findEmploymentTypes().size shouldBe 4
    }

    @Test
    fun `reference data - several departments - are returned in name order`() = runTest {
        // Inserted in an order that is neither alphabetical nor the order asserted, so insertion
        // order cannot produce a pass.
        addDepartment("d00000000002", "Warehouse")
        addDepartment("d00000000003", "Accounting")
        addDepartment("d00000000004", "Store Operations")

        reference.findDepartments().map { it.name } shouldContainExactly
            listOf("Accounting", "Store Operations", "Unassigned", "Warehouse")
    }

    @Test
    fun `reference data - the seeded employment types - are returned in name order`() = runTest {
        // Pins the decision rather than the seed: `employment_types` has no sort_order column, so
        // alphabetical is the only deterministic order available. It is not the semantic order HR
        // would choose -- Regular reads first on a form -- and that is the missing column speaking.
        // If this ever needs to change, it is 8.11 that adds sort_order, not this adapter.
        reference.findEmploymentTypes().map { it.name } shouldContainExactly
            listOf("Part-time", "Probationary", "Project-based", "Regular")
    }

    @Test
    fun `reference data - an empty departments table - returns an empty list rather than failing`() = runTest {
        // No employee rows exist yet, so nothing references a department and the delete succeeds.
        execute("delete from departments")

        reference.findDepartments().shouldBeEmpty()
    }

    @Test
    fun `reference data - the departments list - carries the stored id`() = runTest {
        reference.findDepartments().single().id shouldBe entityId("d00000000001")
    }

    // ── Existence ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reference data - an employment type id that exists - is reported as existing`() = runTest {
        reference.employmentTypeExists(entityId("e00000000001")) shouldBe true
    }

    @Test
    fun `reference data - an employment type id that does not exist - is reported as absent`() = runTest {
        // The repository half of the 8.1 criterion. Turning this into a NotFound that names the
        // field belongs to CreateHireUseCase (ERT-430); there is no use case to put it in yet.
        reference.employmentTypeExists(entityId("zzzzzzzzzzzz")) shouldBe false
    }

    @Test
    fun `reference data - a department id that exists - is reported as existing`() = runTest {
        reference.departmentExists(entityId("d00000000001")) shouldBe true
    }

    @Test
    fun `reference data - a department id offered as an employment type - is reported as absent`() = runTest {
        // The two checks are one copy-paste apart and both take an EntityId, so a body duplicated
        // from the other compiles and passes every test above. This is the one that separates them:
        // a real department id must not satisfy the employment-type question, or 8.2's "flagged
        // rather than silently created" is enforced by a validator that answers the wrong question.
        reference.employmentTypeExists(entityId("d00000000001")) shouldBe false
        reference.departmentExists(entityId("e00000000001")) shouldBe false
    }

    private suspend fun addDepartment(id: String, name: String) {
        execute("insert into departments (id, \"name\") values ('$id', '$name')")
    }
}
