package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementTemplate
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeRequirementTemplateRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * `GET /api/requirement-templates` — the first HR route (ERT-340).
 *
 * **No test here authenticates successfully, and that is a stated gap rather than an oversight.**
 * `configureSecurity` signs with a fresh random key when `JWT_SECRET` is unset, so no test can mint
 * a token this application would accept; minting one against a second, test-owned `jwt(HR_AUTH)`
 * provider would prove the duplicate rather than the gate. So the refusal case mounts the real
 * `rootModule()` and asserts 401, and the payload cases mount the handler directly — which is
 * possible only because `authenticate` lives in `Routing.kt` rather than in the route file. The gap
 * closes with PRD 14 Q4, when the placeholder scheme is replaced.
 */
class RequirementTemplateRoutesTest {

    // ── The payload ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `requirement templates - an authenticated caller - returns active templates in sort order`() =
        testApplication {
            val catalogue = FakeRequirementTemplateRepository(
                aRequirementTemplate(id = entityId("c00000000003"), name = "Third", sortOrder = 3),
                aRequirementTemplate(id = entityId("c00000000001"), name = "First", sortOrder = 1),
                aRequirementTemplate(id = entityId("c00000000002"), name = "Second", sortOrder = 2),
            )
            withCatalogue(catalogue)

            val response = client.get("/api/requirement-templates")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe
                """{"result":"success","data":[""" +
                """{"id":"c00000000001","name":"First","instructions":"Upload a clear photo or scan of the full document.","isRequired":true,"sortOrder":1},""" +
                """{"id":"c00000000002","name":"Second","instructions":"Upload a clear photo or scan of the full document.","isRequired":true,"sortOrder":2},""" +
                """{"id":"c00000000003","name":"Third","instructions":"Upload a clear photo or scan of the full document.","isRequired":true,"sortOrder":3}""" +
                """],"meta":{"total":3}}"""
        }

    @Test
    fun `requirement templates - the response - carries no field beyond id name instructions required and sort order`() =
        testApplication {
            // The DTO's omissions are the control, not tidiness: `isActive` is always true on this
            // route and publishing it invites a client to filter on it, and the three Phase 4
            // validity fields are internals that 9.3 will change. Asserted as absence, because a
            // field added later would otherwise ship unnoticed.
            withCatalogue(
                FakeRequirementTemplateRepository(
                    aRequirementTemplate(expires = true, validityMonths = 12, renewalLeadDays = 60),
                )
            )

            val body = client.get("/api/requirement-templates").bodyAsText()

            body shouldNotContain "isActive"
            body shouldNotContain "expires"
            body shouldNotContain "validityMonths"
            body shouldNotContain "renewalLeadDays"
        }

    @Test
    fun `requirement templates - an inactive template - is not returned`() = testApplication {
        // Proves the handler calls findAll() rather than findAll(includeInactive = true). The
        // catalogue a hire is offered is the active one; the inactive rows exist for 8.11's admin
        // screen alone.
        withCatalogue(
            FakeRequirementTemplateRepository(
                aRequirementTemplate(id = entityId("c00000000001"), name = "Live", sortOrder = 1),
                aRequirementTemplate(id = entityId("c00000000002"), name = "Retired", sortOrder = 2, isActive = false),
            )
        )

        val body = client.get("/api/requirement-templates").bodyAsText()

        body shouldContain "Live"
        body shouldNotContain "Retired"
    }

    @Test
    fun `requirement templates - an empty catalogue - returns 200 with an empty list`() = testApplication {
        // Never a 404: an empty result is a fact about the catalogue, not a missing resource.
        withCatalogue(FakeRequirementTemplateRepository())

        val response = client.get("/api/requirement-templates")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"result":"success","data":[],"meta":{"total":0}}"""
    }

    @Test
    fun `requirement templates - the route - never reads a single employment type`() = testApplication {
        // Filtering by employment type is explicitly out of scope until the add-hire screen needs
        // it. A handler that reached for findActiveForEmploymentType would be making the snapshot
        // rule's mistake in the read direction.
        val catalogue = FakeRequirementTemplateRepository(aRequirementTemplate())
        withCatalogue(catalogue)

        client.get("/api/requirement-templates")

        catalogue.employmentTypeReads.shouldBeEmpty()
    }

    // ── The gate ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `requirement templates - no credentials - is refused`() = testApplication {
        application { rootModule() }

        client.get("/api/requirement-templates").status shouldBe HttpStatusCode.Unauthorized
    }

    // ── The generated spec ──────────────────────────────────────────────────────────────────────

    @Test
    fun `api docs - the requirement templates route is mounted - appears in the generated spec`() =
        testApplication {
            application { rootModule() }

            client.get("/swagger/documentation.yaml").bodyAsText() shouldContain "/api/requirement-templates"
        }

    @Test
    fun `api docs - the requirement templates response - publishes its schema rather than an untyped body`() =
        testApplication {
            // The test above passes against an operation with no body type at all, which is exactly
            // what ERT-145 found: the generator infers nothing from `call.respond`. This is the
            // first enveloped schema in the project, so whether `jsonSchema` survives
            // ApiResponse<List<T>> is unproven until a field name shows up in the document.
            application { rootModule() }

            val spec = client.get("/swagger/documentation.yaml").bodyAsText()

            spec shouldContain "sortOrder"
            spec shouldContain "isRequired"
        }

    /**
     * Mounts the handler with no security plugin.
     *
     * Possible only because `authenticate` is applied in `Routing.kt`, so the route function itself
     * is auth-agnostic. `configureStatusPages` is included so a mapping failure surfaces as the
     * envelope rather than an empty 500 body.
     */
    private fun ApplicationTestBuilder.withCatalogue(catalogue: FakeRequirementTemplateRepository) =
        application {
            configureStatusPages()
            configureSerialization()
            routing { requirementTemplateRoutes(catalogue) }
        }
}
