package com.pgsystem.employee.requirement.tracker.domain.port

import com.pgsystem.employee.requirement.tracker.core.value.AccessToken
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import java.time.Instant

/**
 * Mints the bearer credential a signed-in HR user presents (ERT-190).
 *
 * **In `domain/port/` rather than `core/`, unlike [com.pgsystem.employee.requirement.tracker.core.crypto.Hasher]
 * and `TokenDigest`.** Those two take and return strings and know nothing about this system; this one
 * takes an [HrUser] and reads its [HrUser.role], which are domain types — and `core/` may not depend
 * on `domain/`. The layer is decided by the signature, not by the feeling that it is "infrastructure".
 *
 * Why a port at all rather than minting the token in the route: the use case has to write the sign-in
 * audit row and decide the outcome, so it is already the place that knows a sign-in succeeded. Having
 * it return a user for a route to then turn into a token would put half of one decision on each side
 * of the boundary, and would leave the route able to issue a token for a user that failed.
 *
 * The domain never learns this is a JWT. [AccessToken] carries no format rule for the same reason.
 */
interface AccessTokenIssuer {
    /**
     * A token naming [user] and the instant it stops being accepted.
     *
     * [issuedAt] is passed rather than read, so the expiry is anchored to the same injected
     * [com.pgsystem.employee.requirement.tracker.core.time.Clock] the use case used — a `FixedClock`
     * test can then assert the window rather than assert against wall-clock time.
     *
     * Not `suspend`: signing is CPU work on bytes already in hand, with no I/O to await. Marking it
     * `suspend` would suggest there is something to wait for and would invite an implementation to
     * go looking for it.
     */
    fun issue(user: HrUser, issuedAt: Instant): TokenGrant
}

/** An issued token and the instant it expires. */
data class TokenGrant(val token: AccessToken, val expiresAt: Instant)
