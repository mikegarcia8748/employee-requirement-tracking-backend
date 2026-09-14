package com.pgsystem.employee.requirement.tracker.plugin

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import org.koin.ktor.ext.getKoin

/**
 * Opens the connection pool on start and closes it on stop.
 *
 * Three things here are deliberate rather than incidental:
 *
 *  - **`connect()` is called eagerly in the module body, not from an `ApplicationStarted` handler.**
 *    A bad `DATABASE_URL` must abort startup before the server begins listening (PRD 11).
 *    `EmbeddedServer.start()` wraps module loading in try/catch, calls `destroyApplication()` and
 *    rethrows, and only then calls `engine.start()` — so a throw here aborts startup before the
 *    connector binds. `ApplicationStarted` happens to behave the same way today because it is
 *    raised with `Events.raise` rather than `safeRaiseEvent`, but that distinction is an internal
 *    detail of one Ktor method and differs per event inside it. Not worth depending on.
 *
 *  - **The factory is resolved eagerly rather than through `by inject()`.** Koin's own Ktor plugin
 *    subscribes to `ApplicationStopping` during `install(Koin)` — that is, before the subscription
 *    below — and its handler closes the container. A lazy delegate first dereferenced inside the
 *    stop handler would be resolving from a container that is already shut.
 *
 *  - **The stop subscription is registered before `connect()`,** so a failure between building the
 *    pool and handing it to Exposed still closes it. [DatabaseFactory.close] is guarded by
 *    `::dataSource.isInitialized`, so it is safe on the never-connected path too.
 */
fun Application.configureDatabase() {
    val factory = getKoin().get<DatabaseFactory>()

    monitor.subscribe(ApplicationStopping) {
        log.info("Closing the database connection pool.")
        factory.close()
    }

    factory.connect()
    log.info("Database connected before any route is mounted.")
}
