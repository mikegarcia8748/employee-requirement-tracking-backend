package com.pgsystem.employee.requirement.tracker.data.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * How a deployment is pointed at a database, and what happens when it is not (ERT-1240, ERT-1241).
 *
 * **`DATABASE_URL` was the one configuration path that did not follow this project's own fail-loudly
 * contract.** `JWT_SECRET`, `TOKEN_PEPPER` and `PORTAL_BASE_URL` all refuse to start outside dev.
 * `DATABASE_URL` fell back to in-memory H2 with user `sa` and an empty password — so a production
 * deploy that lost the variable did not crash. It started, migrated a fresh schema into memory,
 * created a working admin account from `HR_BOOTSTRAP_*`, and returned 200 from `/health`. HR would
 * sign in, create hires, and lose every one of them at the next instance recycle. An integrity
 * failure that presents as a green deploy.
 *
 * The pool settings are here rather than in an integration test because they are a **function of the
 * environment**, and the values that matter on Cloud Run — a bounded `connectionTimeout`, a
 * `maxLifetime` shorter than any middlebox idle drop — are exactly the ones no local run exercises.
 */
class DatabaseConfigTest {

    @Test
    fun `database config - production with no database url - refuses to start`() {
        val failure = shouldThrow<IllegalStateException> {
            DatabaseConfig.fromEnvironment(devMode = false, url = null)
        }

        failure.message!! shouldContain "DATABASE_URL"
        failure.message!! shouldContain "Refusing to start"
    }

    @Test
    fun `database config - a blank database url - is treated as unset`() {
        // Every other reader in this codebase uses takeUnless(isBlank), because sourcing a file copied
        // from .env.example exports the empty string rather than leaving the variable unset. This one
        // used `?:`, which catches null and not "" -- so following the documentation reached the very
        // fallback the check exists to prevent.
        shouldThrow<IllegalStateException> {
            DatabaseConfig.fromEnvironment(devMode = false, url = "")
        }
        shouldThrow<IllegalStateException> {
            DatabaseConfig.fromEnvironment(devMode = false, url = "   ")
        }
    }

    @Test
    fun `database config - the refusal message - names the variable and no value`() {
        // The message is logged and may be shipped to Cloud Logging. It must be actionable without
        // being a disclosure: name what to set, never what was set.
        val failure = shouldThrow<IllegalStateException> {
            DatabaseConfig.fromEnvironment(devMode = false, url = null, password = "hunter2sekrit")
        }

        failure.message!! shouldNotContain "hunter2sekrit"
    }

    @Test
    fun `database config - dev with no database url - falls back to in-memory h2`() {
        // A fresh checkout must still run with no configuration at all -- the same promise
        // JWT_SECRET and TOKEN_PEPPER make in dev.
        val config = DatabaseConfig.fromEnvironment(devMode = true, url = null)

        config.url shouldContain "jdbc:h2:mem:"
        config.driverClassName shouldBe "org.h2.Driver"
    }

    @Test
    fun `database config - a postgres url - selects the postgres driver`() {
        val config = DatabaseConfig.fromEnvironment(
            devMode = false,
            url = "jdbc:postgresql://10.20.128.3:5432/ert",
        )

        config.driverClassName shouldBe "org.postgresql.Driver"
    }

    @Test
    fun `database config - no pool environment variables - applies the documented defaults`() {
        val config = DatabaseConfig.fromEnvironment(devMode = true, url = null)

        config.maxPoolSize shouldBe 10
        // The Hikari default is 30 seconds. With Dispatchers.IO's 64 threads against a pool of five,
        // that parks 64 coroutines for half a minute while Cloud Run's request timeout has not
        // noticed anything is wrong. Failing fast is also the signal that makes Cloud Run scale out.
        config.connectionTimeoutMs shouldBe 10_000L
        // Shorter than any middlebox idle drop. Without it a connection the network already closed is
        // handed out as healthy -- "connection reset by peer" on the first request after a quiet
        // period, which is what a min-instances=1 service does overnight.
        config.maxLifetimeMs shouldBe 1_800_000L
        config.keepaliveTimeMs shouldBe 120_000L
    }

    @Test
    fun `database config - a pool timeout override - is read from the environment`() {
        val config = DatabaseConfig.fromEnvironment(
            devMode = true,
            url = null,
            maxPoolSize = "5",
            connectionTimeoutMs = "3000",
            maxLifetimeMs = "600000",
            keepaliveTimeMs = "60000",
        )

        config.maxPoolSize shouldBe 5
        config.connectionTimeoutMs shouldBe 3_000L
        config.maxLifetimeMs shouldBe 600_000L
        config.keepaliveTimeMs shouldBe 60_000L
    }

    @Test
    fun `database config - an unparseable pool value - falls back rather than refusing to boot`() {
        // Same trade JWT_TTL_MINUTES makes: a tuning knob is not a secret, and a typo in one should
        // not take a deployment down. It is still not silent -- the warning carries the line.
        val config = DatabaseConfig.fromEnvironment(
            devMode = true,
            url = null,
            maxPoolSize = "ten",
            connectionTimeoutMs = "-1",
        )

        config.maxPoolSize shouldBe 10
        config.connectionTimeoutMs shouldBe 10_000L
        config.warnings.size shouldBe 2
    }

    @Test
    fun `database config - the pool size - never exceeds the connection budget silently`() {
        // max_instances x maxPoolSize is the number of backends a Cloud SQL tier must serve, and the
        // default max-instances of 100 with a pool of 10 is 1000 connections from one service. The
        // code cannot know max_instances, so the least it can do is refuse an absurd pool.
        val failure = shouldThrow<IllegalArgumentException> {
            DatabaseConfig.fromEnvironment(devMode = true, url = null, maxPoolSize = "500")
        }

        failure.message!! shouldContain "DATABASE_MAX_POOL_SIZE"
    }
}
