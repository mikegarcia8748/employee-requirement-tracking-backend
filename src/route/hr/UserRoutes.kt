package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.flatMap
import com.pgsystem.employee.requirement.tracker.core.error.map
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHrUser
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ResetHrPassword
import com.pgsystem.employee.requirement.tracker.domain.usecase.ResetHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.SetHrUserActive
import com.pgsystem.employee.requirement.tracker.domain.usecase.SetHrUserActiveUseCase
import com.pgsystem.employee.requirement.tracker.route.auth.hrAdminOrRefuse
import com.pgsystem.employee.requirement.tracker.route.dto.ApiMeta
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import com.pgsystem.employee.requirement.tracker.route.dto.CreateHrUserRequest
import com.pgsystem.employee.requirement.tracker.route.dto.HrUserDto
import com.pgsystem.employee.requirement.tracker.route.dto.ResetPasswordRequest
import com.pgsystem.employee.requirement.tracker.route.dto.SetActiveRequest
import com.pgsystem.employee.requirement.tracker.route.mapper.orNotFound
import com.pgsystem.employee.requirement.tracker.route.mapper.respondError
import com.pgsystem.employee.requirement.tracker.route.mapper.respondOk
import com.pgsystem.employee.requirement.tracker.route.mapper.respondResult
import com.pgsystem.employee.requirement.tracker.route.mapper.toDto
import com.pgsystem.employee.requirement.tracker.route.mapper.toHrRole
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.openapi.describe

/**
 * User administration — `HR_ADMIN` only (ERT-190, PRD §8.13).
 *
 * The model and these endpoints land with ERT-190; the **screen** is Phase 2 with the rest of the
 * admin surface.
 *
 * ### Every handler here opens with `hrAdminOrRefuse`, and that is the point of the second role
 *
 * Without at least one route an `HR_OFFICER` cannot reach, `HR_ADMIN` would gate nothing — which is
 * precisely the "a role with no requirement behind it becomes a place to put permissions nobody has
 * thought about" that Q4's answer dropped two roles to avoid. The refusal is audited, unlike a plain
 * 401: a signed-in user reaching past their role is a §8.13 signal, while an unauthenticated 401 is
 * background noise.
 *
 * **This does not close SEC-10.** The roles differ in *configuration* rights, not *validation*
 * rights. An `HR_OFFICER` can still create a hire, change its email and approve every document
 * unaided; §8.13 keeps one effective role for v1 and mitigates it with the exception report. Q4's
 * answer is explicit that there is no separation-of-duties enforcement, and nothing here adds any.
 *
 * ### There is no delete
 *
 * `employees.created_by`, `employees.originals_sighted_by`, `submissions.reviewed_by` and
 * `app_settings.updated_by` all reference `users(id)` `on delete restrict`, so a user who has acted
 * cannot be removed — an audit trail naming a row that no longer exists is not a trail. Deactivation
 * is the off switch, and `POST /api/users/{id}/active` is it.
 */
fun Route.userAdminRoutes(
    users: HrUserRepository,
    createUser: CreateHrUserUseCase,
    setActive: SetHrUserActiveUseCase,
    resetPassword: ResetHrPasswordUseCase,
    audit: AuditLog,
    clock: Clock,
    ids: EntityIdGenerator,
) {
    get("/api/users") {
        hrAdminOrRefuse(audit, clock, ids, action = "listUsers") ?: return@get

        // Straight through the port, no use case: there is no decision here, only a projection.
        // `referenceRoutes` reads the same way and for the same reason -- a use case that does
        // nothing but forward a list is a layer, not a rule.
        val all = users.findAll().map { it.toDto() }

        call.respondOk(all, meta = ApiMeta(total = all.size))
    }.describe {
        summary = "List HR accounts"
        description = """
            Every account in email order, **deactivated ones included** — an admin needs to see who
            has been switched off, and there is no delete for them to have used instead.

            No password material is published: `HrUserDto` has no hash field.
        """.trimIndent()
        operationId = "listHrUsers"
        tag("User administration")
        responses {
            response(200) {
                description = "Every account, in email order."
                schema = jsonSchema<ApiResponse<List<HrUserDto>>>()
            }
            response(403) { description = "The caller is not an HR_ADMIN. The attempt is audited." }
        }
    }

    post("/api/users") {
        val admin = hrAdminOrRefuse(audit, clock, ids, action = "createUser") ?: return@post
        val body = call.receive<CreateHrUserRequest>()

        val result = body.role.toHrRole().flatMap { role ->
            createUser(
                CreateHrUser(
                    email = body.email,
                    fullName = body.fullName,
                    role = role,
                    initialPassword = body.initialPassword,
                    actingUserId = admin.userId,
                )
            )
        }

        call.respondResult(result.map { it.toDto() }, HttpStatusCode.Created)
    }.describe {
        summary = "Create an HR account"
        description = """
            The initial password is **supplied by the admin, not generated**, so no live credential
            is ever put in a response body — bodies get logged by proxies and pasted into tickets.

            The account is created with `passwordChangeRequired`, so whatever the admin chose stops
            working the moment its owner signs in. That is what keeps "the admin knows the password"
            from mattering.

            A duplicate address is a `409`, not the uniform sign-in failure: this route is behind
            `HR_ADMIN`, and someone who can list every account learns nothing from being told one
            exists.
        """.trimIndent()
        operationId = "createHrUser"
        tag("User administration")
        requestBody {
            description =
                "The account to create. `initialPassword` is chosen by the admin and is never returned."
            required = true
            schema = jsonSchema<CreateHrUserRequest>()
        }
        responses {
            response(201) {
                description = "The account, which owes a password change."
                schema = jsonSchema<ApiResponse<HrUserDto>>()
            }
            response(409) { description = "An account already exists for that address." }
            response(422) { description = "The address, name, role or password does not validate." }
        }
    }

    post("/api/users/{id}/active") {
        val admin = hrAdminOrRefuse(audit, clock, ids, action = "setUserActive") ?: return@post
        val body = call.receive<SetActiveRequest>()

        val result = PersonId.of(call.parameters["id"].orEmpty())
            .orNotFound(HrUser.AUDIT_ENTITY)
            .flatMap { id ->
                setActive(SetHrUserActive(userId = id, isActive = body.isActive, actingUserId = admin.userId))
            }

        call.respondResult(result.map { it.toDto() })
    }.describe {
        summary = "Activate or deactivate an account"
        description = """
            Accounts are **deactivated, never deleted**: the four actor columns reference `users(id)`
            `on delete restrict`, so a user who has created a hire or approved a document cannot be
            removed.

            An admin may not deactivate themselves — with a handful of staff and no self-registration,
            the last admin switching themselves off leaves no way back that does not involve a psql
            prompt. That is a `409`.

            **This does not revoke a live token.** The verifier reads claims and does not resolve the
            subject per request, so a deactivated user keeps working for up to `JWT_TTL_MINUTES`.

            Idempotent: setting the state it already has is a `200` with no audit row.
        """.trimIndent()
        operationId = "setHrUserActive"
        tag("User administration")
        requestBody {
            description = "The state to set. Setting the state the account already has is a no-op."
            required = true
            schema = jsonSchema<SetActiveRequest>()
        }
        responses {
            response(200) {
                description = "The account in its new state."
                schema = jsonSchema<ApiResponse<HrUserDto>>()
            }
            response(404) { description = "No such account, or a malformed id." }
            response(409) { description = "An admin cannot deactivate their own account." }
        }
    }

    post("/api/users/{id}/reset-password") {
        val admin = hrAdminOrRefuse(audit, clock, ids, action = "resetPassword") ?: return@post
        val body = call.receive<ResetPasswordRequest>()

        val result = PersonId.of(call.parameters["id"].orEmpty())
            .orNotFound(HrUser.AUDIT_ENTITY)
            .flatMap { id ->
                resetPassword(
                    ResetHrPassword(userId = id, newPassword = body.newPassword, actingUserId = admin.userId)
                )
            }

        when (result) {
            is DomainResult.Ok -> call.respondOk(HttpStatusCode.NoContent)
            is DomainResult.Err -> call.respondError(result.error)
        }
    }.describe {
        summary = "Reset an account's password"
        description = """
            **This is the whole of password recovery in v1.** Self-service reset needs a mail
            transport (ERT-1010) and a second bearer credential with its own expiry and threat model;
            for a handful of people who share an office, an admin resetting has the property a mailed
            link does not — the admin knows who they handed it to.

            `passwordChangeRequired` is set unconditionally, so the password the admin chose works
            exactly once and only for change-password.

            Like deactivation, this does **not** invalidate a token already issued to the account.
        """.trimIndent()
        operationId = "resetHrPassword"
        tag("User administration")
        requestBody {
            description = "The replacement password. `passwordChangeRequired` is set unconditionally."
            required = true
            schema = jsonSchema<ResetPasswordRequest>()
        }
        responses {
            response(204) { description = "Reset. The user must change it at next sign-in." }
            response(404) { description = "No such account, or a malformed id." }
            response(422) { description = "The new password does not satisfy the length rule." }
        }
    }
}
