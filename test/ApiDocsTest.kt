package com.pgsystem.employee.requirement.tracker

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureApiDocs
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * The OpenAPI surface.
 *
 * Because the spec is derived from the live route tree rather than a checked-in file, these tests
 * assert plumbing rather than content: the endpoints resolve, and a route that exists shows up in
 * the spec. Drift between code and docs is not possible by construction, which is the point of
 * generating it.
 *
 * The outside-dev tests mount [configureApiDocs] directly with `devMode = false`, because a JVM test
 * cannot unset an environment variable in its own process — the same reason the plugin now takes the
 * flag as a parameter rather than reading it, which is the rule `configureRouting(authName)` and
 * `configureSecurity(jwt)` already follow.
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

    @Test
    fun `api docs - outside dev - does not mount the generated html reference`() = testApplication {
        // `openAPI()` *is* swagger-codegen: mounting it runs the generator and writes HTML to a
        // relative path at every boot. In a container that is a filesystem write from a non-root
        // user, on the cold-start path, into a tmpfs charged against the memory limit, producing
        // output nobody reads -- and Ktor itself warns the result is incomplete for OpenAPI 3.1,
        // which this spec is. Outside dev the route is not mounted at all.
        application {
            configureSecurity(testJwtConfig())
            configureApiDocs(devMode = false)
        }

        client.get("/openapi").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `api docs - outside dev - still serves the interactive ui behind hr authentication`() = testApplication {
        // Dropping the pre-rendered mirror must not drop the documentation. Swagger UI renders the
        // same spec, interactively and without a generator, so the surface an HR user needs survives.
        application {
            configureSecurity(testJwtConfig())
            configureApiDocs(devMode = false)
        }

        client.get("/swagger").status shouldBe HttpStatusCode.Unauthorized
        client.get("/swagger/documentation.yaml").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `api docs - in dev - serves the generated html reference as before`() = testApplication {
        application {
            configureSecurity(testJwtConfig())
            configureApiDocs(devMode = true)
        }

        client.get("/openapi").status shouldBe HttpStatusCode.OK
    }
}
