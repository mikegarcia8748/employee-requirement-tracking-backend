package com.pgsystem.employee.requirement.tracker.plugin

import io.ktor.openapi.OpenApiDocDsl
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.openapi.OpenAPIConfig
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.routing

/**
 * OpenAPI spec + Swagger UI, **generated from the routes themselves**.
 *
 * Split out of `Http.kt`, where the generator mounted `openAPI()` and `swaggerUI()` on the *same*
 * `"openapi"` path so one shadowed the other. They now sit on distinct paths:
 *
 *  - `/openapi`  — rendered static API reference
 *  - `/swagger`  — interactive Swagger UI
 *  - `/swagger/documentation.yaml` — the machine-readable spec
 *
 * **Why generated rather than a checked-in file.** PRD Appendix B specifies roughly 38 endpoints. A
 * hand-maintained `documentation.yaml` covering that surface drifts from the code within a sprint,
 * and a spec that lies is worse than no spec. [OpenApiDocSource.Routing] reads the live route tree,
 * so an endpoint cannot exist without appearing in the docs, and the JWT security scheme is picked
 * up from the `authenticate` blocks rather than restated by hand. Per-route detail is attached with
 * `describe { }` beside the handler; `hide()` removes a route from the published spec.
 *
 * This required declaring `io.ktor:ktor-server-routing-openapi` explicitly in `libs.versions.toml`:
 * Amper 0.12.0's Ktor catalog has no key for it, and the `$ktor.server.routingOpenapi` key the
 * scaffold referenced does not exist — which is why the project did not build at all before.
 *
 * **Why the docs are gated outside dev.** Swagger UI publishes the exact shape of the portal
 * endpoints — `/api/portal/{token}/verify`, its error contract, its rate limits — to anyone who
 * asks. The portal surface is precisely what the security audit is about, so outside dev the docs
 * sit behind HR authentication.
 *
 * When portal routes arrive, their descriptions must state the deliberate behaviours as *intended*:
 * a wrong PIN and an unknown token return identical failures, and `request-new-link` returns a
 * constant response whether or not the address exists (PRD 6.6, Appendix B). Documented that way, a
 * future engineer cannot mistake them for bugs and "fix" them into an enumeration oracle.
 */
fun Application.configureApiDocs() {
    val devMode = isDevMode()

    routing {
        if (devMode) {
            openAPI(path = "openapi") { apiReference() }
            swaggerUI(path = "swagger") { apiSpec() }
        } else {
            authenticate(HR_AUTH) {
                openAPI(path = "openapi") { apiReference() }
                swaggerUI(path = "swagger") { apiSpec() }
            }
        }
    }

    log.info(
        "API docs: reference at /openapi, UI at /swagger, spec at /swagger/documentation.yaml " +
            "(generated from routes); authentication ${if (devMode) "disabled (dev)" else "required"}."
    )
}

/**
 * `openAPI()` runs swagger-codegen, which writes static HTML to [OpenAPIConfig.outputPath].
 *
 * That path defaults to `docs/` — this project's PRD and security-audit folder, which the plugin
 * will otherwise fill with `index.html` and `.swagger-codegen/` on every boot. Redirected under
 * `build/` so generated output stays with build artefacts and out of version control.
 */
private fun OpenAPIConfig.apiReference() {
    apiSpec()
    outputPath = "build/openapi-docs"
}

private fun OpenApiDocDsl.apiSpec() {
    if (this is OpenAPIConfig) source = OpenApiDocSource.Routing()
    if (this is io.ktor.server.plugins.swagger.SwaggerConfig) source = OpenApiDocSource.Routing()
    info = OpenApiInfo(
        title = "Employee Requirements Tracker API",
        version = "0.1.0",
        description = """
            Pre-employment document collection and validation.

            This API is authoritative for "a document was received, from a link issued to this hire,
            and an HR officer looked at it and judged it valid". It is **not** authoritative for the
            identity of the person who submitted it. A packet status of COMPLETE means the paperwork
            is in and looks right — it does not mean the person has been verified, and no downstream
            process should read it that way (PRD 1).
        """.trimIndent(),
    )
}

internal fun isDevMode(): Boolean = System.getenv("APP_ENV").orEmpty().ifEmpty { "dev" } == "dev"
