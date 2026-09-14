package com.pgsystem.employee.requirement.tracker

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * The OpenAPI surface.
 *
 * Because the spec is derived from the live route tree rather than a checked-in file, these tests
 * assert plumbing rather than content: the endpoints resolve, and a route that exists shows up in
 * the spec. Drift between code and docs is not possible by construction, which is the point of
 * generating it.
 */
class ApiDocsTest {

    @Test
    fun `api docs - spec endpoint is served - it describes the routes actually mounted`() = testApplication {
        application { rootModule() }

        val response = client.get("/openapi")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "/health"
    }

    @Test
    fun `api docs - swagger ui is served in dev - the page renders`() = testApplication {
        application { rootModule() }

        val response = client.get("/swagger")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "swagger"
    }
}
