package com.pgsystem.employee.requirement.tracker.data.db

import com.pgsystem.employee.requirement.tracker.data.db.table.allTables
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.io.File
import java.util.UUID
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
 * The private `freshDatabase()` helper below is duplicated in `SeedDataTest`. That is deliberate —
 * ERT-240 owns the shared repository test base, and inventing it a ticket early would pre-empt its
 * design.
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
        allTables.size shouldBe 12
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
    fun `migration portability - the baseline sql - uses no dialect-specific syntax`() {
        // The only mechanical coverage available for "applies cleanly on both H2 and PostgreSQL"
        // without a Postgres in CI. A proxy, not proof.
        val sql = File(projectDir, "resources/db/migration/V1__baseline.sql").readText().uppercase()

        listOf("MERGE INTO", "::", "SERIAL", "IDENTITY", "AUTO_INCREMENT", "ON CONFLICT", "CREATE OR REPLACE", "`", "[")
            .filter { sql.contains(it) }
            .shouldBeEmpty()
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers. Shared with SeedDataTest by duplication until ERT-240 introduces the real base class.
// ---------------------------------------------------------------------------------------------

internal val projectDir: File
    get() = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "module.yaml").exists() }

internal fun freshDatabase(): DatabaseConfig = DatabaseConfig(
    url = "jdbc:h2:mem:ert_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    user = "sa",
    password = "",
    driverClassName = "org.h2.Driver",
    maxPoolSize = 2,
)

internal fun migrate(config: DatabaseConfig) = Flyway.configure()
    .dataSource(config.url, config.user, config.password)
    .locations("classpath:db/migration")
    .load()
    .migrate()

/** A connected factory plus the config it was built from, so a test can hand either to Flyway. */
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
