package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.domain.model.PortalSession
import com.pgsystem.employee.requirement.tracker.domain.port.PortalSessionRepository
import java.time.Instant

/**
 * PIN-verified portal sessions, in memory.
 *
 * [findActive] treats a session expiring exactly at `now` as expired — `expiresAt.isAfter(now)`, not
 * `!isBefore`. A session is a window that has closed by the instant it names, and the boundary has
 * to be decided somewhere; deciding it here means ERT-620's adapter has a number to match rather
 * than a preference to guess.
 *
 * **[findActiveForLink] cannot filter by expiry**, because the port hands it no clock. "Active"
 * there can only mean "not explicitly ended", which is what a real adapter would also be limited to
 * without a `now` parameter. That is a real gap, not a shortcut taken by the fake: the P1 control
 * "HR can terminate active portal sessions" reads this list, and it will show sessions that have
 * quietly lapsed. ERT-620 should decide whether the port grows a `now`.
 */
class FakePortalSessionRepository(vararg seed: PortalSession) : PortalSessionRepository {

    val failure = FakeFailure()

    private val sessions = seed.associateBy { it.id }.toMutableMap()
    private val savedSessions = mutableListOf<PortalSession>()
    private val endCalls = mutableListOf<Ended>()

    /** Every [save] call, in order. Seeded sessions do not appear here. */
    val saved: List<PortalSession> get() = savedSessions.toList()

    /** Every [end] call, in order — the HR session-termination control's evidence. */
    val ended: List<Ended> get() = endCalls.toList()

    val all: List<PortalSession> get() = sessions.values.toList()

    data class Ended(val sessionId: EntityId, val endedAt: Instant)

    override suspend fun findActive(sessionId: EntityId, now: Instant): PortalSession? {
        failure.check()
        return sessions[sessionId]?.takeIf { it.endedAt == null && it.expiresAt.isAfter(now) }
    }

    override suspend fun findActiveForLink(uploadLinkId: EntityId): List<PortalSession> {
        failure.check()
        return sessions.values
            .filter { it.uploadLinkId == uploadLinkId && it.endedAt == null }
            .sortedBy { it.startedAt }
    }

    override suspend fun save(session: PortalSession): PortalSession {
        failure.check()
        sessions[session.id] = session
        savedSessions += session
        return session
    }

    override suspend fun end(sessionId: EntityId, endedAt: Instant) {
        failure.check()
        val session = sessions[sessionId]
            ?: error("No session ${sessionId.value} to end; ending one that was never saved is a test mistake")

        sessions[sessionId] = session.copy(endedAt = endedAt)
        endCalls += Ended(sessionId, endedAt)
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    fun given(vararg sessions: PortalSession): FakePortalSessionRepository = apply {
        sessions.forEach { this.sessions[it.id] = it }
    }
}
