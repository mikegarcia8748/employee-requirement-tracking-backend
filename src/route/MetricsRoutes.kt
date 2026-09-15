package com.pgsystem.employee.requirement.tracker.route

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * The Prometheus scrape target.
 *
 * `Monitoring.kt` installs `MicrometerMetrics` against a registry that, until now, nothing exposed —
 * metrics were collected and unreachable. PRD 13 sets leading indicators (PIN entry failure rate,
 * sessions blocked by lockout, upload error rate) that need a scrape target to be measurable at all
 * rather than estimated.
 *
 * **Not public.** This is the third operational surface after `/openapi` and `/swagger`, and it is
 * gated identically: open in dev, HR-authenticated otherwise. A scrape body names every route, every
 * status code and every error count the service has produced, which is reconnaissance against the
 * portal endpoints the security audit is about.
 *
 * **[hide] keeps it out of the published spec.** `/metrics` is an operational surface, not an API —
 * there is nothing here for a client to generate against, and listing it in the spec advertises it.
 * This is the counterpart to `describe { }`: every business route declares its schema, every
 * operational one hides.
 *
 * **Why the gate arrives as parameters rather than being read here.** `HR_AUTH` and `isDevMode()`
 * both live in `plugin/`, and the dependency between the two packages runs `plugin` → `route`, never
 * the reverse (ERT-145). So the caller in `plugin/Monitoring.kt` supplies them. That also makes the
 * refused-outside-dev case testable: a JVM test cannot unset `APP_ENV` in its own process, so
 * `devMode` has to be injectable to be provable.
 */
fun Route.metricsRoutes(registry: PrometheusMeterRegistry, devMode: Boolean, authName: String) {
    if (devMode) scrape(registry) else authenticate(authName) { scrape(registry) }
}

private fun Route.scrape(registry: PrometheusMeterRegistry) {
    get("/metrics") {
        call.respondText(registry.scrape(), PROMETHEUS_TEXT)
    }.hide()
}

/**
 * `text/plain; version=0.0.4; charset=UTF-8` — the Prometheus text exposition format.
 *
 * Stated explicitly rather than left as bare `text/plain`. Scrapers accept the latter, but the
 * version parameter is what tells one which format it is being handed, and it costs nothing to be
 * unambiguous about a wire contract.
 */
private val PROMETHEUS_TEXT = ContentType.Text.Plain
    .withParameter("version", "0.0.4")
    .withCharset(Charsets.UTF_8)
