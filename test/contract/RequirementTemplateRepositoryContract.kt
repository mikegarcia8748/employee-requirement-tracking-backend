package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.MigratedDatabase
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementTemplate
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementTemplate
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeRequirementTemplateRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * One catalogue, two implementations, one set of rules (ERT-250).
 *
 * The ordering rule became part of the port's contract in ERT-432 rather than a coincidence two
 * files happened to share: `CreateHireUseCase` copies the order it is handed straight into the
 * snapshot, so an implementation returning storage order would hand a new hire a shuffled checklist
 * and still satisfy every other clause in the KDoc.
 *
 * **The adapter side clears the V2 seed**, for the reason ERT-320 recorded: the seed assigns
 * `sort_order` 1..14 in the same order as the ids, and against that coincidence **dropping the
 * `ORDER BY` entirely failed only one test**. Both sides here start empty and are arranged
 * identically.
 *
 * The `is_active` asymmetry is a rule, not an oversight: [RequirementTemplateRepository.findById]
 * does **not** filter, because the Phase 2 admin screen has to open a template it has just
 * deactivated; [RequirementTemplateRepository.findActiveForEmploymentType] does.
 */
abstract class RequirementTemplateRepositoryContract {

    protected abstract val catalogue: RequirementTemplateRepository

    protected abstract suspend fun given(vararg template: RequirementTemplate)

    protected abstract suspend fun assign(employmentTypeId: EntityId, templateIds: List<EntityId>)

    private val regular = entityId("e00000000001")
    private val probationary = entityId("e00000000002")

    // ── Ordering ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `template contract - a catalogue for one employment type - both implementations order by sort order then name`() =
        runTest {
            // Sort order decides first; where it ties, the name. Seeded in an order that is neither
            // the asserted one nor its reverse, with ids ascending in a third order — so insertion
            // order, id order and name order each name a different first row than the rule does.
            given(
                template("c00000000003", name = "Birth Certificate", sortOrder = 2),
                template("c00000000001", name = "TIN Card", sortOrder = 1),
                template("c00000000002", name = "Aadhaar Card", sortOrder = 2),
            )
            assign(regular, listOf(entityId("c00000000001"), entityId("c00000000002"), entityId("c00000000003")))

            catalogue.findActiveForEmploymentType(regular).map { it.name } shouldBe
                listOf("TIN Card", "Aadhaar Card", "Birth Certificate")
        }

    @Test
    fun `template contract - a template assigned to another employment type - both implementations exclude it`() =
        runTest {
            given(
                template("c00000000001", name = "TIN Card", sortOrder = 1),
                template("c00000000002", name = "Aadhaar Card", sortOrder = 2),
            )
            assign(regular, listOf(entityId("c00000000001")))
            assign(probationary, listOf(entityId("c00000000002")))

            // Paired in both directions, so a predicate that returned nothing — or everything —
            // fails one of the two.
            catalogue.findActiveForEmploymentType(regular).map { it.name } shouldBe listOf("TIN Card")
            catalogue.findActiveForEmploymentType(probationary).map { it.name } shouldBe listOf("Aadhaar Card")
        }

    @Test
    fun `template contract - an employment type with no assignments - both implementations answer empty`() =
        runTest {
            given(template("c00000000001", name = "TIN Card", sortOrder = 1))
            assign(regular, listOf(entityId("c00000000001")))

            catalogue.findActiveForEmploymentType(regular).size shouldBe 1
            catalogue.findActiveForEmploymentType(entityId("e00000000099")).shouldBeEmpty()
        }

    // ── The is_active asymmetry ─────────────────────────────────────────────────────────────────

    @Test
    fun `template contract - a retired template - both implementations drop it from the assigned list`() =
        runTest {
            given(
                template("c00000000001", name = "TIN Card", sortOrder = 1),
                template("c00000000002", name = "Aadhaar Card", sortOrder = 2, isActive = false),
            )
            assign(regular, listOf(entityId("c00000000001"), entityId("c00000000002")))

            catalogue.findActiveForEmploymentType(regular).map { it.name } shouldBe listOf("TIN Card")
        }

    @Test
    fun `template contract - a retired template - both implementations still resolve it by id`() = runTest {
        // The asymmetry, stated as its own test: the Phase 2 admin screen must be able to open a
        // template it has just deactivated, and a `findById` that hid one makes that impossible.
        given(template("c00000000002", name = "Aadhaar Card", sortOrder = 2, isActive = false))

        catalogue.findById(entityId("c00000000002"))?.name shouldBe "Aadhaar Card"
    }

    @Test
    fun `template contract - an id nothing holds - both implementations answer null`() = runTest {
        given(template("c00000000001", name = "TIN Card", sortOrder = 1))

        (catalogue.findById(entityId("c00000000001")) != null) shouldBe true
        catalogue.findById(entityId("c00000000099")).shouldBeNull()
    }

    // ── findAll ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `template contract - findAll by default - both implementations omit the retired ones`() = runTest {
        given(
            template("c00000000001", name = "TIN Card", sortOrder = 1),
            template("c00000000002", name = "Aadhaar Card", sortOrder = 2, isActive = false),
        )

        catalogue.findAll().map { it.name } shouldBe listOf("TIN Card")
        catalogue.findAll(includeInactive = true).map { it.name } shouldBe
            listOf("TIN Card", "Aadhaar Card")
    }

    private fun template(id: String, name: String, sortOrder: Int, isActive: Boolean = true) =
        aRequirementTemplate(
            id = entityId(id),
            name = name,
            sortOrder = sortOrder,
            isActive = isActive,
        )
}

class FakeRequirementTemplateRepositoryContractTest : RequirementTemplateRepositoryContract() {

    private val fake = FakeRequirementTemplateRepository()

    override val catalogue: RequirementTemplateRepository = fake

    override suspend fun given(vararg template: RequirementTemplate) {
        fake.given(*template)
    }

    override suspend fun assign(employmentTypeId: EntityId, templateIds: List<EntityId>) {
        fake.assign(employmentTypeId, templateIds)
    }
}

class ExposedRequirementTemplateRepositoryContractTest : RequirementTemplateRepositoryContract() {

    private val database = MigratedDatabase()

    override val catalogue: RequirementTemplateRepository by lazy {
        ExposedRequirementTemplateRepository(database.factory)
    }

    override suspend fun given(vararg template: RequirementTemplate) {
        template.forEach {
            execute(
                """
                insert into requirement_templates (id, "name", instructions, is_required, expires,
                                                   is_active, sort_order)
                values ('${it.id.value}', '${it.name}', '${it.instructions}', ${it.isRequired},
                        ${it.expires}, ${it.isActive}, ${it.sortOrder})
                """.trimIndent()
            )
        }
    }

    override suspend fun assign(employmentTypeId: EntityId, templateIds: List<EntityId>) {
        // The employment type has to exist first: `template_assignments` restricts on both sides.
        execute(
            """
            insert into employment_types (id, "name")
            select '${employmentTypeId.value}', 'Type ${employmentTypeId.value}'
            where not exists (select 1 from employment_types where id = '${employmentTypeId.value}')
            """.trimIndent()
        )
        templateIds.forEach {
            execute(
                """
                insert into template_assignments (employment_type_id, requirement_template_id)
                values ('${employmentTypeId.value}', '${it.value}')
                """.trimIndent()
            )
        }
    }

    @BeforeTest
    fun openDatabaseAndClearTheSeed() {
        database.open()
        runBlocking {
            // ERT-320's coincidence, removed rather than worked around: the seed cross-joins
            // fourteen templates against four employment types with `sort_order` running 1..14 in
            // id order, and an ordering test over that proves nothing.
            execute("delete from template_assignments")
            execute("delete from requirement_templates")
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
