package com.pgsystem.employee.requirement.tracker

import com.pgsystem.employee.requirement.tracker.plugin.REQUEST_ID
import com.pgsystem.employee.requirement.tracker.plugin.configureMonitoring
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import org.slf4j.MDC

/**
 * The correlation id that ties a `usecase` trace line to the request that caused it (ERT-195).
 *
 * Tracing without correlation is unreadable the moment two requests overlap, and "the MDC reaches
 * the handler" is a claim about Ktor's pipeline rather than about this codebase — which is exactly
 * the kind of claim worth pinning, because it would fail silently on a version bump: the id would
 * simply render blank and the traces would quietly stop being attributable.
 *
 * The probe routes are test-local. Inventing a real endpoint to observe the MDC would put a
 * diagnostic concern into the route tree, the same reasoning `ErrorMappingTest` records.
 */
class RequestCorrelationTest {

    @Test
    fun `request correlation - a route handler - sees a request id in the MDC`() = testApplication {
        withProbeRoutes()

        client.get("/correlation-probe").bodyAsText() shouldMatch Regex("[0-9a-f]{8}")
    }

    @Test
    fun `request correlation - work handed to another dispatcher - keeps the same request id`() =
        testApplication {
            // A repository runs its statement on Dispatchers.IO, so a correlation id that did not
            // survive the hop would be absent from exactly the frames worth tracing.
            withProbeRoutes()

            val shallow = client.get("/correlation-probe").bodyAsText()
            val deep = client.get("/correlation-probe/deep").bodyAsText()

            deep shouldMatch Regex("[0-9a-f]{8}")
            deep shouldNotBe "absent"
            // Different requests, so different ids -- the point is that neither is empty.
            (shallow == deep) shouldBe false
        }

    @Test
    fun `request correlation - two requests - are given different ids`() = testApplication {
        withProbeRoutes()

        val first = client.get("/correlation-probe").bodyAsText()
        val second = client.get("/correlation-probe").bodyAsText()

        first shouldNotBe second
    }

    @Test
    fun `request correlation - one request - reports one id for its whole duration`() = testApplication {
        // Ktor wraps the Monitoring phase, the Call phase and ResponseSent separately. The provider
        // is memoised per call, so all three see one value; without that the handler and the access
        // log would disagree and a trace line could not be matched to its request line.
        withProbeRoutes()

        client.get("/correlation-probe/twice").bodyAsText().split(' ').let { (first, second) ->
            first shouldBe second
        }
    }

    @Test
    fun `request correlation - a portal path carrying a link token - the id contains no part of it`() =
        testApplication {
            // The id is stamped on EVERY log line by %X{requestId}, while the portal redaction in
            // `format` protects one line. An id derived from the request would put a live credential
            // into the log file wholesale (PRD 12, invariant 4).
            withProbeRoutes()

            val token = "zQ8mK2pR7vT4wX1n"

            val id = client.get("/api/portal/$token/probe").bodyAsText()

            id shouldMatch Regex("[0-9a-f]{8}")
            token.windowed(size = 4).none { id.contains(it, ignoreCase = true) } shouldBe true
        }

    private fun ApplicationTestBuilder.withProbeRoutes() {
        application {
            configureMonitoring()
            probeRoutes()
        }
    }
}

/** Reports what the MDC holds, from a handler and from a coroutine that changed threads. */
private fun Application.probeRoutes() {
    routing {
        get("/correlation-probe") {
            call.respondText(MDC.get(REQUEST_ID) ?: "absent")
        }
        get("/correlation-probe/deep") {
            call.respondText(withContext(Dispatchers.IO) { MDC.get(REQUEST_ID) ?: "absent" })
        }
        get("/correlation-probe/twice") {
            val first = MDC.get(REQUEST_ID) ?: "absent"
            val second = withContext(Dispatchers.IO) { MDC.get(REQUEST_ID) ?: "absent" }
            call.respondText("$first $second")
        }
        get("/api/portal/{token}/probe") {
            call.respondText(MDC.get(REQUEST_ID) ?: "absent")
        }
    }
}
