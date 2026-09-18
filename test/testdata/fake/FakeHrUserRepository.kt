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
 *
 * **Case-insensitive uniqueness is enforced on every door in, and that is ERT-250's doing.** Until
 * the contract suite compared the two, [save] was a plain map put and [findByEmail] was
 * `firstOrNull` — so this fake would hold two accounts on one address and then sign the *first* of
 * them in, while `ExposedHrUserRepository` holds one (`users_email_unique` plus the
 * `users_email_is_lowercase` check) and resolves with `singleOrNull`, which answers **null** when
 * more than one row matches. A fake-backed sign-in test could be green in a state where production
 * refuses every sign-in for that address (HAR-01 b and c).
 *
 * `CreateHrUserUseCase` also guards the duplicate and says it is belt-and-braces with the index.
 * That guard is the thing under test, so it cannot be what makes the fake correct: deleting it left
 * `HrUserAdministrationTest` green.
 */
class FakeHrUserRepository(vararg seed: HrUser) : HrUserRepository {

    val failure = FakeFailure()

    private val users = seed.associateBy { it.id }.toMutableMap()
    private val savedUsers = mutableListOf<HrUser>()

    /** Every [save] call, in order. Seeded users do not appear here. */
    val saved: List<HrUser> get() = savedUsers.toList()

    /** Everything currently held, seeded or saved. */
    val all: List<HrUser> get() = users.values.toList()

    init {
        // The fourth door, found by ERT-250's mutation pass rather than by the review.
        // `save` and `given` both refuse a duplicate address; the constructor did not, so
        // `FakeHrUserRepository(anHrUser(email = x), anHrUser(email = x))` still arranged a pair the
        // database has refused since V4. `FakeUploadLinkRepository` has guarded its own constructor
        // since ERT-420 and is the shape copied here.
        users.values.groupBy { it.email.value.lowercase() }.forEach { (address, sharing) ->
            check(sharing.size == 1) {
                "Seeded ${sharing.size} accounts on address '$address'; users.email is unique"
            }
        }
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Adds a user without recording a save.
     *
     * Refuses a duplicate address for the same reason [save] does. A seed helper that skipped the
     * constraint would be the unguarded third door into a state the database cannot hold — the
     * shape HAR-01 (e) found in `FakeUploadLinkRepository.given`.
     */
    fun given(vararg user: HrUser) = apply {
        user.forEach {
            refuseDuplicateAddress(it)
            users[it.id] = it
        }
    }

    // ── The port ────────────────────────────────────────────────────────────────────────────────

    override suspend fun findById(id: PersonId): HrUser? {
        failure.check()
        return users[id]
    }

    /**
     * `singleOrNull`, not `firstOrNull` — the adapter's answer when more than one row matches is
     * **null**, not the first of them.
     *
     * **No test reaches this, on either side, and that is recorded rather than papered over.** With
     * uniqueness enforced on all four doors here and by `users_email_unique` plus the lowercase
     * CHECK there, two rows on one address are unreachable through the port in both implementations
     * — so `singleOrNull` and `firstOrNull` are indistinguishable and ERT-250's mutation pass
     * reports swapping them as SURVIVED on the fake *and* on the adapter. It is an equivalent
     * mutant, not a gap in the suite.
     *
     * It stays because the adapter's does: `EmailAddress.of` lower-cases on construction, so this
     * application cannot write the pair, but a migration, an import or a person at a psql prompt
     * can — and then the right answer is "I cannot tell you who this is", not "here is the first
     * one I found". Kotlin's `singleOrNull` returns null for a list of any size but one, exactly as
     * Exposed's does over a `Query`.
     */
    override suspend fun findByEmail(email: EmailAddress): HrUser? {
        failure.check()
        return users.values
            .filter { it.email.value.lowercase() == email.value.lowercase() }
            .singleOrNull()
    }

    override suspend fun findAll(): List<HrUser> {
        failure.check()
        return users.values.sortedBy { it.email.value }
    }

    override suspend fun save(user: HrUser): HrUser {
        failure.check()
        refuseDuplicateAddress(user)
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

    /**
     * `users_email_unique` and `users_email_is_lowercase`, in memory.
     *
     * Keyed on the lower-cased address because the adapter's pair is strictly stronger than a plain
     * unique index: the check forces every stored value into canonical lower case, so two casings
     * of one address cannot coexist there either. Re-saving the *same* id is an update and must
     * pass — that is how a password change and a deactivation are stored.
     */
    private fun refuseDuplicateAddress(user: HrUser) {
        val holder = users.values.firstOrNull {
            it.id != user.id && it.email.value.lowercase() == user.email.value.lowercase()
        }

        check(holder == null) {
            "Account ${user.id.value} carries the address already held by ${holder?.id?.value}; " +
                "users.email is unique and is forced to lower case"
        }
    }
}
