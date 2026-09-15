package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository

/**
 * The PRD 6.4 policy, in memory.
 *
 * **Bounds are deliberately not enforced here.** §6.4 caps absolute expiry at 7..180 days so a
 * well-meant edit cannot turn a token into a permanent credential, and that enforcement belongs to
 * ERT-310's adapter. A fake that also enforced it would accept a bounds bug in the adapter without
 * complaint, because every test would have been passing values the fake had already filtered.
 *
 * [reads] exists for the quietest risk on the roadmap: if `expiresAt` comes from `LinkPolicy`'s
 * Kotlin defaults rather than from this port, §8.10 is violated from the first row and **nothing
 * detects it**, because the numbers are identical. Asserting that the policy was actually read is
 * the only way to tell the two apart.
 */
class FakeAppSettingsRepository(private var policy: LinkPolicy = LinkPolicy()) : AppSettingsRepository {

    val failure = FakeFailure()

    private var readCount = 0
    private val updateCalls = mutableListOf<Update>()

    /** How many times the policy was read. Zero means a use case used hardcoded durations. */
    val reads: Int get() = readCount

    /** Every [updateLinkPolicy] call, in order, with the actor that made it. */
    val updates: List<Update> get() = updateCalls.toList()

    /** The policy as it currently stands, without recording a read. */
    val current: LinkPolicy get() = policy

    data class Update(val policy: LinkPolicy, val actor: String)

    override suspend fun linkPolicy(): LinkPolicy {
        failure.check()
        readCount++
        return policy
    }

    override suspend fun updateLinkPolicy(policy: LinkPolicy, actor: String) {
        failure.check()
        this.policy = policy
        updateCalls += Update(policy, actor)
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** Set the policy without recording an update, and without counting a read. */
    fun given(policy: LinkPolicy): FakeAppSettingsRepository = apply { this.policy = policy }
}
