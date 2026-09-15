package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.core.crypto.TokenDigest
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.dsl.koinApplication
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.getKoin
import org.koin.logger.slf4jLogger

/**
 * Composition root.
 *
 * Modules stack outward: [coreModule] holds framework-free infrastructure, [dataModule] the
 * adapters, and a `domainModule` will hold use cases once they exist. Nothing in `domain/`
 * references Koin — dependencies arrive through constructors, which is why a use case can be
 * instantiated in a test with plain fakes and no container at all.
 *
 * **[TokenDigest] is resolved eagerly.** Koin singles are lazy, so a missing `TOKEN_PEPPER` would
 * otherwise surface on the first portal request rather than at boot — in production, on the one path
 * that matters, long after the deploy looked successful. This is the same argument `Database.kt`
 * records for calling `connect()` in the module body: a configuration fault must abort startup
 * before the connector binds, not become a 500 later. Any other binding whose construction can fail
 * on configuration belongs on this line too.
 */
val appModules = listOf(coreModule, dataModule)

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(appModules)
    }

    getKoin().get<TokenDigest>()
}

/** Used by tests that need the graph without an embedded server. */
fun testKoinApplication() = koinApplication { modules(appModules) }
