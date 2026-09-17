package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.data.crypto.HmacTokenDigest
import com.pgsystem.employee.requirement.tracker.domain.model.LinkScope
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aRevokedLink
import com.pgsystem.employee.requirement.tracker.testdata.aSuspendedLink
import com.pgsystem.employee.requirement.tracker.testdata.anUploadLink
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Upload links against real SQL (ERT-420).
 *
 * What is tested here is everything only a database can answer: that fifteen fields round-trip
 * through the mapper, that the one encoding with no column type behind it — [LinkScope] — survives
 * both directions, that a presented digest resolves the row the unique index promises, and that the
 * active-link lookup is scoped and ordered rather than returning whatever the engine hands back.
 * The rules about *when* a link is issued, expires or locks belong to `CreateHireUseCase` (ERT-430)
 * and `RedeemRecoveryPinUseCase` (ERT-640), neither of which exists yet.
 *
 * **H2 in PostgreSQL mode is not PostgreSQL** — `RepositoryTestBase` says why at length.
 *
 * ### The vacuity trap, anticipated — and hit anyway, twice
 *
 * ERT-320 found `sort_order` coincidentally matching id order, ERT-350 found "in name order"
 * vacuous against one seeded department, and ERT-410 found it **inside a test written specifically
 * to prevent it**. This class opened by claiming to have learned that, and **two of its tests were
 * vacuous on the first pass anyway** — which the eight deliberate breaks caught and no amount of
 * care in writing them would have:
 *
 *  - the ordering test first gave the newest link the **lowest id**, so dropping the `ORDER BY`
 *    entirely still passed: H2 with no ordering returns the primary-key scan, and "lowest id" and
 *    "newest" were the same row;
 *  - the scope test first used **two** ids given as `[2, 1]`, so replacing `sorted()` with
 *    `reversed()` produced the sorted order anyway. That is ERT-410's exact coincidence, reproduced
 *    in a test whose own comment claimed to have avoided it.
 *
 * Both now arrange a case where every accident returns a different row than the rule does. The
 * carried-forward lesson is ERT-410's, one turn sharper: **writing the anti-coincidence test is not
 * the same as checking that it works, and intending to check is not the same as checking.** What is
 * left standing in this class:
 *
 *  - the ordering test arranges three links whose newest carries the middle id and the middle
 *    insertion position, so id order, insertion order and `issued_at ASC` each name a different row;
 *  - the scope test uses three ids in an order that is neither the sorted one nor its reverse;
 *  - every exclusion test is paired with an inclusion, so a filter that returns nothing at all
 *    cannot satisfy it.
 *
 * The eight breaks are listed in this ticket's block; each fails a named test above.
 */
class ExposedUploadLinkRepositoryTest : RepositoryTestBase() {

    private val links by lazy { ExposedUploadLinkRepository(factory) }

    /**
     * The foreign keys a builder default cannot satisfy on its own, plus the hire the link hangs
     * off.
     *
     * `anUploadLink()` points at `Fixtures.EMPLOYEE_ID`, and `upload_links.employee_id` is an
     * `on delete restrict` foreign key — so a link cannot be stored until a hire exists, and a hire
     * cannot be stored until its department, employment type and creating user do. None of the four
     * is present after migration: the V2 seed holds `d00000000001` and `e00000000001`..`4`, and
     * `users` is empty because the bootstrap admin is a startup use case rather than a seed row.
     *
     * Inserted under the `Fixtures` ids rather than re-pointing every builder call, exactly as
     * `ExposedEmployeeRepositoryTest` does — which is what keeps each test below reading as the rule
     * it is about. ERT-410's block predicted this ticket would hit it.
     */
    @BeforeTest
    fun seedFixtureReferences() {
        runBlocking {
            execute(
                """
                insert into users (id, email, full_name, password_hash, "role", is_active,
                                   password_change_required, created_at)
                values ('${Fixtures.HR_USER_ID.value}', 'hr.officer@example.com', 'Ana Reyes', 'x',
                        'HR_OFFICER', true, false, current_timestamp)
                """.trimIndent()
            )
            execute(
                """
                insert into departments (id, "name")
                values ('${Fixtures.DEPARTMENT_ID.value}', 'Fixture Department')
                """.trimIndent()
            )
            execute(
                """
                insert into employment_types (id, "name")
                values ('${Fixtures.EMPLOYMENT_TYPE_ID.value}', 'Fixture Employment Type')
                """.trimIndent()
            )
            insertHire(Fixtures.EMPLOYEE_ID.value, "jose@example.com")
        }
    }

    // ── Round trip ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link persistence - a fully populated link - round-trips unchanged`() = runTest {
        // Every nullable column carries a value, so a mapper that dropped one on either side fails
        // here rather than in the first ERT-640 rule that reads it.
        val link = anUploadLink(
            pinHash = "\$2a\$04\$recoverypinhashplaceholder",
            scope = LinkScope.Only(setOf(Fixtures.TEMPLATE_ID)),
            status = LinkStatus.SUSPENDED,
            idleExpiresAt = FixedClock.DEFAULT.plus(Duration.ofDays(30)),
            extendedCount = 2,
            failedPinCount = 7,
            lockedUntil = FixedClock.DEFAULT.plus(Duration.ofMinutes(15)),
            warnedAt = FixedClock.DEFAULT.plus(Duration.ofDays(83)),
            revokedAt = FixedClock.DEFAULT.plus(Duration.ofDays(84)),
            revokedReason = "Employee reported the link was forwarded",
        )

        links.save(link)

        links.findByTokenHash(link.tokenHash) shouldBe link
    }

    @Test
    fun `link persistence - a link with every nullable empty - round-trips unchanged`() = runTest {
        // The mirror of the test above, and not redundant with it: a writeTo that wrote a
        // placeholder instead of null for one column would pass the fully-populated case.
        val link = anUploadLink(
            pinHash = null,
            idleExpiresAt = null,
            lockedUntil = null,
            warnedAt = null,
            revokedAt = null,
            revokedReason = null,
        )

        links.save(link)

        links.findByTokenHash(link.tokenHash) shouldBe link
    }

    @Test
    fun `link persistence - a new link - stores no recovery pin`() = runTest {
        // The criterion V5 exists for. Before it, pin_hash was NOT NULL and this link could not be
        // stored at all without inventing a bcrypt hash of a PIN nobody was told -- which the column
        // could not have been distinguished from a live one (PRD 6.6, reversed 2026-09-16).
        val link = anUploadLink(pinHash = null)

        links.save(link)

        links.findByTokenHash(link.tokenHash)?.pinHash.shouldBeNull()
        strings("select count(*) from upload_links where pin_hash is null").single() shouldBe "1"
    }

    @Test
    fun `link persistence - an update - writes every mutable column`() = runTest {
        // save() is insert-or-update on an EntityId, unlike EmployeeRepository's split create/save.
        // An adapter that always inserted would fail on the primary key; one that always updated
        // would match no rows and write nothing. Both are caught by asserting the count as well.
        val issued = anUploadLink()
        links.save(issued)

        val suspended = issued.copy(
            status = LinkStatus.SUSPENDED,
            failedPinCount = 10,
            revokedReason = "Ten failed recovery attempts",
        )
        links.save(suspended)

        links.findByTokenHash(issued.tokenHash) shouldBe suspended
        countOf("upload_links") shouldBe 1
    }

    // ── Lookup by token digest ──────────────────────────────────────────────────────────────────

    @Test
    fun `link lookup - the hash of a presented token - resolves the link`() = runTest {
        // Paired with a second link so the lookup has something to discriminate against: with one
        // row stored, a findByTokenHash that ignored its argument entirely would pass.
        val digest = HmacTokenDigest("a-test-pepper-at-least-32-characters-long")
        val presented = "the-token-from-the-invitation-email"
        val mine = anUploadLink(tokenHash = digest.digest(presented))
        val theirs = anUploadLink(
            id = entityId("LNK000000002"),
            tokenHash = digest.digest("somebody-else's-token"),
        )
        links.save(mine)
        links.save(theirs)

        links.findByTokenHash(digest.digest(presented))?.id shouldBe mine.id
    }

    @Test
    fun `link lookup - the same token presented twice - resolves to the same row`() = runTest {
        // The reason ERT-160 split TokenDigest from Hasher, proved end to end rather than asserted
        // in the crypto test alone. bcrypt salts every call, so a link stored under a bcrypt hash
        // would be found by the presentation that stored it and by no other -- and this test is
        // where that shows up, because it digests the same plaintext twice through one adapter.
        val digest = HmacTokenDigest("a-test-pepper-at-least-32-characters-long")
        val presented = "the-token-from-the-invitation-email"
        links.save(anUploadLink(tokenHash = digest.digest(presented)))

        val first = links.findByTokenHash(digest.digest(presented))
        val second = links.findByTokenHash(digest.digest(presented))

        first?.id shouldBe Fixtures.LINK_ID
        second?.id shouldBe first?.id
    }

    @Test
    fun `link lookup - a hash no link holds - is not found`() = runTest {
        links.save(anUploadLink(tokenHash = "token-hash-0000000001"))

        links.findByTokenHash("token-hash-0000009999").shouldBeNull()
    }

    @Test
    fun `link persistence - a stored link - exposes no plaintext token or pin`() = runTest {
        // Reads the columns rather than the domain object: the object cannot hold a plaintext token
        // because the field is named tokenHash, which proves nothing about what reached the row.
        val digest = HmacTokenDigest("a-test-pepper-at-least-32-characters-long")
        val plaintextToken = "the-token-from-the-invitation-email"
        val plaintextPin = "424242"
        links.save(
            anUploadLink(
                tokenHash = digest.digest(plaintextToken),
                pinHash = "\$2a\$04\$notthepinitself",
            )
        )

        val row = strings(
            "select token_hash || '|' || coalesce(pin_hash, '') || '|' || coalesce(revoked_reason, '') " +
                "from upload_links where id = '${Fixtures.LINK_ID.value}'"
        ).single()

        row.contains(plaintextToken) shouldBe false
        row.contains(plaintextPin) shouldBe false
    }

    @Test
    fun `link persistence - a second link taking a stored token hash - is refused by the unique index`() =
        runTest {
            // FakeUploadLinkRepository refuses this in Kotlin so a use-case test cannot arrange a
            // state the database rejects. This is the half that proves the database really does
            // reject it -- without it, the fake could be guarding against nothing.
            links.save(anUploadLink(tokenHash = "token-hash-0000000001"))

            assertFailsWith<Exception> {
                links.save(
                    anUploadLink(id = entityId("LNK000000002"), tokenHash = "token-hash-0000000001")
                )
            }

            countOf("upload_links") shouldBe 1
        }

    // ── Link scope ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link scope - Only with three template ids - round-trips intact and in one stable order`() =
        runTest {
            // THREE ids, not two, and given in an order that is neither the sorted one nor its
            // reverse -- the correction ERT-410 had to make to its own anomaly-flag test and which
            // this test needed too. With two ids given as [2, 1], replacing `sorted()` with
            // `reversed()` produces the sorted order anyway: `setOf` is insertion-ordered, so the
            // broken encoder wrote exactly what the correct one writes and the assertion passed.
            //
            // Given [2, 3, 1]: sorted is 1,2,3; reversed is 1,3,2; unsorted is 2,3,1. All three
            // differ, so both breaks fail.
            //
            // The raw-column assertion is the one that bites. A set compares equal whatever order it
            // was written in, so the round trip alone would pass against an encoder with no sort at
            // all -- and an unsorted Set writes the same scope two different ways on two saves.
            val one = Fixtures.TEMPLATE_ID
            val two = entityId("TPL000000002")
            val three = entityId("TPL000000003")
            links.save(anUploadLink(scope = LinkScope.Only(setOf(two, three, one))))

            links.findByTokenHash("token-hash-0000000001")?.scope shouldBe
                LinkScope.Only(setOf(one, two, three))
            strings("select scope from upload_links where id = '${Fixtures.LINK_ID.value}'")
                .single() shouldBe "ONLY:${one.value},${two.value},${three.value}"
        }

    @Test
    fun `link scope - Only with no template ids - round-trips as an empty set rather than one blank id`() =
        runTest {
            // THE ERT-410 BLANK-ELEMENT TRAP, one file over. "ONLY:".removePrefix("ONLY:") is the
            // empty string, and "".split(",") yields one BLANK element rather than none -- the same
            // shape that would have reported an anomaly flag on every hire in the system and
            // silently disabled the 7.1 purge. Here the blank reaches EntityId.of instead, so
            // without the filter this fails as a corrupt row and the message names the id format
            // rather than the encoding that produced it.
            links.save(anUploadLink(scope = LinkScope.Only(emptySet())))

            links.findByTokenHash("token-hash-0000000001")?.scope shouldBe LinkScope.Only(emptySet())
        }

    @Test
    fun `link scope - All - is stored as the column's own default value`() = runTest {
        // Not cosmetic. 'ALL' is upload_links.scope's DEFAULT since V1, so a row inserted by a
        // migration, an import or a psql prompt carries it -- and this assertion is what guarantees
        // such a row decodes as the scope it obviously means instead of as corruption.
        links.save(anUploadLink(scope = LinkScope.All))

        strings("select scope from upload_links where id = '${Fixtures.LINK_ID.value}'")
            .single() shouldBe "ALL"
        links.findByTokenHash("token-hash-0000000001")?.scope shouldBe LinkScope.All
    }

    @Test
    fun `link scope - a stored value that parses as no scope - is rejected as a corrupt row`() =
        runTest {
            insertRawLink(id = "LNK000000009", tokenHash = "token-hash-0000000009", scope = "EVERY")

            failureFrom { links.findByTokenHash("token-hash-0000000009") } shouldContainMessage "scope"
        }

    // ── The active-link lookup ──────────────────────────────────────────────────────────────────

    @Test
    fun `active link lookup - a revoked link and an active one - returns only the active one`() =
        runTest {
            // The inclusion is the half that matters: a lookup returning nothing at all would
            // satisfy "the revoked one is not returned" on its own.
            links.save(aRevokedLink(id = entityId("LNK000000001")).copy(tokenHash = "token-hash-1"))
            links.save(
                anUploadLink(id = entityId("LNK000000002"), tokenHash = "token-hash-2")
            )

            links.findActiveForEmployee(Fixtures.EMPLOYEE_ID)?.id shouldBe entityId("LNK000000002")
        }

    @Test
    fun `active link lookup - a completed link - is not returned`() = runTest {
        // COMPLETED has opensPortal = true (PRD 6.3), which is exactly why this test exists: a
        // filter written as `status.opensPortal` rather than `status == ACTIVE` passes every other
        // test in this section and fails only here. A completed packet's read-only confirmation is
        // reachable, but it is not a link resend-link should reuse.
        links.save(
            anUploadLink(
                id = entityId("LNK000000001"),
                tokenHash = "token-hash-1",
                status = LinkStatus.COMPLETED,
            )
        )
        links.save(aSuspendedLink(id = entityId("LNK000000002")).copy(tokenHash = "token-hash-2"))

        links.findActiveForEmployee(Fixtures.EMPLOYEE_ID).shouldBeNull()
    }

    @Test
    fun `active link lookup - three active links - returns the most recently issued`() = runTest {
        // THE WINNER IS DISTINGUISHED BY `issued_at` AND BY NOTHING ELSE, which took two attempts.
        //
        // The first arrangement gave the newest link the lowest id, and dropping the ORDER BY
        // entirely still passed -- H2 with no ordering returns the primary-key scan, so "lowest id"
        // and "newest" were the same row and the test proved nothing. That is ERT-410's coincidence
        // exactly, in a test written to prevent it, which is why the mutation pass exists rather
        // than the intention to write a good test.
        //
        // Here the newest carries the MIDDLE id and is inserted in the MIDDLE, so id order in either
        // direction, insertion order in either direction, and `issued_at ASC` each return a
        // different row. Only `issued_at DESC` returns this one.
        links.save(issuedAt(id = "LNK000000001", tokenHash = "token-hash-1", daysAfterDefault = 0))
        links.save(issuedAt(id = "LNK000000002", tokenHash = "token-hash-2", daysAfterDefault = 20))
        links.save(issuedAt(id = "LNK000000003", tokenHash = "token-hash-3", daysAfterDefault = 10))

        links.findActiveForEmployee(Fixtures.EMPLOYEE_ID)?.id shouldBe entityId("LNK000000002")
    }

    @Test
    fun `active link lookup - another employee's active link - is not returned`() = runTest {
        // Paired with an inclusion, so a predicate that matched nothing cannot pass.
        insertHire("EMP00002", "maria@example.com")
        links.save(
            anUploadLink(
                id = entityId("LNK000000002"),
                employeeId = personId("EMP00002"),
                tokenHash = "token-hash-2",
            )
        )
        links.save(anUploadLink(id = entityId("LNK000000001"), tokenHash = "token-hash-1"))

        links.findActiveForEmployee(Fixtures.EMPLOYEE_ID)?.id shouldBe entityId("LNK000000001")
        links.findActiveForEmployee(personId("EMP00002"))?.id shouldBe entityId("LNK000000002")
    }

    @Test
    fun `active link lookup - an employee with no link - is not found`() = runTest {
        links.findActiveForEmployee(Fixtures.EMPLOYEE_ID).shouldBeNull()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun issuedAt(id: String, tokenHash: String, daysAfterDefault: Long) = anUploadLink(
        id = entityId(id),
        tokenHash = tokenHash,
        issuedAt = FixedClock.DEFAULT.plus(Duration.ofDays(daysAfterDefault)),
    )

    private suspend fun insertHire(id: String, email: String) = execute(
        """
        insert into employees (id, first_name, last_name, department_id, "position",
                               employment_type_id, email, packet_status, submitted_by_hr,
                               anomaly_flags, created_at, created_by)
        values ('$id', 'Jose', 'Dela Cruz', '${Fixtures.DEPARTMENT_ID.value}', 'Store Associate',
                '${Fixtures.EMPLOYMENT_TYPE_ID.value}', '$email', 'DRAFT_COLLECTING', false,
                '', current_timestamp, '${Fixtures.HR_USER_ID.value}')
        """.trimIndent()
    )

    /**
     * A link written past [com.pgsystem.employee.requirement.tracker.domain.model.UploadLink] and
     * its mapper, for the rows this application cannot produce but another writer can.
     */
    private suspend fun insertRawLink(id: String, tokenHash: String, scope: String) = execute(
        """
        insert into upload_links (id, employee_id, token_hash, scope, status, issued_at, expires_at)
        values ('$id', '${Fixtures.EMPLOYEE_ID.value}', '$tokenHash', '$scope', 'ACTIVE',
                current_timestamp, current_timestamp)
        """.trimIndent()
    )

    /**
     * The whole cause chain of a failure, as one string.
     *
     * `factory.transaction` hops a dispatcher and Exposed may wrap what a mapper throws, so
     * asserting on `failure.message` alone would pass or fail depending on how deep the wrapping
     * goes. Asserting only that *something* was thrown would be weaker still — a foreign-key
     * violation in the arrange step would satisfy it.
     */
    private fun failureFrom(block: suspend () -> Unit): String {
        val failure = assertFailsWith<Exception> { runBlocking { block() } }
        return generateSequence(failure as Throwable) { it.cause }.mapNotNull { it.message }
            .joinToString(" | ")
    }

    private infix fun String.shouldContainMessage(fragment: String) {
        check(contains(fragment)) { "Expected a failure mentioning '$fragment', but got: $this" }
    }
}
