package com.pgsystem.employee.requirement.tracker.plugin

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * The rule that decides which configuration a deployment came up in (ERT-1120).
 *
 * Tests the **resolution**, not the plugin, for the reason `HrBootstrapTest` tests
 * `bootstrapDecision`: a JVM test cannot unset an environment variable in its own process, so the
 * only provable form of "unset means production" is a pure function over the raw string.
 *
 * This file exists because the previous default ran the other way. `APP_ENV` unset meant dev, and
 * dev meant `/openapi`, `/swagger` and `/metrics` served unauthenticated on an ephemeral signing key
 * and an ephemeral pepper. A deployment that forgot one variable did not fail — it came up looking
 * healthy and fully permissive, and nothing in the running system said so.
 */
class AppEnvironmentTest {

    @Test
    fun `environment resolution - APP_ENV unset - resolves to production`() {
        resolveEnvironment(null) shouldBe AppEnvironment.PRODUCTION
    }

    @Test
    fun `environment resolution - APP_ENV blank - resolves to production`() {
        // Sourcing a file copied from .env.example exports the empty string rather than leaving the
        // variable unset. Every other reader in this codebase uses takeUnless(isBlank) for exactly
        // that reason; resolution has to agree, or following the documentation selects dev.
        resolveEnvironment("") shouldBe AppEnvironment.PRODUCTION
        resolveEnvironment("   ") shouldBe AppEnvironment.PRODUCTION
    }

    @Test
    fun `environment resolution - APP_ENV dev - keeps the dev affordances`() {
        resolveEnvironment("dev") shouldBe AppEnvironment.DEV
        resolveEnvironment("dev").isDev shouldBe true
    }

    @Test
    fun `environment resolution - a typo near dev - resolves to production`() {
        // The whole point of the inversion: anything unrecognised must land on the strict side.
        // "devv" and "develop" are the shapes a typo actually takes.
        resolveEnvironment("devv") shouldBe AppEnvironment.PRODUCTION
        resolveEnvironment("develop") shouldBe AppEnvironment.PRODUCTION
        resolveEnvironment("d3v") shouldBe AppEnvironment.PRODUCTION
    }

    @Test
    fun `environment resolution - staging - is not dev`() {
        // .env.example documents dev | staging | production, and UAT runs as staging. Staging must
        // select the strict path: it holds real invitations addressed to real people.
        resolveEnvironment("staging") shouldBe AppEnvironment.STAGING
        resolveEnvironment("staging").isDev shouldBe false
    }

    @Test
    fun `environment resolution - surrounding whitespace and case - does not change the answer`() {
        // A value pasted into a Cloud Run console or a YAML file picks up spacing and capitalisation.
        // Failing closed on " dev " would be correct and useless -- it is unambiguously dev.
        resolveEnvironment(" dev ") shouldBe AppEnvironment.DEV
        resolveEnvironment("DEV") shouldBe AppEnvironment.DEV
        resolveEnvironment("Production") shouldBe AppEnvironment.PRODUCTION
    }

    @Test
    fun `startup summary - any boot - names every environment-gated control`() {
        val lines = StartupSummary(
            environment = AppEnvironment.PRODUCTION,
            jwtSecretConfigured = true,
            tokenPepperConfigured = true,
            databaseUrl = "jdbc:postgresql://10.20.128.3:5432/ert",
            tracingEnabled = false,
        ).lines()

        val text = lines.joinToString("\n")
        // Every control that hangs off APP_ENV, by name. "Which mode is this?" has to be answerable
        // from the log rather than by reading five files.
        text shouldContain "/openapi"
        text shouldContain "/swagger"
        text shouldContain "/metrics"
        text shouldContain "JWT"
        text shouldContain "pepper"
        text shouldContain "database"
        text shouldContain "tracing"
        text shouldContain "PRODUCTION"
    }

    @Test
    fun `startup summary - a secret in the database url - is redacted`() {
        // A JDBC URL is the one summary input that can carry a credential, because a password can be
        // passed as a query parameter instead of DATABASE_PASSWORD. Everything else on this line is
        // a boolean by construction, so it cannot leak a value.
        val lines = StartupSummary(
            environment = AppEnvironment.PRODUCTION,
            jwtSecretConfigured = true,
            tokenPepperConfigured = true,
            databaseUrl = "jdbc:postgresql://db:5432/ert?user=ert&password=hunter2sekrit",
            tracingEnabled = false,
        ).lines()

        val text = lines.joinToString("\n")
        text shouldNotContain "hunter2sekrit"
        text shouldNotContain "password"
        text shouldContain "jdbc:postgresql://db:5432/ert"
    }

    @Test
    fun `startup summary - dev - reports the permissive state rather than hiding it`() {
        val text = StartupSummary(
            environment = AppEnvironment.DEV,
            jwtSecretConfigured = false,
            tokenPepperConfigured = false,
            databaseUrl = null,
            tracingEnabled = true,
        ).lines().joinToString("\n")

        text shouldContain "DEV"
        text shouldContain "ephemeral"
        // An unset DATABASE_URL in dev is the in-memory fallback. Saying so is the difference
        // between "my data vanished" being a mystery and being a line in the log.
        text shouldContain "in-memory"
    }

    @Test
    fun `startup summary - production - states the multi-instance assumption`() {
        // Architecture 14 left "pinned to one instance, or not" open, and ERT-660 and ERT-1020 both
        // read the answer. Cloud Run is multi-instance, so the answer is recorded where an operator
        // sees it, not only in a document.
        val text = StartupSummary(
            environment = AppEnvironment.PRODUCTION,
            jwtSecretConfigured = true,
            tokenPepperConfigured = true,
            databaseUrl = "jdbc:postgresql://db:5432/ert",
            tracingEnabled = false,
        ).lines().joinToString("\n")

        text shouldContain "multi-instance"
    }

    @Test
    fun `startup summary - every line - is one log line`() {
        val lines = StartupSummary(
            environment = AppEnvironment.STAGING,
            jwtSecretConfigured = true,
            tokenPepperConfigured = true,
            databaseUrl = "jdbc:postgresql://db:5432/ert_uat",
            tracingEnabled = false,
        ).lines()

        // Cloud Logging ingests one entry per line, so an embedded newline would fragment the
        // summary into entries that read as unrelated.
        lines.forEach { it shouldNotContain "\n" }
        lines shouldHaveSize lines.distinct().size
    }

    @Test
    fun `startup summary - a key missing outside dev - says it will refuse rather than ephemeral`() {
        // Outside dev an unset key is not an ephemeral key -- the application refuses to start a few
        // lines later. Reporting "ephemeral" would send whoever is reading the log looking for a
        // rotation problem instead of a missing variable.
        val text = StartupSummary(
            environment = AppEnvironment.PRODUCTION,
            jwtSecretConfigured = false,
            tokenPepperConfigured = false,
            databaseUrl = "jdbc:postgresql://db:5432/ert",
            tracingEnabled = false,
        ).lines().joinToString("\n")

        text shouldContain "JWT_SECRET is required outside dev"
        text shouldContain "TOKEN_PEPPER is required outside dev"
        text shouldNotContain "ephemeral"
    }
}
