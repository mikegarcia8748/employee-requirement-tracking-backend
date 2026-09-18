package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.crypto.HmacTokenDigest
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHireUseCase
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedTokenGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aDepartment
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementTemplate
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anEmploymentType
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.authenticatedAs
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeEmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeNotifier
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeUploadLinkRepository
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * `POST /api/employees` (ERT-450, PRD §8.1, Appendix B).
 *
 * **Wiring, status codes and serialization — never a decision.** Every rule exercised below is
 * already proven exhaustively in `CreateHireUseCaseTest`; what these assert is that the route
 * reaches the rule, and that the sealed result lands on the right status with the right body. A test
 * here that could only be made green by changing a business rule would mean the route grew one.
 *
 * The use case is built from fakes with no container and no database, the way `UserRoutesTest` does
 * it — three plugins, and the route function called directly.
 *
 * **Tokens are issued at `Instant.now()`, not at `FixedClock.DEFAULT`.** `authenticatedAs` handles
 * that; it is noted because the clock injected into the use case below *is* fixed, and the two
 * living side by side is the one documented boundary of the fixed-clock rule — a JWT's `exp` is
 * checked against the real system clock, which no injected `Clock` reaches.
 */
class EmployeeRoutesTest {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val personIds = FixedPersonIdGenerator("NEWHIRE1")
    private val audit = FakeAuditLog()
    private val employees = FakeEmployeeRepository(ids = personIds)
    private val uploadLinks = FakeUploadLinkRepository()
    private val notifier = FakeNotifier()
    private val appSettings = FakeAppSettingsRepository()

    private val reference = FakeReferenceDataRepository(
        departments = listOf(aDepartment(id = Fixtures.DEPARTMENT_ID)),
        employmentTypes = listOf(anEmploymentType(id = Fixtures.EMPLOYMENT_TYPE_ID)),
    )

    /**
     * Two templates, arranged so that no accident produces the right answer.
     *
     * Catalogue order is `sortOrder`, which here says **NBI then Birth** — while the names say Birth
     * then NBI, the ids say Birth(`…001`) then NBI(`…003`), and they are seeded in that same id
     * order. So a route that re-sorted by name, by id, or leaned on the order the fake happened to
     * hold them in names a different sequence than the rule does.
     *
     * The first draft of this file had all three orders agreeing, which is the arrangement ERT-320,
     * ERT-350, ERT-410 and ERT-420 each shipped without — and which this branch had just finished
     * documenting one level up, in HAR-20.
     *
     * The optional one is not decoration either: it makes "the requirement set is rendered"
     * distinguishable from "the required ones are", and it is what would catch a DTO quietly
     * filtering on `isRequired`. It is deliberately the one that sorts **first**.
     */
    private val templates = FakeRequirementTemplateRepository().givenAssigned(
        Fixtures.EMPLOYMENT_TYPE_ID,
        aRequirementTemplate(id = entityId("TPL000000001"), name = "Birth certificate", sortOrder = 2),
        aRequirementTemplate(
            id = entityId("TPL000000003"),
            name = "NBI clearance",
            sortOrder = 1,
            isRequired = false,
        ),
    )

    private val officer = anHrUser()

    // ── The happy path ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `create hire - a valid body - returns 201 with the hire id`() = testApplication {
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }

        response.status shouldBe HttpStatusCode.Created
        response.bodyAsText() shouldContain """"result":"success""""
        // The id the repository STORED, not the one that was drawn -- ERT-431's rule, asserted here
        // because the route is what publishes it.
        response.bodyAsText() shouldContain """"id":"NEWHIRE1""""
        employees.created.single().packetStatus shouldBe PacketStatus.DRAFT_COLLECTING
    }

    @Test
    fun `create hire - a valid body - returns the requirement set snapshotted from the catalogue`() =
        testApplication {
            // ERT-432 proves the snapshot; what this proves is that the route publishes it rather
            // than making a second repository call for it, which the dependency rule forbids.
            mountEmployees()

            val body = client.post("/api/employees") {
                authenticatedAs(officer)
                contentType(ContentType.Application.Json)
                setBody(createBody())
            }.bodyAsText()

            body shouldContain """"name":"Birth certificate""""
            body shouldContain """"name":"NBI clearance""""

            // Catalogue order, which here is neither name order nor id order -- see the fixture.
            withClue("the requirement set must reach the wire in sortOrder, not name or id order") {
                (body.indexOf("NBI clearance") < body.indexOf("Birth certificate")) shouldBe true
            }

            // The optional one is present rather than filtered away.
            body shouldContain """"isRequired":false"""
            body shouldContain """"isRequired":true"""
        }

    @Test
    fun `create hire - a valid body - reports the invitation as queued rather than sent`() = testApplication {
        // ERT-434: `Sent` means durably queued. A body claiming the invitation arrived would be
        // false for every hire created before ERT-1010 drains the outbox.
        mountEmployees()

        val body = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }.bodyAsText()

        body shouldContain """"invitation":{"status":"QUEUED"}"""
        body shouldNotContain "SENT"
    }

    @Test
    fun `create hire - a body with no middle initial - is accepted and the key is absent from the response`() =
        testApplication {
            // Found by the review step: every other test sends one, so nothing exercised the
            // optional field in either direction. Two properties, and the second is the one a
            // serializer setting could silently change -- `explicitNulls = false` means an absent
            // value is an absent KEY, not `"middleInitial":null`, and a client destructuring the
            // response sees undefined rather than null.
            mountEmployees()

            val response = client.post("/api/employees") {
                authenticatedAs(officer)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"firstName":"Maria","lastName":"Santos","departmentId":"${Fixtures.DEPARTMENT_ID.value}",""" +
                        """"position":"Store Associate","employmentTypeId":"${Fixtures.EMPLOYMENT_TYPE_ID.value}",""" +
                        """"email":"maria.santos@example.com"}"""
                )
            }

            response.status shouldBe HttpStatusCode.Created
            response.bodyAsText() shouldNotContain "middleInitial"
            employees.created.single().middleInitial shouldBe null
        }

    @Test
    fun `create hire - a body missing a required field - is 422 rather than a 500`() = testApplication {
        // ERT-146's lesson, pointed at the newest body-reading route: an unreadable body must be a
        // client error, not an unhandled exception. `firstName` has no default, so this is the one
        // shape `ignoreUnknownKeys` cannot paper over -- and `StatusPages` is what has to catch it,
        // which is why this route deliberately wraps nothing itself.
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody("""{"lastName":"Santos","email":"maria.santos@example.com"}""")
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "request_malformed"
        // Nothing was written by a request that never reached the use case.
        employees.created.isEmpty() shouldBe true
    }

    // ── The refusals, all 422 ───────────────────────────────────────────────────────────────────

    @Test
    fun `create hire - an invalid email - returns 422 naming the field`() = testApplication {
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody(email = "not-an-address"))
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain """"field":"email""""
        response.bodyAsText() shouldContain "email.invalid_format"
    }

    @Test
    fun `create hire - a duplicate email with no reason - returns 422 naming the duplicate reason field`() =
        testApplication {
            // C1 and C24 together: 422 rather than 409, and the `details` entry names
            // `duplicateReason` -- the field on the request body a form has to fill -- rather than
            // `reason`, which named nothing a client could bind to.
            employees.given(
                anEmployee(id = Fixtures.EMPLOYEE_ID, email = anEmail("maria.santos@example.com"))
            )
            mountEmployees()

            val response = client.post("/api/employees") {
                authenticatedAs(officer)
                contentType(ContentType.Application.Json)
                setBody(createBody(email = "maria.santos@example.com"))
            }

            response.status shouldBe HttpStatusCode.UnprocessableEntity
            response.bodyAsText() shouldContain "duplicate_email.reason_required"
            response.bodyAsText() shouldContain """"field":"duplicateReason""""
            response.bodyAsText() shouldNotContain """"field":"reason""""
            // The action is server-side context and stays off the wire.
            response.bodyAsText() shouldNotContain "create_hire"
        }

    @Test
    fun `create hire - a duplicate email with a typed reason that has no spaces - is created rather than a 500`() =
        testApplication {
            // C25, and this route is what made it reachable. The reason is written to the audit
            // trail, where the credential guard's value tripwire used to refuse 32+ characters of
            // mixed-case base64url -- which a plausible reason with no spaces satisfies. It threw
            // out of `record` AFTER the hire was written, so HR saw a 500 for a hire that existed.
            employees.given(
                anEmployee(id = Fixtures.EMPLOYEE_ID, email = anEmail("maria.santos@example.com"))
            )
            mountEmployees()

            val response = client.post("/api/employees") {
                authenticatedAs(officer)
                contentType(ContentType.Application.Json)
                setBody(
                    createBody(
                        email = "maria.santos@example.com",
                        duplicateReason = "ReplacingRecord2026ForJoseDelaCruz",
                    )
                )
            }

            response.status shouldBe HttpStatusCode.Created
            response.bodyAsText() shouldContain "SHARED_EMAIL"
        }

    @Test
    fun `create hire - an unknown department - returns 422 naming which id`() = testApplication {
        // E8: a body field's failure is a validation error naming that field, never a 404. Two
        // codes rather than one, because both ids are 12-character EntityIds and a single code
        // could not tell HR which picker to fix.
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody(departmentId = "DPT000000099"))
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "department_unknown"
        response.bodyAsText() shouldContain """"field":"departmentId""""
    }

    @Test
    fun `create hire - a department id supplied as the employment type - returns 422 naming the employment type`() =
        testApplication {
            // The two existence checks read different tables and their bodies differ by one
            // identifier, so a copied body would accept this. Asserted at the route because this is
            // the layer a client's mistake actually arrives at.
            mountEmployees()

            val response = client.post("/api/employees") {
                authenticatedAs(officer)
                contentType(ContentType.Application.Json)
                setBody(createBody(employmentTypeId = Fixtures.DEPARTMENT_ID.value))
            }

            response.status shouldBe HttpStatusCode.UnprocessableEntity
            response.bodyAsText() shouldContain "employment_type_unknown"
            response.bodyAsText() shouldContain """"field":"employmentTypeId""""
        }

    // ── Delivery failure is still a 201 ─────────────────────────────────────────────────────────

    @Test
    fun `create hire - delivery failed - returns 201 carrying a failure indicator`() = testApplication {
        // §8.1's story: HR must be able to tell "the invitation did not go out" from "the hire did
        // not save". The hire saved; only the invitation failed, and the 201 says which.
        notifier.failNextSend("outbox insert failed")
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }

        response.status shouldBe HttpStatusCode.Created
        response.bodyAsText() shouldContain """"status":"FAILED""""
        response.bodyAsText() shouldContain "outbox insert failed"
        // The hire exists, which is the half a status code alone cannot say.
        employees.created.single().email.value shouldBe "maria.santos@example.com"
    }

    // ── The credential rule ─────────────────────────────────────────────────────────────────────

    @Test
    fun `create hire - any response - carries no pin and no plaintext token`() = testApplication {
        // The token travels in the invitation email and nowhere else. This is structural today --
        // `HireCreated` has no plaintext field to leak -- and the point of the test is that it stays
        // structural: the token the notifier was handed is read back out of the fake and looked for
        // in the body, so publishing it later fails here rather than in production.
        mountEmployees()

        val body = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }.bodyAsText()

        val issuedToken = notifier.attempts
            .map { it.notification }
            .filterIsInstance<FakeNotifier.Sent.Invitation>()
            .single()
            .linkToken

        issuedToken.isNotBlank() shouldBe true
        body shouldNotContain issuedToken
        body shouldNotContain "token"
        body shouldNotContain "pin"
        // The digest is not a credential, but it has no business on the wire either.
        body shouldNotContain uploadLinks.saved.single().tokenHash
    }

    @Test
    fun `create hire - no credentials - is refused`() = testApplication {
        application { rootModule() }

        val response = client.post("/api/employees") {
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `create hire - a caller who must change their password - is refused`() = testApplication {
        // ERT-1245's gate is per-handler, and a new route is exactly where it gets forgotten.
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(anHrUser(passwordChangeRequired = true))
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }

        response.status shouldBe HttpStatusCode.Conflict
        response.bodyAsText() shouldContain "password_change_required"
    }

    @Test
    fun `create hire - a token minted by this application - reaches the handler`() = testApplication {
        // The positive gate case: a real token, through the real security plugin, arrives as a
        // principal the handler can name -- and the hire it creates is attributed to that caller
        // rather than to anything the body supplied.
        mountEmployees()

        val response = client.post("/api/employees") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }

        response.status shouldBe HttpStatusCode.Created
        employees.created.single().createdBy shouldBe officer.id
    }

    // ── The spec ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `api docs - the employee route is mounted - publishes its request as well as its response schema`() =
        testApplication {
            application { rootModule() }

            val spec = client.get("/swagger/documentation.yaml").bodyAsText()

            spec shouldContain "/api/employees"
            // ERT-146: the path alone passes against an operation with no body type, so the schema
            // names are what prove both halves were published.
            spec shouldContain "CreateHireRequest"
            spec shouldContain "HireCreatedDto"
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun ApplicationTestBuilder.mountEmployees() = application {
        configureStatusPages()
        configureSerialization()
        configureSecurity(testJwtConfig())

        routing {
            authenticate(HR_AUTH) {
                employeeRoutes(
                    CreateHireUseCase(
                        employees = employees,
                        reference = reference,
                        templates = templates,
                        uploadLinks = uploadLinks,
                        tokenGenerator = FixedTokenGenerator(),
                        tokenDigest = HmacTokenDigest("this-is-a-very-long-and-secure-test-pepper-32-chars"),
                        appSettings = appSettings,
                        notifier = notifier,
                        audit = audit,
                        clock = clock,
                        ids = ids,
                        personIds = personIds,
                        tracer = NoOpUseCaseTracer,
                    )
                )
            }
        }
    }

    private fun createBody(
        email: String = "maria.santos@example.com",
        departmentId: String = Fixtures.DEPARTMENT_ID.value,
        employmentTypeId: String = Fixtures.EMPLOYMENT_TYPE_ID.value,
        duplicateReason: String? = null,
    ): String {
        val reason = duplicateReason?.let { ""","duplicateReason":"$it"""" } ?: ""
        return """{"firstName":"Maria","middleInitial":"L","lastName":"Santos",""" +
            """"departmentId":"$departmentId","position":"Store Associate",""" +
            """"employmentTypeId":"$employmentTypeId","email":"$email"$reason}"""
    }
}
