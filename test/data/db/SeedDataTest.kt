package com.pgsystem.employee.requirement.tracker.data.db

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.freshDatabase
import com.pgsystem.employee.requirement.tracker.data.mapper.LinkPolicySetting
import com.pgsystem.employee.requirement.tracker.data.migrate
import com.pgsystem.employee.requirement.tracker.data.projectDir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * The V2 and V3 seeds, against real SQL.
 *
 * "Idempotent" here is two layers. Flyway guarantees once-only execution, so an admin's later edit
 * is never touched. The second layer is that the SQL itself is safe to replay by hand — no UPDATE
 * statement anywhere, every INSERT guarded by WHERE NOT EXISTS. The replay test below exercises
 * the second layer, which is the part the migration author controls.
 *
 * See `MigrationTest` for why these tests bypass Ktor and for the H2-is-not-Postgres caveat.
 */
class SeedDataTest {

    /**
     * The nine PRD §6.4 settings — one per `LinkPolicy` field.
     *
     * Read from the adapter's own enum rather than copied, so the seed and the reader cannot drift:
     * a key renamed in one place without the other now fails here (ERT-310).
     */
    private val policyKeys = LinkPolicySetting.entries.map { it.key }

    @Test
    fun `policy seed - a migrated database - every LinkPolicy field has a settings row`() =
        withFreshDatabase { db ->
            migrate(db.config)

            // Driven off the key list, so a tenth LinkPolicy field without a seed row fails here.
            db.settingKeys() shouldContainAll policyKeys
        }

    @Test
    fun `policy seed - absolute expiry bounds - are stored as 7 to 180`() = withFreshDatabase { db ->
        migrate(db.config)

        db.setting("link.absolute_expiry_days") shouldBe Setting("90", "INT", "7", "180")
    }

    @Test
    fun `policy seed - idle expiry bounds - permit zero so the idle clock can be disabled`() =
        withFreshDatabase { db ->
            migrate(db.config)

            // PRD §6.4: set to 0 to disable the idle clock and rely on the absolute ceiling alone.
            db.setting("link.idle_expiry_days")?.min shouldBe "0"
        }

    @Test
    fun `policy seed - every settings row - declares a value type and both bounds`() =
        withFreshDatabase { db ->
            migrate(db.config)

            val incomplete = policyKeys.filter { key ->
                val s = db.setting(key)
                s == null || s.type.isBlank() || s.min == null || s.max == null
            }

            incomplete.shouldBeEmpty()
        }

    @Test
    fun `policy seed - applied twice - does not overwrite an admin-changed value`() =
        withFreshDatabase { db ->
            migrate(db.config)
            // `updated_by` references users(id) since ERT-190, so the admin has to exist. Kept
            // rather than dropped from the statement: an admin-changed row is what this test is
            // about, and a change with no actor is not the shape a real one has.
            db.exec(
                "insert into users (id, email, full_name, password_hash, \"role\", is_active, " +
                    "password_change_required, created_at) values ('HRA00001', 'admin@example.com', " +
                    "'Seed Admin', 'x', 'HR_ADMIN', true, false, current_timestamp)"
            )
            db.exec("update app_settings set \"value\" = '45', updated_by = 'HRA00001' where \"key\" = 'link.absolute_expiry_days'")

            db.replay("V3__app_settings.sql")

            db.setting("link.absolute_expiry_days")?.value shouldBe "45"
            db.countOf("app_settings") shouldBe policyKeys.size
        }

    @Test
    fun `catalogue seed - an employment type - resolves to a non-empty template set`() =
        withFreshDatabase { db ->
            migrate(db.config)

            db.countOf("departments") shouldBeGreaterThan 0
            db.countOf("employment_types") shouldBe 4
            db.templatesForEachEmploymentType().filter { it.second == 0 }.shouldBeEmpty()
        }

    @Test
    fun `catalogue seed - the Appendix A rows - carry is_required, expires and sort_order`() =
        withFreshDatabase { db ->
            migrate(db.config)

            db.countOf("requirement_templates") shouldBe 14
            // sort_order 1..14, each used once: a default-0 row would collapse the ordering.
            db.distinctSortOrders() shouldBe (1..14).toList()
            db.requiredTemplateCount() shouldBe 10
        }

    @Test
    fun `catalogue seed - applied twice - creates no duplicate templates or assignments`() =
        withFreshDatabase { db ->
            migrate(db.config)
            val assignmentsBefore = db.countOf("template_assignments")

            db.replay("V2__reference_data.sql")

            db.countOf("requirement_templates") shouldBe 14
            db.countOf("employment_types") shouldBe 4
            db.countOf("template_assignments") shouldBe assignmentsBefore
        }

    @Test
    fun `catalogue seed - the migration - records the catalogue as illustrative pending Q2`() {
        // PRD Appendix A is explicitly illustrative. A reader of the seeded rows must not mistake
        // them for a company decision.
        val sql = File(projectDir, "resources/db/migration/V2__reference_data.sql").readText()

        sql shouldContain "ILLUSTRATIVE ONLY, PENDING OPEN QUESTION 2"
    }

    @Test
    fun `catalogue seed - every seeded id - is a well formed entity id`() = withFreshDatabase { db ->
        migrate(db.config)

        // Without this, someone regenerating V2 from an older copy can paste a UUID back in and
        // nothing fails until the first insert against the narrower column.
        val malformed = listOf("departments", "employment_types", "requirement_templates")
            .flatMap { table -> db.seededIds(table).map { table to it } }
            .filter { (_, id) -> EntityId.of(id) is DomainResult.Err }

        malformed.shouldBeEmpty()
    }

    @Test
    fun `catalogue seed - the id sweep above - reads all nineteen seeded rows`() =
        withFreshDatabase { db ->
            migrate(db.config)

            val counted = listOf("departments", "employment_types", "requirement_templates")
                .sumOf { db.seededIds(it).size }

            counted shouldBe 19
        }
}

internal data class Setting(val value: String, val type: String, val min: String?, val max: String?)

internal fun TestDatabase.settingKeys(): List<String> =
    strings("select \"key\" from app_settings")

internal fun TestDatabase.setting(key: String): Setting? = runBlocking {
    factory.transaction {
        var found: Setting? = null
        exec("select \"value\", value_type, min_value, max_value from app_settings where \"key\" = '$key'") { rs ->
            if (rs.next()) found = Setting(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
        }
        found
    }
}

internal fun TestDatabase.countOf(table: String): Int =
    strings("select count(*) from $table").first().toInt()

internal fun TestDatabase.distinctSortOrders(): List<Int> =
    strings("select distinct sort_order from requirement_templates order by sort_order").map { it.toInt() }

internal fun TestDatabase.requiredTemplateCount(): Int =
    strings("select count(*) from requirement_templates where is_required = true").first().toInt()

internal fun TestDatabase.templatesForEachEmploymentType(): List<Pair<String, Int>> = runBlocking {
    factory.transaction {
        val rows = mutableListOf<Pair<String, Int>>()
        exec(
            """
            select et."name", count(ta.requirement_template_id)
            from employment_types et
            left join template_assignments ta on ta.employment_type_id = et.id
            group by et."name"
            """.trimIndent()
        ) { rs -> while (rs.next()) rows += rs.getString(1) to rs.getInt(2) }
        rows
    }
}

internal fun TestDatabase.exec(sql: String) = runBlocking {
    factory.transaction { exec(sql) }
    Unit
}

/**
 * Re-runs a migration file's statements by hand, which is what "idempotent" has to mean here:
 * Flyway would never run it a second time on its own.
 *
 * Splitting on `;` is why V2 and V3 carry no semicolon inside a string literal.
 */
internal fun TestDatabase.replay(fileName: String) {
    File(projectDir, "resources/db/migration/$fileName").readText()
        .lineSequence()
        .filterNot { it.trimStart().startsWith("--") }
        .joinToString("\n")
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { exec(it) }
}

internal fun TestDatabase.seededIds(table: String): List<String> =
    strings("select id from $table")

private fun TestDatabase.strings(sql: String): List<String> = runBlocking {
    factory.transaction {
        val rows = mutableListOf<String>()
        exec(sql) { rs -> while (rs.next()) rows += rs.getString(1) }
        rows
    }
}
