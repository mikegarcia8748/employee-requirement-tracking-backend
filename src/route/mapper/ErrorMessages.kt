package com.pgsystem.employee.requirement.tracker.route.mapper

/**
 * The display string for a failure code (ERT-145).
 *
 * `code` is what a client branches on; `message` is what it shows when it has no copy of its own.
 * Keeping the two apart means the wording can change without breaking a client, and a client can
 * override the wording without parsing prose.
 *
 * **Codes are `snake_case`, except field-scoped validation codes which are `<field>.<rule>`.** The
 * dot is meaningful rather than decorative: it marks a code that appears inside an `ApiErrorDetail`
 * with `field` set. Before ERT-145 the two forms were mixed at random (`request.malformed` beside
 * `internal_error`), which is a poor thing to ask a client to branch on.
 *
 * Codes are assembled at runtime (`"${entity}_not_found"`), so this is a `when` over strings with a
 * fallback rather than an exhaustive mapping. An unlisted code gets a generic string, which is safe:
 * it names nothing about the cause.
 *
 * This is the **default** source of a wire message, not the only one. A case that must interpolate a
 * value — a storage cap that should state the limit (ERT-732) — may pass its own string from the
 * mapper's `when`. What protects invariant 3 is not this function but
 * [com.pgsystem.employee.requirement.tracker.core.error.AppError.Denied] being a `data object`: it
 * holds no fields, so it cannot carry a per-instance message and maps to one shared constant.
 */
internal fun messageFor(code: String): String = when {
    // `not_found` and `<entity>_not_found` render the same string on purpose. The code still names
    // the entity for an HR client that wants it, but the prose adds nothing a caller could compare.
    code == "not_found" || code.endsWith("_not_found") -> "Not found."
    code == "internal_error" -> "An unexpected error occurred."
    code == "request_malformed" -> "The request could not be read."
    // Its sibling, and deliberately not "send JSON": the same code answers the multipart
    // upload route ERT-710 writes, where JSON is exactly the wrong thing to send.
    code == "unsupported_media_type" -> "The request body was not sent in a supported format."
    code == "validation_failed" -> "Some fields need attention."
    // One string for all four sign-in failures. It must not hint at which occurred, so it names
    // neither the email nor the account -- "incorrect" covers a wrong password and an address with
    // no account equally, which is the whole requirement.
    code == "authentication_failed" -> "Email or password is incorrect."
    code == "forbidden" -> "You do not have access to this."
    code == "password_change_required" -> "Change your password before continuing."
    code.endsWith(".reason_required") -> "Give a reason to continue."
    else -> "The request could not be completed."
}
