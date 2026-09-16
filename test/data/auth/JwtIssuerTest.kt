package com.pgsystem.employee.requirement.tracker.data.auth

import com.auth0.jwt.JWT
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The JWT adapter and its configuration (ERT-190).
 *
 * The claims are asserted by **decoding** the token rather than by trusting the builder, because the
 * failure worth guarding against is a claim the verifier cannot read — a name spelled differently on
 * the two sides compiles, and passes any test that only inspects the issuer.
 */
class JwtIssuerTest {

    private val config = testJwtConfig()

    @Test
    fun `hr token - an issued token - carries the user id as sub and the role as a claim`() {
        val admin = anHrAdmin()

        val token = JWT.decode(JwtIssuer(config).issue(admin, FixedClock.DEFAULT).token.value)

        token.subject shouldBe admin.id.value
        token.getClaim(JwtIssuer.ROLE_CLAIM).asString() shouldBe HrRole.HR_ADMIN.name
        token.audience shouldBe listOf(config.audience)
        token.issuer shouldBe config.issuer
    }

    @Test
    fun `hr token - an issued token - carries the password-change gate as a claim`() {
        val owing = anHrUser(passwordChangeRequired = true)

        JWT.decode(JwtIssuer(config).issue(owing, FixedClock.DEFAULT).token.value)
            .getClaim(JwtIssuer.CHANGE_REQUIRED_CLAIM).asBoolean() shouldBe true
    }

    @Test
    fun `hr token - an issued token - carries nothing but the claims authorization reads`() {
        // A JWT is signed, not encrypted, so every claim is readable by anyone holding the token. A
        // claim nobody checks is only a disclosure -- which is why there is no email and no name.
        val user = anHrUser()

        val token = JwtIssuer(config).issue(user, FixedClock.DEFAULT).token.value

        JWT.decode(token).claims.keys shouldBe
            setOf("iss", "aud", "sub", JwtIssuer.ROLE_CLAIM, JwtIssuer.CHANGE_REQUIRED_CLAIM, "iat", "exp")
    }

    @Test
    fun `hr token - the reported expiry - matches the token's own exp claim`() {
        // The route publishes expiresAt beside the token so a client need not decode a JWT. If the
        // two disagreed a client would refresh at the wrong moment, and nothing would say why.
        val grant = JwtIssuer(config).issue(anHrUser(), FixedClock.DEFAULT)

        grant.expiresAt shouldBe FixedClock.DEFAULT.plus(config.ttl)
        JWT.decode(grant.token.value).expiresAtAsInstant shouldBe grant.expiresAt
    }

    @Test
    fun `hr token - an issued-at with sub-second precision - is truncated so the two still agree`() {
        // `exp` and `iat` are NumericDate -- whole seconds. Without truncating, the returned
        // expiresAt carries precision the token does not, and the assertion above fails for a reason
        // that has nothing to do with the rule it tests.
        val odd = FixedClock.DEFAULT.plusNanos(123_456_789)

        val grant = JwtIssuer(config).issue(anHrUser(), odd)

        JWT.decode(grant.token.value).expiresAtAsInstant shouldBe grant.expiresAt
        grant.expiresAt shouldBe FixedClock.DEFAULT.plus(config.ttl)
    }

    @Test
    fun `hr token - the token value - is redacted when rendered as a string`() {
        // It is a live bearer credential for JWT_TTL_MINUTES. Accidental interpolation into a log
        // line hands it over, and the only place that may step around this is the sign-in response.
        val grant = JwtIssuer(config).issue(anHrUser(), FixedClock.DEFAULT)

        grant.token.toString() shouldNotContain grant.token.value
        grant.token.toString() shouldBe "AccessToken(******)"
    }

    // ── Configuration ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `jwt configuration - JWT_SECRET is unset outside dev - startup still refuses`() {
        // Unchanged from the Q4 placeholder, and it has to stay that way: this is the check that
        // stops a deployment booting on a key anyone can read out of the repository.
        assertFailsWith<IllegalStateException> {
            JwtConfig.fromEnvironment(devMode = false, secret = null)
        }
    }

    @Test
    fun `jwt configuration - an empty JWT_SECRET outside dev - is treated as unset`() {
        // Sourcing a .env copied from .env.example exports JWT_SECRET="" rather than leaving it
        // unset, so `== null` alone would boot production on an empty signing key -- reached by
        // following the documentation.
        assertFailsWith<IllegalStateException> {
            JwtConfig.fromEnvironment(devMode = false, secret = "")
        }
    }

    @Test
    fun `jwt configuration - JWT_SECRET is unset in dev - generates an ephemeral key and warns`() {
        val configured = JwtConfig.fromEnvironment(devMode = true, secret = null)

        configured.warnings.size shouldBe 1
        configured.config.secret.isNotBlank() shouldBe true
    }

    @Test
    fun `jwt configuration - two dev instances with no secret - do not share a key`() {
        // Per-process, so tokens do not survive a restart. This is also why a route test cannot use
        // fromEnvironment to configure both halves -- HrTokens pins a fixed secret instead.
        JwtConfig.fromEnvironment(devMode = true, secret = null).config.secret shouldNotBe
            JwtConfig.fromEnvironment(devMode = true, secret = null).config.secret
    }

    @Test
    fun `jwt configuration - no JWT_TTL_MINUTES - defaults to sixty`() {
        JwtConfig.fromEnvironment(devMode = true, secret = "a-secret", ttlMinutes = null)
            .config.ttl shouldBe Duration.ofMinutes(JwtConfig.DEFAULT_TTL_MINUTES)
    }

    @Test
    fun `jwt configuration - a JWT_TTL_MINUTES that is not a positive whole number - warns and keeps the default`() {
        // A tuning knob, not a secret: a typo in it must not take a deployment down. It must not be
        // silent either, or someone spends an afternoon wondering why the window did not move.
        listOf("sixty", "0", "-5", "60.5").forEach { raw ->
            val configured = JwtConfig.fromEnvironment(devMode = true, secret = "a-secret", ttlMinutes = raw)

            configured.config.ttl shouldBe Duration.ofMinutes(JwtConfig.DEFAULT_TTL_MINUTES)
            configured.warnings.size shouldBe 1
        }
    }

    @Test
    fun `jwt configuration - a valid JWT_TTL_MINUTES - is used without a warning`() {
        val configured = JwtConfig.fromEnvironment(devMode = true, secret = "a-secret", ttlMinutes = "15")

        configured.config.ttl shouldBe Duration.ofMinutes(15)
        configured.warnings.isEmpty() shouldBe true
    }

    @Test
    fun `jwt configuration - unset issuer audience and realm - fall back to the documented defaults`() {
        // .env.example publishes these three strings as the defaults. If they drifted, a deployment
        // that set only some of them would verify against an audience nothing issues.
        val configured = JwtConfig.fromEnvironment(devMode = true, secret = "a-secret").config

        configured.issuer shouldBe "http://localhost:8080/"
        configured.audience shouldBe "employee-requirements-tracker"
        configured.realm shouldBe "Employee Requirements Tracker"
    }
}
