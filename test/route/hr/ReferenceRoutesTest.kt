package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.testdata.aDepartment
import com.pgsystem.employee.requirement.tracker.testdata.anEmploymentType
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.authenticatedAs
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.ktor.server.auth.authenticate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * `GET /api/departments` and `GET /api/employment-types` (ERT-350).
 *
 * Same split as `RequirementTemplateRoutesTest`: the refusal case mounts the real `rootModule()` and
 * the payload cases mount the handler against a fake with no security plugin, which keeps a payload
 * assertion about the payload.
 *
 * The note that used to sit here — that no test could mint a token this application accepts — is
 * obsolete since ERT-190 replaced the Q4 placeholder. `RequirementTemplateRoutesTest` carries the
 * positive gate case for the pair, since both routes mount inside the same `authenticate` block.
 */
class ReferenceRoutesTest {

    @Test
    fun `reference data - no credentials - is refused`() = testApplication {
        application { rootModule() }

        client.get("/api/departments").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/employment-types").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `reference data - a caller who must change their password - is refused`() = testApplication {
        // ERT-1245, and the comment in ReferenceRoutes.kt is why both handlers were missed together:
        // it claimed "the gate is applied once, around every HR route". True of `authenticate`, false
        // of the password gate, which is per-handler. A reader who trusted it wrote the next route
        // the same way -- which is exactly what happened.
        application {
            configureStatusPages()
            configureSerialization()
            configureSecurity(testJwtConfig())
            routing {
                authenticate(HR_AUTH) {
                    referenceRoutes(FakeReferenceDataRepository())
                }
            }
        }

        val mustChange = anHrUser(passwordChangeRequired = true)

        client.get("/api/departments") { authenticatedAs(mustChange) }
            .status shouldBe HttpStatusCode.Conflict
        client.get("/api/employment-types") { authenticatedAs(mustChange) }
            .status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `reference data - a caller in good standing - is served`() = testApplication {
        application {
            configureStatusPages()
            configureSerialization()
            configureSecurity(testJwtConfig())
            routing {
                authenticate(HR_AUTH) {
                    referenceRoutes(FakeReferenceDataRepository())
                }
            }
        }

        client.get("/api/departments") { authenticatedAs(anHrUser()) }
            .status shouldBe HttpStatusCode.OK
        client.get("/api/employment-types") { authenticatedAs(anHrUser()) }
            .status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `reference data - an authenticated caller - returns the departments in the order the port gave them`() =
        testApplication {
            // Ordering is the port's job and is asserted against SQL and the fake together by
            // `ReferenceDataRepositoryContract`; what matters here is that the route does not
            // re-sort or re-shape what it was handed.
            //
            // HAR-20 is why this reads against the shared fake: the local one it replaced did not
            // sort, so "in name order" was satisfied by the seed rather than by the port, and the
            // test could not be read as evidence about ordering at all.
            //
            // The rows are seeded in REVERSE name order, with ids ascending opposite to the names,
            // and that is for the reader rather than for coverage. Measured, not assumed: seeding
            // them already in name order catches the same route mutations, because the fake sorts
            // before the route sees anything and the handler is handed an identical list either way.
            // What the reversal buys is that input and output visibly differ, so the ordering is
            // demonstrably the port's and not the seed's — which is the confusion HAR-20 was.
            withReference(
                FakeReferenceDataRepository(
                    departments = listOf(
                        aDepartment(id = entityId("d00000000001"), name = "Unassigned"),
                        aDepartment(id = entityId("d00000000003"), name = "Accounting"),
                    ),
                )
            )

            val response = client.get("/api/departments") { authenticatedAs(anHrUser()) }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe
                """{"result":"success","data":[""" +
                """{"id":"d00000000003","name":"Accounting"},""" +
                """{"id":"d00000000001","name":"Unassigned"}""" +
                """],"meta":{"total":2}}"""
        }

    @Test
    fun `reference data - an authenticated caller - returns the employment types`() = testApplication {
        withReference(
            FakeReferenceDataRepository(
                employmentTypes = listOf(anEmploymentType(id = entityId("e00000000001"), name = "Regular")),
            )
        )

        client.get("/api/employment-types") { authenticatedAs(anHrUser()) }.bodyAsText() shouldBe
            """{"result":"success","data":[{"id":"e00000000001","name":"Regular"}],"meta":{"total":1}}"""
    }

    @Test
    fun `reference data - an empty department list - returns 200 with an empty list`() = testApplication {
        withReference(FakeReferenceDataRepository())

        val response = client.get("/api/departments") { authenticatedAs(anHrUser()) }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"result":"success","data":[],"meta":{"total":0}}"""
    }

    @Test
    fun `api docs - the reference routes are mounted - appear in the generated spec with their schemas`() =
        testApplication {
            application { rootModule() }

            val spec = client.get("/swagger/documentation.yaml").bodyAsText()

            spec shouldContain "/api/departments"
            spec shouldContain "/api/employment-types"
            // As in ERT-340: the two lines above pass against an operation with no body type, so a
            // field name from the DTO is what proves the schema was published.
            spec shouldContain "DepartmentDto"
        }

    /**
     * The payload harness.
     *
     * It used to mount the handlers with no security plugin at all, which was possible while
     * `authenticate` was the only gate and it lived in `Routing.kt`. ERT-1245 put `hrUserOrRefuse()`
     * inside each handler — correctly, since that gate is per-handler — so a payload case now needs a
     * caller. The cost is this block; the benefit is that a payload assertion is made against the
     * same wiring the route actually runs under.
     */
    private fun ApplicationTestBuilder.withReference(reference: ReferenceDataRepository) = application {
        configureStatusPages()
        configureSerialization()
        configureSecurity(testJwtConfig())
        routing { authenticate(HR_AUTH) { referenceRoutes(reference) } }
    }

}
