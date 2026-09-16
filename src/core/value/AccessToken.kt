package com.pgsystem.employee.requirement.tracker.core.value

/**
 * An issued HR access token, as an opaque bearer credential.
 *
 * Deliberately **no `of` factory and no format rule**, unlike every other value class here. A
 * [PersonId] has a width and an alphabet the domain owns; this string's shape is decided entirely by
 * whatever [com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer] is bound, and a
 * domain-side pattern check would encode "it is a JWT" into a layer that must not know that. The
 * only property worth asserting is that it is not empty, which [init] does.
 *
 * [toString] is redacted for the reason [AccessPin]'s is: this is a live credential for
 * `JWT_TTL_MINUTES`, and accidental interpolation into a log line hands it over. It is also why a
 * token is returned from exactly one place — the sign-in response — and is never persisted, never
 * audited, and never reaches [com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer],
 * which is given no argument to render.
 */
@JvmInline
value class AccessToken(val value: String) {

    init {
        require(value.isNotBlank()) { "An access token cannot be blank" }
    }

    /** Never render the token. */
    override fun toString(): String = "AccessToken(******)"
}
