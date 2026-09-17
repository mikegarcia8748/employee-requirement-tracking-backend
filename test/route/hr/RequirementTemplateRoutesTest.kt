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
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.authenticatedAs
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.ktor.server.auth.authenticate
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * `GET /api/requirement-templates` — the first HR route (ERT-340).
 *
 * **The gap this file recorded is closed (ERT-190).** It used to say that no test could authenticate
 * successfully, because `configureSecurity` signed with a fresh random key when `JWT_SECRET` was
 * unset — so a token could only be minted against a second, test-owned provider, which would prove
 * the duplicate rather than the gate. Q4 was answered, the scheme was replaced, and
 * `testdata/HrTokens` now signs through the real `JwtIssuer` while `configureSecurity` takes the
 * config it was signed with.
 *
 * The positive case below is what that bought. The payload cases still mount the handler against a
 * fake with no security plugin at all — which is possible only because `authenticate` lives in
 * `Routing.kt` rather than in the route file, and is still the right shape for asserting a payload.
 */
class RequirementTemplateRoutesTest {

    // ── The gate ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `requirement templates - a token minted by this application - reach the handler`() =
        testApplication {
            // The half that had never been tested on any route: `authenticate(HR_AUTH)` ADMITTING a
            // valid caller. Asserted here rather than only in `AuthRoutesTest` because the gate is
            // applied in `Routing.kt`, around every HR route -- so "it works for the auth routes"
            // is not evidence that it works for this one.
            application {
                configureStatusPages()
                configureSerialization()
                configureSecurity(testJwtConfig())
                routing {
                    authenticate(HR_AUTH) {
                        requirementTemplateRoutes(FakeRequirementTemplateRepository())
                    }
                }
            }

            client.get("/api/requirement-templates") { authenticatedAs(anHrUser()) }
                .status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `requirement templates - a caller who must change their password - is refused`() =
        testApplication {
            // ERT-1245. `AuthGates.kt` says "Every HR route calls this", and this one did not -- it
            // called the repository straight through. So the bootstrap admin before first sign-in,
            // and anyone whose password an admin has just reset, could read the catalogue. The data
            // is low-sensitivity; the gap between the stated control and the applied one is not.
            application {
                configureStatusPages()
                configureSerialization()
                configureSecurity(testJwtConfig())
                routing {
                    authenticate(HR_AUTH) {
                        requirementTemplateRoutes(FakeRequirementTemplateRepository())
                    }
                }
            }

            val response = client.get("/api/requirement-templates") {
                authenticatedAs(anHrUser(passwordChangeRequired = true))
            }

            response.status shouldBe HttpStatusCode.Conflict
            response.bodyAsText() shouldContain "password_change_required"
        }

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

            val response = client.get("/api/requirement-templates") { authenticatedAs(anHrUser()) }

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

            val body = client.get("/api/requirement-templates") { authenticatedAs(anHrUser()) }.bodyAsText()

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

        val body = client.get("/api/requirement-templates") { authenticatedAs(anHrUser()) }.bodyAsText()

        body shouldContain "Live"
        body shouldNotContain "Retired"
    }

    @Test
    fun `requirement templates - an empty catalogue - returns 200 with an empty list`() = testApplication {
        // Never a 404: an empty result is a fact about the catalogue, not a missing resource.
        withCatalogue(FakeRequirementTemplateRepository())

        val response = client.get("/api/requirement-templates") { authenticatedAs(anHrUser()) }

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

        client.get("/api/requirement-templates") { authenticatedAs(anHrUser()) }

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
    /**
     * The payload harness.
     *
     * It used to mount the handler with no security plugin at all, which was possible while
     * `authenticate` was the only gate and it lived in `Routing.kt`. ERT-1245 put `hrUserOrRefuse()`
     * inside the handler — correctly, since that gate is per-handler — so a payload case now needs a
     * caller. The cost is this block; the benefit is that a payload assertion is made against the
     * same wiring the route actually runs under.
     */
    private fun ApplicationTestBuilder.withCatalogue(catalogue: FakeRequirementTemplateRepository) =
        application {
            configureStatusPages()
            configureSerialization()
            configureSecurity(testJwtConfig())
            routing { authenticate(HR_AUTH) { requirementTemplateRoutes(catalogue) } }
        }
}
