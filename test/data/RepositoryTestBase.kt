package com.pgsystem.employee.requirement.tracker.data

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseConfig
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * A migrated, seeded, isolated database for a repository test.
 *
 * Extend it and the database is already there: `factory` is the production [DatabaseFactory],
 * [transaction] is the production entry point, and the ERT-130 reference data is present because
 * the seeds are migrations (V2, V3) rather than a separate fixture step.
 *
 * ```
 * class ExposedAppSettingsRepositoryTest : RepositoryTestBase() {
 *     @Test
 *     fun `link policy read - a seeded database - returns the stored nine values`() = runTest {
 *         ExposedAppSettingsRepository(factory).linkPolicy() // ...
 *     }
 * }
 * ```
 *
 * Test bodies are `= runTest { }`, matching the rest of the suite. Never `= runBlocking { }`: that
 * returns a value, and a `@Test` method that returns a value is silently skipped by JUnit 5 with
 * only a discovery warning — see `ServerTest.application shutdown`.
 *
 * A subclass may add its own `@BeforeTest` — to construct the adapter under test, say. JUnit 5 runs
 * a superclass's `@BeforeEach` first, so `factory` is already connected and migrated by then.
 *
 * **H2 in PostgreSQL mode is not PostgreSQL.** Nothing below narrows that gap. `MigrationTest`
 * catches divergence between `Tables.kt` and the SQL, and its `migration portability` test catches
 * dialect-specific syntax, but genuinely Postgres-specific behaviour — collation, `on conflict`,
 * `serial`, timestamp/timezone handling, lock escalation — will not surface here at all. A green
 * repository suite is evidence that the SQL is *correct*, not that it *runs on the production
 * database*. A Postgres CI job is worth adding before launch; ERT-240 puts it out of scope.
 *
 * **Isolation is by construction: one brand-new in-memory database per test.** Not truncation —
 * a truncation path would have to know the foreign-key order of every table added from here on, and
 * would have to restore the seeded reference rows that ERT-310's bounds tests deliberately corrupt.
 * A forgotten table there is a silent cross-test leak; a forgotten table here is impossible. The
 * cost is one Flyway run per test, which is the trade being made knowingly.
 */
abstract class RepositoryTestBase {

    /** The database this test was given. Useful for a test that needs a raw JDBC connection. */
    protected lateinit var config: DatabaseConfig
        private set

    /** The production factory, connected. Repository adapters are constructed with it. */
    protected lateinit var factory: DatabaseFactory
        private set

    @BeforeTest
    fun openMigratedDatabase() {
        config = freshDatabase()

        // Assigned only once connected, so `::factory.isInitialized` in teardown means "there is
        // something to tear down" rather than "the constructor ran".
        val connected = DatabaseFactory(config)
        connected.connect()
        factory = connected

        migrate(config)
    }

    @AfterTest
    fun discardDatabase() {
        if (!::factory.isInitialized) return

        // Three steps, and only the pool close is the obvious one. The other two are why this
        // method exists at all — see `discard` for what each of them reclaims.
        TransactionManager.closeAndUnregister(factory.database)
        factory.close()
        discard(config)
    }

    /** The one entry point production uses. Nothing in this base opens a connection any other way. */
    protected suspend fun <T> transaction(block: suspend JdbcTransaction.() -> T): T =
        factory.transaction(block)

    /** Arrange step: run a statement for its effect. */
    protected suspend fun execute(sql: String) {
        transaction { exec(sql) }
    }

    /** Assert step: the first column of every row, as text. */
    protected suspend fun strings(sql: String): List<String> = transaction {
        val rows = mutableListOf<String>()
        exec(sql) { rs -> while (rs.next()) rows += rs.getString(1) }
        rows
    }

    protected suspend fun countOf(table: String): Int =
        strings("select count(*) from $table").first().toInt()

    protected suspend fun tableNames(): List<String> =
        strings("select table_name from information_schema.tables where table_schema = 'PUBLIC'")
            .map { it.lowercase() }
}

// ---------------------------------------------------------------------------------------------
// The lifecycle, as functions rather than private methods, so `RepositoryTestBaseTeardownTest` can
// drive one cycle by hand and inspect what a finished test leaves behind. `MigrationTest` and
// `SeedDataTest` share them too — they test the migration itself and so need a database *before*
// it is migrated, which is the one thing this base will not hand out.
// ---------------------------------------------------------------------------------------------

internal val projectDir: File
    get() = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "module.yaml").exists() }

/**
 * A database no other test has a name for.
 *
 * `DB_CLOSE_DELAY=-1` keeps it alive while the pool has no open connection — and keeps it alive
 * after the pool closes too, which is why [discard] exists.
 */
internal fun freshDatabase(): DatabaseConfig = DatabaseConfig(
    url = "jdbc:h2:mem:ert_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    user = "sa",
    password = "",
    driverClassName = "org.h2.Driver",
    maxPoolSize = 2,
)

/**
 * Runs the versioned SQL, seeds included.
 *
 * Duplicates the Flyway configuration in `plugin/Database.kt`, which is private to an `Application`
 * extension and so unreachable from here. ERT-200 scopes this epic to `test/`, so the duplication is
 * accepted rather than extracted. It is not silent: pointing production at a different `locations`
 * would leave these tests finding no migrations at all, and every repository test would fail.
 */
internal fun migrate(config: DatabaseConfig) = Flyway.configure()
    .dataSource(config.url, config.user, config.password)
    .locations("classpath:db/migration")
    .load()
    .migrate()

/**
 * Releases the database, which closing the pool does not do.
 *
 * `DB_CLOSE_DELAY=-1` means H2 holds an in-memory database until the JVM exits even after the last
 * connection closes. With one database per test that is the whole schema and its seed rows retained
 * per test, for the life of the test JVM. `SHUTDOWN` removes it from H2's registry outright.
 *
 * Its partner is [TransactionManager.closeAndUnregister] in the teardown above: `Database.connect()`
 * registers every instance in a companion-object map that nothing else ever prunes.
 *
 * Neither leak is visible in a green suite, which is why both have a test.
 */
internal fun discard(config: DatabaseConfig) {
    DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
        connection.createStatement().use { it.execute("SHUTDOWN") }
    }
}

/** The tables a database currently holds, read without Exposed — for asserting on a discarded one. */
internal fun tableNamesVia(config: DatabaseConfig): List<String> =
    DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select table_name from information_schema.tables where table_schema = 'PUBLIC'"
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1).lowercase()) }
            }
        }
    }
