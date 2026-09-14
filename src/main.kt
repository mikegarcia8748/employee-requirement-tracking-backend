package com.pgsystem.employee.requirement.tracker

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(
        factory = Netty,
        port = port,
        host = "0.0.0.0",
        module = Application::rootModule,
    ).start(wait = true)
}
