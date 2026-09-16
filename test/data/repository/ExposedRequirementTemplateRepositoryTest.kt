package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.testdata.codeOf
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The requirement catalogue, against real SQL (ERT-320).
 *
 * **The seeded data hides two defects, and most of the tests below exist because of it.**
 * `template_assignments` is seeded as a cross join — all four employment types see all fourteen
 * templates — so a query that forgot the `employment_type_id` predicate *entirely* returns the
 * right answer for every seeded type. And `sort_order` 1..14 was assigned in id order, so a query
 * with no `ORDER BY` at all comes back correctly ordered on H2. Each of those has a test that
 * deliberately breaks the coincidence.
 *
 * The other theme is PRD 8.11: a template edit must not move an in-flight hire. That is an
 * *absence* — the adapter must offer no path from an existing employee to the live catalogue — so
 * it is asserted by reading the source, the way `ExposedAuditLogTest` asserts append-only.
 */
class ExposedRequirementTemplateRepositoryTest : RepositoryTestBase() {

    private val catalogue by lazy { ExposedRequirementTemplateRepository(factory) }

    /** The port type, for the one test that must call `findAll()` with its declared default. */
    private val port: RequirementTemplateRepository by lazy { catalogue }

    // ── The requirement set for an employment type ──────────────────────────────────────────────

    @Test
    fun `template catalogue - an employment type - returns only active templates in sort order`() = runTest {
        deactivate(FIFTH_TEMPLATE)

        val templates = catalogue.findActiveForEmploymentType(REGULAR)

        templates.map { it.sortOrder } shouldContainExactly listOf(1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14)
    }

    @Test
    fun `template catalogue - an unassigned employment type - returns an empty list`() = runTest {
        // Not an error: whether an employment type with no requirements is a problem is the
        // caller's decision, and CreateHireUseCase is where it gets made.
        execute("insert into employment_types (id, \"name\") values ('e00000000009', 'Consultant')")

        catalogue.findActiveForEmploymentType(entityId("e00000000009")).shouldBeEmpty()
    }

    @Test
    fun `template catalogue - templates assigned to another employment type only - are not returned`() = runTest {
        // The seed is a cross join, so every employment type sees every template and a query that
        // dropped the employment_type_id predicate would still pass the two tests above. Break the
        // symmetry: Probationary loses three assignments, Regular keeps all fourteen.
        execute(
            """
            delete from template_assignments
            where employment_type_id = 'e00000000002'
              and requirement_template_id in ('c00000000001', 'c00000000002', 'c00000000003')
            """.trimIndent()
        )

        catalogue.findActiveForEmploymentType(REGULAR).size shouldBe 14
        catalogue.findActiveForEmploymentType(PROBATIONARY).size shouldBe 11
    }

    @Test
    fun `template catalogue - sort order deliberately reversed - is read from sort_order not insertion order`() = runTest {
        // Seeded sort_order runs 1..14 in the same order as the ids, so an unordered query returns
        // the right sequence by accident. Move the first template last and nothing but a real
        // ORDER BY will follow it.
        execute("update requirement_templates set sort_order = 99 where id = 'c00000000001'")

        val templates = catalogue.findActiveForEmploymentType(REGULAR)

        templates.last().id shouldBe entityId("c00000000001")
        templates.first().sortOrder shouldBe 2
    }

    @Test
    fun `template catalogue - a deactivated template - is excluded while its assignment row survives`() = runTest {
        deactivate(FIFTH_TEMPLATE)

        catalogue.findActiveForEmploymentType(REGULAR).map { it.id } shouldNotContain FIFTH_TEMPLATE

        // Filtering happens at read time. Deleting the assignment instead would lose the HR
        // decision that this template belongs to this employment type.
        countOf("template_assignments") shouldBe 56
    }

    // ── The whole catalogue ─────────────────────────────────────────────────────────────────────

    @Test
    fun `template catalogue - findAll including inactive - returns inactive templates too`() = runTest {
        deactivate(FIFTH_TEMPLATE)

        catalogue.findAll(includeInactive = true).size shouldBe 14
        catalogue.findAll(includeInactive = false).size shouldBe 13
    }

    @Test
    fun `template catalogue - findAll called through the port with no argument - excludes inactive`() = runTest {
        // An override cannot restate a default, so `includeInactive = false` lives on the interface
        // and only applies to calls made through it. A caller holding the concrete class gets no
        // default at all, which is why this goes through `port`.
        deactivate(FIFTH_TEMPLATE)

        port.findAll().size shouldBe 13
    }

    @Test
    fun `template catalogue - the whole catalogue - is returned in sort order`() = runTest {
        catalogue.findAll(includeInactive = true).map { it.sortOrder } shouldContainExactly (1..14).toList()
    }

    // ── One template by id ──────────────────────────────────────────────────────────────────────

    @Test
    fun `template catalogue - findById - returns the stored template`() = runTest {
        val template = catalogue.findById(FIRST_TEMPLATE)

        template.shouldNotBeNull()
        template.name shouldBe "Government-issued ID"
        template.isRequired shouldBe true
        template.sortOrder shouldBe 1
    }

    @Test
    fun `template catalogue - findById an unknown id - returns null`() = runTest {
        catalogue.findById(entityId("zzzzzzzzzzzz")) shouldBe null
    }

    @Test
    fun `template catalogue - findById an inactive template - still returns it`() = runTest {
        // Only `findActiveForEmploymentType` filters. The Phase 2 admin screen (8.11) has to be
        // able to open a template it has just deactivated.
        deactivate(FIFTH_TEMPLATE)

        catalogue.findById(FIFTH_TEMPLATE).shouldNotBeNull().isActive shouldBe false
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `template catalogue - a template with a lead time but no validity window - maps both columns`() = runTest {
        // Two adjacent nullable Int columns are the classic transposition, and it is invisible on
        // any row where both are null or both are set. These two rows are the ones that differ:
        // the ID expires with a 60-day lead and no validity window; the clearance has both.
        val id = catalogue.findById(FIRST_TEMPLATE).shouldNotBeNull()
        val clearance = catalogue.findById(entityId("c00000000007")).shouldNotBeNull()

        id.expires shouldBe true
        id.validityMonths shouldBe null
        id.renewalLeadDays shouldBe 60

        clearance.validityMonths shouldBe 12
        clearance.renewalLeadDays shouldBe 60
    }

    @Test
    fun `template catalogue - an optional template - round-trips its required flag`() = runTest {
        // c00000000010 is Appendix A's "Conditional" category collapsed to is_required = false,
        // which also drops it from the 6.5 progress denominator. Worth pinning: the whole of
        // progress arithmetic reads this one boolean.
        catalogue.findById(entityId("c00000000010")).shouldNotBeNull().isRequired shouldBe false
    }

    // ── PRD 8.11: a template edit must not move an in-flight hire ───────────────────────────────

    @Test
    fun `template catalogue - the adapter source - offers no live-lookup path for an existing hire`() {
        // The snapshot happens in ERT-432. What this ticket owes is the guarantee that no
        // convenience method here tempts a later caller into re-reading the catalogue for a hire
        // that is already collecting. That is an absence, so it is read as text — reflection would
        // only see the three methods the port declares.
        val forbidden = listOf("Employees", "EmployeeRequirements", "PersonId")

        forbidden.filter { it in adapterCode() }.shouldBeEmpty()
    }

    @Test
    fun `template catalogue - the no-live-lookup sweep above - is pointed at code rather than at comments`() {
        // A mistyped path reads an empty string and the sweep above passes having checked nothing.
        val code = adapterCode()

        code.contains("TemplateAssignments") shouldBe true
        code.contains("RequirementTemplates") shouldBe true
    }

    private suspend fun deactivate(id: com.pgsystem.employee.requirement.tracker.core.value.EntityId) {
        execute("update requirement_templates set is_active = false where id = '${id.value}'")
    }

    private fun adapterCode(): String = codeOf(ADAPTER)

    private infix fun <T> List<T>.shouldNotContain(item: T) {
        contains(item) shouldBe false
    }

    private companion object {
        const val ADAPTER = "src/data/repository/ExposedRequirementTemplateRepository.kt"

        val REGULAR = entityId("e00000000001")
        val PROBATIONARY = entityId("e00000000002")
        val FIRST_TEMPLATE = entityId("c00000000001")
        val FIFTH_TEMPLATE = entityId("c00000000005")
    }
}
