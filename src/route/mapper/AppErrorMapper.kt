package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.route.dto.ApiError
import com.pgsystem.employee.requirement.tracker.route.dto.ApiErrorDetail
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/**
 * The single mapping from a domain failure to an HTTP response (ERT-140, ERT-145).
 *
 * Use cases return `DomainResult.Err(AppError)` rather than throwing, so without this every route
 * would invent its own status — and routes are supposed to make no decisions. A handler calls
 * [respondError] and is done.
 *
 * Both `when`s below are expressions over a sealed interface, so **adding an `AppError` case is a
 * compile error here** rather than a silent 500 at runtime. That is the guard; it needs no test.
 */
fun AppError.toStatus(): HttpStatusCode = when (this) {
    is AppError.Validation -> HttpStatusCode.UnprocessableEntity
    is AppError.ValidationFailed -> HttpStatusCode.UnprocessableEntity
    is AppError.ReasonRequired -> HttpStatusCode.UnprocessableEntity
    is AppError.Conflict -> HttpStatusCode.Conflict
    is AppError.NotFound -> HttpStatusCode.NotFound
    AppError.Denied -> HttpStatusCode.NotFound
}

/**
 * The failure body, which carries the stable `code` and nothing internal — no stack trace, no SQL,
 * no table name, no file path (PRD 12).
 *
 * `Validation` and `ValidationFailed` render through the **same** shape, a `details` list of one
 * entry or many, so a client binds errors to a form with one expression and never branches on how
 * many fields failed.
 *
 * `NotFound` does not echo its `entity`: the code already names it (`employee_not_found`), and a
 * second field is one more thing that could differ between two failures that ought to look alike.
 *
 * `ReasonRequired` does not echo its `action` either. The action is server-side context the code
 * already implies; what a client needs is that a `reason` must be collected, which the detail says
 * directly.
 *
 * `Denied` renders one shared constant. The mapper **must not** give it a detail, a bespoke message
 * or a `details` entry — any of those is precisely what would pull a wrong PIN and an unknown token
 * apart (PRD 6.6, SEC-01).
 */
fun AppError.toApiError(): ApiError = when (this) {
    is AppError.Validation ->
        ApiError(
            code = "validation_failed",
            message = messageFor("validation_failed"),
            details = listOf(toDetail()),
        )

    is AppError.ValidationFailed ->
        ApiError(
            code = code,
            message = messageFor(code),
            details = errors.map { it.toDetail() },
        )

    is AppError.ReasonRequired ->
        ApiError(
            code = code,
            message = messageFor(code),
            details = listOf(ApiErrorDetail(code = code, field = "reason")),
        )

    is AppError.Conflict -> ApiError(code = code, message = detail)
    is AppError.NotFound -> ApiError(code = code, message = messageFor(code))
    AppError.Denied -> DENIED_ERROR
}

/** One field that did not validate. The domain's own `detail` is the display string here. */
private fun AppError.Validation.toDetail(): ApiErrorDetail =
    ApiErrorDetail(code = code, field = field, message = detail)

/** The full envelope for a failure, with `result` derived from the status rather than chosen. */
fun AppError.toResponse(): ApiResponse<Unit> =
    if (this == AppError.Denied) NOT_FOUND_BODY else errorEnvelope(toStatus(), toApiError())

/**
 * The failure body every unmatched route and every `Denied` produces, to the byte.
 *
 * A mistyped portal sub-path and a denied one must look the same. If the framework's 404 were empty
 * while a denied 404 carried a body, the difference would answer "is this path real" and, one step
 * later, "is this link real" (PRD 6.6, SEC-01).
 */
internal val DENIED_ERROR = ApiError(code = "not_found", message = messageFor("not_found"))

internal val NOT_FOUND_BODY: ApiResponse<Unit> = errorEnvelope(HttpStatusCode.NotFound, DENIED_ERROR)

/**
 * Marks a call whose failure body was written here.
 *
 * Lives beside the mapper rather than in `StatusPages` so the dependency runs one way —
 * `plugin` reads from `route.mapper`, never the reverse.
 *
 * 401 and 405 deliberately keep Ktor's own handling. A `status(Unauthorized)` handler risks
 * dropping the `WWW-Authenticate` challenge the JWT plugin sets, and neither status discloses
 * anything about whether a link exists, so there is nothing to gain.
 */
internal val MappedErrorKey: AttributeKey<Unit> = AttributeKey("com.pgsystem.ert.mapped-error")

/** Answers the call with the mapped status and body. */
suspend fun ApplicationCall.respondError(error: AppError) {
    attributes.put(MappedErrorKey, Unit)
    respond(error.toStatus(), error.toResponse())
}
