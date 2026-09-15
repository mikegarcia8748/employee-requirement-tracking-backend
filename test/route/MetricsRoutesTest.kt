package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureApiDocs
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.rootModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test

/**
 * The `/metrics` scrape surface (ERT-150).
 *
 * `/metrics` is an **operational** surface, not an API: it is gated exactly like `/openapi` and
 * `/swagger` — open in dev, behind HR authentication otherwise — and it is kept out of the published
 * spec, because a client has nothing to generate from it.
 */
class MetricsRoutesTest {

    @Test
    fun `metrics endpoint - dev mode - returns prometheus exposition format`() = testApplication {
        application { rootModule() }

        val response = client.get("/metrics")

        response.status shouldBe HttpStatusCode.OK

        // Asserting the status alone would pass on an empty body, which is the realistic failure
        // here: a registry that was never handed to MicrometerMetrics scrapes to "". `# HELP` and
        // `# TYPE` are the exposition format's own signature, and the JVM meter proves the registry
        // under the route is the one collecting.
        val body = response.bodyAsText()
        body shouldContain "# HELP"
        body shouldContain "# TYPE"
        body shouldContain "jvm_memory_used_bytes"
    }

    @Test
    fun `metrics endpoint - outside dev without credentials - is refused`() = testApplication {
        // Deliberately not `rootModule()`. That already mounts /metrics in dev, and a second mount
        // on the same path would leave which of the two answers up to match order — the test would
        // be measuring Ktor's routing precedence rather than the gate.
        application {
            configureSecurity()
            routing { metricsRoutes(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), devMode = false, authName = HR_AUTH) }
        }

        client.get("/metrics").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `metrics endpoint - the generated spec - does not publish it`() = testApplication {
        application { rootModule() }

        val spec = client.get("/swagger/documentation.yaml").bodyAsText()

        // The positive half is the anti-vacuity guard: without it this test would also pass against
        // an empty body, a 404 page, or a spec the generator failed to build.
        spec shouldContain "/health"
        spec shouldNotContain "/metrics"
    }

    @Test
    fun `metrics endpoint - the response content type - names the prometheus text format version`() =
        testApplication {
            // Bare `text/plain` is accepted by scrapers, so nothing downstream would fail loudly if
            // the version parameter were dropped in an edit. Pinned here instead.
            application { rootModule() }

            val contentType = client.get("/metrics").contentType()

            contentType?.contentType shouldBe "text"
            contentType?.contentSubtype shouldBe "plain"
            contentType?.parameter("version") shouldBe "0.0.4"
        }

    @Test
    fun `metrics endpoint - outside dev behind authentication - is still absent from the generated spec`() =
        testApplication {
            // The dev test above scrapes a route mounted at the top level. Outside dev the same
            // handler is nested inside `authenticate { }`, and `hide()` attaches to whatever Route
            // node `get` returned in *that* tree. If it attached to the wrong node, /metrics would
            // be published in exactly the configuration where it is protected and where nobody
            // looks — so the case is worth its own test rather than an assumption.
            //
            // Docs stay dev-open (configureApiDocs reads isDevMode(), true under test) while metrics
            // are forced to the non-dev branch. That combination exists only here, which is the
            // point: it is the only way to read the spec the non-dev gate produces.
            application {
                configureSerialization()
                configureSecurity()
                configureApiDocs()
                routing {
                    healthRoutes()
                    metricsRoutes(
                        PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
                        devMode = false,
                        authName = HR_AUTH,
                    )
                }
            }

            val spec = client.get("/swagger/documentation.yaml").bodyAsText()

            spec shouldContain "/health"
            spec shouldNotContain "/metrics"
        }
}
