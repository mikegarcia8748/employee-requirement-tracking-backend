package com.pgsystem.employee.requirement.tracker.data.db

import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.data.TestEngine
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedEmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anEmployeeRequirement
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Where H2 and PostgreSQL order text differently, and what depends on it (ERT-260).
 *
 * The 2026-09-18 review ranked PERF-12's collation claim as **not measured**, saying it *"cannot be
 * exhibited on H2 by definition"*. With a PostgreSQL job it can, and it is larger than the review
 * assumed. The same four strings, ordered three ways:
 *
 * | | `Apple`, `Zebra`, `_Underscore`, `apple` sorted ascending |
 * |---|---|
 * | Kotlin `String.compareTo` (every fake) | `Apple`, `Zebra`, `_Underscore`, `apple` |
 * | H2 in PostgreSQL mode | `Apple`, `Zebra`, `_Underscore`, `apple` |
 * | **PostgreSQL 17, `en_US.UTF-8`** | **`apple`, `Apple`, `_Underscore`, `Zebra`** |
 *
 * Kotlin and H2 compare UTF-16 code units, so every capital sorts before every lower-case letter.
 * PostgreSQL uses the database's collation, which weights letters before case and ignores the
 * underscore's position entirely. **The fakes agree with H2 and disagree with production.**
 *
 * ### Why `employee_requirements.name_snapshot` is the one that matters
 *
 * `ExposedRequirementTemplateRepository` states this caveat and argues it cannot fire, because the
 * catalogue's `sort_order` runs 1..14 with each used once — the name is only ever a tiebreaker for a
 * tie that does not occur. **The same argument was never made for `name_snapshot`**, where
 * `sort_order_snapshot` carries `default(0)` so that V7 could apply to a populated table. ERT-432's
 * own migration header warns that a writer inheriting that default puts every row at one value and
 * collapses the order back to name — and name order is exactly what the two engines disagree about.
 *
 * ### This pins today's behaviour rather than fixing it
 *
 * On the C25/C27 precedent: the trap is visible here instead of being discovered when a hire's
 * checklist reorders itself between a test and production. The fix is a collation decision that
 * belongs in a migration — `collate "C"` on the column, or an ordering key that is not text — and a
 * migration is ERT-1190's territory, not a test harness ticket's. See the ERT-260 block.
 */
class CollationTest : RepositoryTestBase() {

    private val hires by lazy { ExposedEmployeeRepository(factory, FixedPersonIdGenerator()) }

    @BeforeTest
    fun seedAHire() {
        runBlocking {
            execute(
                """
                insert into users (id, email, full_name, password_hash, "role", is_active,
                                   password_change_required, created_at)
                values ('${Fixtures.HR_USER_ID.value}', 'hr@example.com', 'Ana', 'x', 'HR_OFFICER',
                        true, false, current_timestamp)
                """.trimIndent()
            )
            execute(
                """
                insert into employees (id, first_name, last_name, department_id, "position",
                                       employment_type_id, email, packet_status, submitted_by_hr,
                                       anomaly_flags, created_at, created_by)
                values ('EMP00001', 'Jose', 'Dela Cruz', 'd00000000001', 'Store Associate',
                        'e00000000001', 'jose@example.com', 'DRAFT_COLLECTING', false, '',
                        current_timestamp, '${Fixtures.HR_USER_ID.value}')
                """.trimIndent()
            )
        }
    }

    @Test
    fun `requirement ordering - a sort order tie with one case convention - resolves the same on both engines`() =
        runTest {
            // The safe subset, and the one the catalogue actually produces: Appendix A's fourteen
            // template names are all Title Case and differ in their first letter. Every engine and
            // every fake agree here, which is why nothing has ever caught the case below.
            givenTied("Barangay Clearance", "Aadhaar Card", "Certificate of Employment")

            hires.requirementsOf(personId("EMP00001")).requirements.map { it.nameSnapshot } shouldBe
                listOf("Aadhaar Card", "Barangay Clearance", "Certificate of Employment")
        }

    @Test
    fun `requirement ordering - a sort order tie with mixed case - resolves differently on each engine`() =
        runTest {
            // The pin. Not an assertion that the divergence is acceptable — an assertion that it is
            // THERE, so the day someone gives the column a collation this test fails and says so.
            givenTied("Zebra Clearance", "apple Clearance")

            val ordered = hires.requirementsOf(personId("EMP00001")).requirements.map { it.nameSnapshot }

            if (TestEngine.isPostgres) {
                // Letters before case: a checklist on a hire's phone reads apple, then Zebra.
                ordered shouldBe listOf("apple Clearance", "Zebra Clearance")
            } else {
                // UTF-16 code units: every capital precedes every lower-case letter. This is what
                // H2 does, what Kotlin does, and therefore what every fake-backed test asserts.
                ordered shouldBe listOf("Zebra Clearance", "apple Clearance")
            }
        }

    @Test
    fun `requirement ordering - a sort order that does not tie - beats the name on both engines`() =
        runTest {
            // Paired with the two above so the divergence cannot be read as "ordering is arbitrary".
            // `sort_order_snapshot` is decisive wherever it differs, on either engine — which is why
            // the defect is the `default(0)` path rather than the ordering rule itself.
            hires.saveRequirements(
                listOf(
                    requirement("REQ000000001", "apple Clearance", sortOrder = 2),
                    requirement("REQ000000002", "Zebra Clearance", sortOrder = 1),
                )
            )

            hires.requirementsOf(personId("EMP00001")).requirements.map { it.nameSnapshot } shouldBe
                listOf("Zebra Clearance", "apple Clearance")
        }

    private suspend fun givenTied(vararg names: String) {
        hires.saveRequirements(
            names.mapIndexed { index, name ->
                requirement("REQ00000000${index + 1}", name, sortOrder = 0)
            }
        )
    }

    private fun requirement(id: String, name: String, sortOrder: Int) = anEmployeeRequirement(
        id = entityId(id),
        employeeId = personId("EMP00001"),
        templateId = entityId("c00000000001"),
        nameSnapshot = name,
        sortOrderSnapshot = sortOrder,
    )
}
