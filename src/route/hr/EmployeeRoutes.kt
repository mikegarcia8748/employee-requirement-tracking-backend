package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.core.error.map
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHireUseCase
import com.pgsystem.employee.requirement.tracker.route.auth.hrUserOrRefuse
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import com.pgsystem.employee.requirement.tracker.route.dto.CreateHireRequest
import com.pgsystem.employee.requirement.tracker.route.dto.HireCreatedDto
import com.pgsystem.employee.requirement.tracker.route.mapper.respondResult
import com.pgsystem.employee.requirement.tracker.route.mapper.toCommand
import com.pgsystem.employee.requirement.tracker.route.mapper.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.openapi.describe

/**
 * Hire creation over HTTP (ERT-450, PRD 8.1, Appendix B).
 *
 * **A thin adapter, and thinner than most.** Every rule was settled and tested in ERT-431…434:
 * the address format, the duplicate-on-active reason, both reference-id checks, the empty-catalogue
 * refusal, the requirement-set snapshot, the link and its expiry, and the invitation. This file
 * parses a body, names the acting user from the token, calls one use case and maps the sealed result
 * onto a status. There is no branch here that a use-case test could not already reach.
 *
 * **Every failure is a 422, and that is two settled rulings rather than an accident.** An unknown
 * `departmentId` or `employmentTypeId` is a validation error naming the field rather than a `404`
 * (E8): both arrive in the body, and the API contract's rule is that a body field's failure names
 * that field. And a duplicate with no reason is `ReasonRequired` → `422` rather than `Conflict` →
 * `409` (C1): the system does not refuse the request, it asks for a justification and then proceeds,
 * which is a statement about an incomplete request rather than a conflicting resource.
 *
 * **Nothing here re-handles a malformed body or a missing `Content-Type`.** `StatusPages` answers
 * `422 request_malformed` and `415 unsupported_media_type` respectively (ERT-146), and a second
 * `try` in this handler would shadow them with a worse message.
 *
 * **The response carries no credential, and not by being careful.** The plaintext token never
 * reaches `HireCreated` — it is generated, digested and handed to the notifier inside the use case —
 * and `UploadLink` holds only the digest. The route could not leak it if it tried; the test says so
 * anyway, because "could not" is a property of today's domain types.
 */
fun Route.employeeRoutes(createHire: CreateHireUseCase) {
    post("/api/employees") {
        val officer = hrUserOrRefuse() ?: return@post
        val body = call.receive<CreateHireRequest>()

        val result = createHire(body.toCommand(officer.userId))

        call.respondResult(result.map { it.toDto() }, HttpStatusCode.Created)
    }.describe {
        summary = "Create a hire"
        description = """
            Creates the hire, snapshots its requirement set from the catalogue, issues a tokenised
            upload link and queues the invitation — one call, one transaction boundary per write.

            **`invitation.status` is `QUEUED`, never `SENT`.** The invitation is written durably to
            the outbox; nothing transmits until the relay ships. A `FAILED` invitation means the hire
            **was still created** — the `201` is not conditional on delivery — and the retry is
            `resend-link`, which reissues the credential, rather than replaying a body that was never
            stored.

            **No credential appears in this response.** The link token travels in the invitation
            email and nowhere else, and no recovery PIN exists until HR issues one. Only the link's
            expiry is published.

            Every failure below is a `422` naming the body field at fault, including an unknown
            department or employment type: both arrive in the body, so neither is a `404`.
        """.trimIndent()
        operationId = "createHire"
        tag("Hire creation")
        requestBody {
            description =
                "The hire to create. `duplicateReason` is required only after a `duplicate_email.reason_required` refusal."
            required = true
            schema = jsonSchema<CreateHireRequest>()
        }
        responses {
            response(201) {
                description = "The hire, its snapshotted requirement set, and whether the invitation was queued."
                schema = jsonSchema<ApiResponse<HireCreatedDto>>()
            }
            response(409) { description = "The caller owes a password change." }
            response(422) {
                description =
                    "The address, a name, the position, a reference id or the missing duplicate reason does not validate."
            }
        }
    }
}
