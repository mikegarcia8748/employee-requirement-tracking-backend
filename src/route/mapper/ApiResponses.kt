package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.route.dto.ApiError
import com.pgsystem.employee.requirement.tracker.route.dto.ApiMeta
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * The label a client sees, derived from the status class rather than chosen (ERT-145).
 *
 * Deriving it is the whole point: a handler that could pass `SUCCESS` alongside a 409 would make
 * the envelope disagree with the status line, and nothing would catch it. There is one expression,
 * here, and no call site supplies the value.
 *
 * `@PublishedApi` because the public inline [respondOk] below needs it; it is not API to call
 * directly.
 */
@PublishedApi
internal fun resultFor(status: HttpStatusCode): ApiResult = when {
    status.value < 400 -> ApiResult.SUCCESS
    status.value < 500 -> ApiResult.FAIL
    else -> ApiResult.ERROR
}

/** A failure envelope. `data` stays null and is omitted, so the `Unit` serializer is never used. */
internal fun errorEnvelope(status: HttpStatusCode, error: ApiError): ApiResponse<Unit> =
    ApiResponse(result = resultFor(status), error = error)

/**
 * Answers the call with a use case's result (ERT-145).
 *
 * The one function a handler calls. `DomainResult<T> = Ok(T) | Err(AppError)` maps onto the
 * envelope exactly, so a route stays parse, call one use case, map the sealed result — and makes no
 * decision of its own:
 *
 * ```
 * post("/api/employees") {
 *     val command = call.receive<CreateHireRequest>().toCommand()
 *     call.respondResult(createHire(command), HttpStatusCode.Created)
 * }
 * ```
 *
 * **`inline` and `reified` are load-bearing.** Ktor resolves a serializer from `typeInfo<T>()`; in a
 * non-reified helper the type argument of `ApiResponse<T>` erases and serialization fails at
 * runtime rather than at compile time.
 */
suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    result: DomainResult<T>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    meta: ApiMeta? = null,
) {
    when (result) {
        is DomainResult.Ok -> respondOk(result.value, successStatus, meta)
        is DomainResult.Err -> respondError(result.error)
    }
}

/** Answers with a success envelope directly, for the rare handler that has no use case to call. */
suspend inline fun <reified T : Any> ApplicationCall.respondOk(
    data: T,
    status: HttpStatusCode = HttpStatusCode.OK,
    meta: ApiMeta? = null,
) {
    respond(status, ApiResponse(result = resultFor(status), data = data, meta = meta))
}

/** Answers with a success envelope carrying no payload — `data` is omitted, not null. */
suspend fun ApplicationCall.respondOk(status: HttpStatusCode = HttpStatusCode.OK) {
    respond(status, ApiResponse<Unit>(result = resultFor(status)))
}
