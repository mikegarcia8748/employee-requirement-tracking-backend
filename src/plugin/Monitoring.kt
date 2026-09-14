package com.pgsystem.employee.requirement.tracker.plugin

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.event.Level

/**
 * Metrics and request logging.
 *
 * Call logging deliberately records the request path only. Portal URLs carry the link token as a
 * path segment, and a token in a log file is a credential in a log file — paths under `/api/portal`
 * are redacted before they reach the log (PRD 12: tokens are never logged).
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
}

val MeterRegistryKey = io.ktor.util.AttributeKey<PrometheusMeterRegistry>("PrometheusMeterRegistry")
