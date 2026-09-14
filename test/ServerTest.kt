package com.pgsystem.employee.requirement.tracker

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.getKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wiring only.
 *
 * Route tests cover that the application assembles, that a request reaches a handler, and that the
 * status code and shape are right. Business decisions are tested in their use cases, where they run
 * in milliseconds without a server.
 *
 * `rootModule()` installs Koin into the **global** context and Koin's own `ApplicationStopping`
 * handler stops it, so these tests are order-independent but **not parallel-safe**. JUnit 5's
 * default sequential execution is required; do not enable `junit.jupiter.execution.parallel`.
 */
class ServerTest {

    @Test
    fun `application assembles and health responds`() = testApplication {
        application { rootModule() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"UP\""))
    }

    @Test
    fun `generator demo endpoints are gone`() = testApplication {
        application { rootModule() }

        listOf("/users/1", "/cities/1", "/session/increment", "/json/kotlinx-serialization").forEach { path ->
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, "expected $path to be removed")
        }
    }

    @Test
    fun `application startup - no DATABASE_URL set - connects to the in-memory default and health responds`() =
        testApplication {
            application { rootModule() }

            client.get("/health").status shouldBe HttpStatusCode.OK

            // The statement is not decoration. An unconnected factory throws
            // UninitializedPropertyAccessException reading `database`; a connected-but-broken pool
            // throws when the connection is acquired. Exposed opens that connection lazily, so an
            // empty transaction body would prove neither.
            application.getKoin().get<DatabaseFactory>().transaction { exec("SELECT 1") }
        }

    @Test
    fun `application shutdown - server stops - the connection pool is closed`() {
        // Block body, not `= runBlocking { }`: a @Test method that returns a value is silently
        // skipped by JUnit 5 with only a discovery warning, which is indistinguishable from
        // passing. The last expression here is an assertion, so the expression form would return
        // a String and this test would never run.
        runBlocking {
            // `testApplication { }` calls stop() only after its block returns, so the shutdown is
            // unobservable from inside it. TestApplication drives the same EmbeddedServer by hand.
            val server = TestApplication { application { rootModule() } }
            server.start()

            val factory = server.application.getKoin().get<DatabaseFactory>()
            factory.transaction { exec("SELECT 1") }

            server.stop()

            val failure = assertFailsWith<Exception> { factory.transaction { exec("SELECT 1") } }
            failure.stackTraceToString() shouldContain "has been closed"
        }
    }
}
