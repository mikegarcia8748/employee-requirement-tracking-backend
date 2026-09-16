package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.domain.model.Department
import com.pgsystem.employee.requirement.tracker.domain.model.EmploymentType
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.testdata.aDepartment
import com.pgsystem.employee.requirement.tracker.testdata.anEmploymentType
import com.pgsystem.employee.requirement.tracker.testdata.entityId
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
 * Same split as `RequirementTemplateRoutesTest` and for the same reason: the refusal case mounts
 * the real `rootModule()`, the payload cases mount the handler against a fake with no security
 * plugin, because no test can mint a token this application accepts while the Q4 placeholder stands.
 */
class ReferenceRoutesTest {

    @Test
    fun `reference data - no credentials - is refused`() = testApplication {
        application { rootModule() }

        client.get("/api/departments").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/employment-types").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `reference data - an authenticated caller - returns the departments in the order given`() =
        testApplication {
            // Ordering is the adapter's job and is asserted against SQL; what matters here is that
            // the route does not re-sort or re-shape what it was handed.
            withReference(
                FakeReferenceData(
                    departments = listOf(
                        aDepartment(id = entityId("d00000000003"), name = "Accounting"),
                        aDepartment(id = entityId("d00000000001"), name = "Unassigned"),
                    ),
                )
            )

            val response = client.get("/api/departments")

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
            FakeReferenceData(
                employmentTypes = listOf(anEmploymentType(id = entityId("e00000000001"), name = "Regular")),
            )
        )

        client.get("/api/employment-types").bodyAsText() shouldBe
            """{"result":"success","data":[{"id":"e00000000001","name":"Regular"}],"meta":{"total":1}}"""
    }

    @Test
    fun `reference data - an empty department list - returns 200 with an empty list`() = testApplication {
        withReference(FakeReferenceData())

        val response = client.get("/api/departments")

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

    private fun ApplicationTestBuilder.withReference(reference: ReferenceDataRepository) = application {
        configureStatusPages()
        configureSerialization()
        routing { referenceRoutes(reference) }
    }

    /**
     * Local rather than in `testdata/fake/`: the shared fakes exist for use-case tests, and there is
     * no use case for this port yet. ERT-430 is where a `FakeReferenceDataRepository` earns its
     * place, with the `given`/arrange surface the others have.
     */
    private class FakeReferenceData(
        private val departments: List<Department> = emptyList(),
        private val employmentTypes: List<EmploymentType> = emptyList(),
    ) : ReferenceDataRepository {
        override suspend fun findDepartments(): List<Department> = departments
        override suspend fun findEmploymentTypes(): List<EmploymentType> = employmentTypes
        override suspend fun departmentExists(id: EntityId): Boolean = departments.any { it.id == id }
        override suspend fun employmentTypeExists(id: EntityId): Boolean = employmentTypes.any { it.id == id }
    }
}
