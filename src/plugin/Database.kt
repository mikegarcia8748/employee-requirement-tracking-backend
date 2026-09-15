package com.pgsystem.employee.requirement.tracker.plugin

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseConfig
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import org.flywaydb.core.Flyway
import org.koin.ktor.ext.getKoin

/** Where the versioned SQL lives. `resources/` is the classpath root under Amper. */
private const val MIGRATION_LOCATION = "classpath:db/migration"

/**
 * Opens the connection pool on start, migrates the schema, and closes the pool on stop.
 *
 * Four things here are deliberate rather than incidental:
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
 *
 *  - **`connect()` runs before [migrate].** Two reasons. The fail-fast of an unreachable database
 *    should be the first thing that happens, not the second. And with an in-memory H2 URL, if
 *    Flyway's own DataSource were the only thing that had ever opened the database, the schema
 *    would vanish the moment Flyway closed its connection — unless `DB_CLOSE_DELAY=-1` is present.
 *    Opening Hikari first pins the database regardless of how that URL is later edited.
 */
fun Application.configureDatabase() {
    val koin = getKoin()
    val config = koin.get<DatabaseConfig>()
    val factory = koin.get<DatabaseFactory>()

    monitor.subscribe(ApplicationStopping) {
        log.info("Closing the database connection pool.")
        factory.close()
    }

    factory.connect()
    migrate(config)
    log.info("Database connected and migrated before any route is mounted.")
}

/**
 * Runs the versioned SQL in `resources/db/migration`.
 *
 * Flyway gets its own short-lived connections rather than the application pool: `DatabaseFactory`
 * does not expose its `DataSource`, and keeping them separate means the application pool is never
 * handed a connection mid-DDL.
 *
 * `baselineOnMigrate` is left at its default of `false` on purpose. Every database this meets is
 * empty — a fresh H2 per test run, a fresh Postgres per environment — so an unexpected pre-existing
 * schema is a fault worth failing on, not something to silently adopt as a baseline. Pointing the
 * app at a hand-built database is a deliberate `flyway baseline`, not an accident.
 */
private fun Application.migrate(config: DatabaseConfig) {
    val result = Flyway.configure()
        .dataSource(config.url, config.user, config.password)
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate()

    // targetSchemaVersion is null when nothing was applied, which is not the same as "no schema" --
    // say so plainly rather than printing a version that reads as a baseline.
    if (result.migrationsExecuted == 0) {
        log.info("Flyway: schema already up to date; no migration applied.")
    } else {
        log.info("Flyway: applied ${result.migrationsExecuted} migration(s); schema now at ${result.targetSchemaVersion}.")
    }
}
