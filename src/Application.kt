package com.pgsystem.employee.requirement.tracker

import com.pgsystem.employee.requirement.tracker.di.configureKoin
import com.pgsystem.employee.requirement.tracker.plugin.configureApiDocs
import com.pgsystem.employee.requirement.tracker.plugin.configureDatabase
import com.pgsystem.employee.requirement.tracker.plugin.configureHttp
import com.pgsystem.employee.requirement.tracker.plugin.configureMonitoring
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.route.configureRouting
import io.ktor.server.application.Application

/**
 * Application assembly. Order matters: error handling and DI first, then transport concerns,
 * then routes.
 *
 * `configureExposed()` and `configurePostgres()` are gone. The generator's versions opened a
 * connection and declared CRUD routes in the same function, which put schema access and HTTP
 * routing in one place and made the former impossible to test without the latter. Database wiring
 * now lives behind `DatabaseFactory`, connected by `configureDatabase()` — which must follow
 * `configureKoin()`, since the factory comes from the container, and precede `configureRouting()`,
 * so no route is ever mounted against an unconnected pool.
 */
fun Application.rootModule() {
    configureStatusPages()
    configureKoin()
    configureDatabase()
    configureSerialization()
    configureHttp()
    configureSecurity()
    configureMonitoring()
    configureApiDocs()
    configureRouting()
}
