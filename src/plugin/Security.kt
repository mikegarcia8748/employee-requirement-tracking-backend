package com.pgsystem.employee.requirement.tracker.plugin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig
import com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.route.auth.HrPrincipal
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt

/**
 * HR authentication (ERT-190).
 *
 * **The Q4 placeholder is gone.** PRD §14 Q4 was answered on 2026-09-16 — a handful of HR staff,
 * local accounts, two roles, bcrypt, no SSO — and architecture §14 said the scheme should be
 * *replaced* rather than extended once it landed. The mechanism survives; the framing does not. The
 * scheme no longer signs with a per-run random key outside dev, and `sub` now names a `users` row.
 *
 * ### The verifier and the issuer share one config, which arrives as a parameter
 *
 * [JwtConfig] is a `single` that `Application.kt` resolves and passes in, exactly as it passes
 * `HR_AUTH` to `configureRouting` and `MetricsRoutes` takes its gate. Two properties follow.
 *
 * **Issue and verify cannot disagree.** Read from the environment twice instead, they would agree in
 * every test that mints and verifies in one process, and differ only in a deployment nobody can
 * reproduce locally. In dev the secret is generated per instance, so a second reading would refuse
 * every token this application issued.
 *
 * **A test can configure both halves.** Reaching into the container here would make this function
 * unusable without one — `MetricsRoutesTest` assembles three plugins and no Koin at all — and, more
 * to the point, a route test could not hand it the same config `testdata/HrTokens.kt` signs with. The
 * parameter is what turns "requires HR auth" from an untestable mounting convention into an
 * assertion, which is the gap ERT-340 recorded and this ticket closes.
 *
 * ### `validate` builds an [HrPrincipal], and refuses anything it cannot fully read
 *
 * A token whose `sub` is not a [PersonId], or whose `role` is not an [HrRole], is rejected rather
 * than admitted with a default. Both are written from typed values by [JwtIssuer], so either being
 * unreadable means the token came from somewhere else — and "somewhere else" must not get an
 * `HR_OFFICER` by falling back to the lesser role.
 *
 * **A missing `pwd_change` claim reads as `true`.** Fail closed: a token minted by anything that
 * does not set the claim must land in the change-password gate rather than bypass it.
 *
 * ### What this deliberately does not do
 *
 * It does not resolve `sub` against `users`. A deactivated account therefore stays usable until its
 * token expires — see [JwtIssuer] for that trade, its default window and the control that is
 * immediate instead. Nothing here should be "fixed" into a per-request lookup without revisiting
 * that decision, because the cost is a query in front of every HR call.
 */
const val HR_AUTH = "hr-jwt"

fun Application.configureSecurity(jwt: JwtConfig) {
    authentication {
        jwt(HR_AUTH) {
            realm = jwt.realm
            verifier(
                JWT.require(Algorithm.HMAC256(jwt.secret))
                    .withAudience(jwt.audience)
                    .withIssuer(jwt.issuer)
                    .build()
            )
            validate { credential ->
                // The audience check is the verifier's job and is repeated here because
                // `JWTCredential` is what the validate block is handed; a token for another audience
                // that somehow reached this point must not produce a principal.
                if (!credential.payload.audience.contains(jwt.audience)) return@validate null

                val userId = credential.payload.subject
                    ?.let { PersonId.of(it) }
                    ?.let { (it as? DomainResult.Ok)?.value }
                    ?: return@validate null

                val role = credential.payload.getClaim(JwtIssuer.ROLE_CLAIM)
                    .asString()
                    ?.let { name -> HrRole.entries.firstOrNull { it.name == name } }
                    ?: return@validate null

                HrPrincipal(
                    userId = userId,
                    role = role,
                    // `asBoolean()` is null for a missing claim and for a claim of the wrong type.
                    // Both mean "this token does not say", and this gate fails closed.
                    passwordChangeRequired =
                        credential.payload.getClaim(JwtIssuer.CHANGE_REQUIRED_CLAIM).asBoolean() ?: true,
                )
            }
        }
    }
}
