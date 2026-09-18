package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.AccessToken
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.TokenGrant
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The [AccessTokenIssuer] a use-case test uses (ERT-190).
 *
 * Returns a readable, obviously-fake string rather than a real JWT. A use case must not care what a
 * token looks like — it hands one back — so signing one here would mean a domain test failing when
 * the signing algorithm changed, which is the opposite of what these tests are for. `HrTokens` is
 * the helper for the other job, minting a token the **application** accepts.
 *
 * [issuedFor] records who a token was minted for, so "a token was issued for the wrong user" and "no
 * token was issued at all" are distinguishable. That matters on the sign-in path, where the failure
 * branch must not reach the issuer at all — and a fake that only returned a value could not say so.
 */
class FakeAccessTokenIssuer(private val ttl: Duration = Duration.ofMinutes(60)) : AccessTokenIssuer {

    private val issued = mutableListOf<HrUser>()

    /** Every user a token was minted for, in order. Empty means the issuer was never reached. */
    val issuedFor: List<HrUser> get() = issued.toList()

    /**
     * Expiry is computed from `issuedAt` **truncated to whole seconds**, exactly as `JwtIssuer` does.
     *
     * A JWT's `exp` is a NumericDate — seconds since the epoch — so an issuer that adds the TTL to
     * the instant as given returns a time the token cannot represent, and tells its caller an expiry
     * the verifier disagrees with by up to a second.
     *
     * This fake added the TTL to the raw instant until ERT-250's contract suite compared the two.
     * The divergence is on every `Instant.now()` and therefore on every real sign-in; it stayed
     * invisible because `FixedClock.DEFAULT` happens to sit on a whole second, so no existing test
     * ever handed either implementation an instant that could tell them apart. It was not one of the
     * six HAR-01 listed — the contract suite found it.
     */
    override fun issue(user: HrUser, issuedAt: Instant): TokenGrant {
        issued += user
        return TokenGrant(
            token = AccessToken("fake-token-for-${user.id.value}-as-${user.role.name}"),
            expiresAt = issuedAt.truncatedTo(ChronoUnit.SECONDS).plus(ttl),
        )
    }
}
