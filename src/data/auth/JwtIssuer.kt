package com.pgsystem.employee.requirement.tracker.data.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.pgsystem.employee.requirement.tracker.core.value.AccessToken
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.TokenGrant
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * The [AccessTokenIssuer] adapter: an HMAC-SHA256 JWT (ERT-190).
 *
 * The mechanism survives from the Q4 placeholder; the framing does not. The scheme no longer signs
 * with a key generated per run — it signs [config]'s key, and the subject is a `users` row rather
 * than nobody.
 *
 * **[JwtConfig] is shared with `plugin/Security.kt`, which is what makes issue and verify agree.**
 * Issuer, audience and secret being read twice from the environment would compile and would pass
 * every test that mints and verifies in one process; it would fail only where the two readings
 * differed, which is a deployment nobody can reproduce locally. One value object, read once, bound
 * once.
 *
 * ### The claims, and what is deliberately absent
 *
 * `sub` is the user id, `role` the role name, and [CHANGE_REQUIRED_CLAIM] the password gate — the
 * three things an authorization decision needs, and **nothing else**. No email, no name: a JWT is
 * signed, not encrypted, so every claim is readable by anyone holding the token, and a claim nobody
 * checks is only a disclosure.
 *
 * ### The TTL is the revocation window, and it is a trade
 *
 * The verifier validates claims only and does **not** resolve `sub` against `users` on every request,
 * so **deactivating a user takes effect within [JwtConfig.ttl], default 60 minutes.** Stated here
 * rather than discovered later: an account disabled for cause is live for up to an hour, and the
 * immediate control is revoking what the person can reach, not the token. Resolving the subject per
 * request would close that window and put a query in front of every HR call; that is the trade, and
 * it is revisable — a short-TTL token plus a refresh endpoint is the usual next step.
 *
 * **[CHANGE_REQUIRED_CLAIM] inherits that window, and the consequence is worth naming.** An admin
 * resetting a password does not invalidate a token already issued to that account: its holder keeps
 * working until it expires. This is the same exposure deactivation has, for the same reason, and it
 * is why a reset is not a way to eject someone mid-session.
 *
 * In the other direction the claim goes stale harmlessly: after a successful password change the
 * old token still says a change is required, so the user signs in again. A route could avoid that by
 * minting a replacement, and deliberately does not — issuing a token outside the one use case that
 * decides a sign-in succeeded is how a second, unaudited grant path gets built.
 */
class JwtIssuer(private val config: JwtConfig) : AccessTokenIssuer {

    override fun issue(user: HrUser, issuedAt: Instant): TokenGrant {
        // Truncated to whole seconds because `exp` and `iat` are NumericDate -- seconds since the
        // epoch. Without this the returned expiresAt carries sub-second precision the token does
        // not, and a test asserting the two agree fails for a reason that has nothing to do with
        // the rule it is testing.
        val issued = issuedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val expiresAt = issued.plus(config.ttl)

        val token = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(user.id.value)
            .withClaim(ROLE_CLAIM, user.role.name)
            .withClaim(CHANGE_REQUIRED_CLAIM, user.passwordChangeRequired)
            .withIssuedAt(Date.from(issued))
            .withExpiresAt(Date.from(expiresAt))
            .sign(Algorithm.HMAC256(config.secret))

        return TokenGrant(AccessToken(token), expiresAt)
    }

    companion object {
        /** The claim carrying [com.pgsystem.employee.requirement.tracker.domain.model.HrRole]. */
        const val ROLE_CLAIM = "role"

        /**
         * Whether the holder must change their password before doing anything else.
         *
         * Underscored rather than camelCase because it is a wire name on a token other tools read,
         * and JWT convention is snake_case. It is the one claim whose *absence* must be read as
         * `true`: a token minted before this claim existed should be treated as needing a change
         * rather than as exempt. `plugin/Security.kt` does that.
         */
        const val CHANGE_REQUIRED_CLAIM = "pwd_change"
    }
}

/**
 * Everything both halves of the JWT scheme need, read from the environment once.
 *
 * [secret] is nullable **only** so `fromEnvironment` can express "unset in dev, so generate one".
 * Outside dev it cannot be null: [fromEnvironment] refuses to start instead. See [signingKey].
 */
data class JwtConfig(
    val issuer: String,
    val audience: String,
    val realm: String,
    val secret: String,
    val ttl: Duration,
) {
    companion object {
        const val DEFAULT_TTL_MINUTES = 60L

        /**
         * Reads the environment, refusing to start outside dev without a secret.
         *
         * `takeUnless(isBlank)`, not a bare read: a `.env` copied from `.env.example` supplies the
         * empty string rather than nothing, and `== null` catches an unset variable but not an empty
         * one. Without this, `JWT_SECRET=""` boots outside dev on an empty signing key — the precise
         * failure the check exists to prevent, reached by following the documentation.
         *
         * An unparseable `JWT_TTL_MINUTES` falls back to the default rather than refusing to boot.
         * It is a tuning knob, not a secret; a typo in it should not take a deployment down. It is
         * still not silent — [warnings] carries the line, and the caller logs it.
         */
        fun fromEnvironment(
            devMode: Boolean,
            secret: String? = System.getenv("JWT_SECRET"),
            issuer: String? = System.getenv("JWT_ISSUER"),
            audience: String? = System.getenv("JWT_AUDIENCE"),
            realm: String? = System.getenv("JWT_REALM"),
            ttlMinutes: String? = System.getenv("JWT_TTL_MINUTES"),
        ): Configured {
            val warnings = mutableListOf<String>()
            val configured = secret?.takeUnless(String::isBlank)

            if (configured == null) {
                check(devMode) { "JWT_SECRET must be set outside dev. Refusing to start on a default key." }
                warnings += "JWT_SECRET is not set. Using an ephemeral development key; tokens will not survive a restart."
            }

            val ttl = ttlMinutes?.takeUnless(String::isBlank)?.let { raw ->
                raw.toLongOrNull()?.takeIf { it > 0 } ?: run {
                    warnings += "JWT_TTL_MINUTES is '$raw', which is not a positive whole number. Using $DEFAULT_TTL_MINUTES."
                    null
                }
            } ?: DEFAULT_TTL_MINUTES

            return Configured(
                config = JwtConfig(
                    issuer = issuer?.takeUnless(String::isBlank) ?: "http://localhost:8080/",
                    audience = audience?.takeUnless(String::isBlank) ?: "employee-requirements-tracker",
                    realm = realm?.takeUnless(String::isBlank) ?: "Employee Requirements Tracker",
                    secret = configured ?: signingKey(),
                    ttl = Duration.ofMinutes(ttl),
                ),
                warnings = warnings,
            )
        }

        /** The ephemeral dev key. Per-process, so tokens do not survive a restart — which is the point. */
        private fun signingKey(): String = java.util.UUID.randomUUID().toString()
    }

    /**
     * The config plus anything the caller should log.
     *
     * Warnings are returned rather than logged here because this class is constructed from `di/`,
     * and `org.slf4j` is the wrong dependency for a value object — the caller holds an
     * `Application.log` that tags the line with the right logger.
     */
    data class Configured(val config: JwtConfig, val warnings: List<String>)
}
