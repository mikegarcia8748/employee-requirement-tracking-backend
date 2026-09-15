package com.pgsystem.employee.requirement.tracker.plugin

import com.pgsystem.employee.requirement.tracker.route.metricsRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.event.Level
import java.util.concurrent.ThreadLocalRandom

/**
 * Metrics and request logging.
 *
 * Call logging deliberately records the request path only. Portal URLs carry the link token as a
 * path segment, and a token in a log file is a credential in a log file — paths under `/api/portal`
 * are redacted before they reach the log (PRD 12: tokens are never logged).
 *
 * **Why the scrape route is mounted here rather than in `configureRouting()`** (ERT-150). The gate
 * needs [HR_AUTH] and [isDevMode], both of which live in this package, and the dependency between
 * `plugin` and `route` runs one way — `plugin` reads from `route`, never the reverse (ERT-145). The
 * route itself therefore stays in `route/MetricsRoutes.kt` and takes its gate as parameters, and
 * this function supplies them. `plugin/ApiDocs.kt` mounts `/openapi` and `/swagger` the same way and
 * for the same reason: all three are operational surfaces, gated identically, and none of them is
 * part of the `/api` contract `configureRouting()` exists to assemble.
 *
 * `routing { }` is additive, so mounting before `configureRouting()` runs is not an ordering
 * hazard — the two blocks contribute to the same route tree.
 */
fun Application.configureMonitoring(registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) {
    install(CallLogging) {
        level = Level.INFO

        // Correlation (ERT-195). Ktor wraps the Monitoring and Call phases in
        // withContext(MDCContext(...)), and routing intercepts Call, so this value reaches every
        // suspend frame the request opens -- including a use case, and including work handed to
        // another dispatcher inside a transaction. That is what ties a `usecase` trace line to the
        // request that caused it; without it, concurrent requests interleave unreadably.
        //
        // THE VALUE MUST STAY OPAQUE AND GENERATED. The redaction below lives inside `format` and
        // protects that one line only. An id derived from the path, the URI or a header would go
        // through `%X{requestId}` onto EVERY line in the file, and a portal path carries the link
        // token, which is a live credential (PRD 12, invariant 4). The `call` argument is ignored
        // on purpose -- see `RequestCorrelationTest`.
        mdc(REQUEST_ID) { newRequestId() }

        format { call ->
            val path = call.request.path()
            val safePath = if (path.startsWith("/api/portal/")) "/api/portal/[redacted]" else path
            "${call.request.local.method.value} $safePath -> ${call.response.status()?.value}"
        }
    }

    install(MicrometerMetrics) {
        this.registry = registry
    }

    attributes.put(MeterRegistryKey, registry)

    routing { metricsRoutes(registry, devMode = isDevMode(), authName = HR_AUTH) }
}

val MeterRegistryKey = io.ktor.util.AttributeKey<PrometheusMeterRegistry>("PrometheusMeterRegistry")

/** MDC key carrying the per-request correlation id, rendered by `%X{requestId}` in `logback.xml`. */
const val REQUEST_ID = "requestId"

/**
 * Eight hex characters of randomness, and nothing derived from the request.
 *
 * Not a [com.pgsystem.employee.requirement.tracker.core.value.EntityId] and not a UUID: this
 * identifies a log span, never a row, and giving it an entity type would invite someone to
 * persist it. It needs no unpredictability either -- it is a correlation handle, not a
 * credential -- so a thread-local PRNG is the right cost.
 */
private fun newRequestId(): String = "%08x".format(ThreadLocalRandom.current().nextInt())
