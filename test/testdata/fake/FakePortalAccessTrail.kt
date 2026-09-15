package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.domain.model.PortalAccessLog
import com.pgsystem.employee.requirement.tracker.domain.model.PortalOutcome
import java.time.Instant
import com.pgsystem.employee.requirement.tracker.domain.port.PortalAccessTrail

/**
 * Portal access attempts, in memory, append-only.
 *
 * **There is no update or delete path at all**, and that is the design rather than an omission. The
 * PRD v0.3 model carried a single `upload_link.last_accessed_at`, a timestamp overwritten on every
 * visit, which cannot answer who, from where, or how often — the first question asked when a
 * fraudulent submission surfaces (SEC-05). A fake that offered a mutation would let a use case be
 * written against the model this one replaced.
 *
 * ### `countRecentFailures` counts `DENIED`, and nothing else
 *
 * The port takes no action filter, so the fake has to decide what a "failure" is, and **this
 * definition is the contract ERT-610's SQL adapter must match.** If the two drift, the §6.6
 * auto-suspend threshold fires at a different count in production than in every test.
 *
 * [PortalOutcome.DENIED] is the single value meaning "wrong PIN or unknown token" — the thing being
 * counted. `LOCKED_OUT`, `SUSPENDED` and `RATE_LIMITED` are *consequences* of failures that have
 * already been counted, so including them would count the same burst twice and suspend a link
 * early; `EXPIRED` is not a failed credential attempt at all, and letting an expired link accumulate
 * toward suspension would be simply wrong.
 *
 * The `since` boundary is inclusive (`!timestamp.isBefore(since)`), matching a SQL `timestamp >= ?`.
 */
class FakePortalAccessTrail(vararg seed: PortalAccessLog) : PortalAccessTrail {

    val failure = FakeFailure()

    private val trail = seed.toMutableList()

    /** Everything recorded, oldest first. A copy — the caller cannot append through it. */
    val entries: List<PortalAccessLog> get() = trail.toList()

    override suspend fun record(entry: PortalAccessLog) {
        failure.check()
        trail += entry
    }

    override suspend fun findFor(uploadLinkId: EntityId): List<PortalAccessLog> {
        failure.check()
        return trail.filter { it.uploadLinkId == uploadLinkId }.sortedBy { it.timestamp }
    }

    override suspend fun distinctIpsFor(uploadLinkId: EntityId): Set<String> {
        failure.check()
        return trail.filter { it.uploadLinkId == uploadLinkId }.mapTo(mutableSetOf()) { it.ip }
    }

    override suspend fun countRecentFailures(uploadLinkId: EntityId, since: Instant): Int {
        failure.check()
        return trail.count {
            it.uploadLinkId == uploadLinkId &&
                it.outcome == PortalOutcome.DENIED &&
                !it.timestamp.isBefore(since)
        }
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** Seed without going through [record]. Still append-only; there is no way to remove one. */
    fun given(vararg entries: PortalAccessLog): FakePortalAccessTrail = apply { trail += entries }

    // ── Assert ──────────────────────────────────────────────────────────────────────────────────

    fun outcomesFor(uploadLinkId: EntityId): List<PortalOutcome> =
        trail.filter { it.uploadLinkId == uploadLinkId }.sortedBy { it.timestamp }.map { it.outcome }
}
