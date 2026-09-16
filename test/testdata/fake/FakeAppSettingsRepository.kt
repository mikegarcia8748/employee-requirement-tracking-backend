package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
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
 *
 * [refuses] is the second exception to `FakeFailure`'s throw-don't-return rule, alongside
 * [FakeNotifier]: ERT-310 made `DomainResult` part of this port's signature, so an `Err` is a path
 * the real adapter genuinely has — an unreadable settings row — and a use case that mistook it for
 * success would otherwise be untestable. [failure] still exists for the different case of the
 * database being unreachable.
 */
class FakeAppSettingsRepository(private var policy: LinkPolicy = LinkPolicy()) : AppSettingsRepository {

    val failure = FakeFailure()

    private var readCount = 0
    private var refusal: AppError? = null
    private val updateCalls = mutableListOf<Update>()

    /** How many times the policy was read. Zero means a use case used hardcoded durations. */
    val reads: Int get() = readCount

    /** Every [updateLinkPolicy] call, in order, with the actor that made it. */
    val updates: List<Update> get() = updateCalls.toList()

    /** The policy as it currently stands, without recording a read. */
    val current: LinkPolicy get() = policy

    data class Update(val policy: LinkPolicy, val actor: String)

    override suspend fun linkPolicy(): DomainResult<LinkPolicy> {
        failure.check()
        readCount++
        return refusal?.asErr() ?: policy.asOk()
    }

    override suspend fun updateLinkPolicy(policy: LinkPolicy, actor: String): DomainResult<Unit> {
        failure.check()
        refusal?.let { return it.asErr() }
        this.policy = policy
        updateCalls += Update(policy, actor)
        return Unit.asOk()
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** Set the policy without recording an update, and without counting a read. */
    fun given(policy: LinkPolicy): FakeAppSettingsRepository = apply { this.policy = policy }

    /**
     * Every call returns this error, as the adapter does for a settings row that cannot be read.
     *
     * Still counts as a read: a use case that asked and was refused did ask, and conflating the two
     * would let a use case that never reads the policy pass a test written for the refusal path.
     */
    fun refuses(error: AppError): FakeAppSettingsRepository = apply { refusal = error }
}
