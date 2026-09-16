package com.pgsystem.employee.requirement.tracker.route.auth

import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole

/**
 * The verified caller, as the route layer sees them (ERT-190).
 *
 * **In `route/`, not in `plugin/`, and the direction of that dependency is the reason.**
 * `ArchitectureTest` fails the build on a route importing `plugin`, because the arrow runs
 * `plugin` → `route` and never back. `plugin/Security.kt` builds one of these inside its `validate`
 * block; every handler that needs to know who is calling reads it from here. Putting it in `plugin/`
 * would leave route files unable to name the type they are handed.
 *
 * Built only from claims a signature has already been checked over, so its fields are trusted in the
 * narrow sense that they are what the issuer wrote. They are **not** fresh: see
 * [com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer] for the revocation window this
 * inherits — a role or a password gate can be up to `JWT_TTL_MINUTES` out of date.
 */
data class HrPrincipal(
    val userId: PersonId,
    val role: HrRole,
    /**
     * True while this account still owes a password change.
     *
     * Read from the claim, defaulting to **true** when the claim is missing. A token minted before
     * the claim existed, or by anything that does not set it, must fail closed: treating absence as
     * "no change needed" would make forgetting the claim a silent bypass of the gate.
     */
    val passwordChangeRequired: Boolean,
)
