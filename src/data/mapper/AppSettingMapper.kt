package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.db.table.AppSettings
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * `app_settings` rows ↔ [LinkPolicy] (ERT-310, PRD 6.4).
 *
 * **There is no compiled-in default anywhere below.** A row that is missing, unreadable, or outside
 * its own stored bounds is refused; it is never substituted. The reason is specific rather than
 * doctrinal: [LinkPolicy]'s Kotlin defaults are *identical* to the values `V3__app_settings.sql`
 * seeds, so an adapter that fell back to them would return exactly what a working one returns, and
 * no behavioural test could tell the two apart. A corrupted `absolute_expiry_days` silently becoming
 * 90 is the "well-meant edit turns a token into a permanent credential" case §6.4 warns about.
 *
 * That is also why [LinkPolicy] is assembled from nine explicit named arguments below and never by
 * `copy`ing a default instance — and why `ExposedAppSettingsRepositoryTest` reads this file as text
 * and fails the build if the token `LinkPolicy()` ever appears in it.
 */

/**
 * The nine §6.4 settings: the one place a key string is written.
 *
 * Lives here rather than on [LinkPolicy] because a key is a storage naming decision and the domain
 * must not learn one. Three tests drive off this list — `SeedDataTest` checks every key has a seeded
 * row, `AppSettingMapperTest` checks every `Int` field of [LinkPolicy] has a key, and
 * `ExposedAppSettingsRepositoryTest` checks the seeded table holds all nine — so a tenth field
 * added without a key, or a key added without a row, fails rather than quietly taking a default.
 */
enum class LinkPolicySetting(val key: String, val field: String, val read: (LinkPolicy) -> Int) {
    ABSOLUTE_EXPIRY_DAYS("link.absolute_expiry_days", "absoluteExpiryDays", LinkPolicy::absoluteExpiryDays),
    IDLE_EXPIRY_DAYS("link.idle_expiry_days", "idleExpiryDays", LinkPolicy::idleExpiryDays),
    EXTEND_ON_REJECTION_DAYS("link.extend_on_rejection_days", "extendOnRejectionDays", LinkPolicy::extendOnRejectionDays),
    WARN_BEFORE_EXPIRY_DAYS("link.warn_before_expiry_days", "warnBeforeExpiryDays", LinkPolicy::warnBeforeExpiryDays),
    COMPLETED_GRACE_DAYS("link.completed_grace_days", "completedGraceDays", LinkPolicy::completedGraceDays),
    SESSION_MINUTES("portal.session_minutes", "sessionMinutes", LinkPolicy::sessionMinutes),
    PIN_ATTEMPTS_BEFORE_LOCKOUT("portal.pin_attempts_before_lockout", "pinAttemptsBeforeLockout", LinkPolicy::pinAttemptsBeforeLockout),
    LOCKOUT_MINUTES("portal.lockout_minutes", "lockoutMinutes", LinkPolicy::lockoutMinutes),
    PIN_FAILURES_BEFORE_SUSPEND("portal.pin_failures_before_suspend", "pinFailuresBeforeSuspend", LinkPolicy::pinFailuresBeforeSuspend),
}

/** One `app_settings` row, wholly untrusted: every field is the raw string the column holds. */
data class StoredSetting(
    val key: String,
    val value: String,
    val valueType: String,
    val min: String?,
    val max: String?,
)

fun ResultRow.toStoredSetting(): StoredSetting = StoredSetting(
    key = this[AppSettings.key],
    value = this[AppSettings.value],
    valueType = this[AppSettings.valueType],
    min = this[AppSettings.minValue],
    max = this[AppSettings.maxValue],
)

/**
 * The audited subject of a settings change.
 *
 * `audit_logs.entity_id` is 12 characters wide and `Identifier.of` recovers an id's *kind from its
 * length alone*, so a 25-character setting key cannot go in it — and widening the column would make
 * that dispatch ambiguous.
 *
 * The resolution is not a workaround but the honest model: **the thing being audited is not a row.**
 * The link policy *is* the entity — one domain object whose nine fields happen to be stored as nine
 * rows, and which the Phase 2 admin screen saves as one form. One audit row per save, under this
 * id, answers the question an auditor actually asks ("every change to the link policy, in order,
 * with who and when"). Nine rows per save would multiply the trail ninefold to answer a question
 * nobody asks.
 *
 * `SecureEntityIdGenerator` could in principle draw this value; at 62^-12 it is recorded rather than
 * guarded.
 */
val LINK_POLICY_ID: EntityId = EntityId.of("LINKPOLICY01").orFail()

/** `AuditEntry.entity` for that subject. Singular snake_case, like `employee` and `upload_link`. */
const val LINK_POLICY_ENTITY = "link_policy"

/**
 * The stored rows as a policy, or every reason they could not be.
 *
 * Errors **accumulate** rather than short-circuiting, and are always reported through
 * [AppError.ValidationFailed] even when exactly one key is bad. Reading nine rows is form-shaped,
 * not value-object-shaped, and `AppError`'s own KDoc says both render through one wire shape so a
 * client never branches on how many failed — if the consumer must not branch on the count, neither
 * should the producer. The practical payoff is that an operator repairing a hand-edited table sees
 * every broken key at once instead of one per attempt, and the iteration order of the nine keys
 * never becomes load-bearing.
 */
fun Map<String, StoredSetting>.toLinkPolicy(): DomainResult<LinkPolicy> {
    val errors = mutableListOf<AppError.Validation>()
    val values = mutableMapOf<LinkPolicySetting, Int>()

    LinkPolicySetting.entries.forEach { setting ->
        when (val parsed = readStored(setting, this[setting.key])) {
            is DomainResult.Ok -> values[setting] = parsed.value
            is DomainResult.Err -> errors += parsed.error as AppError.Validation
        }
    }
    if (errors.isNotEmpty()) return AppError.ValidationFailed(errors).asErr()

    val policy = LinkPolicy(
        absoluteExpiryDays = values.getValue(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS),
        idleExpiryDays = values.getValue(LinkPolicySetting.IDLE_EXPIRY_DAYS),
        extendOnRejectionDays = values.getValue(LinkPolicySetting.EXTEND_ON_REJECTION_DAYS),
        warnBeforeExpiryDays = values.getValue(LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS),
        completedGraceDays = values.getValue(LinkPolicySetting.COMPLETED_GRACE_DAYS),
        sessionMinutes = values.getValue(LinkPolicySetting.SESSION_MINUTES),
        pinAttemptsBeforeLockout = values.getValue(LinkPolicySetting.PIN_ATTEMPTS_BEFORE_LOCKOUT),
        lockoutMinutes = values.getValue(LinkPolicySetting.LOCKOUT_MINUTES),
        pinFailuresBeforeSuspend = values.getValue(LinkPolicySetting.PIN_FAILURES_BEFORE_SUSPEND),
    )

    val crossField = policy.crossFieldErrors()
    return if (crossField.isEmpty()) policy.asOk() else AppError.ValidationFailed(crossField).asErr()
}

/**
 * Whether this policy may be written, given the bounds the table declares.
 *
 * Checks the incoming policy against the stored **metadata** — that each row exists, declares INT,
 * and declares readable bounds — and deliberately **not** against the stored values. Validating the
 * current values would make a corrupt row unfixable through the application, and repairing one is
 * exactly what the settings screen is for.
 *
 * A missing row is refused rather than inserted. An insert here would create a row with null
 * `min_value` and `max_value`, permanently disarming the bounds check for that key — the §6.4
 * failure mode in one line. A missing row is a migration problem and gets a migration.
 */
fun LinkPolicy.validateAgainst(stored: Map<String, StoredSetting>): DomainResult<Unit> {
    val errors = mutableListOf<AppError.Validation>()

    LinkPolicySetting.entries.forEach { setting ->
        when (val range = declaredRange(setting, stored[setting.key])) {
            is DomainResult.Ok -> outOfRange(setting, setting.read(this), range.value)?.let { errors += it }
            is DomainResult.Err -> errors += range.error as AppError.Validation
        }
    }
    if (errors.isNotEmpty()) return AppError.ValidationFailed(errors).asErr()

    val crossField = crossFieldErrors()
    return if (crossField.isEmpty()) Unit.asOk() else AppError.ValidationFailed(crossField).asErr()
}

// ── Per-row rules ───────────────────────────────────────────────────────────────────────────────

private fun readStored(setting: LinkPolicySetting, stored: StoredSetting?): DomainResult<Int> {
    // Metadata first. A row whose declared bounds are unusable cannot judge its own value, so there
    // is nothing useful to say about the value until this has passed.
    val range = when (val declared = declaredRange(setting, stored)) {
        is DomainResult.Ok -> declared.value
        is DomainResult.Err -> return declared
    }
    val row = stored ?: return refuse(setting, "missing", missingDetail(setting))
    val value = row.value.toIntOrNull()
        ?: return refuse(
            setting, "unreadable",
            "'${row.value}' is not a whole number. '${setting.key}' is declared $INT_TYPE.",
        )

    return outOfRange(setting, value, range)?.asErr() ?: value.asOk()
}

/**
 * The permitted range as the row itself declares it.
 *
 * Read from `min_value` / `max_value` rather than from a constant in code, because §6.4 is explicit
 * that the bounds must be revisable as data. [LinkPolicy.ABSOLUTE_EXPIRY_DAYS_RANGE] is therefore
 * *not* consulted here; `AppSettingMapperTest` asserts the seeded row agrees with it instead, which
 * is the drift that could otherwise go unnoticed.
 */
private fun declaredRange(setting: LinkPolicySetting, stored: StoredSetting?): DomainResult<IntRange> {
    if (stored == null) return refuse(setting, "missing", missingDetail(setting))
    if (stored.valueType != INT_TYPE) {
        return refuse(
            setting, "type_unexpected",
            "'${setting.key}' is declared ${stored.valueType}; the link policy reads only $INT_TYPE settings.",
        )
    }
    if (stored.min == null || stored.max == null) {
        return refuse(
            setting, "bounds_missing",
            "'${setting.key}' declares no min_value or max_value, so its PRD 6.4 bounds cannot be enforced.",
        )
    }
    val low = stored.min.toIntOrNull()
    val high = stored.max.toIntOrNull()
    if (low == null || high == null) {
        return refuse(
            setting, "bounds_unreadable",
            "'${setting.key}' declares bounds '${stored.min}' to '${stored.max}', which are not whole numbers.",
        )
    }
    return (low..high).asOk()
}

/** PRD 8.10: the message states the range, so an admin can correct the value rather than guess. */
private fun outOfRange(setting: LinkPolicySetting, value: Int, range: IntRange): AppError.Validation? =
    if (value in range) {
        null
    } else {
        validation(
            setting, "out_of_range",
            "$value is outside the permitted range for '${setting.key}': " +
                "${range.first} to ${range.last}.",
        )
    }

// ── Cross-field rules ───────────────────────────────────────────────────────────────────────────

/**
 * The two invariants `V3__app_settings.sql` hands to this adapter by name.
 *
 * Neither can be expressed as a per-row `min_value` / `max_value`, because each constrains one
 * setting *relative to another* and both values can be legal on their own. Stage two: these run only
 * once every row has parsed, since complaining that the warning is too late, about a policy that
 * cannot be read or saved anyway, is noise on top of the real fault.
 */
private fun LinkPolicy.crossFieldErrors(): List<AppError.Validation> = buildList {
    if (warnBeforeExpiryDays >= absoluteExpiryDays) {
        add(
            validation(
                LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS, "warn_not_before_expiry",
                "The expiry warning must fall before the absolute expiry: warn $warnBeforeExpiryDays " +
                    "is not less than absolute $absoluteExpiryDays.",
            )
        )
    }
    if (pinFailuresBeforeSuspend < pinAttemptsBeforeLockout) {
        add(
            validation(
                LinkPolicySetting.PIN_FAILURES_BEFORE_SUSPEND, "suspend_below_lockout",
                "Auto-suspend must not fire before lockout: $pinFailuresBeforeSuspend failures before " +
                    "suspend is below $pinAttemptsBeforeLockout attempts before lockout.",
            )
        )
    }
}

// ── Error construction ──────────────────────────────────────────────────────────────────────────

/**
 * `setting.<rule>` as the code, the setting key as the field.
 *
 * The dot follows the `<kind>.<rule>` convention `ErrorMessages` records for a code that appears
 * inside an `ApiErrorDetail` with `field` set — the same shape as `entity_id.invalid_format` on
 * field `id`. Three stable codes beat twenty-seven per-key ones for a client that has to branch.
 * The `detail` is what a caller sees: `AppErrorMapper.toDetail()` uses it verbatim.
 */
private fun validation(setting: LinkPolicySetting, rule: String, detail: String) =
    AppError.Validation(code = "setting.$rule", field = setting.key, detail = detail)

private fun refuse(setting: LinkPolicySetting, rule: String, detail: String): DomainResult<Nothing> =
    validation(setting, rule, detail).asErr()

private fun missingDetail(setting: LinkPolicySetting) =
    "No app_settings row for '${setting.key}'. The link policy is read from the database " +
        "and has no compiled-in default."

private const val INT_TYPE = "INT"

private fun <T> DomainResult<T>.orFail(): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("A constant identifier failed its own validation: ${error.code}")
}
