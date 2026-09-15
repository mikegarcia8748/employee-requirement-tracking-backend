package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.Submission
import com.pgsystem.employee.requirement.tracker.domain.port.SubmissionRepository

/**
 * Document versions, in memory, with the PRD 7.1 arithmetic.
 *
 * Three behaviours here are load-bearing rather than incidental.
 *
 * **[purgeBeyondRetention] purges whenever it is asked, and records it.** The retention freeze lives
 * in the *use case*, not here — the port's own documentation says callers must not invoke this for a
 * record with an open anomaly flag. A fake that checked the flag itself would make the freeze
 * untestable: the use case could forget it entirely and the fake would cover for it. Asserting
 * `purges.shouldBeEmpty()` is how ERT-734 proves the use case declined to call.
 *
 * **[save] does not touch the previous version's `isCurrent`.** Whether a new upload supersedes the
 * one before it is ERT-733's decision, expressed in the use case. A helpful fake that flipped the
 * flag would hide a use case that forgot to.
 *
 * **[totalBytesFor] resolves ownership through [RequirementOwners] and fails loudly when it cannot.**
 * See that class for why a silent zero would be worse than an exception.
 */
class FakeSubmissionRepository(
    vararg seed: Submission,
    private val owners: RequirementOwners = RequirementOwners(),
) : SubmissionRepository {

    val failure = FakeFailure()

    private val submissions = seed.associateBy { it.id }.toMutableMap()
    private val savedSubmissions = mutableListOf<Submission>()
    private val purgeCalls = mutableListOf<Purge>()

    /** Every [save] call, in order. Seeded submissions do not appear here. */
    val saved: List<Submission> get() = savedSubmissions.toList()

    /** Every [purgeBeyondRetention] call, in order — including ones that removed nothing. */
    val purges: List<Purge> get() = purgeCalls.toList()

    /** Everything still held. A purged version is gone from here, as it would be from storage. */
    val all: List<Submission> get() = submissions.values.toList()

    data class Purge(val employeeRequirementId: EntityId, val keep: Int, val removed: List<Submission>)

    override suspend fun findCurrentFor(employeeRequirementId: EntityId): Submission? {
        failure.check()
        val current = versionsOf(employeeRequirementId).filter { it.isCurrent }
        check(current.size <= 1) {
            "Requirement ${employeeRequirementId.value} has ${current.size} current versions; at most one may be"
        }
        return current.firstOrNull()
    }

    /** Newest first, so a test can read `findVersions(id).first()` as "the latest". */
    override suspend fun findVersions(employeeRequirementId: EntityId): List<Submission> {
        failure.check()
        return versionsOf(employeeRequirementId).sortedByDescending { it.version }
    }

    override suspend fun save(submission: Submission): Submission {
        failure.check()
        submissions[submission.id] = submission
        savedSubmissions += submission
        return submission
    }

    override suspend fun purgeBeyondRetention(employeeRequirementId: EntityId, keep: Int) {
        failure.check()
        require(keep >= 0) { "Cannot retain a negative number of versions" }

        val removed = versionsOf(employeeRequirementId)
            .sortedByDescending { it.version }
            .drop(keep)

        removed.forEach { submissions.remove(it.id) }
        purgeCalls += Purge(employeeRequirementId, keep, removed)
    }

    /**
     * Every byte the employee currently occupies, superseded versions included.
     *
     * Counting only the current version would make the PRD 7.1 cap unreachable in practice — five
     * retained versions of five documents is what actually fills the 100 MB.
     */
    override suspend fun totalBytesFor(employeeId: PersonId): Long {
        failure.check()
        return submissions.values
            .filter { owners.ownerOrFail(it.employeeRequirementId) == employeeId }
            .sumOf { it.sizeBytes }
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    fun given(vararg submissions: Submission): FakeSubmissionRepository = apply {
        submissions.forEach { this.submissions[it.id] = it }
    }

    fun given(submissions: List<Submission>): FakeSubmissionRepository = given(*submissions.toTypedArray())

    private fun versionsOf(employeeRequirementId: EntityId): List<Submission> =
        submissions.values.filter { it.employeeRequirementId == employeeRequirementId }
}
