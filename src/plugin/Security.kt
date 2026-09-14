package com.pgsystem.employee.requirement.tracker.plugin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt

/**
 * HR authentication.
 *
 * **This is a marked placeholder, not a decision.** PRD 14 Q4 — who the HR users are, whether they
 * share an account, and whether an SSO provider already exists — is a blocking open question. The
 * JWT scheme here keeps the routing layer compiling and the `authenticate` blocks honest; it should
 * be replaced once Q4 is answered.
 *
 * The generator hardcoded `jwtSecret = "secret"`. Configuration now comes from the environment and
 * startup fails loudly outside dev rather than booting on a known key.
 */
const val HR_AUTH = "hr-jwt"

fun Application.configureSecurity() {
    val issuer = System.getenv("JWT_ISSUER") ?: "http://localhost:8080/"
    val audience = System.getenv("JWT_AUDIENCE") ?: "employee-requirements-tracker"
    val realm = System.getenv("JWT_REALM") ?: "Employee Requirements Tracker"
    val secret = System.getenv("JWT_SECRET")

    if (secret == null) {
        val devMode = isDevMode()
        check(devMode) { "JWT_SECRET must be set outside dev. Refusing to start on a default key." }
        log.warn("JWT_SECRET is not set. Using an ephemeral development key; tokens will not survive a restart.")
    }

    val signingKey = secret ?: java.util.UUID.randomUUID().toString()

    authentication {
        jwt(HR_AUTH) {
            this.realm = realm
            verifier(
                JWT.require(Algorithm.HMAC256(signingKey))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(audience)) JWTPrincipal(credential.payload) else null
            }
        }
    }
}
