package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.AccessToken
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.TokenGrant
import java.time.Duration
import java.time.Instant

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

    override fun issue(user: HrUser, issuedAt: Instant): TokenGrant {
        issued += user
        return TokenGrant(
            token = AccessToken("fake-token-for-${user.id.value}-as-${user.role.name}"),
            expiresAt = issuedAt.plus(ttl),
        )
    }
}
