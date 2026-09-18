package com.pgsystem.employee.requirement.tracker.data

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseConfig
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.Departments
import com.pgsystem.employee.requirement.tracker.data.db.table.allTables
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.DriverManager

/**
 * The base, from a subclass's seat — exactly where ERT-310 onward will sit.
 *
 * The two `writes a row` tests are a **pair**, and the pairing is the point. Each asserts the row is
 * absent and then inserts it under the same primary key, so isolation holds whichever order JUnit
 * runs them in. JUnit 5's default method order is deliberately not source order, so a one-sided test
 * would pass vacuously every time it happened to run first.
 */
class RepositoryTestBaseTest : RepositoryTestBase() {

    @Test
    fun `integration base - a fresh database - seed data from the migrations is present`() = runTest {
        // The ERT-130 seeds are migrations V2 and V3, so "migrated" and "seeded" are one step.
        countOf("departments") shouldBe 1
        countOf("employment_types") shouldBe 4
        countOf("requirement_templates") shouldBe 14
        countOf("template_assignments") shouldBe 56
        countOf("app_settings") shouldBe 9
    }

    @Test
    fun `integration base - the seed counts above - are read from a fully migrated schema`() = runTest {
        // Without this, a seed count could be satisfied by a half-applied migration. Name the
        // schema explicitly rather than inferring it from five counts that happen to be right.
        tableNames() shouldContainAll allTables.map { it.tableName.lowercase() }
    }

    @Test
    fun `integration base - a test writes a row - the next test does not see it`() = runTest {
        countOf("departments") shouldBe 1

        execute("insert into departments (id, \"name\") values ('d99999999001', 'Written by one')")

        countOf("departments") shouldBe 2
    }

    @Test
    fun `integration base - the paired test writes the same row - it likewise starts from a clean database`() =
        runTest {
            // Same id and a uniquely-indexed name: if the other test's row survived, this insert
            // fails on the primary key rather than merely miscounting.
            countOf("departments") shouldBe 1

            execute("insert into departments (id, \"name\") values ('d99999999001', 'Written by two')")

            countOf("departments") shouldBe 2
        }

    @Test
    fun `integration base - an Exposed statement - reads and writes the migrated schema`() = runTest {
        // Every repository from ERT-310 on works through the DSL against the objects in Tables.kt,
        // not through raw SQL. The drift test proves those objects *describe* the schema; nothing
        // else proves a statement built from them reaches the database this base connected.
        val names = transaction {
            Departments.insert {
                it[id] = "d99999999002"
                it[name] = "Written through the DSL"
            }
            Departments.selectAll().map { row -> row[Departments.name] }
        }

        names shouldContainExactlyInAnyOrder listOf("Unassigned", "Written through the DSL")
    }

    @Test
    fun `integration base - no engine override - is H2 in PostgreSQL mode and needs no external service`() {
        // A later edit pointing the harness at a real server BY DEFAULT fails here rather than in
        // someone's CI. ERT-260 made the engine selectable, and the default staying H2 is the half
        // of that trade worth a test: the local loop needs no Docker.
        assumeTrue(TestEngine.configuredUrl == null, "an engine override is set; see the test below")

        config.url shouldStartWith "jdbc:h2:mem:"
        config.url shouldContain "MODE=PostgreSQL"
        config.driverClassName shouldBe "org.h2.Driver"
    }

    @Test
    fun `integration base - a postgres override - runs each test in its own schema`() {
        // The mirror, so the PostgreSQL job asserts something rather than merely skipping. Schema
        // per test is what replaces "a brand-new in-memory database per test" on an engine where
        // creating a database is expensive.
        assumeTrue(TestEngine.isPostgres, "no PostgreSQL override set; H2 is the default")

        config.url shouldStartWith "jdbc:postgresql:"
        config.driverClassName shouldBe "org.postgresql.Driver"
        (config.schemaName()?.startsWith("ert_test_") == true) shouldBe true
    }

    @Test
    fun `integration base - a query - runs on the database the factory connected`() = runTest {
        // An empty transaction body would prove nothing: Exposed opens the connection lazily. The
        // statement forces it, and the returned `db` is what a reimplementation on a raw JDBC
        // connection could not produce.
        val ranOn = transaction {
            exec("select 1")
            db
        }

        ranOn shouldBe factory.database
    }
}

/**
 * What a finished test leaves behind.
 *
 * These drive the lifecycle by hand rather than extending the base, because both assertions are
 * about state that only exists *after* teardown has run. Both leaks are invisible in a green suite —
 * they cost memory across a run, not correctness in any one test — so they get tests rather than a
 * comment that a later edit can quietly falsify.
 */
class RepositoryTestBaseTeardownTest {

    @Test
    fun `integration base - a finished test - leaves no database behind`() {
        // H2 only, and deliberately so: both assertions in this class are about `DB_CLOSE_DELAY=-1`
        // and H2's in-memory registry, which have no PostgreSQL analogue. Porting them would mean
        // inventing a claim rather than checking one. The PostgreSQL half of teardown — the schema
        // is dropped — is asserted below instead.
        assumeTrue(!TestEngine.isPostgres, "H2-only: this is about DB_CLOSE_DELAY, not about SQL")

        val config = freshDatabase()
        val factory = DatabaseFactory(config)
        factory.connect()
        migrate(config)
        tableNamesVia(config) shouldContain "employees"

        TransactionManager.closeAndUnregister(factory.database)
        factory.close()
        discard(config)

        // DB_CLOSE_DELAY=-1 keeps an in-memory database alive for the whole JVM even after the last
        // connection closes, so closing the pool alone would leave the schema and its seed rows
        // resident — once per test. Reconnecting now gets a brand-new empty database instead.
        try {
            tableNamesVia(config).shouldBeEmpty()
        } finally {
            discard(config)
        }
    }

    @Test
    fun `integration base - a finished test - drops the schema it was given`() {
        // The PostgreSQL counterpart of the test above: isolation there is a schema rather than a
        // database, so "leaves nothing behind" means the schema is gone.
        assumeTrue(TestEngine.isPostgres, "no PostgreSQL override set; H2 is the default")

        val config = freshDatabase()
        val schema = config.schemaName() ?: error("a postgres config must name a schema")
        val factory = DatabaseFactory(config)
        factory.connect()
        migrate(config)
        tableNamesVia(config) shouldContain "employees"

        TransactionManager.closeAndUnregister(factory.database)
        factory.close()
        discard(config)

        schemaExists(config, schema) shouldBe false
    }

    @Test
    fun `integration base - a finished test - unregisters its database from Exposed`() {
        val config = freshDatabase()
        val factory = DatabaseFactory(config)
        factory.connect()
        val database = factory.database

        // Registered by Database.connect(), into a companion-object map nothing else prunes.
        TransactionManager.managerFor(database)

        TransactionManager.closeAndUnregister(database)
        factory.close()
        discard(config)

        assertFailsWith<IllegalStateException> { TransactionManager.managerFor(database) }
    }
}

/** Whether a schema is still present, read on a connection that does not depend on it existing. */
private fun schemaExists(config: DatabaseConfig, schema: String): Boolean =
    DriverManager.getConnection(config.url, config.user, config.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select 1 from information_schema.schemata where schema_name = '$schema'"
            ).use { it.next() }
        }
    }
