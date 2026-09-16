package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.table.Users
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

/**
 * `users` rows ↔ [HrUser] (ERT-190).
 *
 * **A stored value that will not parse is a corrupt row, not a case to branch on**, so this throws
 * rather than returning a `DomainResult`. The same choice `ExposedReferenceDataRepository` makes and
 * for the same reason: `role` is written from an enum name and `email` from an already-validated
 * [EmailAddress], so the only way either fails here is that something outside this application wrote
 * the row. Handing the caller an error to render would invite a sign-in path to treat "the database
 * is corrupt" as "these credentials are wrong", which is the one confusion worth refusing outright.
 *
 * The write side is shared by insert and update through [writeTo]. Two hand-written column lists
 * drift — the realistic failure is a field added to [HrUser] and wired into the insert only, so a
 * password change silently keeps the old hash — and `ExposedHrUserRepositoryTest` round-trips a
 * modified user through `save` to catch exactly that.
 */
internal fun ResultRow.toHrUser(): HrUser = HrUser(
    id = PersonId.of(this[Users.id].value).orCorrupt("users.id"),
    email = EmailAddress.of(this[Users.email]).orCorrupt("users.email"),
    fullName = this[Users.fullName],
    passwordHash = this[Users.passwordHash],
    role = this[Users.role].toHrRole(),
    isActive = this[Users.isActive],
    passwordChangeRequired = this[Users.passwordChangeRequired],
    createdAt = this[Users.createdAt],
)

/**
 * Every non-key column, in one place, for both insert and update.
 *
 * `id` is excluded on purpose: it is the key an update matches on, and Exposed would reject writing
 * it in an update statement. [com.pgsystem.employee.requirement.tracker.data.repository.ExposedHrUserRepository]
 * supplies it on the insert path only.
 */
internal fun UpdateBuilder<*>.writeTo(user: HrUser) {
    this[Users.email] = user.email.value
    this[Users.fullName] = user.fullName
    this[Users.passwordHash] = user.passwordHash
    this[Users.role] = user.role.name
    this[Users.isActive] = user.isActive
    this[Users.passwordChangeRequired] = user.passwordChangeRequired
    this[Users.createdAt] = user.createdAt
}

/**
 * The stored `role` string back to its enum.
 *
 * `valueOf` would throw `IllegalArgumentException` naming the enum class, which reads as a
 * programming error; this says which column holds the bad value, which is the thing an operator
 * needs. Dropping a role from [HrRole] is what makes this reachable, so the message says so.
 */
private fun String.toHrRole(): HrRole =
    HrRole.entries.firstOrNull { it.name == this }
        ?: error("users.role holds '$this', which is not an HrRole. A role was removed from the enum without a migration.")

private fun <T> DomainResult<T>.orCorrupt(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value the domain rejects: ${error.code}")
}
