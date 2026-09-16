package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.error.flatMap
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.AppSettings
import com.pgsystem.employee.requirement.tracker.data.mapper.LINK_POLICY_ENTITY
import com.pgsystem.employee.requirement.tracker.data.mapper.LINK_POLICY_ID
import com.pgsystem.employee.requirement.tracker.data.mapper.LinkPolicySetting
import com.pgsystem.employee.requirement.tracker.data.mapper.StoredSetting
import com.pgsystem.employee.requirement.tracker.data.mapper.toLinkPolicy
import com.pgsystem.employee.requirement.tracker.data.mapper.toStoredSetting
import com.pgsystem.employee.requirement.tracker.data.mapper.validateAgainst
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The PRD 6.4 policy, read from `app_settings` at runtime (ERT-310).
 *
 * **No duration is ever compiled in.** `AppSettingMapper` carries the reasoning; the short version
 * is that [LinkPolicy]'s Kotlin defaults are identical to the seeded rows, so a fallback would be
 * invisible. A row that cannot be read is an `Err`, not a default.
 *
 * [updateLinkPolicy] has **no caller until the Phase 2 settings screen (§8.10)**. It is written here
 * anyway because the port declares it and because the bounds check belongs beside the reader that
 * enforces the same bounds — not because something is about to use it. It is not dead code awaiting
 * removal.
 *
 * *Known and accepted:* the update is a read-modify-write under `READ_COMMITTED`, so two admins
 * saving the form at the same instant produce a last-writer-wins result whose trail reads as though
 * the two saves were sequential. There is one admin screen and no writer at all until Phase 2;
 * `selectAll().forUpdate()` is the fix if that ever changes. H2 will not surface it either way.
 */
class ExposedAppSettingsRepository(
    private val factory: DatabaseFactory,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
) : AppSettingsRepository {

    override suspend fun linkPolicy(): DomainResult<LinkPolicy> = factory.transaction {
        storedSettings().toLinkPolicy()
    }

    /**
     * Writes the changed rows and one audit entry, in **one** transaction.
     *
     * The audit row goes through [insertAuditEntry] rather than through the `AuditLog` port, whose
     * `record` takes no transaction and so would open its own.
     *
     * **Be clear about what that buys, because it is less than it looks.** Routing the write through
     * the port is atomic *too*: Exposed's `suspendTransaction` joins an ambient transaction, and
     * this was checked by making the change and re-running the suite, which stayed green. So no test
     * here distinguishes the two designs, and none can — do not add a comment claiming otherwise.
     *
     * The shared writer is still the right choice, for one reason: the port hop is atomic only while
     * `useNestedTransactions` stays false and while `DatabaseFactory`'s `withContext(Dispatchers.IO)`
     * hop keeps the transaction's context element. Neither is this codebase's decision to make.
     * Writing the row on the transaction we already hold does not depend on either, and the cost is
     * one internal function. `transaction nesting - a factory transaction inside another - joins it`
     * pins the behaviour the alternative would have rested on, so an Exposed upgrade that changed it
     * would at least be visible.
     *
     * Only changed rows are touched, so an untouched setting keeps the `updated_at` that says when
     * *it* last changed rather than when someone last pressed Save. A policy identical to the stored
     * one therefore writes nothing at all, audit row included: an entry recording that nothing
     * happened only dilutes the trail.
     */
    override suspend fun updateLinkPolicy(policy: LinkPolicy, actor: PersonId): DomainResult<Unit> =
        factory.transaction {
            val stored = storedSettings()

            policy.validateAgainst(stored).flatMap {
                val changes = stored.changesTo(policy)
                if (changes.isNotEmpty()) {
                    val now = clock.now()
                    changes.forEach { change -> apply(change, actor, now) }
                    insertAuditEntry(auditEntryFor(changes, actor, now))
                }
                Unit.asOk()
            }
        }

    private fun JdbcTransaction.storedSettings(): Map<String, StoredSetting> =
        AppSettings.selectAll().associate { it[AppSettings.key] to it.toStoredSetting() }

    private fun JdbcTransaction.apply(change: SettingChange, actor: PersonId, now: java.time.Instant) {
        val rows = AppSettings.update({ AppSettings.key eq change.setting.key }) {
            it[value] = change.new
            it[updatedBy] = actor.value
            it[updatedAt] = now
        }

        // Unreachable: the key was read in this same transaction two statements ago. Checked anyway
        // because the alternative to a loud failure here is a settings change that reports success
        // and wrote nothing -- and an audit row claiming it did.
        check(rows == 1) { "Updating '${change.setting.key}' matched $rows rows, expected exactly 1" }
    }

    /**
     * One entry per save, not one per key.
     *
     * The metadata carries only the keys that moved, and only their old and new values — the actor
     * and the timestamp are columns. ERT-330 is explicit that `actor`, `action` and `entity_id` must
     * be queryable for the §8.13 exception report rather than buried in JSON, and copying them into
     * the JSON as well would create two sources for one fact that can disagree.
     *
     * The old value is the **raw stored string**. An admin correcting a corrupt row is exactly when
     * the trail matters most, and a parsed old value would be unrepresentable.
     */
    private fun auditEntryFor(changes: List<SettingChange>, actor: PersonId, now: java.time.Instant) =
        AuditEntry(
            // `actor` keeps the id as its text, and `actorUserId` carries it as a reference. The
            // free-text column stays free text because the trail must also record actors who are not
            // users -- the seed, the expiry sweep -- so it cannot become the typed one (ERT-190).
            actor = actor.value,
            actorUserId = actor,
            id = ids.newEntityId(),
            action = AuditAction.SETTING_CHANGED,
            entity = LINK_POLICY_ENTITY,
            entityId = LINK_POLICY_ID,
            timestamp = now,
            metadata = changes.flatMap {
                listOf("${it.setting.key}.old" to it.old, "${it.setting.key}.new" to it.new)
            }.toMap(),
        )
}

private data class SettingChange(val setting: LinkPolicySetting, val old: String, val new: String)

private fun Map<String, StoredSetting>.changesTo(policy: LinkPolicy): List<SettingChange> =
    LinkPolicySetting.entries.mapNotNull { setting ->
        val old = getValue(setting.key).value
        val new = setting.read(policy).toString()
        if (old == new) null else SettingChange(setting, old, new)
    }
