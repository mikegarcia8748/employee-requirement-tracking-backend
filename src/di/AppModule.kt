package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.core.crypto.TokenDigest
import com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig
import com.pgsystem.employee.requirement.tracker.data.notify.PortalBaseUrl
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
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
 * **[TokenDigest] and [JwtConfig] are resolved eagerly.** Koin singles are lazy, so a missing `TOKEN_PEPPER` would
 * otherwise surface on the first portal request rather than at boot — in production, on the one path
 * that matters, long after the deploy looked successful. This is the same argument `Database.kt`
 * records for calling `connect()` in the module body: a configuration fault must abort startup
 * before the connector binds, not become a 500 later. Any other binding whose construction can fail
 * on configuration belongs on this line too — which is why [JwtConfig] joined it in ERT-190: outside
 * dev a missing `JWT_SECRET` must abort startup, not become a 401 on the first sign-in attempt.
 *
 * [PortalBaseUrl] joined it in ERT-440, and its case is the sharpest of the three. The other two
 * fail *recoverably*: fix the variable, restart, and the next request works. An invitation rendered
 * without an origin has already left, and the token it carried is not stored — so correcting the
 * variable does not correct the link, and the remedy is reissuing the credential to every hire
 * invited since the deploy.
 */
val appModules = listOf(coreModule, dataModule, domainModule)

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(appModules)
    }

    getKoin().get<TokenDigest>()
    getKoin().get<JwtConfig>()
    getKoin().get<PortalBaseUrl>()

    // Drained after resolution, never before: each list is filled while its binding is built.
    jwtWarnings.forEach { log.warn(it) }
    jwtWarnings.clear()
    notifierWarnings.forEach { log.warn(it) }
    notifierWarnings.clear()
}

/** Used by tests that need the graph without an embedded server. */
fun testKoinApplication() = koinApplication { modules(appModules) }
