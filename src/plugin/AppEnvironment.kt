package com.pgsystem.employee.requirement.tracker.plugin

import io.ktor.server.application.Application
import io.ktor.server.application.log

/**
 * Which deployment this is, and what that implies (ERT-1120).
 *
 * **The default used to fail open, and that is what this file exists to change.** `isDevMode()` read
 * `APP_ENV` and treated *unset* as `dev`, so a deployment that forgot one variable did not fail — it
 * silently selected the permissive configuration. Five controls hang off this one answer:
 *
 *  1. `/openapi` and `/swagger` served unauthenticated ([configureApiDocs])
 *  2. `/metrics` served unauthenticated (`MetricsRoutes`)
 *  3. the JWT signing key becomes a per-process random UUID (`JwtIssuer.fromEnvironment`)
 *  4. the token pepper becomes ephemeral (`HmacTokenDigest.fromEnvironment`)
 *  5. HR bootstrap warns instead of refusing ([bootstrapDecision])
 *
 * Unset now means [PRODUCTION] and `dev` is opt-in. That breaks `./kotlin run` on a fresh checkout,
 * which is why it was not done in passing and why `.env.dev` ships with it: the replacement
 * affordance is part of the change, or one silent failure is traded for a worse first-run.
 *
 * **Anything unrecognised resolves to [PRODUCTION].** A typo must land on the strict side — `devv`
 * selecting the permissive configuration would reintroduce the defect through the back door.
 */
enum class AppEnvironment {
    DEV,
    STAGING,
    PRODUCTION,
    ;

    /**
     * The single question the rest of the codebase asks.
     *
     * [STAGING] is deliberately not dev. UAT runs as staging and holds real invitations addressed to
     * real people, so it takes the strict path: real secrets, gated docs, gated metrics.
     */
    val isDev: Boolean get() = this == DEV
}

/** The variable. Named once so the summary and the documentation cannot drift from the reader. */
internal const val APP_ENV_VARIABLE = "APP_ENV"

/**
 * Resolves [raw] to an environment, failing closed.
 *
 * Trimmed and lower-cased before matching because a value pasted into a Cloud Run console or a YAML
 * file picks up spacing and capitalisation, and `" dev "` is unambiguously dev — failing closed
 * there would be correct and useless. Everything that is *not* a recognised name, including `null`
 * and blank, is [PRODUCTION].
 *
 * A parameter rather than an inline `System.getenv` so the rule is provable: a JVM test cannot unset
 * an environment variable in its own process, which is the same reason `bootstrapDecision`,
 * `JwtIssuer.fromEnvironment` and `HmacTokenDigest.fromEnvironment` all take theirs.
 */
internal fun resolveEnvironment(raw: String?): AppEnvironment =
    when (raw?.trim()?.lowercase()) {
        "dev", "development", "local" -> AppEnvironment.DEV
        "staging", "uat" -> AppEnvironment.STAGING
        else -> AppEnvironment.PRODUCTION
    }

/** The live answer. Everything gated on the environment goes through here. */
internal fun isDevMode(): Boolean = resolveEnvironment(System.getenv(APP_ENV_VARIABLE)).isDev

/**
 * The resolved state of every environment-gated control, as lines for the log.
 *
 * **Every field is a boolean or a destination — never a secret.** That is a design choice, not a
 * convention: a summary that took `jwtSecret: String` would be one careless interpolation away from
 * printing it, and [databaseUrl] is redacted because a JDBC URL is the one input here that can carry
 * a credential (a password may be passed as a query parameter instead of `DATABASE_PASSWORD`).
 *
 * "Which mode is this?" should be answerable from the log rather than by reading five files, and it
 * is logged **before** anything can refuse to start, so a boot that fails on a missing `JWT_SECRET`
 * still says what mode it thought it was in.
 */
internal data class StartupSummary(
    val environment: AppEnvironment,
    val jwtSecretConfigured: Boolean,
    val tokenPepperConfigured: Boolean,
    val databaseUrl: String?,
    val tracingEnabled: Boolean,
) {
    fun lines(): List<String> {
        val gated = if (environment.isDev) "OPEN (dev)" else "HR authentication required"
        return listOf(
            "Startup configuration — $APP_ENV_VARIABLE resolved to $environment.",
            "  api docs (/openapi, /swagger) : $gated",
            "  metrics (/metrics)            : $gated",
            "  JWT signing key               : ${keySource(jwtSecretConfigured, "JWT_SECRET")}",
            "  token pepper                  : ${keySource(tokenPepperConfigured, "TOKEN_PEPPER")}",
            "  database                      : ${describeDatabase(databaseUrl)}",
            "  use-case tracing              : ${if (tracingEnabled) "on" else "off"}",
            "  mail transport                : outbox only; nothing is transmitted (ERT-1010)",
            "  instance assumption           : multi-instance — no component may assume one process",
        )
    }

    /**
     * Where a key comes from — and, when it comes from nowhere, what that means *here*.
     *
     * The three cases are genuinely different and collapsing them misleads at the worst moment.
     * Outside dev an unset key is not an ephemeral one: the application refuses to start a few lines
     * later, and a summary that said "ephemeral" would have the reader looking for a key-rotation
     * problem instead of a missing variable.
     */
    private fun keySource(configured: Boolean, variable: String): String = when {
        configured -> "from $variable"
        environment.isDev -> "ephemeral, regenerated per process — not valid across a restart"
        else -> "NOT SET — $variable is required outside dev; startup will refuse"
    }

    /**
     * The URL without anything after the first parameter separator.
     *
     * `?` is the JDBC query separator and `;` is H2's, and a credential can appear after either. The
     * host and database name are the part an operator actually needs: "am I pointed at the right
     * database" is the question this line answers.
     */
    private fun describeDatabase(url: String?): String {
        if (url.isNullOrBlank()) return "in-memory H2 — data does not survive a restart"
        return url.takeWhile { it != '?' && it != ';' }
    }

    companion object {
        /** Reads the environment. The constructor stays pure so the rule above can be tested. */
        fun fromEnvironment(environment: AppEnvironment): StartupSummary = StartupSummary(
            environment = environment,
            jwtSecretConfigured = !System.getenv("JWT_SECRET").isNullOrBlank(),
            tokenPepperConfigured = !System.getenv("TOKEN_PEPPER").isNullOrBlank(),
            databaseUrl = System.getenv("DATABASE_URL"),
            tracingEnabled = System.getenv("TRACE_USECASES")?.trim()?.lowercase() == "true",
        )
    }
}

/**
 * Writes the summary to the log, one entry per line.
 *
 * Called **first** in [Application.rootModule], before anything can refuse to start. A boot that
 * dies on a missing `JWT_SECRET` two lines later still says what mode it thought it was in, which is
 * the difference between a one-line diagnosis and reading the deployment's environment by hand.
 */
fun Application.logStartupConfiguration() {
    StartupSummary
        .fromEnvironment(resolveEnvironment(System.getenv(APP_ENV_VARIABLE)))
        .lines()
        .forEach(log::info)
}
