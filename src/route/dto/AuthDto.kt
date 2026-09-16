package com.pgsystem.employee.requirement.tracker.route.dto

import kotlinx.serialization.Serializable

/**
 * The wire shapes for sign-in and user administration (ERT-190).
 *
 * **No DTO here carries a password hash, and none may.** The request types carry plaintext inbound
 * and nothing carries a credential outbound except [SignInResponse.token], which is the one thing a
 * sign-in exists to produce. `HrUser.toString()` is redacted for the same reason; these are the two
 * halves of "a password never appears in a response".
 *
 * `middleInitial`-style optionality is expressed with nullable fields and `explicitNulls = false`,
 * so an absent value is an absent key rather than `null` — matching every other DTO here.
 */

@Serializable
data class SignInRequest(val email: String, val password: String)

/**
 * A successful sign-in.
 *
 * [expiresAt] is sent as an ISO-8601 string rather than left for the client to decode out of the
 * token: the token is opaque to everything above the issuer by design, and a client parsing a JWT to
 * find its own expiry would be reaching through that boundary.
 *
 * [user] rides along so the client can render a name and branch on a role without a second call on
 * the one request that happens before it knows anything.
 */
@Serializable
data class SignInResponse(
    val token: String,
    val expiresAt: String,
    val user: HrUserDto,
)

/**
 * An HR account as the API renders it.
 *
 * No `passwordHash`, and no `password` of any kind. [passwordChangeRequired] is published because the
 * client has to route the user to the change-password screen; it discloses nothing, since only that
 * user and an admin can see it.
 */
@Serializable
data class HrUserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val role: String,
    val isActive: Boolean,
    val passwordChangeRequired: Boolean,
    val createdAt: String,
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class CreateHrUserRequest(
    val email: String,
    val fullName: String,
    val role: String,
    val initialPassword: String,
)

@Serializable
data class ResetPasswordRequest(val newPassword: String)

@Serializable
data class SetActiveRequest(val isActive: Boolean)
