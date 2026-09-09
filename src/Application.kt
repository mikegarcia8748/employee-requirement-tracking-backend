package com.pgsystem.employee.requirement.tracker

import io.ktor.server.application.Application

fun Application.rootModule() {
    configureStatusPages()
    configureKoin()
    configureExposed()
    configurePostgres()
    configureHttp()
    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureRouting()
}
