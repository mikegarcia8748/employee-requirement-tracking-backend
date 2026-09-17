package com.pgsystem.employee.requirement.tracker

import com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig
import com.pgsystem.employee.requirement.tracker.di.configureKoin
import com.pgsystem.employee.requirement.tracker.plugin.configureApiDocs
import com.pgsystem.employee.requirement.tracker.plugin.configureDatabase
import com.pgsystem.employee.requirement.tracker.plugin.configureHrBootstrap
import com.pgsystem.employee.requirement.tracker.plugin.configureHttp
import com.pgsystem.employee.requirement.tracker.plugin.logStartupConfiguration
import com.pgsystem.employee.requirement.tracker.plugin.configureMonitoring
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.route.configureRouting
import io.ktor.server.application.Application
import org.koin.ktor.ext.get

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
 *
 * `configureSecurity` takes its [JwtConfig] rather than reading the container itself, for the reason
 * `configureRouting` takes `HR_AUTH`: a plugin that is a function of its inputs can be assembled in a
 * test without the rest of the graph — and a route test can then sign tokens with the very config the
 * verifier was given. Resolving it here also means a missing `JWT_SECRET` fails during assembly.
 *
 * `logStartupConfiguration()` runs first and deliberately depends on nothing (ERT-1120). Every
 * refusal below it — a missing `JWT_SECRET`, an absent `TOKEN_PEPPER`, an empty `users` table with
 * no bootstrap credentials — aborts assembly before any other line is logged. Putting the summary
 * ahead of them means a failed boot still says which mode it resolved to, rather than leaving the
 * reader to reconstruct the environment by hand.
 *
 * `configureHrBootstrap()` has the tightest ordering constraint of the three (ERT-190). It writes a
 * row, so it must follow `configureDatabase()`; and it may refuse to start, so it must run before
 * `configureRouting()` mounts anything — a deployment with no way in should fail during assembly
 * rather than answer requests it has nobody to authorise.
 */
fun Application.rootModule() {
    logStartupConfiguration()
    configureStatusPages()
    configureKoin()
    configureDatabase()
    configureSerialization()
    configureHttp()
    configureSecurity(get<JwtConfig>())
    configureHrBootstrap(get(), get())
    configureMonitoring()
    configureApiDocs()
    configureRouting(HR_AUTH)
}
