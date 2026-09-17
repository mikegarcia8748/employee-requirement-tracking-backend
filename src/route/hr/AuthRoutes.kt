package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.map
import com.pgsystem.employee.requirement.tracker.domain.usecase.AuthenticateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ChangeHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ChangePassword
import com.pgsystem.employee.requirement.tracker.domain.usecase.SignIn
import com.pgsystem.employee.requirement.tracker.route.auth.hrPrincipalOrRefuse
import com.pgsystem.employee.requirement.tracker.route.auth.hrUserOrRefuse
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import com.pgsystem.employee.requirement.tracker.route.dto.ChangePasswordRequest
import com.pgsystem.employee.requirement.tracker.route.dto.HrUserDto
import com.pgsystem.employee.requirement.tracker.route.dto.SignInRequest
import com.pgsystem.employee.requirement.tracker.route.dto.SignInResponse
import com.pgsystem.employee.requirement.tracker.route.mapper.respondError
import com.pgsystem.employee.requirement.tracker.route.mapper.respondOk
import com.pgsystem.employee.requirement.tracker.route.mapper.respondResult
import com.pgsystem.employee.requirement.tracker.route.mapper.toDto
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.openapi.describe

/**
 * Sign-in and the signed-in user's own account (ERT-190, PRD §14 Q4).
 *
 * **`signInRoutes` mounts OUTSIDE `authenticate` and the rest inside**, which is why they are two
 * functions rather than one. `Routing.kt` places them; nothing here knows about the scheme, for the
 * reason that file records — the `plugin` → `route` arrow never reverses.
 *
 * ### The gate each handler calls, and the one exception
 *
 * Change-password calls `hrPrincipalOrRefuse()`; everything else calls `hrUserOrRefuse()`, which
 * additionally refuses while a password change is owed. That exception is the point of the gate: a
 * bootstrap account and every HR-issued reset arrive with a credential that exists in a deployment
 * environment or a chat message, and change-password is the one thing it may do.
 *
 * `GET /api/auth/me` is behind the full gate too, so a user who owes a change cannot read it either.
 * That is the acceptance criterion read literally — "every route except change-password refuses" —
 * and it costs the client nothing: the sign-in response already carried the profile, including the
 * `passwordChangeRequired` flag that tells it where to go.
 */
fun Route.signInRoutes(authenticate: AuthenticateHrUserUseCase) {
    post("/api/auth/login") {
        val body = call.receive<SignInRequest>()

        val result = authenticate(
            SignIn(
                email = body.email,
                password = body.password,
                // The peer address, not a forwarded header. `X-Forwarded-For` is caller-controlled
                // and this application sits behind no proxy it has been told to trust, so reading
                // one would let an attacker choose what the audit row says about them.
                ip = call.request.local.remoteAddress,
            )
        )

        call.respondResult(result.map { it.toDto() })
    }.describe {
        summary = "Sign in"
        description = """
            Exchanges an email and password for a bearer token.

            **All four failures are intended to be identical, and must stay that way.** An address
            that is not well-formed, an address with no account, a wrong password and a deactivated
            account return the same `401` with the same body — and take the same time, because every
            path verifies a password against *some* hash. Anything that separates them turns this
            endpoint into an oracle for who works in HR. Do not "improve" the error message.

            The token carries the user id, the role and whether a password change is owed. It is
            valid for `JWT_TTL_MINUTES` (default 60), **which is also the revocation window**:
            deactivating an account does not invalidate a token already issued to it.

            Not rate-limited yet — ERT-660 owns that, and until then the audit row is the detection.
        """.trimIndent()
        operationId = "signIn"
        tag("Authentication")
        // Declared, not inferred. `OpenApiDocSource.Routing` reads the route tree and never the
        // handler body, so it no more sees `call.receive<SignInRequest>()` than it sees
        // `call.respond` -- and an operation with no `requestBody` gives Swagger UI no body editor,
        // so its "Try it out" posts nothing at all and this route answers 415 (ERT-146). Note that
        // `description` here belongs to the body: the inner receiver shadows the operation's.
        //
        // It describes the shape and says nothing about which inputs fail. Naming a failing input
        // here would put back, in prose, the oracle the four identical 401s exist to close.
        requestBody {
            description = "The address and password to exchange for a token."
            required = true
            schema = jsonSchema<SignInRequest>()
        }
        responses {
            response(200) {
                description = "A bearer token, its expiry, and the signed-in user."
                schema = jsonSchema<ApiResponse<SignInResponse>>()
            }
            response(401) {
                description =
                    "Authentication failed. Deliberately identical for a malformed address, an " +
                        "unknown address, a wrong password and a deactivated account."
                schema = jsonSchema<ApiResponse<Unit>>()
            }
        }
    }
}

/** The signed-in user's own account. Mounted inside `authenticate`. */
fun Route.accountRoutes(changePassword: ChangeHrPasswordUseCase, users: HrUserRepository) {
    post("/api/auth/change-password") {
        // hrPrincipalOrRefuse, NOT hrUserOrRefuse. This is the one route a user owing a password
        // change may reach, and the only place in the codebase that may call the weaker gate.
        val principal = hrPrincipalOrRefuse() ?: return@post
        val body = call.receive<ChangePasswordRequest>()

        val result = changePassword(
            ChangePassword(
                userId = principal.userId,
                currentPassword = body.currentPassword,
                newPassword = body.newPassword,
            )
        )

        when (result) {
            is DomainResult.Ok -> call.respondOk(HttpStatusCode.NoContent)
            is DomainResult.Err -> call.respondError(result.error)
        }
    }.describe {
        summary = "Change your own password"
        description = """
            The only route reachable while `passwordChangeRequired` is set — which is what makes a
            bootstrap account and an HR-issued reset safe to hand over.

            The existing token keeps its old `pwd_change` claim, so **sign in again afterwards**. A
            replacement token is deliberately not minted here: issuing one outside the use case that
            decides a sign-in succeeded would build a second, unaudited grant path.

            A wrong current password is `401`. There is no "must differ from the current password"
            rule, on purpose — enforcing it would answer whether a guessed string is the current
            password, from a route that is not rate-limited.
        """.trimIndent()
        operationId = "changeOwnPassword"
        tag("Authentication")
        requestBody {
            description = "The current password and its replacement."
            required = true
            schema = jsonSchema<ChangePasswordRequest>()
        }
        responses {
            response(204) { description = "Changed. Sign in again to obtain a token without the flag." }
            response(401) { description = "The current password is wrong." }
            response(422) { description = "The new password does not satisfy the length rule." }
        }
    }

    get("/api/auth/me") {
        val principal = hrUserOrRefuse() ?: return@get

        // Read through rather than rendered from the claims. The token is up to JWT_TTL_MINUTES
        // stale by design, and this is the one endpoint whose entire job is to say who the caller
        // currently is -- answering it from the token would report a role that may have changed.
        val user = users.findById(principal.userId)
            ?: return@get call.respondError(
                com.pgsystem.employee.requirement.tracker.core.error.AppError.AuthenticationFailed
            )

        call.respondOk(user.toDto())
    }.describe {
        summary = "The signed-in user"
        description = """
            Resolved against the `users` table rather than rendered from the token's claims, so a
            role changed since sign-in is reported correctly.

            Refused with `409` while a password change is owed, like every route but change-password.
        """.trimIndent()
        operationId = "currentHrUser"
        tag("Authentication")
        responses {
            response(200) {
                description = "The signed-in user."
                schema = jsonSchema<ApiResponse<HrUserDto>>()
            }
            response(409) { description = "A password change is owed." }
        }
    }
}
