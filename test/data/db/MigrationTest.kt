package com.pgsystem.employee.requirement.tracker.data.db

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.table.allTables
import com.pgsystem.employee.requirement.tracker.data.freshDatabase
import com.pgsystem.employee.requirement.tracker.data.migrate
import com.pgsystem.employee.requirement.tracker.data.projectDir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.io.File
import kotlin.test.Test

/**
 * The schema, against real SQL.
 *
 * These tests deliberately do **not** go through Ktor. `DatabaseConfig.fromEnvironment()` is baked
 * into the Koin graph and `configureKoin()` hardcodes `appModules`, so a `testApplication` test
 * cannot point at a different database without adding production surface for a test-only need.
 * `DatabaseConfig` is a plain data class, so a fresh database is one constructor call.
 *
 * **H2 in PostgreSQL mode is not PostgreSQL.** The drift test catches divergence between
 * `Tables.kt` and the SQL, and `migration portability` catches dialect-specific syntax, but
 * genuinely Postgres-specific behaviour will not surface here. A Postgres CI job is worth adding
 * before launch.
 *
 * These tests do **not** extend `RepositoryTestBase`, and cannot: it hands out a database that is
 * already migrated, which is the one state a migration test needs to observe the far side of. They
 * share its `freshDatabase()` and `migrate()` so that "how a test gets a database" has one answer.
 */
class MigrationTest {

    @Test
    fun `schema migration - a fresh database - creates every table in allTables`() = withFreshDatabase { db ->
        migrate(db.config)

        val tables = db.tableNames()

        tables shouldContainAll allTables.map { it.tableName.lowercase() }
    }

    @Test
    fun `schema migration - run twice - applies nothing the second time`() = withFreshDatabase { db ->
        val first = migrate(db.config)
        val second = migrate(db.config)

        first.migrationsExecuted shouldBeGreaterThan 0
        second.migrationsExecuted shouldBe 0
    }

    @Test
    fun `schema drift - migrations have run - Exposed reports no pending statements`() {
        val config = freshDatabase()
        migrate(config)

        // Exposed caches schema metadata per Database and populates it on first read, so the
        // check runs on a Database opened *after* the migration, and resets the caches anyway.
        // Checking on a connection that had already run a transaction reports every table missing.
        val factory = DatabaseFactory(config)
        try {
            factory.connect()
            val pending = runBlocking {
                factory.transaction {
                    currentDialectMetadata.resetCaches()
                    SchemaUtils.statementsRequiredToActualizeScheme(*allTables)
                }
            }
            pending.shouldBeEmpty()
        } finally {
            factory.close()
        }
    }

    @Test
    fun `schema drift - the guard is pointed at a real schema - allTables is non-empty`() {
        // statementsRequiredToActualizeScheme() over an empty array is trivially empty forever.
        // 13 since ERT-190 added `users`.
        allTables.size shouldBe 13
    }

    @Test
    fun `access trail schema - upload_links - has no last_accessed_at column`() = withFreshDatabase { db ->
        migrate(db.config)

        // A single overwritten timestamp cannot answer who, from where, or how often (§11, SEC-05).
        // The access trail is portal_access_logs, append-only. Restoring this column undoes it.
        val columns = db.columnsOf("upload_links")

        // Non-empty first: "does not contain" passes trivially against a mistyped table name.
        columns shouldContain "token_hash"
        columns.contains("last_accessed_at") shouldBe false
    }

    @Test
    fun `upload link schema - upload_links - token_hash is uniquely indexed`() = withFreshDatabase { db ->
        migrate(db.config)

        db.uniqueColumns("upload_links").contains("token_hash") shouldBe true
    }

    @Test
    fun `portal session schema - portal_sessions - carries a uniquely indexed token_hash column`() =
        withFreshDatabase { db ->
            migrate(db.config)

            // Without it the session cookie must carry the primary key, which stores a live bearer
            // token in plaintext.
            db.columnsOf("portal_sessions").contains("token_hash") shouldBe true
            db.uniqueColumns("portal_sessions").contains("token_hash") shouldBe true
        }

    @Test
    fun `identifier columns - the migrated schema - are the width their id type declares`() =
        withFreshDatabase { db ->
            migrate(db.config)

            // The drift test CANNOT catch this. H2 reports every VARCHAR(n) as equivalent to every
            // VARCHAR(m), and an id or foreign-key column's Exposed type is EntityIDColumnType, not
            // VarCharColumnType, so the size comparison falls through to "no difference". A
            // varchar(8) in Tables.kt against a varchar(36) in the baseline would pass drift in
            // silence. This test is the only coverage the widths have.
            // ERT-190 added six: `users` is keyed by a PersonId, and the five actor columns point
            // at it. Getting one of these wrong is exactly the failure this sweep exists for -- an
            // 8-character id in a 12-wide column comes back blank-padded on PostgreSQL and fails the
            // validation it was written under.
            val personColumns = setOf(
                "employees.id",
                "employee_requirements.employee_id",
                "upload_links.employee_id",
                "users.id",
                "employees.created_by",
                "employees.originals_sighted_by",
                "submissions.reviewed_by",
                "app_settings.updated_by",
                "audit_logs.actor_user_id",
            )

            val wrong = allTables.flatMap { table ->
                val name = table.tableName.lowercase()
                db.columnWidths(name)
                    // The ERT-190 actor columns are `created_by`, `reviewed_by` and friends -- they
                    // hold an id but do not spell one, so the two structural rules miss them and
                    // they have to be named. Being in `personColumns` is what puts them in scope.
                    .filterKeys { it == "id" || it.endsWith("_id") || "$name.$it" in personColumns }
                    .mapNotNull { (column, width) ->
                        val qualified = "$name.$column"
                        val expected =
                            if (qualified in personColumns) PersonId.LENGTH else EntityId.LENGTH
                        "$qualified is $width, expected $expected".takeIf { width != expected }
                    }
            }

            wrong.shouldBeEmpty()
        }

    @Test
    fun `identifier columns - the sweep above - actually inspects the employee key`() =
        withFreshDatabase { db ->
            migrate(db.config)

            // Guards the test above against passing vacuously if a query returns nothing.
            db.columnWidths("employees")["id"] shouldBe PersonId.LENGTH
            db.columnWidths("employee_requirements")["employee_id"] shouldBe PersonId.LENGTH
            db.columnWidths("audit_logs")["entity_id"] shouldBe EntityId.LENGTH

            // And the ERT-190 columns, which are the ones a drift test cannot see at all.
            db.columnWidths("users")["id"] shouldBe PersonId.LENGTH
            db.columnWidths("employees")["created_by"] shouldBe PersonId.LENGTH
            db.columnWidths("audit_logs")["actor_user_id"] shouldBe PersonId.LENGTH
        }

    @Test
    fun `identifier generation - every keyed table - declares no client default`() {
        // UUIDTable supplied autoGenerate(), so an insert omitting the id silently received one and
        // bypassed the injected generator. Nothing may reintroduce that: an insert must name its id.
        allTables.filterIsInstance<IdTable<*>>()
            .filter { it.id.defaultValueFun != null }
            .map { it.tableName }
            .shouldBeEmpty()
    }

    @Test
    fun `identifier generation - the guard above - is pointed at the ten keyed tables`() {
        // app_settings is keyed by name and template_assignments by a composite; the other eleven
        // carry a generated identifier. Without this, the filter above could pass on an empty list.
        allTables.filterIsInstance<IdTable<*>>().size shouldBe 11
    }

    @Test
    fun `migration portability - every migration - uses no dialect-specific syntax`() {
        // The only mechanical coverage available for "applies cleanly on both H2 and PostgreSQL"
        // without a Postgres in CI. A proxy, not proof.
        //
        // Sweeps the whole directory rather than naming V1, because ERT-190 added V4 and naming
        // files means the next migration is unguarded until someone remembers to add it here. It
        // would not have caught V4's first draft either way -- `create unique index ... (lower(x))`
        // is valid PostgreSQL that H2 rejects, so it is on neither list -- which is the standing
        // reminder that this is a proxy and the H2 run is what actually found that one.
        //
        // Comments are stripped first. Widening the sweep to V2 immediately flagged `MERGE INTO`
        // there, in a comment explaining why MERGE INTO is NOT used -- a file being careful about
        // exactly this rule failed it for saying so. A guard that cannot be documented around is
        // one people write around instead.
        val violations = File(projectDir, "resources/db/migration")
            .listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .flatMap { file ->
                val sql = file.readText().statementsOnly().uppercase()
                DIALECT_SPECIFIC.filter { sql.contains(it) }.map { "${file.name}: $it" }
            }

        violations.shouldBeEmpty()
    }

    @Test
    fun `migration portability - the sweep above - is pointed at every migration`() {
        // A listFiles() that matched nothing would make the check above pass forever.
        File(projectDir, "resources/db/migration").listFiles { f -> f.extension == "sql" }.orEmpty()
            .size shouldBe 5
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers specific to testing the migration itself. `projectDir`, `freshDatabase()` and
// `migrate()` now live beside `RepositoryTestBase` (ERT-240); what stays here is the part that
// only a schema test wants -- a database it can inspect *before* and *after* migrating.
// ---------------------------------------------------------------------------------------------

/** A connected factory plus the config it was built from, so a test can hand either to Flyway. */
/**
 * The SQL, with `--` comments removed.
 *
 * Line-based, and that is sufficient here rather than lucky: every statement in these files is on
 * one line, and none contains a `--` inside a string literal. `V3__app_settings.sql` already carries
 * a formatting rule of the same shape -- no semicolon inside a string literal, because `SeedDataTest`
 * splits on semicolons. If a migration ever needs `--` inside a literal, both rules need a real
 * parser rather than an exception.
 */
private fun String.statementsOnly(): String =
    lineSequence().joinToString("\n") { it.substringBefore("--") }

private val DIALECT_SPECIFIC = listOf(
    "MERGE INTO", "::", "SERIAL", "IDENTITY", "AUTO_INCREMENT", "ON CONFLICT", "CREATE OR REPLACE", "`", "[",
)

internal class TestDatabase(val config: DatabaseConfig, val factory: DatabaseFactory) {

    fun tableNames(): List<String> = query(
        "select table_name from information_schema.tables where table_schema = 'PUBLIC'"
    ) { it.lowercase() }

    fun columnsOf(table: String): List<String> = query(
        "select column_name from information_schema.columns where lower(table_name) = '${table.lowercase()}'"
    ) { it.lowercase() }

    fun uniqueColumns(table: String): List<String> = query(
        """
        select c.column_name from information_schema.table_constraints t
        join information_schema.key_column_usage c on t.constraint_name = c.constraint_name
        where t.constraint_type = 'UNIQUE' and lower(t.table_name) = '${table.lowercase()}'
        """.trimIndent()
    ) { it.lowercase() }

    /** Declared character width per column, for the columns that have one. */
    fun columnWidths(table: String): Map<String, Int> = runBlocking {
        factory.transaction {
            val widths = mutableMapOf<String, Int>()
            exec(
                """
                select column_name, character_maximum_length from information_schema.columns
                where lower(table_name) = '${table.lowercase()}'
                  and character_maximum_length is not null
                """.trimIndent()
            ) { rs -> while (rs.next()) widths[rs.getString(1).lowercase()] = rs.getInt(2) }
            widths
        }
    }

    private fun query(sql: String, map: (String) -> String): List<String> = runBlocking {
        factory.transaction {
            val rows = mutableListOf<String>()
            exec(sql) { rs -> while (rs.next()) rows += map(rs.getString(1)) }
            rows
        }
    }
}

internal fun withFreshDatabase(block: (TestDatabase) -> Unit) {
    val config = freshDatabase()
    val factory = DatabaseFactory(config)
    try {
        factory.connect()
        block(TestDatabase(config, factory))
    } finally {
        factory.close()
    }
}
