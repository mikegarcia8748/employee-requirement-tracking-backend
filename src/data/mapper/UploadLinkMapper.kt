package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.table.UploadLinks
import com.pgsystem.employee.requirement.tracker.domain.model.LinkScope
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.UploadLink
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

/**
 * `upload_links` rows ↔ [UploadLink] (ERT-420).
 *
 * **A stored value that will not parse is a corrupt row, not a case to branch on**, so this throws
 * rather than returning a `DomainResult` — the same choice `EmployeeMapper` and `HrUserMapper` make.
 * Every value here was written from an already-validated value object, an enum name, or this file's
 * own scope encoder, so the only way one fails is that something outside this application wrote it.
 *
 * One mapping is not mechanical, and it has named tests in `ExposedUploadLinkRepositoryTest`: the
 * [LinkScope] encoding below. Everything else is a column for a field.
 *
 * `pinHash` is nullable and null on almost every row — the recovery PIN is minted on demand by HR
 * (PRD 6.6, reversed 2026-09-16), not issued with the link. See `V5__recovery_pin_nullable.sql`.
 */
internal fun ResultRow.toUploadLink(): UploadLink = UploadLink(
    id = EntityId.of(this[UploadLinks.id].value).orCorrupt("upload_links.id"),
    employeeId = PersonId.of(this[UploadLinks.employeeId].value).orCorrupt("upload_links.employee_id"),
    tokenHash = this[UploadLinks.tokenHash],
    pinHash = this[UploadLinks.pinHash],
    scope = this[UploadLinks.scope].toLinkScope(),
    status = this[UploadLinks.status].toLinkStatus(),
    issuedAt = this[UploadLinks.issuedAt],
    expiresAt = this[UploadLinks.expiresAt],
    idleExpiresAt = this[UploadLinks.idleExpiresAt],
    extendedCount = this[UploadLinks.extendedCount],
    failedPinCount = this[UploadLinks.failedPinCount],
    lockedUntil = this[UploadLinks.lockedUntil],
    warnedAt = this[UploadLinks.warnedAt],
    revokedAt = this[UploadLinks.revokedAt],
    revokedReason = this[UploadLinks.revokedReason],
)

/**
 * Every non-key column of `upload_links`, in one place, for both insert and update.
 *
 * `id` is excluded on purpose: it is the key an update matches on, and Exposed rejects writing it in
 * an update statement. `ExposedUploadLinkRepository` supplies it on the insert path only. Sharing
 * one function is what stops the realistic failure `EmployeeMapper` names — a field added to
 * [UploadLink] and wired into the insert alone.
 */
internal fun UpdateBuilder<*>.writeTo(link: UploadLink) {
    this[UploadLinks.employeeId] = link.employeeId.value
    this[UploadLinks.tokenHash] = link.tokenHash
    this[UploadLinks.pinHash] = link.pinHash
    this[UploadLinks.scope] = link.scope.toColumn()
    this[UploadLinks.status] = link.status.name
    this[UploadLinks.issuedAt] = link.issuedAt
    this[UploadLinks.expiresAt] = link.expiresAt
    this[UploadLinks.idleExpiresAt] = link.idleExpiresAt
    this[UploadLinks.extendedCount] = link.extendedCount
    this[UploadLinks.failedPinCount] = link.failedPinCount
    this[UploadLinks.lockedUntil] = link.lockedUntil
    this[UploadLinks.warnedAt] = link.warnedAt
    this[UploadLinks.revokedAt] = link.revokedAt
    this[UploadLinks.revokedReason] = link.revokedReason
}

/**
 * [LinkScope] as one column value: `ALL`, or `ONLY:` and the template ids.
 *
 * Nothing in the codebase encoded this before — `upload_links.scope` has carried a literal `'ALL'`
 * default since V1 with no writer and no reader — so the format is decided here.
 *
 * **[LinkScope.All] encodes to the column's own default**, which is what makes a row inserted by a
 * migration or by hand read back as the scope it obviously means rather than as corruption.
 *
 * **The ids are sorted**, for the reason `EmployeeMapper` sorts anomaly flags by ordinal: a `Set`
 * has no order, so an unsorted join writes the same scope two different ways on two saves. That
 * makes a diff noise and an assertion on the raw column text flaky — it passes or fails on iteration
 * order, which is the worst kind of test.
 *
 * **Capacity is 39 ids** — `varchar(512)` holds `ONLY:` plus 39 twelve-character ids and their
 * separators. Appendix A's illustrative catalogue has 14 templates. A renewal link covering more
 * requirements than that is a wider column and a migration, not a silent truncation, so the insert
 * fails loudly rather than storing a scope that decodes to fewer ids than it was given.
 */
private fun LinkScope.toColumn(): String = when (this) {
    is LinkScope.All -> ALL_SCOPE
    is LinkScope.Only ->
        ONLY_PREFIX + requirementTemplateIds.map { it.value }.sorted().joinToString(SCOPE_SEPARATOR)
}

/**
 * The column value as a [LinkScope].
 *
 * **The blank filter is load-bearing, and it is the trap ERT-410 hit one file over.**
 * `"ONLY:".removePrefix("ONLY:")` is the empty string, and `"".split(",")` yields one **blank**
 * element rather than none — exactly how `employees.anomaly_flags` would have reported a flag on
 * every hire in the system and silently disabled the PRD 7.1 purge. Here the blank would reach
 * `EntityId.of` and turn an empty scope into a corrupt row, so `Only(emptySet())` would be
 * unstorable and the failure would name the id format rather than the encoding.
 */
private fun String.toLinkScope(): LinkScope = when {
    this == ALL_SCOPE -> LinkScope.All

    startsWith(ONLY_PREFIX) -> LinkScope.Only(
        removePrefix(ONLY_PREFIX)
            .split(SCOPE_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { EntityId.of(it).orCorrupt("upload_links.scope") }
            .toSet()
    )

    else -> error(
        "upload_links.scope holds '$this', which is neither '$ALL_SCOPE' nor '$ONLY_PREFIX' and a " +
            "list of template ids. A scope was written by something that does not share this encoding."
    )
}

private const val ALL_SCOPE = "ALL"
private const val ONLY_PREFIX = "ONLY:"
private const val SCOPE_SEPARATOR = ","

private fun String.toLinkStatus(): LinkStatus =
    LinkStatus.entries.firstOrNull { it.name == this }
        ?: error(
            "upload_links.status holds '$this', which is not a LinkStatus. " +
                "A status was removed from the enum without a migration."
        )

private fun <T> DomainResult<T>.orCorrupt(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value the domain rejects: ${error.code}")
}
