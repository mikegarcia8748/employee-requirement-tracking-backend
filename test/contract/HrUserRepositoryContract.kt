package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.MigratedDatabase
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedHrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * One account store, two implementations, one set of rules (ERT-250).
 *
 * **This is the pair HAR-01 calls sharpest, and it is worth stating plainly what was wrong.** The
 * fake's `save` was a map put with no uniqueness at all, and its `findByEmail` was `firstOrNull`.
 * The adapter has `users_email_unique` plus a `users_email_is_lowercase` check, and resolves with
 * `singleOrNull`. So the fake would happily hold two accounts on one address and then sign the
 * **first** of them in, while production holds one — or, if a migration or a psql prompt ever wrote
 * two, finds two rows and returns **null**, refusing every sign-in for that address.
 *
 * A fake-backed sign-in test could therefore be green in a state production cannot serve.
 * `CreateHrUserUseCase` guards the duplicate and calls itself belt-and-braces with the index — but
 * that guard is the thing under test, and deleting it left `HrUserAdministrationTest` green.
 *
 * **Both halves are closed here, not just the reachable one.** With `save` refusing duplicates, a
 * second row on one address is unreachable through the port, so `findByEmail`'s `singleOrNull`
 * semantics can never fire — through the port. `given` is the other door, and a test using it is
 * how a future reader would reach exactly the state the adapter answers null for.
 */
abstract class HrUserRepositoryContract {

    protected abstract val users: HrUserRepository

    /** Arrange through each side's own door: the fake's seed helper, the adapter's insert. */
    protected abstract suspend fun given(vararg user: HrUser)

    // ── Uniqueness ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user contract - a second account on a stored address - both implementations refuse it`() =
        runTest {
            users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

            assertFails {
                users.save(anHrUser(id = personId("HRU00002"), email = anEmail("ana@example.com")))
            }

            users.findAll().map { it.id.value } shouldBe listOf("HRU00001")
        }

    @Test
    fun `hr user contract - the same address in another case - both implementations still refuse it`() =
        runTest {
            // `EmailAddress.of` lower-cases on construction, so this application cannot present the
            // pair — the adapter's CHECK plus unique index exist for the writers it does not
            // control. The fake must not be the more forgiving of the two.
            users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

            assertFails {
                users.save(anHrUser(id = personId("HRU00002"), email = anEmail("ANA@Example.COM")))
            }
        }

    @Test
    fun `hr user contract - saving the same account twice - both implementations update rather than refuse`() =
        runTest {
            // The mirror of the two above, and the reason `save` cannot simply reject every repeat
            // address: re-saving the SAME id is how a password change and a deactivation are stored.
            val stored = users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

            users.save(stored.copy(fullName = "Ana Reyes-Cruz", isActive = false))

            users.findById(personId("HRU00001"))?.fullName shouldBe "Ana Reyes-Cruz"
            users.findById(personId("HRU00001"))?.isActive shouldBe false
            users.countAll() shouldBe 1L
        }

    @Test
    fun `hr user contract - a store seeded with two accounts on one address - both implementations refuse it`() =
        runTest {
            // The fourth door. `save` and `given` are two; the fake's CONSTRUCTOR was a third that
            // let a test arrange the pair anyway, and the adapter has no constructor door at all.
            // Found by the mutation pass, not by the review: swapping `singleOrNull` for
            // `firstOrNull` SURVIVED on both sides, because with the other doors shut nothing could
            // build the state that tells them apart.
            assertFails {
                givenTwoAccountsSharing("ana@example.com")
            }
        }

    /** Seeds two accounts on one address through whichever door each implementation still has. */
    protected abstract suspend fun givenTwoAccountsSharing(address: String)

    // ── findByEmail ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user contract - a stored address - both implementations resolve it`() = runTest {
        users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

        users.findByEmail(anEmail("ana@example.com"))?.id?.value shouldBe "HRU00001"
    }

    @Test
    fun `hr user contract - an address in another case - both implementations resolve it`() = runTest {
        users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

        // `anEmail` lower-cases, so this asserts the lookup is case-insensitive on the stored side
        // rather than merely comparing two already-canonical strings.
        users.findByEmail(anEmail("ANA@EXAMPLE.COM"))?.id?.value shouldBe "HRU00001"
    }

    @Test
    fun `hr user contract - an address held by nobody - both implementations answer null`() = runTest {
        users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

        // Paired with a stored row, so a lookup that always answered null cannot satisfy it.
        users.findByEmail(anEmail("ana@example.com")).shouldNotBeNullId()
        users.findByEmail(anEmail("nobody@example.com")).shouldBeNull()
    }

    @Test
    fun `hr user contract - an id held by nobody - both implementations answer null`() = runTest {
        users.save(anHrUser(id = personId("HRU00001")))

        users.findById(personId("HRU00001")).shouldNotBeNullId()
        users.findById(personId("HRU99999")).shouldBeNull()
    }

    // ── findAll ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user contract - several accounts - both implementations list them in address order`() =
        runTest {
            // Saved in an order that is neither the asserted one nor its reverse, with ids that
            // ascend in a third order, so insertion order and id order each name a different first
            // row than `email ASC` does.
            given(
                anHrUser(id = personId("HRU00002"), email = anEmail("mila@example.com")),
                anHrUser(id = personId("HRU00001"), email = anEmail("zoe@example.com")),
                anHrUser(id = personId("HRU00003"), email = anEmail("ana@example.com")),
            )

            users.findAll().map { it.email.value } shouldBe
                listOf("ana@example.com", "mila@example.com", "zoe@example.com")
        }

    @Test
    fun `hr user contract - a deactivated account - both implementations still list it`() = runTest {
        // `findAll` backs the §8.13 admin screen, which has to be able to see an account in order
        // to reactivate it. A filter here would make deactivation irreversible through the UI.
        given(
            anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com"), isActive = false),
            anHrUser(id = personId("HRU00002"), email = anEmail("mila@example.com"), isActive = true),
        )

        users.findAll().map { it.id.value } shouldBe listOf("HRU00001", "HRU00002")
    }

    @Test
    fun `hr user contract - an empty store - both implementations answer empty and count zero`() =
        runTest {
            // `EnsureBootstrapHrUserUseCase` decides on the row count, so "is this store empty"
            // must mean the same thing on both sides or the bootstrap admin appears in one and not
            // the other.
            users.findAll().shouldBeEmpty()
            users.countAll() shouldBe 0L
        }

    @Test
    fun `hr user contract - two accounts - both implementations count both`() = runTest {
        given(
            anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")),
            anHrUser(id = personId("HRU00002"), email = anEmail("mila@example.com"), role = HrRole.HR_ADMIN),
        )

        users.countAll() shouldBe 2L
    }

    private fun HrUser?.shouldNotBeNullId() {
        (this != null) shouldBe true
    }
}

class FakeHrUserRepositoryContractTest : HrUserRepositoryContract() {

    private val fake = FakeHrUserRepository()

    override val users: HrUserRepository = fake

    override suspend fun given(vararg user: HrUser) {
        fake.given(*user)
    }

    override suspend fun givenTwoAccountsSharing(address: String) {
        FakeHrUserRepository(
            anHrUser(id = personId("HRU00001"), email = anEmail(address)),
            anHrUser(id = personId("HRU00002"), email = anEmail(address)),
        )
    }
}

class ExposedHrUserRepositoryContractTest : HrUserRepositoryContract() {

    private val database = MigratedDatabase()

    override val users: HrUserRepository by lazy { ExposedHrUserRepository(database.factory) }

    override suspend fun given(vararg user: HrUser) {
        user.forEach { users.save(it) }
    }

    override suspend fun givenTwoAccountsSharing(address: String) {
        // The adapter has no constructor door: `users_email_unique` is the whole guard, and the
        // insert is the only way in.
        users.save(anHrUser(id = personId("HRU00001"), email = anEmail(address)))
        users.save(anHrUser(id = personId("HRU00002"), email = anEmail(address)))
    }

    @BeforeTest
    fun openDatabase() {
        database.open()
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
    }
}
