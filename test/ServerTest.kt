package com.pgsystem.employee.requirement.tracker

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wiring only.
 *
 * Route tests cover that the application assembles, that a request reaches a handler, and that the
 * status code and shape are right. Business decisions are tested in their use cases, where they run
 * in milliseconds without a server.
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
}
