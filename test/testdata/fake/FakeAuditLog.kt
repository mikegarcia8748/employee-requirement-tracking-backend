package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog

/**
 * HR-side actions, in memory, append-only.
 *
 * The backing list is private and [entries] returns a copy, so there is no update or delete path at
 * all — a use case that tried to amend a recorded action could not compile against this. The real
 * table has the same property for the same reason: an audit row must outlive the row it describes.
 *
 * [findFor] compares [Identifier]s directly, which means a [com.pgsystem.employee.requirement.tracker.core.value.PersonId]
 * never matches an [com.pgsystem.employee.requirement.tracker.core.value.EntityId] even when the two
 * hold the same characters. That is the behaviour the polymorphic `audit_logs.entity_id` column
 * needs, and it holds here because both are value classes of distinct types.
 */
class FakeAuditLog : AuditLog {

    val failure = FakeFailure()

    private val recorded = mutableListOf<AuditEntry>()

    /** Everything recorded, oldest first. A copy — the caller cannot append through it. */
    val entries: List<AuditEntry> get() = recorded.toList()

    override suspend fun record(entry: AuditEntry) {
        failure.check()
        recorded += entry
    }

    override suspend fun findFor(entityId: Identifier): List<AuditEntry> {
        failure.check()
        return recorded.filter { it.entityId == entityId }
    }

    // ── Assert ──────────────────────────────────────────────────────────────────────────────────

    fun entriesFor(action: AuditAction): List<AuditEntry> = recorded.filter { it.action == action }

    fun recorded(action: AuditAction): Boolean = recorded.any { it.action == action }
}
