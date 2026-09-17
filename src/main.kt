package com.pgsystem.employee.requirement.tracker

import io.ktor.server.application.Application
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * How long in-flight requests get once SIGTERM arrives, and how long the engine waits after that.
 *
 * Ktor's defaults are 1 s and 5 s, chosen for a process a developer is restarting by hand. Cloud Run
 * sends SIGTERM and follows with SIGKILL 10 seconds later, and every rolling deploy is a SIGTERM —
 * so at the default a deploy abandons requests it had ample time to finish, and leaves the Postgres
 * backends they were holding to age out server-side.
 *
 * Both values sit inside the 10-second window with room to spare. Going closer to it would trade a
 * clean stop for the chance of being killed mid-shutdown, which is the failure this exists to avoid.
 */
private const val SHUTDOWN_GRACE_PERIOD_MS = 3_000L
private const val SHUTDOWN_TIMEOUT_MS = 8_000L

/**
 * `PORT` and `0.0.0.0` are what Cloud Run requires: the port is assigned per instance rather than
 * chosen, and binding a loopback address makes the container unreachable with no error to show for
 * it.
 *
 * The engine is configured through [connector] rather than the shorter `embeddedServer(factory,
 * port, host, module)` overload, which takes no `configure` block and so offers nowhere to put the
 * shutdown timings above. Ktor registers the JVM shutdown hook itself inside `start()`, so
 * `ApplicationStopping` fires and `DatabaseFactory.close()` runs without anything further here —
 * this only widens the window that hook is given.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(
        factory = Netty,
        environment = applicationEnvironment { },
        configure = {
            connector {
                this.host = "0.0.0.0"
                this.port = port
            }
            shutdownGracePeriod = SHUTDOWN_GRACE_PERIOD_MS
            shutdownTimeout = SHUTDOWN_TIMEOUT_MS
        },
        module = Application::rootModule,
    ).start(wait = true)
}
