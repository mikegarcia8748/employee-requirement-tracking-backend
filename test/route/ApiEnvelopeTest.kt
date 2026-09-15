package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.route.dto.ApiMeta
import com.pgsystem.employee.requirement.tracker.route.mapper.respondOk
import com.pgsystem.employee.requirement.tracker.route.mapper.respondResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test

/**
 * The `/api` response envelope (ERT-145).
 *
 * Route-level, because what is worth proving is the bytes on the wire: whether a generic type
 * argument survives serialization, whether an absent field is omitted or rendered as `null`, and
 * whether `result` agrees with the status. None of that is visible from the types.
 *
 * Routes here are test-local. There are no business routes yet, and inventing one to test the
 * envelope would put a decision in a handler.
 */
class ApiEnvelopeTest {

    @Serializable
    private data class Token(val token: String, val issuedBy: Issuer)

    @Serializable
    private data class Issuer(val name: String)

    private fun ApplicationTestBuilder.withEnvelopeRoutes() = application {
        rootModule()
        envelopeTestRoutes()
    }

    @Test
    fun `response envelope - a success with a payload - wraps the dto under data with result success`() =
        testApplication {
            withEnvelopeRoutes()

            val response = client.get("/test/ok")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe
                """{"result":"success","data":{"token":"ada9a8sd6789a","issuedBy":{"name":"hr"}}}"""
        }

    @Test
    fun `response envelope - a generic payload - serialises the concrete type rather than erasing it`() =
        testApplication {
            // The helpers are `inline` + `reified` for this reason. Without it the type argument of
            // ApiResponse<T> erases and kotlinx cannot resolve a serializer — a runtime failure, not
            // a compile one, so only a wire-level test catches it. The nested DTO is the real check:
            // an erased generic renders an empty object rather than the fields below.
            withEnvelopeRoutes()

            val body = client.get("/test/ok").bodyAsText()

            body shouldContain """"token":"ada9a8sd6789a""""
            body shouldContain """"issuedBy":{"name":"hr"}"""
        }

    @Test
    fun `response envelope - a success with no payload - omits data rather than sending null`() = testApplication {
        // `explicitNulls = false` is what keeps a denied body constant, so it is worth pinning here
        // rather than trusting the serializer configuration to stay put.
        withEnvelopeRoutes()

        val response = client.get("/test/ok-empty")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"result":"success"}"""
    }

    @Test
    fun `response envelope - a list with counts - carries total under meta`() = testApplication {
        // `meta` sits beside `data`, never inside it: a count nested in the payload would force a
        // wrapper type per list endpoint and ApiResponse<List<T>> would stop working.
        withEnvelopeRoutes()

        val body = client.get("/test/list").bodyAsText()

        body shouldBe """{"result":"success","data":[{"name":"hr"},{"name":"ops"}],"meta":{"total":120}}"""
    }

    @Test
    fun `response envelope - an Ok domain result - responds with the success status given`() = testApplication {
        withEnvelopeRoutes()

        val response = client.get("/test/created")

        response.status shouldBe HttpStatusCode.Created
        response.bodyAsText() shouldContain """"result":"success""""
    }

    @Test
    fun `response envelope - an Err domain result - responds with the mapped error status`() = testApplication {
        withEnvelopeRoutes()

        val response = client.get("/test/result-err")

        response.status shouldBe HttpStatusCode.Conflict
        response.bodyAsText() shouldContain """"code":"requirement_locked""""
    }

    @Test
    fun `response envelope - a 4xx failure - is labelled fail`() = testApplication {
        withEnvelopeRoutes()

        client.get("/test/result-err").bodyAsText() shouldContain """"result":"fail""""
    }

    @Test
    fun `response envelope - a 5xx failure - is labelled error`() = testApplication {
        // The split earns its place here: `fail` means the caller can fix it, `error` means the
        // server broke, and the UI does different things with the two.
        withEnvelopeRoutes()

        client.get("/test/boom").bodyAsText() shouldContain """"result":"error""""
    }

    @Test
    fun `response envelope - a 5xx failure - carries no details`() = testApplication {
        // A 5xx names nothing about the cause (PRD 12). `details` is the field most likely to grow
        // one by accident, so it is guarded rather than assumed.
        withEnvelopeRoutes()

        val body = client.get("/test/boom").bodyAsText()

        body shouldNotContain "details"
        body shouldNotContain "IllegalStateException"
    }

    @Test
    fun `error envelope - a single invalid field - renders a details list of length one`() = testApplication {
        withEnvelopeRoutes()

        val response = client.get("/test/one-bad-field")

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldBe
            """{"result":"fail","error":{"code":"validation_failed","message":"Some fields need attention.",""" +
            """"details":[{"code":"email.invalid_format","field":"email","message":"Not an email address"}]}}"""
    }

    @Test
    fun `error envelope - one invalid field versus several - render the same shape`() = testApplication {
        // The point of collapsing `field` and `detail` into `details`: a client binds errors to a
        // form with one expression and never branches on how many failed. If a single-field failure
        // ever renders differently, that expression has to grow a special case.
        withEnvelopeRoutes()

        val one = client.get("/test/one-bad-field").bodyAsText()
        val many = client.get("/test/many-bad-fields").bodyAsText()

        one shouldContain """"code":"validation_failed""""
        many shouldContain """"code":"validation_failed""""
        one.substringBefore(""""details"""") shouldBe many.substringBefore(""""details"""")
        many shouldContain """"field":"email""""
        many shouldContain """"field":"start_date""""
    }

    @Test
    fun `error envelope - a failure with no field context - omits details rather than sending an empty list`() =
        testApplication {
            withEnvelopeRoutes()

            val body = client.get("/test/result-err").bodyAsText()

            body shouldNotContain "details"
        }

    private fun Application.envelopeTestRoutes() = routing {
        get("/test/ok") { call.respondOk(Token(token = "ada9a8sd6789a", issuedBy = Issuer("hr"))) }

        get("/test/ok-empty") { call.respondOk() }

        get("/test/list") {
            call.respondOk(listOf(Issuer("hr"), Issuer("ops")), meta = ApiMeta(total = 120))
        }

        get("/test/created") {
            call.respondResult(DomainResult.Ok(Issuer("hr")), HttpStatusCode.Created)
        }

        get("/test/result-err") {
            call.respondResult<Issuer>(
                DomainResult.Err(AppError.Conflict(code = "requirement_locked", detail = "Under review"))
            )
        }

        get("/test/one-bad-field") {
            call.respondResult<Issuer>(
                DomainResult.Err(
                    AppError.Validation(
                        code = "email.invalid_format",
                        field = "email",
                        detail = "Not an email address",
                    )
                )
            )
        }

        get("/test/many-bad-fields") {
            call.respondResult<Issuer>(
                DomainResult.Err(
                    AppError.ValidationFailed(
                        listOf(
                            AppError.Validation("email.invalid_format", "email", "Not an email address"),
                            AppError.Validation("start_date.in_past", "start_date", "Cannot be in the past"),
                        )
                    )
                )
            )
        }

        get("/test/boom") { error("database is on fire: portal_access_logs") }
    }
}
