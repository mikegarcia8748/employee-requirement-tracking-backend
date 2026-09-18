package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.domain.model.PortalOutcome
import com.pgsystem.employee.requirement.tracker.domain.port.DocumentStorage
import com.pgsystem.employee.requirement.tracker.domain.port.PortalAccessTrail
import com.pgsystem.employee.requirement.tracker.domain.port.PortalSessionRepository
import com.pgsystem.employee.requirement.tracker.domain.port.SubmissionRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aDeniedAttempt
import com.pgsystem.employee.requirement.tracker.testdata.aPortalAccessLog
import com.pgsystem.employee.requirement.tracker.testdata.aPortalSession
import com.pgsystem.employee.requirement.tracker.testdata.aSubmission
import com.pgsystem.employee.requirement.tracker.testdata.aSuccessfulAccess
import com.pgsystem.employee.requirement.tracker.testdata.aVersionChain
import com.pgsystem.employee.requirement.tracker.testdata.accessLogId
import com.pgsystem.employee.requirement.tracker.testdata.anEndedSession
import com.pgsystem.employee.requirement.tracker.testdata.anExpiredSession
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeDocumentStorage
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakePortalAccessTrail
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakePortalSessionRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeSubmissionRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.RequirementOwners
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFails

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * The four ports with no adapter yet (ERT-250).
 *
 * `SubmissionRepository`, `PortalSessionRepository`, `PortalAccessTrail` and `DocumentStorage` are
 * declared, faked, and unbound — `DataModule.kt` records all four as comments rather than as
 * throwing placeholders, because an unbound port fails loudly at wiring time.
 *
 * Their contracts run against the fake alone, and they are written **now** rather than with the
 * adapter for the reason ERT-170 gives about the portal guards: a seam laid first is one the adapter
 * is written against, rather than one somebody retrofits afterwards around whatever the adapter
 * turned out to do. ERT-610, ERT-620 and ERT-720 each bind into a harness that already exists.
 *
 * ── TRIPWIRE, NOT A BUG ─────────────────────────────────────────────────────────────────────────
 * WHEN AN ADAPTER FOR ONE OF THESE LANDS: add a second concrete subclass beside the fake's, named
 * `Exposed<Port>ContractTest` — the name matters, see `ArchitectureTest.test discovery - every
 * class holding a test - is named so the scan discovers it`. Do NOT
 * delete the suite and do NOT move its assertions into the adapter's own test class: a rule asserted
 * against one implementation is the state this ticket exists to end.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * Versions, retention and the storage cap (§7.1).
 *
 * **`purgeBeyondRetention` purges whenever it is asked, on both sides, and that is deliberate.** The
 * retention freeze belongs to the **use case**, not the repository: asserting that `purges` is empty
 * is how ERT-734 proves the freeze was honoured, and a repository that checked
 * `Employee.retentionFrozen` itself would cover for a use case that forgot it entirely. A contract
 * test asserting the opposite would delete that control — so this suite asserts the purge happens.
 */
abstract class SubmissionRepositoryContract {

    protected abstract val submissions: SubmissionRepository

    @Test
    fun `submission contract - a version chain - both implementations return the versions newest first`() =
        runTest {
            // Newest first is the port's promise, not an accident of the map: a caller reads
            // `findVersions(id).first()` as "the latest". ERT-720's adapter has to ORDER BY version
            // DESC to match, and this test is what will say so when it does not.
            aVersionChain(count = 3).reversed().forEach { submissions.save(it) }

            submissions.findVersions(Fixtures.REQUIREMENT_ID).map { it.version } shouldBe
                listOf(3, 2, 1)
        }

    @Test
    fun `submission contract - a version chain - both implementations return the current one`() =
        runTest {
            aVersionChain(count = 3).forEach { submissions.save(it) }

            submissions.findCurrentFor(Fixtures.REQUIREMENT_ID)?.version shouldBe 3
        }

    @Test
    fun `submission contract - a requirement with no upload - both implementations answer null`() =
        runTest {
            submissions.save(aSubmission())

            (submissions.findCurrentFor(Fixtures.REQUIREMENT_ID) != null) shouldBe true
            submissions.findCurrentFor(entityId("REQ999999999")).shouldBeNull()
            submissions.findVersions(entityId("REQ999999999")).shouldBeEmpty()
        }

    @Test
    fun `submission contract - six versions with retention of five - both implementations purge the oldest`() =
        runTest {
            aVersionChain(count = 6).forEach { submissions.save(it) }

            submissions.purgeBeyondRetention(Fixtures.REQUIREMENT_ID, keep = 5)

            submissions.findVersions(Fixtures.REQUIREMENT_ID).map { it.version } shouldBe
                listOf(6, 5, 4, 3, 2)
        }

    @Test
    fun `submission contract - fewer versions than the retention limit - both implementations purge nothing`() =
        runTest {
            aVersionChain(count = 2).forEach { submissions.save(it) }

            submissions.purgeBeyondRetention(Fixtures.REQUIREMENT_ID, keep = 5)

            submissions.findVersions(Fixtures.REQUIREMENT_ID).map { it.version } shouldBe listOf(2, 1)
        }

    @Test
    fun `submission contract - several uploads for one hire - both implementations total their bytes`() =
        runTest {
            // The §7.1 per-employee storage cap is enforced against this number, so a total that
            // counted superseded versions twice — or missed them — would let a hire past the cap.
            aVersionChain(count = 3, sizeBytes = 1_000L).forEach { submissions.save(it) }

            submissions.totalBytesFor(Fixtures.EMPLOYEE_ID) shouldBe 3_000L
        }
}

class FakeSubmissionRepositoryContractTest : SubmissionRepositoryContract() {

    private val owners = RequirementOwners().apply {
        register(Fixtures.REQUIREMENT_ID, Fixtures.EMPLOYEE_ID)
    }

    override val submissions: SubmissionRepository = FakeSubmissionRepository(owners = owners)
}

/**
 * Portal sessions (§6.6).
 *
 * **`findActive` takes a `now` and `findActiveForLink` does not, and that asymmetry is C15's
 * answer rather than an omission.** A session records only `started_at`, `expires_at` and
 * `ended_at`, so nothing on the row says whether it has lapsed — the port has to be handed a clock
 * or "active" can only mean "not explicitly ended". C15 settled that `findActiveForLink` grows a
 * `now` parameter; **ERT-620 owns landing it**, and until then this suite pins what the port
 * actually does rather than what it should do, on the C25/C27 precedent.
 */
abstract class PortalSessionRepositoryContract {

    protected abstract val sessions: PortalSessionRepository

    @Test
    fun `session contract - a live session - both implementations resolve it at a time before expiry`() =
        runTest {
            sessions.save(aPortalSession())

            sessions.findActive(Fixtures.SESSION_ID, FixedClock.DEFAULT)?.id shouldBe Fixtures.SESSION_ID
        }

    @Test
    fun `session contract - a lapsed session - both implementations answer null`() = runTest {
        sessions.save(anExpiredSession())

        sessions.findActive(Fixtures.SESSION_ID, FixedClock.DEFAULT).shouldBeNull()
    }

    @Test
    fun `session contract - an ended session - both implementations answer null`() = runTest {
        sessions.save(anEndedSession())

        sessions.findActive(Fixtures.SESSION_ID, FixedClock.DEFAULT).shouldBeNull()
    }

    @Test
    fun `session contract - a session id nothing holds - both implementations answer null`() = runTest {
        sessions.save(aPortalSession())

        sessions.findActive(entityId("SES999999999"), FixedClock.DEFAULT).shouldBeNull()
    }

    @Test
    fun `session contract - ending a session - both implementations stop resolving it`() = runTest {
        sessions.save(aPortalSession())

        sessions.end(Fixtures.SESSION_ID, FixedClock.DEFAULT.plus(Duration.ofMinutes(1)))

        sessions.findActive(Fixtures.SESSION_ID, FixedClock.DEFAULT).shouldBeNull()
    }

    @Test
    fun `session contract - a lapsed session - both implementations still list it for the link`() =
        runTest {
            // Pins C15's live consequence rather than asserting the fix: the port hands
            // findActiveForLink no clock, so "active" there can only mean "not explicitly ended" and
            // the P1 "HR can terminate active sessions" screen will show sessions that have quietly
            // lapsed. ERT-620 decides whether the port grows a `now`; this test moves when it does.
            sessions.save(anExpiredSession())

            sessions.findActiveForLink(Fixtures.LINK_ID).map { it.id } shouldBe listOf(Fixtures.SESSION_ID)
        }

    @Test
    fun `session contract - an ended session - both implementations drop it from the link list`() =
        runTest {
            sessions.save(anEndedSession())

            sessions.findActiveForLink(Fixtures.LINK_ID).shouldBeEmpty()
        }
}

class FakePortalSessionRepositoryContractTest : PortalSessionRepositoryContract() {
    override val sessions: PortalSessionRepository = FakePortalSessionRepository()
}

/**
 * The append-only access trail (§8.12, invariant 7).
 *
 * **`countRecentFailures` counts `DENIED` and nothing else, and ERT-610's SQL adapter must match**
 * or §6.6's auto-suspend threshold fires at a different count in production than in every test that
 * asserts it. `LOCKED_OUT` and `SUSPENDED` are consequences of failures already counted, so
 * including them counts one burst twice; `EXPIRED` is not a credential attempt at all.
 */
abstract class PortalAccessTrailContract {

    protected abstract val trail: PortalAccessTrail

    @Test
    fun `trail contract - three attempts from two addresses - both implementations report two`() =
        runTest {
            trail.record(aDeniedAttempt(id = accessLogId(1), ip = "203.0.113.10"))
            trail.record(aDeniedAttempt(id = accessLogId(2), ip = "203.0.113.20"))
            trail.record(aDeniedAttempt(id = accessLogId(3), ip = "203.0.113.10"))

            trail.distinctIpsFor(Fixtures.LINK_ID).size shouldBe 2
        }

    @Test
    fun `trail contract - denied attempts since an instant - both implementations count only those`() =
        runTest {
            trail.record(aDeniedAttempt(id = accessLogId(1), timestamp = FixedClock.DEFAULT.minus(Duration.ofHours(2))))
            trail.record(aDeniedAttempt(id = accessLogId(2), timestamp = FixedClock.DEFAULT))
            trail.record(aDeniedAttempt(id = accessLogId(3), timestamp = FixedClock.DEFAULT))

            trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT.minus(Duration.ofMinutes(30))) shouldBe 2
        }

    @Test
    fun `trail contract - a locked out outcome - both implementations leave it out of the failure count`() =
        runTest {
            // The rule ERT-610 must copy. LOCKED_OUT and SUSPENDED follow from failures already
            // counted, so counting them again suspends a link at a lower real threshold than §6.6
            // states — and every test asserting the threshold would still pass.
            trail.record(aDeniedAttempt(id = accessLogId(1)))
            trail.record(aPortalAccessLog(id = accessLogId(2), outcome = PortalOutcome.LOCKED_OUT))
            trail.record(aPortalAccessLog(id = accessLogId(3), outcome = PortalOutcome.SUSPENDED))
            trail.record(aPortalAccessLog(id = accessLogId(4), outcome = PortalOutcome.EXPIRED))
            trail.record(aSuccessfulAccess(id = accessLogId(5)))

            trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT.minus(Duration.ofHours(1))) shouldBe 1
        }

    @Test
    fun `trail contract - a link with no entries - both implementations answer empty`() = runTest {
        trail.record(aDeniedAttempt(id = accessLogId(1)))

        trail.findFor(Fixtures.LINK_ID).size shouldBe 1
        trail.findFor(entityId("LNK999999999")).shouldBeEmpty()
        trail.distinctIpsFor(entityId("LNK999999999")).shouldBeEmpty()
        trail.countRecentFailures(entityId("LNK999999999"), FixedClock.DEFAULT) shouldBe 0
    }
}

class FakePortalAccessTrailContractTest : PortalAccessTrailContract() {
    override val trail: PortalAccessTrail = FakePortalAccessTrail()
}

/**
 * Document storage — HR-side only (invariant 1).
 *
 * **`signedUrlFor` on a key that was never stored throws here, and real object storage would sign a
 * URL for a key that does not exist.** That is a deliberate strictness, argued in the fake's own
 * KDoc: a signed URL for an absent object is a 404 the caller discovers later, and a test that
 * arranged one would be asserting against a state ERT-810 must never produce. The contract keeps it,
 * and ERT-710's filesystem adapter has to honour it rather than the other way round.
 */
abstract class DocumentStorageContract {

    protected abstract val storage: DocumentStorage

    @Test
    fun `storage contract - a stored object - both implementations sign a url for it`() = runTest {
        storage.put("documents/EMP00001/REQ000000001/v1", "bytes".toByteArray(), "application/pdf")

        storage.signedUrlFor("documents/EMP00001/REQ000000001/v1", Duration.ofMinutes(5))
            .isNotBlank() shouldBe true
    }

    @Test
    fun `storage contract - a key nothing holds - both implementations refuse to sign it`() = runTest {
        storage.put("documents/EMP00001/REQ000000001/v1", "bytes".toByteArray(), "application/pdf")

        assertFails { storage.signedUrlFor("documents/nobody/holds/this", Duration.ofMinutes(5)) }
    }

    @Test
    fun `storage contract - a deleted object - both implementations stop signing it`() = runTest {
        storage.put("documents/EMP00001/REQ000000001/v1", "bytes".toByteArray(), "application/pdf")

        storage.delete("documents/EMP00001/REQ000000001/v1")

        assertFails { storage.signedUrlFor("documents/EMP00001/REQ000000001/v1", Duration.ofMinutes(5)) }
    }

    @Test
    fun `storage contract - a freshly stored object - both implementations report it clean`() = runTest {
        // `isClean` is stubbed open behind Q22 and ERT-1150 delivers the real scan. Pinning the stub
        // is what makes the day it stops being a stub visible in a diff.
        storage.put("documents/EMP00001/REQ000000001/v1", "bytes".toByteArray(), "application/pdf")

        storage.isClean("documents/EMP00001/REQ000000001/v1") shouldBe true
    }

    @Test
    fun `storage contract - two objects - both implementations keep their keys separate`() = runTest {
        storage.put("documents/a/v1", "one".toByteArray(), "application/pdf")
        storage.put("documents/b/v1", "two".toByteArray(), "image/jpeg")

        storage.delete("documents/a/v1")

        assertFails { storage.signedUrlFor("documents/a/v1", Duration.ofMinutes(5)) }
        storage.signedUrlFor("documents/b/v1", Duration.ofMinutes(5)).isNotBlank() shouldBe true
    }
}

class FakeDocumentStorageContractTest : DocumentStorageContract() {
    override val storage: DocumentStorage = FakeDocumentStorage()
}
