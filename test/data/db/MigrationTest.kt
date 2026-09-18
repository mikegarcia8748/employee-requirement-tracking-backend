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
import kotlin.test.assertFailsWith
import org.flywaydb.core.internal.exception.FlywayMigrateException

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
        allTables.size shouldBe 14
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
            //
            // ERT-440 added `notification_outbox.employee_id`, and the list below is why it had to
            // be declared rather than inferred: an EntityIdTable whose foreign key points at
            // `employees` carries an 8-wide column, so the structural rule reads it as wrong. This
            // sweep FAILED on it, which is the guard working -- a new person-keyed column is a
            // decision, and the list is where the decision is recorded.
            val personColumns = setOf(
                "employees.id",
                "employee_requirements.employee_id",
                "upload_links.employee_id",
                "notification_outbox.employee_id",
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

            // ERT-440's outbox, for the same reason: it is an EntityIdTable pointing at `employees`.
            db.columnWidths("notification_outbox")["employee_id"] shouldBe PersonId.LENGTH
            db.columnWidths("notification_outbox")["id"] shouldBe EntityId.LENGTH
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
    fun `identifier generation - the guard above - is pointed at the twelve keyed tables`() {
        // app_settings is keyed by name and template_assignments by a composite; the other twelve
        // carry a generated identifier. Without this, the filter above could pass on an empty list.
        // The name said "ten" until 2026-09-18 while the assertion said 12 (C32) -- the assertion
        // tracked reality throughout, and the name is what a reader skimming test output trusts.
        allTables.filterIsInstance<IdTable<*>>().size shouldBe 12
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
            .size shouldBe 7
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
        "select table_name from information_schema.tables where table_schema = current_schema"
    ) { it.lowercase() }

    fun columnsOf(table: String): List<String> = query(
        """
        select column_name from information_schema.columns
        where table_schema = current_schema and lower(table_name) = '${table.lowercase()}'
        """.trimIndent()
    ) { it.lowercase() }

    fun uniqueColumns(table: String): List<String> = query(
        """
        select c.column_name from information_schema.table_constraints t
        join information_schema.key_column_usage c
          on t.constraint_name = c.constraint_name
         and t.constraint_schema = c.constraint_schema
        where t.constraint_type = 'UNIQUE'
          and t.table_schema = current_schema
          and lower(t.table_name) = '${table.lowercase()}'
        """.trimIndent()
    ) { it.lowercase() }

    /** Declared character width per column, for the columns that have one. */
    fun columnWidths(table: String): Map<String, Int> = runBlocking {
        factory.transaction {
            val widths = mutableMapOf<String, Int>()
            exec(
                """
                select column_name, character_maximum_length from information_schema.columns
                where table_schema = current_schema
                  and lower(table_name) = '${table.lowercase()}'
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

/**
 * Migrations applied to a database that already holds rows (ERT-260, HAR-05).
 *
 * **Every other test in this file runs against a fresh database, and that is a blind spot rather
 * than a choice.** The idempotence test proves the *runner* is idempotent; the drift test proves the
 * *destination* matches `Tables.kt`. Neither migrates a database with data in it — so
 * `V4__hr_users.sql` shipped green, and **every acceptance criterion ERT-120 wrote is about a fresh
 * database**, which makes this class of defect ship green by construction. ERT-1220 and ERT-1230
 * made "a database with rows in it" a real place.
 *
 * ### What V4 actually does, measured rather than predicted
 *
 * The 2026-09-18 review said V4 against a populated table would be *"either a hard failure … or a
 * silent discard of four actor columns"*. It is **the hard failure**, and that is worth stating
 * plainly because it is the worse of the two: `alter table employees add column created_by
 * varchar(8) not null` cannot apply to a table holding rows, so the migration aborts part-applied
 * rather than quietly losing four columns. A deployment to a database with a single hire in it would
 * stop mid-chain.
 *
 * It is **exempt** rather than fixed, and the exemption is narrow: no database that still has V4
 * unapplied holds any rows. Tests and local runs start fresh; ERT-1260 has not been taken, so no UAT
 * or production database exists at all. V4's own header already argues the same thing from the other
 * end — the four columns held free text with no conversion to a `users(id)` that must exist, so
 * there was nothing to preserve.
 *
 * **The sweep is still the deliverable.** It seeds *after* the exempt migration and runs everything
 * from V5 on against the populated database, so every migration added from here is checked — and
 * [`the exemption is load-bearing`][PopulatedMigrationTest] asserts V4 still fails, so the day
 * someone rewrites it the build says to drop the exemption rather than leaving a stale licence for
 * the next one.
 */
class PopulatedMigrationTest {

    /**
     * Migrations that cannot be applied to a database holding rows, each with the argument for it.
     *
     * Adding a row here is the deliberate, reviewable act this sweep exists to force. It is not a
     * list of known failures to route around: every entry is asserted to still be failing.
     */
    private val cannotApplyToRows = mapOf(
        "4" to "V4 re-adds employees.created_by as `varchar(8) not null` with no default, which no " +
            "engine will apply to a populated table. Accepted because no database with V4 " +
            "unapplied holds rows: tests start fresh and ERT-1260 has not been taken, so no " +
            "deployed database exists. The four columns held free text with no conversion to a " +
            "users(id) that must exist, so there was nothing to preserve either.",
    )

    @Test
    fun `schema migration - a database holding rows - the rows survive the remaining migrations`() =
        withFreshDatabase { db ->
            // Seeded past the one exempt migration, so this sweeps V5 onward -- and every migration
            // added after them, which is the part that matters going forward.
            migrate(db.config, target = LAST_EXEMPT_VERSION)
            db.seedAHireAndASettingChange()

            migrate(db.config)

            db.countOf("employees") shouldBe 1
            db.valueOf("select first_name from employees") shouldBe "Jose"
            db.valueOf("select last_name from employees") shouldBe "Dela Cruz"
            db.valueOf("select email from employees") shouldBe "jose@example.com"
            db.valueOf("select packet_status from employees") shouldBe "DRAFT_COLLECTING"
            db.valueOf("select created_by from employees") shouldBe "HRU00001"

            // A value an administrator had changed away from its seeded default, which is the case
            // a migration rewriting app_settings would destroy.
            db.valueOf("select \"value\" from app_settings where \"key\" = 'link.absolute_expiry_days'") shouldBe "45"

            // And the column V7 added to a table that already had rows: `default 0` is what let it
            // apply at all, and this is the test that says the default reached the existing row.
            db.valueOf("select sort_order_snapshot from employee_requirements") shouldBe "0"
        }

    @Test
    fun `schema migration - the sweep above - actually ran migrations against the populated database`() {
        // The anti-vacuity half. A target that already named the last version would migrate nothing
        // afterwards, and the test above would pass having proved nothing at all.
        withFreshDatabase { db ->
            val toExempt = migrate(db.config, target = LAST_EXEMPT_VERSION)
            val rest = migrate(db.config)

            toExempt.migrationsExecuted shouldBe 4
            (rest.migrationsExecuted >= 3) shouldBe true
        }
    }

    @Test
    fun `schema migration - an exempt migration - still fails against rows so the exemption is load-bearing`() =
        withFreshDatabase { db ->
            // The other half of the exemption, and the half that keeps it honest. If V4 is ever
            // rewritten to apply cleanly, this fails and its author removes the entry deliberately
            // instead of leaving a licence behind that covers the next migration by accident.
            cannotApplyToRows.keys shouldBe setOf("4")

            migrate(db.config, target = "3")
            db.seedAHireBeforeTheActorColumnsBecameKeys()

            assertFailsWith<FlywayMigrateException> { migrate(db.config, target = "4") }
        }
}

// ---------------------------------------------------------------------------------------------
// Arrange and assert helpers for a database that already holds rows.
// ---------------------------------------------------------------------------------------------

/** The last migration that cannot be applied to a populated database. The sweep starts after it. */
private const val LAST_EXEMPT_VERSION = "4"

/**
 * A hire, its checklist row, and an administrator-changed setting — written as V4 leaves the schema.
 *
 * The actor columns are `users(id)` foreign keys from V4 on, so the account has to exist first. This
 * is the state every migration from V5 is applied to in the real world.
 */
private fun TestDatabase.seedAHireAndASettingChange() {
    exec(
        """
        insert into users (id, email, full_name, password_hash, "role", is_active,
                           password_change_required, created_at)
        values ('HRU00001', 'hr.officer@example.com', 'Ana Reyes', 'x', 'HR_OFFICER', true, false,
                current_timestamp)
        """.trimIndent()
    )
    exec(
        """
        insert into employees (id, first_name, last_name, department_id, "position",
                               employment_type_id, email, packet_status, submitted_by_hr,
                               anomaly_flags, created_at, created_by)
        values ('EMP00001', 'Jose', 'Dela Cruz', 'd00000000001', 'Store Associate',
                'e00000000001', 'jose@example.com', 'DRAFT_COLLECTING', false,
                '', current_timestamp, 'HRU00001')
        """.trimIndent()
    )
    exec(
        """
        insert into employee_requirements (id, employee_id, template_id, name_snapshot,
                                           is_required_snapshot, status, rejection_count)
        values ('REQ000000001', 'EMP00001', 'c00000000001', 'NBI Clearance', true, 'PENDING', 0)
        """.trimIndent()
    )
    exec("update app_settings set \"value\" = '45' where \"key\" = 'link.absolute_expiry_days'")
}

/** The same hire at V3, when `created_by` was still `varchar(128)` of free text. */
private fun TestDatabase.seedAHireBeforeTheActorColumnsBecameKeys() {
    exec(
        """
        insert into employees (id, first_name, last_name, department_id, "position",
                               employment_type_id, email, packet_status, submitted_by_hr,
                               anomaly_flags, created_at, created_by)
        values ('EMP00001', 'Jose', 'Dela Cruz', 'd00000000001', 'Store Associate',
                'e00000000001', 'jose@example.com', 'DRAFT_COLLECTING', false,
                '', current_timestamp, 'hr.officer@example.com')
        """.trimIndent()
    )
}

/** The first column of the first row, as text. */
private fun TestDatabase.valueOf(sql: String): String = runBlocking {
    factory.transaction {
        var value: String? = null
        exec(sql) { rs -> if (rs.next()) value = rs.getString(1) }
        value ?: error("No row for: $sql")
    }
}
