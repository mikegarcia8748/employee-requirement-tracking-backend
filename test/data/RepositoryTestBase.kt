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
 * database*.
 *
 * **Since ERT-260 there is a second opinion.** Set `ert.test.database.url` (or `ERT_TEST_DATABASE_URL`)
 * at a PostgreSQL server and the whole repository suite runs against it, one schema per test; CI does
 * exactly that in a second job. H2 remains the default, so a local run needs no Docker. See
 * [TestEngine] for why the variable is not called `DATABASE_URL`.
 *
 * **Isolation is by construction: one brand-new namespace per test** — an in-memory database on
 * H2, a schema on PostgreSQL. Not truncation —
 * a truncation path would have to know the foreign-key order of every table added from here on, and
 * would have to restore the seeded reference rows that ERT-310's bounds tests deliberately corrupt.
 * A forgotten table there is a silent cross-test leak; a forgotten table here is impossible. The
 * cost is one Flyway run per test, which is the trade being made knowingly.
 *
 * **The lifecycle itself lives in [MigratedDatabase], not in this class (ERT-250).** A contract
 * suite's adapter side needs the same database and cannot extend this base, because it already
 * extends the contract it shares with the fake and Kotlin has one superclass. Holding the lifecycle
 * in an object both can own is what stops the second copy being written.
 */
abstract class RepositoryTestBase {

    private val database = MigratedDatabase()

    /** The database this test was given. Useful for a test that needs a raw JDBC connection. */
    protected val config: DatabaseConfig get() = database.config

    /** The production factory, connected. Repository adapters are constructed with it. */
    protected val factory: DatabaseFactory get() = database.factory

    @BeforeTest
    fun openMigratedDatabase() {
        database.open()
    }

    @AfterTest
    fun discardDatabase() {
        database.close()
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
        strings("select table_name from information_schema.tables where table_schema = current_schema")
            .map { it.lowercase() }
}

// ---------------------------------------------------------------------------------------------
// The lifecycle, as functions rather than private methods, so `RepositoryTestBaseTeardownTest` can
// drive one cycle by hand and inspect what a finished test leaves behind. `MigrationTest` and
// `SeedDataTest` share them too — they test the migration itself and so need a database *before*
// it is migrated, which is the one thing this base will not hand out.
// ---------------------------------------------------------------------------------------------

/**
 * One migrated database, opened and discarded on demand.
 *
 * Extracted from [RepositoryTestBase] by ERT-250 so that a contract suite's adapter side can hold
 * one as a field. That side extends the contract it shares with the fake, so it cannot also extend
 * the base — and a second hand-written copy of this lifecycle is exactly the drift ERT-250 exists to
 * stop. The three steps in [close] are each reclaiming something: see [discard] and the teardown
 * test beside it.
 */
internal class MigratedDatabase {

    private lateinit var openConfig: DatabaseConfig
    private lateinit var openFactory: DatabaseFactory

    val config: DatabaseConfig get() = openConfig
    val factory: DatabaseFactory get() = openFactory

    fun open() {
        openConfig = freshDatabase()

        // Assigned only once connected, so `isInitialized` in [close] means "there is something to
        // tear down" rather than "the constructor ran".
        val connected = DatabaseFactory(openConfig)
        connected.connect()
        openFactory = connected

        migrate(openConfig)
    }

    fun close() {
        if (!::openFactory.isInitialized) return

        TransactionManager.closeAndUnregister(openFactory.database)
        openFactory.close()
        discard(openConfig)
    }
}

internal val projectDir: File
    get() = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "module.yaml").exists() }

/**
 * Which engine the repository suite runs against (ERT-260).
 *
 * **Deliberately NOT `DATABASE_URL`, and that is the whole point of the name.** `DATABASE_URL` is
 * the production variable `DatabaseConfig.fromEnvironment()` reads, and Amper's test JVM inherits
 * the ambient environment — so setting it would silently repoint every `testApplication` test in the
 * suite at the CI database. Eleven files call `rootModule()`, which connects, migrates, and runs the
 * HR bootstrap; `ServerTest`'s
 * `application startup - no DATABASE_URL set - connects to the in-memory default` would keep
 * passing while testing something else entirely. A test-only name shares no namespace with
 * production and `fromEnvironment` never sees it.
 *
 * H2 stays the default, so a local run and a developer with no Docker are unchanged. The PostgreSQL
 * job is a second opinion, not a replacement — exactly the trade ERT-240 was protecting.
 */
internal object TestEngine {

    const val URL_PROPERTY = "ert.test.database.url"
    const val URL_VARIABLE = "ERT_TEST_DATABASE_URL"

    /** `takeUnless(isBlank)` for the reason every reader in `src/` uses it: `?:` catches null, not "". */
    val configuredUrl: String?
        get() = (System.getProperty(URL_PROPERTY) ?: System.getenv(URL_VARIABLE))
            ?.takeUnless(String::isBlank)

    val isPostgres: Boolean get() = configuredUrl?.startsWith("jdbc:postgresql") == true

    val user: String get() = System.getenv("ERT_TEST_DATABASE_USER")?.takeUnless(String::isBlank) ?: "ert"

    val password: String
        get() = System.getenv("ERT_TEST_DATABASE_PASSWORD")?.takeUnless(String::isBlank) ?: "ert"
}

/**
 * A database no other test has a name for.
 *
 * On H2 that is a brand-new in-memory database; `DB_CLOSE_DELAY=-1` keeps it alive while the pool
 * has no open connection — and keeps it alive after the pool closes too, which is why [discard]
 * exists.
 *
 * **On PostgreSQL it is a brand-new schema in one shared database**, because `CREATE DATABASE` takes
 * a lock on the template and needs a second connection to a maintenance database, while
 * `CREATE SCHEMA` is cheap. It is the same structural promise — a namespace nothing else has a name
 * for, dropped whole at teardown — so [RepositoryTestBase]'s isolation argument carries over
 * unchanged rather than being weakened into truncation.
 */
internal fun freshDatabase(): DatabaseConfig {
    val configured = TestEngine.configuredUrl
        ?: return DatabaseConfig(
            url = "jdbc:h2:mem:ert_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            user = "sa",
            password = "",
            driverClassName = "org.h2.Driver",
            maxPoolSize = 2,
        )

    val schema = "ert_test_" + UUID.randomUUID().toString().replace("-", "")
    val separator = if ("?" in configured) "&" else "?"

    // Created before the pool connects. Flyway's `schemas()` would create it too, but the pool is
    // opened first and a `search_path` naming a schema that does not exist yet is a trap worth not
    // setting.
    DriverManager.getConnection(configured, TestEngine.user, TestEngine.password).use { connection ->
        connection.createStatement().use { it.execute("create schema \"$schema\"") }
    }

    return DatabaseConfig(
        url = "$configured${separator}currentSchema=$schema",
        user = TestEngine.user,
        password = TestEngine.password,
        driverClassName = "org.postgresql.Driver",
        maxPoolSize = 2,
    )
}

/** The per-test schema this config addresses, read back off the URL rather than carried beside it. */
internal fun DatabaseConfig.schemaName(): String? =
    url.substringAfter("currentSchema=", "").substringBefore("&").takeUnless(String::isBlank)

/**
 * Runs the versioned SQL, seeds included.
 *
 * Duplicates the Flyway configuration in `plugin/Database.kt`, which is private to an `Application`
 * extension and so unreachable from here. ERT-200 scopes this epic to `test/`, so the duplication is
 * accepted rather than extracted. It is not silent: pointing production at a different `locations`
 * would leave these tests finding no migrations at all, and every repository test would fail.
 */
internal fun migrate(config: DatabaseConfig, target: String? = null) = Flyway.configure()
    .dataSource(config.url, config.user, config.password)
    .locations("classpath:db/migration")
    // On PostgreSQL every test owns a schema, and Flyway has to put `flyway_schema_history` and the
    // unqualified `create table`s in V1 into that one rather than into `public`. No-op on H2.
    .apply { config.schemaName()?.let { schemas(it) } }
    // `target` stops the chain at a version, so a test can reach the state a migration is ABOUT to
    // be applied to. Every `MigrationTest` before ERT-260 ran against a fresh database, which is
    // why a migration that destroys data could ship green (HAR-05).
    .apply { if (target != null) target(target) }
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
    val schema = config.schemaName()

    DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
        connection.createStatement().use { statement ->
            if (schema == null) {
                statement.execute("SHUTDOWN")
            } else {
                // The PostgreSQL half of the same job. `cascade` because the schema holds every
                // table the migrations created plus `flyway_schema_history`, and dropping them in
                // foreign-key order is the bookkeeping this design exists to avoid.
                statement.execute("drop schema \"$schema\" cascade")
            }
        }
    }
}

/** The tables a database currently holds, read without Exposed — for asserting on a discarded one. */
internal fun tableNamesVia(config: DatabaseConfig): List<String> =
    DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select table_name from information_schema.tables where table_schema = current_schema"
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1).lowercase()) }
            }
        }
    }
