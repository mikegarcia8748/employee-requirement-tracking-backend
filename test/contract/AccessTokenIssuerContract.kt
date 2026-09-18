package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * One token issuer, two implementations, one expiry rule (ERT-250).
 *
 * Neither side needs a database, so this suite is the cheapest in `test/contract/` — and it found
 * the one divergence the 2026-09-18 review did not, which is the argument for writing a contract per
 * port rather than only for the ports whose divergences were already known.
 *
 * **`exp` is a NumericDate — seconds since the epoch — so `JwtIssuer` truncates `issuedAt` to whole
 * seconds before adding the TTL.** Without that, the `expiresAt` it returns carries sub-second
 * precision the token itself does not, and the two disagree by up to a second.
 * `FakeAccessTokenIssuer` added the TTL to the instant as given, so the two returned different
 * answers for every `issuedAt` that was not already on a second boundary — which is every
 * `Instant.now()`, and therefore every real sign-in.
 *
 * It is latent rather than live only because `FixedClock.DEFAULT` happens to sit on a whole second.
 * A use-case test asserting `grant.expiresAt shouldBe now.plus(ttl)` would pass against the fake and
 * fail against the issuer the moment the clock moved off one.
 */
abstract class AccessTokenIssuerContract {

    protected abstract val issuer: AccessTokenIssuer

    /** Both sides are configured with the same 60 minutes, or the expiry assertions mean nothing. */
    protected val ttl: Duration = Duration.ofMinutes(60)

    @Test
    fun `token contract - an issued grant - both implementations expire it one ttl after issue`() {
        val issuedAt = FixedClock.DEFAULT

        issuer.issue(anHrUser(), issuedAt).expiresAt shouldBe issuedAt.plus(ttl)
    }

    @Test
    fun `token contract - an issue instant carrying sub-second precision - both implementations expire on a whole second`() {
        // `exp` is a NumericDate. An issuer that adds the TTL to the instant as given returns an
        // expiry the token cannot represent, and the caller is told a time the verifier disagrees
        // with. Every `Instant.now()` lands here; only a fixed clock avoids it.
        val issuedAt = FixedClock.DEFAULT.plusNanos(750_000_000)

        val expiresAt = issuer.issue(anHrUser(), issuedAt).expiresAt

        expiresAt shouldBe issuedAt.truncatedTo(ChronoUnit.SECONDS).plus(ttl)
        expiresAt.nano shouldBe 0
    }

    @Test
    fun `token contract - a grant - both implementations return a non-blank token`() {
        issuer.issue(anHrUser(), FixedClock.DEFAULT).token.value.isNotBlank() shouldBe true
    }

    @Test
    fun `token contract - two accounts - both implementations mint different tokens`() {
        // A token that did not vary with its subject would let one account present another's
        // credential. The fake spells the subject into the string it returns for the same reason.
        val one = issuer.issue(anHrUser(), FixedClock.DEFAULT).token.value
        val other = issuer.issue(anHrAdmin(), FixedClock.DEFAULT).token.value

        one shouldNotBe other
    }
}

class FakeAccessTokenIssuerContractTest : AccessTokenIssuerContract() {
    override val issuer: AccessTokenIssuer = FakeAccessTokenIssuer(ttl = Duration.ofMinutes(60))
}

class JwtIssuerContractTest : AccessTokenIssuerContract() {
    override val issuer: AccessTokenIssuer = JwtIssuer(testJwtConfig(ttl = Duration.ofMinutes(60)))
}
