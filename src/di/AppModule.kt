package com.pgsystem.employee.requirement.tracker.di

import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.dsl.koinApplication
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

/**
 * Composition root.
 *
 * Modules stack outward: [coreModule] holds framework-free infrastructure, [dataModule] the
 * adapters, and a `domainModule` will hold use cases once they exist. Nothing in `domain/`
 * references Koin — dependencies arrive through constructors, which is why a use case can be
 * instantiated in a test with plain fakes and no container at all.
 */
val appModules = listOf(coreModule, dataModule)

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(appModules)
    }
}

/** Used by tests that need the graph without an embedded server. */
fun testKoinApplication() = koinApplication { modules(appModules) }
