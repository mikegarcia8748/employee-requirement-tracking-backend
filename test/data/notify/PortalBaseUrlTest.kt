package com.pgsystem.employee.requirement.tracker.data.notify

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `PORTAL_BASE_URL` as a pure function (ERT-440).
 *
 * Tested the way `bootstrapDecision` is: the rule is a function of its inputs, so it needs no
 * container, no server and no environment — which is what lets the "outside dev" branch be exercised
 * at all. A test that had to set a real environment variable could not run both branches in one JVM.
 */
class PortalBaseUrlTest {

    @Test
    fun `portal base url - unset outside dev - refuses to start`() {
        // The whole point of the variable. An invitation rendered without an origin has already
        // left by the time anyone notices, and the token is not stored -- so fixing the variable
        // does not fix the link, and the remedy is reissuing every credential sent since the deploy.
        val failure = assertFailsWith<IllegalStateException> {
            PortalBaseUrl.fromEnvironment(devMode = false, raw = null)
        }

        failure.message!!.contains("PORTAL_BASE_URL") shouldBe true
    }

    @Test
    fun `portal base url - an empty string outside dev - is treated as unset rather than as configured`() {
        // A .env copied from .env.example supplies "" rather than nothing, and `== null` catches an
        // unset variable but not an empty one. Without takeUnless(isBlank) this boots and renders
        // "/portal/abc" with no host -- the exact failure the check exists to prevent, reached by
        // following the documentation. JwtConfig records the same trap.
        assertFailsWith<IllegalStateException> {
            PortalBaseUrl.fromEnvironment(devMode = false, raw = "")
        }
    }

    @Test
    fun `portal base url - unset in dev - falls back to localhost with a warning`() {
        val configured = PortalBaseUrl.fromEnvironment(devMode = true, raw = null)

        configured.url.value shouldBe PortalBaseUrl.DEV_DEFAULT
        configured.warnings.size shouldBe 1
    }

    @Test
    fun `portal base url - a configured origin - is used with no warning`() {
        val configured =
            PortalBaseUrl.fromEnvironment(devMode = false, raw = "https://portal.example.com")

        configured.url.value shouldBe "https://portal.example.com"
        configured.warnings.shouldBeEmpty()
    }

    @Test
    fun `portal base url - a trailing slash - is trimmed so the link has no double slash`() {
        // ".env.example" says "with no trailing slash", and documentation is not enforcement: one
        // copied from a browser address bar has one, and the link would read
        // "https://portal.example.com//portal/abc".
        val configured =
            PortalBaseUrl.fromEnvironment(devMode = false, raw = "https://portal.example.com/")

        configured.url.linkTo("abc") shouldBe "https://portal.example.com/portal/abc"
    }

    @Test
    fun `portal link - a token - appears in the path exactly once`() {
        PortalBaseUrl("https://portal.example.com").linkTo("tok-123") shouldBe
            "https://portal.example.com/portal/tok-123"
    }
}
