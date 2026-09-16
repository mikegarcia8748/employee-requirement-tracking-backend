package com.pgsystem.employee.requirement.tracker.plugin

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The startup rule for the first `HR_ADMIN` (ERT-190).
 *
 * Tests the **decision**, not the plugin. `bootstrapDecision` is a pure function of four values, so
 * every branch is reachable without an environment, a container, a database or a Ktor application —
 * which is the whole reason the rule was pulled out of `configureHrBootstrap`.
 *
 * The one this file exists for is the refusal: a deployment that comes up with no way in, or with a
 * known way in, is worse than one that does not come up. Same shape as `JWT_SECRET` and
 * `TOKEN_PEPPER`, and it needs the same enforcement.
 */
class HrBootstrapTest {

    @Test
    fun `bootstrap - an empty users table outside dev with nothing set - startup fails`() = runTest {
        val decision = bootstrapDecision(devMode = false, email = null, password = null, accountCount = { 0L })

        decision.shouldBeInstanceOf<BootstrapDecision.Refuse>()
        decision.message shouldContain "Refusing to start"
    }

    @Test
    fun `bootstrap - an empty users table in dev with nothing set - starts with a warning`() = runTest {
        // A fresh checkout must run with no configuration at all -- the same promise DATABASE_URL and
        // JWT_SECRET make. The line has to say what to set, or the first sign-in attempt is the only
        // signal.
        val decision = bootstrapDecision(devMode = true, email = null, password = null, accountCount = { 0L })

        decision.shouldBeInstanceOf<BootstrapDecision.Warn>()
        decision.message shouldContain BOOTSTRAP_EMAIL
        decision.message shouldContain BOOTSTRAP_PASSWORD
    }

    @Test
    fun `bootstrap - a populated users table outside dev with nothing set - starts normally`() = runTest {
        // Deliberately narrower than the JWT_SECRET check: an established deployment that has since
        // dropped the variables from its environment must start. Refusing there would turn tidying up
        // a secret store into an outage.
        bootstrapDecision(devMode = false, email = null, password = null, accountCount = { 3L })
            .shouldBeInstanceOf<BootstrapDecision.Skip>()
    }

    @Test
    fun `bootstrap - both variables set - hands them to the use case whatever the table holds`() =
        runTest {
            // "Only when the table is empty" is the USE CASE's rule, not this one. Duplicating it
            // here would give it two definitions that could drift.
            val decision = bootstrapDecision(
                devMode = false,
                email = "admin@example.com",
                password = "a-long-enough-password",
                accountCount = { error("must not be read when both variables are set") },
            )

            decision shouldBe BootstrapDecision.Create("admin@example.com", "a-long-enough-password")
        }

    @Test
    fun `bootstrap - an empty string outside dev - is treated as unset rather than as configured`() =
        runTest {
            // Sourcing a .env copied from .env.example exports HR_BOOTSTRAP_PASSWORD="" rather than
            // leaving it unset. Read as configured, that would hand the use case a blank password --
            // which it would reject, but as a startup crash naming the wrong cause.
            bootstrapDecision(devMode = false, email = "admin@example.com", password = "", accountCount = { 0L })
                .shouldBeInstanceOf<BootstrapDecision.Refuse>()

            bootstrapDecision(devMode = false, email = "   ", password = "a-long-enough-password", accountCount = { 0L })
                .shouldBeInstanceOf<BootstrapDecision.Refuse>()
        }

    @Test
    fun `bootstrap - only one of the two variables set - is treated as neither`() = runTest {
        // Half-configured is not configured. Proceeding with an email and no password would be a
        // crash inside the use case rather than a message naming what is missing.
        bootstrapDecision(devMode = false, email = "admin@example.com", password = null, accountCount = { 0L })
            .shouldBeInstanceOf<BootstrapDecision.Refuse>()
    }
}
