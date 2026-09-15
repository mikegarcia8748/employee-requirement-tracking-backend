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
