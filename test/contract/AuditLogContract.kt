package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.MigratedDatabase
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAuditLog
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anAuditEntry
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * One trail, two implementations, one set of rules (ERT-250).
 *
 * `FakeAuditLog.findFor` filtered a `LinkedHashMap` and returned insertion order; `ExposedAuditLog`
 * has ordered by `timestamp ASC, id ASC` since ERT-330 and has a test saying so. Nothing compared
 * them, so the fake's order was whatever a test happened to record in — HAR-01 (d). `findFor` has no
 * caller yet, which is exactly why this was cheap to close now and would not have been once §8.12
 * rendered a history.
 *
 * **The ordering test arranges three entries where timestamp order, id order and insertion order
 * each name a different sequence.** ERT-410 and ERT-420 both shipped ordering tests that passed
 * against no `ORDER BY` at all, because the accident and the rule agreed; ERT-432 fixed that by
 * designing the arrangement up front rather than discovering it in the mutation pass.
 */
abstract class AuditLogContract {

    protected abstract val trail: AuditLog

    // ── findFor ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `audit contract - several entries for one entity - both implementations order chronologically`() =
        runTest {
            // Recorded newest-first, and the newest carries the LOWEST id. So insertion order and id
            // order each name a different first row than `timestamp ASC` does, and only the rule
            // returns the sequence asserted below.
            trail.record(entryAt(id = "AUD000000001", minutes = 20))
            trail.record(entryAt(id = "AUD000000003", minutes = 0))
            trail.record(entryAt(id = "AUD000000002", minutes = 10))

            trail.findFor(Fixtures.EMPLOYEE_ID).map { it.id.value } shouldBe
                listOf("AUD000000003", "AUD000000002", "AUD000000001")
        }

    @Test
    fun `audit contract - two entries sharing an instant - both implementations break the tie by id`() =
        runTest {
            // The `id` key is a tiebreaker for determinism only: production ids are random, so id
            // order is not time order and must never be read as though it were. Recorded with the
            // higher id first, so insertion order cannot produce the asserted sequence.
            trail.record(entryAt(id = "AUD000000009", minutes = 5))
            trail.record(entryAt(id = "AUD000000004", minutes = 5))

            trail.findFor(Fixtures.EMPLOYEE_ID).map { it.id.value } shouldBe
                listOf("AUD000000004", "AUD000000009")
        }

    @Test
    fun `audit contract - an entity with no entries - both implementations answer empty`() = runTest {
        trail.record(entryAt(id = "AUD000000001", minutes = 0))

        // Paired with a stored row, so a `findFor` that returned nothing at all cannot satisfy it.
        trail.findFor(Fixtures.EMPLOYEE_ID).size shouldBe 1
        trail.findFor(personId("EMP99999")).shouldBeEmpty()
    }

    @Test
    fun `audit contract - entries against two entities - both implementations return only the one asked for`() =
        runTest {
            trail.record(entryAt(id = "AUD000000001", minutes = 0))
            trail.record(anAuditEntry(id = entityId("AUD000000002"), entityId = entityId("LNK000000001")))

            trail.findFor(Fixtures.EMPLOYEE_ID).map { it.id.value } shouldBe listOf("AUD000000001")
            trail.findFor(entityId("LNK000000001")).map { it.id.value } shouldBe listOf("AUD000000002")
        }

    // ── record ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `audit contract - a recorded entry - both implementations return it with its fields intact`() =
        runTest {
            val entry = anAuditEntry(
                id = entityId("AUD000000007"),
                action = AuditAction.HIRE_CREATED,
                actor = "hr.officer@example.com",
                metadata = mapOf("duplicateReason" to "Replacing a lost record"),
            )

            trail.record(entry)

            trail.findFor(Fixtures.EMPLOYEE_ID).single() shouldBe entry
        }

    private fun entryAt(id: String, minutes: Long): AuditEntry = anAuditEntry(
        id = entityId(id),
        timestamp = FixedClock.DEFAULT.plus(Duration.ofMinutes(minutes)),
    )
}

class FakeAuditLogContractTest : AuditLogContract() {
    override val trail = com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog()
}

class ExposedAuditLogContractTest : AuditLogContract() {

    private val database = MigratedDatabase()

    override val trail: AuditLog by lazy { ExposedAuditLog(database.factory) }

    @BeforeTest
    fun openDatabase() {
        database.open()
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
    }
}
