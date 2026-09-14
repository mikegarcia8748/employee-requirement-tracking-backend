package com.pgsystem.employee.requirement.tracker.plugin

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.cors.routing.CORS

/**
 * CORS.
 *
 * The generator called `anyHost()`, which would let any origin drive the portal endpoints from a
 * victim's browser. Origins come from `CORS_ALLOWED_HOSTS` (comma-separated); with none configured
 * no cross-origin host is permitted, which is the correct default for a same-origin deployment.
 */
fun Application.configureHttp() {
    val allowedHosts = System.getenv("CORS_ALLOWED_HOSTS")
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()

    if (allowedHosts.isEmpty()) {
        log.info("CORS: no CORS_ALLOWED_HOSTS configured; cross-origin requests are not permitted.")
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowCredentials = true
        allowedHosts.forEach { allowHost(it, schemes = listOf("https")) }
    }
}
