package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.route.mapper.orNotFound
import com.pgsystem.employee.requirement.tracker.route.mapper.respondError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test

/**
 * The one place a domain failure becomes a status code (ERT-140).
 *
 * Route-level, so it proves the wiring rather than a decision: the mapping itself is a `when` over a
 * sealed interface, which the compiler already keeps exhaustive. What cannot be proven by the
 * compiler is what actually reaches the wire — the status, the exact bytes, and the headers — and
 * that is what invariant 3 is about.
 *
 * Routes here are test-local. There are no business routes yet, and inventing one to test error
 * mapping would put a decision in a handler.
 */
class ErrorMappingTest {

    @Serializable
    private data class IdRequest(val id: String)

    /** Mounts `rootModule()` and a handful of handlers that do nothing but return a failure. */
    private fun ApplicationTestBuilder.withErrorRoutes() = application {
        rootModule()
        errorMappingTestRoutes()
    }

    @Test
    fun `error mapping - a validation failure - returns 422 naming the field`() = testApplication {
        withErrorRoutes()

        val response = client.get("/test/validation")

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "\"field\":\"email\""
        response.bodyAsText() shouldContain "\"code\":\"email.invalid_format\""
    }

    @Test
    fun `error mapping - a conflict - returns 409`() = testApplication {
        withErrorRoutes()

        val response = client.get("/test/conflict")

        response.status shouldBe HttpStatusCode.Conflict
        response.bodyAsText() shouldContain "\"code\":\"requirement_locked\""
    }

    @Test
    fun `error mapping - a not found - returns 404 carrying the code that names the entity`() = testApplication {
        withErrorRoutes()

        val response = client.get("/test/not-found")

        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText() shouldBe """{"code":"employee_not_found"}"""
    }

    @Test
    fun `error mapping - a reason required - returns 422 naming the action`() = testApplication {
        withErrorRoutes()

        val response = client.get("/test/reason-required")

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "create_hire"
    }

    @Test
    fun `denied response - wrong pin versus unknown token - the two responses are byte-identical`() = testApplication {
        // PRD 6.6 / SEC-01. Any difference at all — a byte, a header, a cookie — makes `verify` an
        // oracle for whether a link exists, and therefore for whether a person has been hired.
        withErrorRoutes()

        val wrongPin = client.get("/test/denied/wrong-pin")
        val unknownToken = client.get("/test/denied/unknown-token")

        wrongPin.status shouldBe unknownToken.status
        wrongPin.readRawBytes().toList() shouldBe unknownToken.readRawBytes().toList()
        wrongPin.comparableHeaders() shouldBe unknownToken.comparableHeaders()
    }

    @Test
    fun `denied response - the rendered body - is the bare code with no detail field`() = testApplication {
        // The mapper must not add a detail. A detail is exactly what would pull the two causes apart.
        withErrorRoutes()

        val response = client.get("/test/denied/wrong-pin")

        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText() shouldBe """{"code":"not_found"}"""
    }

    @Test
    fun `error mapping - an unmatched route - is indistinguishable from a denied response`() = testApplication {
        // A mistyped portal sub-path must not answer differently from a denied one.
        withErrorRoutes()

        val unmatched = client.get("/api/portal/nope/checklist")
        val denied = client.get("/test/denied/wrong-pin")

        unmatched.status shouldBe HttpStatusCode.NotFound
        unmatched.bodyAsText() shouldBe denied.bodyAsText()
    }

    @Test
    fun `error mapping - a mapped 404 carrying a body - is not re-wrapped by the status handler`() = testApplication {
        // Ktor's `status(...)` handlers fire on the response status. If one overwrote a body we had
        // already mapped, every NotFound would silently collapse into the Denied body and the HR
        // side would lose its code.
        withErrorRoutes()

        client.get("/test/not-found").bodyAsText() shouldBe """{"code":"employee_not_found"}"""
    }

    @Test
    fun `error mapping - a malformed id in a path - is 404 rather than 422`() = testApplication {
        // A path names a resource. An id that cannot parse names one that cannot exist, so the
        // answer must not differ from a well-formed unknown id — otherwise the endpoint enumerates.
        withErrorRoutes()

        val malformed = client.get("/test/employees/nope")
        val wellFormedUnknown = client.get("/test/employees/aB3xK9Lm")

        malformed.status shouldBe HttpStatusCode.NotFound
        malformed.bodyAsText() shouldBe wellFormedUnknown.bodyAsText()
        malformed.bodyAsText() shouldNotContain "invalid_format"
    }

    @Test
    fun `error mapping - a malformed id in a request body - is 422 naming the field`() = testApplication {
        // The other half of the same rule: where the id was read decides the status, not its shape.
        withErrorRoutes()

        val response = client.post("/test/employees") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"nope"}""")
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "\"field\":\"id\""
    }

    @Test
    fun `error mapping - a malformed json body - is 422 rather than 500`() = testApplication {
        // Before this ticket the only handler was `exception<Throwable>`, so a client sending
        // rubbish was told the server had failed.
        withErrorRoutes()

        val response = client.post("/test/employees") {
            contentType(ContentType.Application.Json)
            setBody("this is not json")
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "request.malformed"
    }

    @Test
    fun `error mapping - an unexpected throwable - leaks no detail to the client`() = testApplication {
        withErrorRoutes()

        val response = client.get("/test/boom")
        val body = response.bodyAsText()

        response.status shouldBe HttpStatusCode.InternalServerError
        body shouldBe """{"code":"internal_error","detail":"An unexpected error occurred."}"""
        body shouldNotContain "portal_access_logs"
        body shouldNotContain "org.h2"
    }

    /** Everything but `Date`, which differs between two requests for reasons nobody can exploit. */
    private fun HttpResponse.comparableHeaders(): Map<String, List<String>> =
        headers.entries()
            .filterNot { it.key.equals("Date", ignoreCase = true) }
            .associate { it.key.lowercase() to it.value }
            .toSortedMap()

    private fun Application.errorMappingTestRoutes() = routing {
        get("/test/validation") {
            call.respondError(
                AppError.Validation(
                    code = "email.invalid_format",
                    field = "email",
                    detail = "Not an email address",
                )
            )
        }

        get("/test/conflict") {
            call.respondError(AppError.Conflict(code = "requirement_locked", detail = "Under review"))
        }

        get("/test/not-found") {
            call.respondError(AppError.NotFound(code = "employee_not_found", entity = "employee"))
        }

        get("/test/reason-required") {
            call.respondError(AppError.ReasonRequired(code = "duplicate_email.reason_required", action = "create_hire"))
        }

        get("/test/denied/wrong-pin") { call.respondError(AppError.Denied) }
        get("/test/denied/unknown-token") { call.respondError(AppError.Denied) }

        get("/test/employees/{id}") {
            // Nothing is stored yet, so a well-formed id is always a lookup miss — which is exactly
            // the comparison worth making. `orNotFound` turns a parse failure into the same error
            // the miss produces, so the two cannot be told apart.
            val parsed = PersonId.of(call.parameters["id"].orEmpty()).orNotFound("employee")
            call.respondError(
                when (parsed) {
                    is DomainResult.Ok -> AppError.NotFound(code = "employee_not_found", entity = "employee")
                    is DomainResult.Err -> parsed.error
                }
            )
        }

        post("/test/employees") {
            when (val id = PersonId.of(call.receive<IdRequest>().id)) {
                is DomainResult.Ok -> call.respondText(id.value.toString())
                is DomainResult.Err -> call.respondError(id.error)
            }
        }

        get("/test/boom") {
            error("Insert into portal_access_logs failed: org.h2.jdbc.JdbcSQLException")
        }
    }
}
