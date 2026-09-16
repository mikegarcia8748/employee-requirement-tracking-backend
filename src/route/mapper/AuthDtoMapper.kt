package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrSession
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.route.dto.HrUserDto
import com.pgsystem.employee.requirement.tracker.route.dto.SignInResponse

/** Domain ↔ wire for the HR account surface (ERT-190). */

fun HrUser.toDto(): HrUserDto = HrUserDto(
    id = id.value,
    email = email.value,
    fullName = fullName,
    role = role.name,
    isActive = isActive,
    passwordChangeRequired = passwordChangeRequired,
    createdAt = createdAt.toString(),
)

fun HrSession.toDto(): SignInResponse = SignInResponse(
    // `.value`, not the AccessToken itself: its `toString()` is redacted, so interpolating it here
    // would ship `AccessToken(******)` to the client and the sign-in would appear to succeed while
    // handing back a token that is not one. The redaction is deliberate; this is the one place that
    // must step around it.
    token = token.value,
    expiresAt = expiresAt.toString(),
    user = user.toDto(),
)

/**
 * A role name from the wire.
 *
 * Returns a [DomainResult] rather than throwing, because this is client input: `"ADMIN"` is a typo
 * to report, not a server fault. The detail lists the accepted values, which is safe — the role names
 * are in the OpenAPI spec already and knowing them grants nothing.
 */
fun String.toHrRole(): DomainResult<HrRole> =
    HrRole.entries.firstOrNull { it.name == this }
        ?.let { DomainResult.Ok(it) }
        ?: DomainResult.Err(
            AppError.Validation(
                code = "role.invalid",
                field = "role",
                detail = "A role is one of ${HrRole.entries.joinToString(", ") { it.name }}",
            )
        )
