package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.repository.ExposedUploadLinkRepository
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.UploadLink
import com.pgsystem.employee.requirement.tracker.domain.port.UploadLinkRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anUploadLink
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeUploadLinkRepository
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * One link store, two implementations, one set of rules (ERT-250).
 *
 * Two divergences are closed here and the second is the one HAR-01 calls most likely to fire next.
 *
 * **(e) `FakeUploadLinkRepository.given` was a plain map put.** The fake's constructor and its
 * `save` both refuse a duplicate `token_hash` and explain at length why; `given` was the unguarded
 * third door into the same state, and `upload_links_token_hash_unique` has existed since V1.
 *
 * **(f) the fake resolved the active link with `maxByOrNull`, which returns the first maximal
 * element in iteration order.** The adapter orders by `issued_at DESC, id DESC` and limits to one,
 * with the tiebreak named in its KDoc. The two agree until two links share an instant — **and every
 * builder defaults `issuedAt` to `FixedClock.DEFAULT`, so that is not an edge case in this harness,
 * it is what you get unless a test goes out of its way.** `FakesTest`'s ordering test sets them a
 * day apart and so never reached the tie the adapter's KDoc exists to describe. The test below does.
 */
abstract class UploadLinkRepositoryContract {

    protected abstract val links: UploadLinkRepository

    /**
     * Arrange through each side's own door — the fake's seed helper, the adapter's insert.
     *
     * This is what puts divergence (e) under test: `given` is the fake's arrange path and `save` is
     * the adapter's, so a constraint the fake enforces only in `save` shows up here as a fake-side
     * failure.
     */
    protected abstract suspend fun given(vararg link: UploadLink)

    // ── findActiveForEmployee ───────────────────────────────────────────────────────────────────

    @Test
    fun `upload link contract - two links issued in the same instant - both implementations break the tie by id`() =
        runTest {
            // The tie is the default in this harness, not an edge case: every builder issues at
            // FixedClock.DEFAULT. Seeded with the LOWER id first, so insertion order names the
            // other row than `id DESC` does.
            given(
                activeLink(id = "LNK000000001", minutesOld = 0),
                activeLink(id = "LNK000000009", minutesOld = 0),
            )

            links.findActiveForEmployee(Fixtures.EMPLOYEE_ID)?.id?.value shouldBe "LNK000000009"
        }

    @Test
    fun `upload link contract - three active links - both implementations return the newest`() = runTest {
        // The newest carries the MIDDLE id and the MIDDLE insertion position, so id order,
        // insertion order and `issued_at ASC` each name a different row than the rule does.
        given(
            activeLink(id = "LNK000000001", minutesOld = 20),
            activeLink(id = "LNK000000005", minutesOld = 0),
            activeLink(id = "LNK000000009", minutesOld = 40),
        )

        links.findActiveForEmployee(Fixtures.EMPLOYEE_ID)?.id?.value shouldBe "LNK000000005"
    }

    @Test
    fun `upload link contract - a completed link - both implementations exclude it from the active lookup`() =
        runTest {
            // COMPLETED also has `opensPortal = true` (§6.3), which is the plausible mistake: it is
            // reachable but is not a link `resend-link` should reuse. Paired with an ACTIVE link so
            // a lookup returning nothing at all cannot satisfy this.
            given(
                activeLink(id = "LNK000000001", minutesOld = 40),
                activeLink(id = "LNK000000002", minutesOld = 0).copy(status = LinkStatus.COMPLETED),
            )

            links.findActiveForEmployee(Fixtures.EMPLOYEE_ID)?.id?.value shouldBe "LNK000000001"
        }

    @Test
    fun `upload link contract - an employee with no active link - both implementations answer null`() =
        runTest {
            given(activeLink(id = "LNK000000001", minutesOld = 0))

            links.findActiveForEmployee(Fixtures.EMPLOYEE_ID).shouldNotBeNullLink()
            links.findActiveForEmployee(personId("EMP00002")).shouldBeNull()
        }

    // ── findByTokenHash ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `upload link contract - a presented digest - both implementations resolve the link holding it`() =
        runTest {
            given(
                activeLink(id = "LNK000000001", minutesOld = 0).copy(tokenHash = "digest-one"),
                activeLink(id = "LNK000000002", minutesOld = 0).copy(tokenHash = "digest-two"),
            )

            links.findByTokenHash("digest-two")?.id?.value shouldBe "LNK000000002"
        }

    @Test
    fun `upload link contract - a digest nothing holds - both implementations answer null`() = runTest {
        given(activeLink(id = "LNK000000001", minutesOld = 0).copy(tokenHash = "digest-one"))

        links.findByTokenHash("digest-one").shouldNotBeNullLink()
        links.findByTokenHash("digest-nobody-holds").shouldBeNull()
    }

    // ── Uniqueness ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `upload link contract - two links sharing a token hash - both implementations refuse the second`() =
        runTest {
            given(activeLink(id = "LNK000000001", minutesOld = 0).copy(tokenHash = "one-digest"))

            assertFails {
                given(activeLink(id = "LNK000000002", minutesOld = 0).copy(tokenHash = "one-digest"))
            }

            links.findByTokenHash("one-digest")?.id?.value shouldBe "LNK000000001"
        }

    @Test
    fun `upload link contract - re-saving one link - both implementations update rather than refuse`() =
        runTest {
            // The mirror of the test above: a link keeps its digest across a revocation, so `save`
            // cannot refuse every repeat of a hash it already holds.
            val link = activeLink(id = "LNK000000001", minutesOld = 0).copy(tokenHash = "one-digest")
            links.save(link)

            links.save(link.copy(status = LinkStatus.REVOKED, revokedReason = "Address corrected"))

            links.findByTokenHash("one-digest")?.status shouldBe LinkStatus.REVOKED
        }

    private fun activeLink(id: String, minutesOld: Long): UploadLink = anUploadLink(
        id = entityId(id),
        tokenHash = "digest-for-$id",
        issuedAt = FixedClock.DEFAULT.minus(Duration.ofMinutes(minutesOld)),
    )

    private fun UploadLink?.shouldNotBeNullLink() {
        (this != null) shouldBe true
    }
}

class FakeUploadLinkRepositoryContractTest : UploadLinkRepositoryContract() {

    private val fake = FakeUploadLinkRepository()

    override val links: UploadLinkRepository = fake

    override suspend fun given(vararg link: UploadLink) {
        fake.given(*link)
    }
}

class ExposedUploadLinkRepositoryContractTest : UploadLinkRepositoryContract() {

    private val database = ContractDatabase()

    override val links: UploadLinkRepository by lazy { ExposedUploadLinkRepository(database.factory) }

    override suspend fun given(vararg link: UploadLink) {
        link.forEach { links.save(it) }
    }

    @BeforeTest
    fun openDatabase() {
        database.open()
        kotlinx.coroutines.runBlocking {
            database.insertHire(Fixtures.EMPLOYEE_ID.value, "jose@example.com")
        }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
    }
}
