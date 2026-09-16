package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig
import com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.time.Duration
import java.time.Instant

/**
 * A token this application accepts (ERT-190).
 *
 * **This closes the gap ERT-340 recorded in its own test class.** Until now no test could mint one:
 * the Q4 placeholder signed with a key generated per run, so `authenticate(HR_AUTH)` could only ever
 * be tested in the negative — "no credentials are refused". The positive half, that a valid token
 * reaches the handler, had never been tested at all, on any route.
 *
 * ### It signs with [JwtIssuer], not with a hand-rolled `JWT.create()`
 *
 * That is the whole point. A helper that assembled its own claims would drift from the issuer the
 * moment either changed — a renamed claim, an added one, a different subject encoding — and every
 * route test would keep passing against tokens production never mints. Going through the real issuer
 * means these tests break when the token shape changes, which is what they are for.
 *
 * ### The secret is fixed here rather than read from the environment
 *
 * A test must be able to configure both halves identically, and [JwtConfig.fromEnvironment] in dev
 * generates a per-instance key — two calls give two keys, and a token minted by one is refused by
 * the other. [TEST_JWT_SECRET] is what a test sets `JWT_SECRET` to, and [testJwtConfig] is what it
 * hands the container.
 */
const val TEST_JWT_SECRET = "test-jwt-secret-not-used-anywhere-else-0123456789"

/** The config both halves of a route test share. Matches `JwtConfig.fromEnvironment`'s defaults. */
fun testJwtConfig(ttl: Duration = Duration.ofMinutes(60)): JwtConfig = JwtConfig(
    issuer = "http://localhost:8080/",
    audience = "employee-requirements-tracker",
    realm = "Employee Requirements Tracker",
    secret = TEST_JWT_SECRET,
    ttl = ttl,
)

/**
 * A signed token for [user], valid from **now**.
 *
 * ### Not [FixedClock.DEFAULT], and this is the one place in the suite that must not be
 *
 * Every other timestamp in these tests comes from that constant, because a fixture anchored to
 * wall-clock time makes an expiry test pass today and fail in ninety days. A JWT is the exception:
 * `exp` is validated by the auth0 verifier against the **real** system clock, which no injected
 * [com.pgsystem.employee.requirement.tracker.core.time.Clock] reaches. `FixedClock.DEFAULT` is a
 * fixed date, so a token issued at it expires an hour later and every authenticated request in the
 * suite is a 401 — which is precisely what happened when this was written the other way.
 *
 * The rule the codebase states still holds; this is its boundary. Verified, not assumed: issuing at
 * the fixed instant turned seven route tests red at once, all with the same symptom and none of them
 * pointing at the cause.
 */
fun tokenFor(user: HrUser, config: JwtConfig = testJwtConfig()): String =
    JwtIssuer(config).issue(user, Instant.now()).token.value

/**
 * Presents [user]'s token as a bearer credential.
 *
 * ```
 * client.get("/api/users") { authenticatedAs(anHrAdmin()) }
 * ```
 */
fun HttpRequestBuilder.authenticatedAs(user: HrUser, config: JwtConfig = testJwtConfig()) {
    header(HttpHeaders.Authorization, "Bearer ${tokenFor(user, config)}")
}

/** A syntactically valid token signed with a **different** key, for the refusal case. */
fun tokenSignedWithAnotherKey(user: HrUser): String =
    tokenFor(user, testJwtConfig().copy(secret = "a-completely-different-signing-key-9876543210"))
