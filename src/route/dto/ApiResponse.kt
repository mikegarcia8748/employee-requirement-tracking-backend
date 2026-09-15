package com.pgsystem.employee.requirement.tracker.route.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The envelope every `/api` response is wrapped in (ERT-145).
 *
 * One generic type rather than a success type and an error type, because the client decodes with a
 * single `BaseResponse<T>` and a second shape would defeat that. Failures are `ApiResponse<Unit>`:
 * [data] is null, `Serialization.kt` sets `explicitNulls = false`, so the key is omitted entirely
 * and the `Unit` serializer is never invoked.
 *
 * [result] is never passed at a call site. It is derived from the HTTP status by `resultFor` in
 * `route/mapper/ApiResponses.kt`, so it cannot disagree with the status line.
 *
 * `/health`, `/metrics` and the docs routes are deliberately outside the envelope, as are 401, 405
 * and 204 — see the scope table in `docs/api-contract.md`.
 */
@Serializable
data class ApiResponse<out T>(
    val result: ApiResult,
    val data: T? = null,
    val meta: ApiMeta? = null,
    val error: ApiError? = null,
)

/**
 * Mirrors the HTTP status class, coarsely: 2xx, 4xx, 5xx.
 *
 * The split earns its place because the two failure kinds want different UI — `FAIL` means the
 * caller can fix it and should see the specific message, `ERROR` means the server broke and the
 * caller should see a generic retry. It is a convenience, not the contract: a 502 from a proxy or a
 * container OOM never reaches this application, so a client's 5xx branch must key on the status
 * class rather than on this field.
 */
@Serializable
enum class ApiResult {
    @SerialName("success")
    SUCCESS,

    @SerialName("fail")
    FAIL,

    @SerialName("error")
    ERROR,
}

/**
 * The failure body, for both `FAIL` and `ERROR`.
 *
 * Three fields, with [details] as the single slot for per-item context. An earlier draft also
 * carried `field` and `detail` at this level, which made a one-field validation failure expressible
 * two ways and forced the client to handle both.
 *
 * [code] is a domain identifier, not the HTTP status — under 422 alone this system has
 * `email.invalid_format` (highlight the field) and `duplicate_email_requires_reason` (open a
 * confirmation modal), which the status cannot distinguish.
 *
 * Never a stack trace, a SQL fragment, a driver message, a table name or a file path: those name
 * library versions and schema internals, so the cause is logged server-side (PRD 12).
 */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: List<ApiErrorDetail>? = null,
)

/**
 * One item of failure context, usually a field that did not validate.
 *
 * [field] is nullable so a detail need not be field-scoped, but every validation detail sets it —
 * that is what lets a client bind errors to a form with one expression regardless of how many
 * fields failed.
 */
@Serializable
data class ApiErrorDetail(
    val code: String,
    val field: String? = null,
    val message: String? = null,
)

/**
 * Response metadata that is not part of the payload.
 *
 * Sits beside [ApiResponse.data] rather than inside it: a count nested in the payload would force
 * every list endpoint to declare its own wrapper type, and `ApiResponse<List<HireDto>>` would stop
 * working.
 *
 * [total] is for list endpoints (api-contract "the API supplies the counts"). [page] and [pageSize]
 * are unused until pagination is specified (ERT-512); being nullable they are omitted until then.
 */
@Serializable
data class ApiMeta(
    val total: Int? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
)
