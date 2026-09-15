package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.plugin.ErrorResponse
import com.pgsystem.employee.requirement.tracker.plugin.MappedErrorKey
import com.pgsystem.employee.requirement.tracker.plugin.NOT_FOUND_BODY
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * The single mapping from a domain failure to an HTTP response (ERT-140, api-contract "AppError to
 * HTTP status").
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
    is AppError.ReasonRequired -> HttpStatusCode.UnprocessableEntity
    is AppError.Conflict -> HttpStatusCode.Conflict
    is AppError.NotFound -> HttpStatusCode.NotFound
    AppError.Denied -> HttpStatusCode.NotFound
}

/**
 * The response body, which carries the stable `code` and nothing internal — no stack trace, no SQL,
 * no table name, no file path (PRD 12).
 *
 * `NotFound` does not echo its `entity`: the code already names it (`employee_not_found`), and a
 * second field is one more thing that could differ between two failures that ought to look alike.
 *
 * `Denied` renders one shared constant. The mapper **must not** add a detail to it — a detail is
 * precisely what would pull a wrong PIN and an unknown token apart (PRD 6.6, SEC-01).
 */
fun AppError.toResponse(): ErrorResponse = when (this) {
    is AppError.Validation -> ErrorResponse(code = code, detail = detail, field = field)
    is AppError.ReasonRequired -> ErrorResponse(code = code, detail = action)
    is AppError.Conflict -> ErrorResponse(code = code, detail = detail)
    is AppError.NotFound -> ErrorResponse(code = code)
    AppError.Denied -> NOT_FOUND_BODY
}

/**
 * Answers the call with the mapped status and body.
 *
 * The call is marked so the `status(NotFound)` handler in `StatusPages` leaves this response alone;
 * that handler exists to give *unmatched* routes the denied body, not to overwrite a mapped one.
 */
suspend fun ApplicationCall.respondError(error: AppError) {
    attributes.put(MappedErrorKey, Unit)
    respond(error.toStatus(), error.toResponse())
}
