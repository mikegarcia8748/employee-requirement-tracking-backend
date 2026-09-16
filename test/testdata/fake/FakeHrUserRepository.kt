package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository

/**
 * HR accounts, in memory (ERT-190).
 *
 * Seeding and saving are separate, as in every fake here: the constructor arranges state without
 * recording anything, and [save] records into [saved], so "the use case saved the user" and "the use
 * case never saved it" are both assertable.
 *
 * **[findByEmail] compares the lower-cased value rather than the [EmailAddress] itself.** Strictly it
 * is redundant — [EmailAddress.of] lower-cases on construction, so two equal addresses are already
 * equal values — and it is here anyway so the fake cannot be *more* forgiving than the adapter it
 * stands in for. A use case tested against a fake that matched case-sensitively would pass here and
 * behave differently against `lower(email)` in SQL.
 */
class FakeHrUserRepository(vararg seed: HrUser) : HrUserRepository {

    val failure = FakeFailure()

    private val users = seed.associateBy { it.id }.toMutableMap()
    private val savedUsers = mutableListOf<HrUser>()

    /** Every [save] call, in order. Seeded users do not appear here. */
    val saved: List<HrUser> get() = savedUsers.toList()

    /** Everything currently held, seeded or saved. */
    val all: List<HrUser> get() = users.values.toList()

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** Adds a user without recording a save. */
    fun given(vararg user: HrUser) = apply { user.forEach { users[it.id] = it } }

    // ── The port ────────────────────────────────────────────────────────────────────────────────

    override suspend fun findById(id: PersonId): HrUser? {
        failure.check()
        return users[id]
    }

    override suspend fun findByEmail(email: EmailAddress): HrUser? {
        failure.check()
        return users.values.firstOrNull { it.email.value.lowercase() == email.value.lowercase() }
    }

    override suspend fun findAll(): List<HrUser> {
        failure.check()
        return users.values.sortedBy { it.email.value }
    }

    override suspend fun save(user: HrUser): HrUser {
        failure.check()
        users[user.id] = user
        savedUsers += user
        return user
    }

    override suspend fun countAll(): Long {
        failure.check()
        return users.size.toLong()
    }

    // ── Assert ──────────────────────────────────────────────────────────────────────────────────

    /** The stored state of one account, which is what a password change or a deactivation moves. */
    fun current(id: PersonId): HrUser? = users[id]
}
