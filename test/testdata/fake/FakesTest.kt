package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aCompletedPacket
import com.pgsystem.employee.requirement.tracker.testdata.aDepartment
import com.pgsystem.employee.requirement.tracker.testdata.aPortalSession
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementSet
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementTemplate
import com.pgsystem.employee.requirement.tracker.testdata.aRevokedLink
import com.pgsystem.employee.requirement.tracker.testdata.anActiveLink
import com.pgsystem.employee.requirement.tracker.testdata.anAuditEntry
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anEmployeeRequirement
import com.pgsystem.employee.requirement.tracker.testdata.anEmploymentType
import com.pgsystem.employee.requirement.tracker.testdata.anEndedSession
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.personId
import com.pgsystem.employee.requirement.tracker.testdata.requirementId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.ok

/**
 * The remaining eight fakes, one or two assertions each, on the semantics that could drift from
 * their real adapters.
 *
 * Not exhaustive coverage of every method — a fake with a wrong `findById` fails loudly the first
 * time a use case uses it. These are the behaviours where a wrong fake would let a use case pass.
 */
class FakesTest {

    // ── FakeEmployeeRepository ──────────────────────────────────────────────────────────────────

    @Test
    fun `fake employee repository - an email shared with a completed hire - is not an active duplicate`() = runTest {
        // PRD 8.1 scopes the duplicate check to active hires. A completed hire sharing an address is
        // not a collision worth warning about, and warning anyway trains HR to click through.
        val shared = anEmail("shared@example.com")
        val repository = FakeEmployeeRepository(
            anEmployee(id = personId("EMP00001"), email = shared),
            aCompletedPacket(id = personId("EMP00002")).copy(email = shared),
        )

        repository.findActiveByEmail(shared) shouldHaveSize 1
    }

    @Test
    fun `fake employee repository - an email shared with an on hold hire - is an active duplicate`() = runTest {
        // ON_HOLD is not terminal. A deferred start date does not release the address.
        val shared = anEmail("shared@example.com")
        val repository = FakeEmployeeRepository(
            anEmployee(id = personId("EMP00001"), email = shared),
            anEmployee(id = personId("EMP00002"), email = shared, packetStatus = PacketStatus.ON_HOLD),
        )

        repository.findActiveByEmail(shared) shouldHaveSize 2
    }

    @Test
    fun `fake employee repository - a cancelled hire - is not an active duplicate`() = runTest {
        val shared = anEmail("shared@example.com")
        val repository = FakeEmployeeRepository(
            anEmployee(id = personId("EMP00002"), email = shared, packetStatus = PacketStatus.CANCELLED),
        )

        repository.findActiveByEmail(shared).shouldBeEmpty()
    }

    @Test
    fun `fake employee repository - seeded but never saved - records no save`() = runTest {
        // Seeding and saving stay separate so "the use case never persisted the hire" is assertable.
        val repository = FakeEmployeeRepository(anEmployee())

        repository.saved.shouldBeEmpty()
        repository.findById(Fixtures.EMPLOYEE_ID).shouldNotBeNull()
    }

    @Test
    fun `fake employee repository - created then saved twice - records each call separately`() = runTest {
        val repository = FakeEmployeeRepository()

        repository.create(anEmployee())
        repository.save(anEmployee(packetStatus = PacketStatus.UNDER_REVIEW))
        repository.save(anEmployee(packetStatus = PacketStatus.COMPLETE))

        repository.created shouldHaveSize 1
        repository.saved shouldHaveSize 2
        repository.all shouldHaveSize 1
    }

    @Test
    fun `fake employee repository - saving a hire that was never created - fails loudly`() = runTest {
        // The port splits create from save so that a new hire cannot overwrite an existing one by
        // drawing its id. A fake that accepted a creation through save() would let a use case call
        // the wrong method and still pass, which is the one thing the split exists to prevent.
        val repository = FakeEmployeeRepository()

        assertFailsWith<IllegalStateException> { repository.save(anEmployee()) }
    }

    @Test
    fun `fake employee repository - creating onto a taken id - draws a fresh one`() = runTest {
        // The in-memory half of the ERT-410 retry. Asserted here as well as against real SQL,
        // because a use-case test that expected a redraw would otherwise be proving nothing.
        val repository = FakeEmployeeRepository(
            anEmployee(id = personId("EMP00001")),
            ids = FixedPersonIdGenerator("EMP00007"),
        )

        val stored = repository.create(
            anEmployee(id = personId("EMP00001"), email = anEmail("second@example.com"))
        )

        stored.id shouldBe personId("EMP00007")
        repository.all shouldHaveSize 2
    }

    @Test
    fun `fake employee repository - the requirement set saved once - records one batch not one per requirement`() = runTest {
        // PRD 5 snapshots the set at creation. "Saved the set once" and "saved five requirements"
        // are different claims, and only the first says the snapshot was written as a unit.
        val repository = FakeEmployeeRepository()

        repository.saveRequirements(aRequirementSet(required = 5).requirements)

        repository.savedRequirementBatches shouldHaveSize 1
        repository.savedRequirementBatches.single() shouldHaveSize 5
    }

    @Test
    fun `fake employee repository - requirements out of sort order - come back in the adapter's order`() =
        runTest {
            // ERT-432 gave `ExposedEmployeeRepository.requirementsOf` a sort-order-first ORDER BY.
            // A fake still returning map order would let a use case that wrote the wrong
            // `sortOrderSnapshot` -- or none at all -- pass every use-case test in the suite, and
            // the defect would first appear as a shuffled checklist on a hire's phone.
            //
            // Sort order says Birth, NBI, Medical; the names say Birth, Medical, NBI; the ids say
            // Medical, Birth, NBI; insertion order says NBI, Medical, Birth. Every accident names a
            // different sequence than the rule does.
            val repository = FakeEmployeeRepository().givenRequirements(
                anEmployeeRequirement(
                    id = requirementId(2), nameSnapshot = "NBI clearance", sortOrderSnapshot = 2,
                ),
                anEmployeeRequirement(
                    id = requirementId(0), nameSnapshot = "Medical certificate", sortOrderSnapshot = 3,
                ),
                anEmployeeRequirement(
                    id = requirementId(1), nameSnapshot = "Birth certificate", sortOrderSnapshot = 1,
                ),
            )

            repository.requirementsOf(Fixtures.EMPLOYEE_ID).requirements.map { it.nameSnapshot } shouldBe
                listOf("Birth certificate", "NBI clearance", "Medical certificate")
        }

    @Test
    fun `fake employee repository - requirements at an equal sort order - fall back to name order`() =
        runTest {
            // The tiebreak the adapter also carries: `sort_order` has a column default, so ties are
            // reachable, and an unbroken tie is left to whatever order the collection happens to
            // hold -- which is not an order at all.
            val repository = FakeEmployeeRepository().givenRequirements(
                anEmployeeRequirement(
                    id = requirementId(0), nameSnapshot = "Medical certificate", sortOrderSnapshot = 5,
                ),
                anEmployeeRequirement(
                    id = requirementId(1), nameSnapshot = "Birth certificate", sortOrderSnapshot = 5,
                ),
                anEmployeeRequirement(
                    id = requirementId(2), nameSnapshot = "NBI clearance", sortOrderSnapshot = 5,
                ),
            )

            repository.requirementsOf(Fixtures.EMPLOYEE_ID).requirements.map { it.nameSnapshot } shouldBe
                listOf("Birth certificate", "Medical certificate", "NBI clearance")
        }

    @Test
    fun `fake employee repository - requirementsOf - returns only that employee's requirements`() = runTest {
        val other = personId("EMP00002")
        val repository = FakeEmployeeRepository()
            .givenRequirements(aRequirementSet(required = 3))
            .givenRequirements(aRequirementSet(required = 2, employeeId = other, firstId = 3))

        repository.requirementsOf(Fixtures.EMPLOYEE_ID).total shouldBe 3
        repository.requirementsOf(other).total shouldBe 2
    }

    // ── FakeUploadLinkRepository ────────────────────────────────────────────────────────────────

    @Test
    fun `fake upload link repository - lookup by token hash - resolves the link`() = runTest {
        val repository = FakeUploadLinkRepository(anActiveLink(tokenHash = "digest-of-the-token"))

        repository.findByTokenHash("digest-of-the-token").shouldNotBeNull()
        repository.findByTokenHash("some-other-digest").shouldBeNull()
    }

    @Test
    fun `fake upload link repository - a second link with the same token hash - fails loudly`() = runTest {
        // Mirrors upload_links.token_hash uniqueIndex. Without it a test could arrange a state the
        // database refuses, and a use case relying on uniqueness would fail on first insert.
        val repository = FakeUploadLinkRepository(anActiveLink(tokenHash = "shared-digest"))

        assertFailsWith<IllegalStateException> {
            repository.save(anActiveLink(id = entityId("LNK000000002"), tokenHash = "shared-digest"))
        }
    }

    @Test
    fun `fake upload link repository - two seeded links sharing a token hash - fails loudly`() = runTest {
        // save() rejects the clash, but a test that arranges the clash through the constructor would
        // have slipped past it into a state the database refuses.
        assertFailsWith<IllegalStateException> {
            FakeUploadLinkRepository(
                anActiveLink(id = entityId("LNK000000001"), tokenHash = "shared-digest"),
                anActiveLink(id = entityId("LNK000000002"), tokenHash = "shared-digest"),
            )
        }
    }

    @Test
    fun `fake upload link repository - resaving the same link - is not a uniqueness clash`() = runTest {
        val link = anActiveLink(tokenHash = "digest")
        val repository = FakeUploadLinkRepository(link)

        repository.save(link.copy(failedPinCount = 1))

        repository.findByTokenHash("digest")!!.failedPinCount shouldBe 1
    }

    @Test
    fun `fake upload link repository - findActiveForEmployee - ignores a revoked link`() = runTest {
        val repository = FakeUploadLinkRepository(aRevokedLink())

        repository.findActiveForEmployee(Fixtures.EMPLOYEE_ID).shouldBeNull()
    }

    @Test
    fun `fake upload link repository - two active links - returns the most recently issued`() = runTest {
        val older = anActiveLink(id = entityId("LNK000000001"), tokenHash = "older")
        val newer = older.copy(
            id = entityId("LNK000000002"),
            tokenHash = "newer",
            issuedAt = FixedClock.DEFAULT.plus(Duration.ofDays(1)),
        )
        val repository = FakeUploadLinkRepository(older, newer)

        repository.findActiveForEmployee(Fixtures.EMPLOYEE_ID)!!.tokenHash shouldBe "newer"
    }

    // ── FakeRequirementTemplateRepository ───────────────────────────────────────────────────────

    @Test
    fun `fake template repository - active templates for an employment type - come back in sort order`() = runTest {
        // The order a hire sees their checklist in comes from here. An unordered fake would let a
        // use case that forgot to sort pass, and the defect surfaces as a shuffled checklist.
        val second = aRequirementTemplate(id = entityId("TPL000000002"), name = "Birth Certificate", sortOrder = 2)
        val first = aRequirementTemplate(id = entityId("TPL000000001"), name = "NBI Clearance", sortOrder = 1)
        val repository = FakeRequirementTemplateRepository().givenAssigned(Fixtures.EMPLOYMENT_TYPE_ID, second, first)

        repository.findActiveForEmploymentType(Fixtures.EMPLOYMENT_TYPE_ID).map { it.name } shouldBe
            listOf("NBI Clearance", "Birth Certificate")
    }

    @Test
    fun `fake template repository - an inactive template - is excluded from the employment type set`() = runTest {
        val retired = aRequirementTemplate(id = entityId("TPL000000002"), name = "Retired Form", isActive = false)
        val repository = FakeRequirementTemplateRepository()
            .givenAssigned(Fixtures.EMPLOYMENT_TYPE_ID, aRequirementTemplate(), retired)

        repository.findActiveForEmploymentType(Fixtures.EMPLOYMENT_TYPE_ID) shouldHaveSize 1
        repository.findAll(includeInactive = true) shouldHaveSize 2
        repository.findAll() shouldHaveSize 1
    }

    @Test
    fun `fake template repository - a template assigned to another employment type - is not returned`() = runTest {
        val repository = FakeRequirementTemplateRepository()
            .givenAssigned(entityId("EMT000000002"), aRequirementTemplate())

        repository.findActiveForEmploymentType(Fixtures.EMPLOYMENT_TYPE_ID).shouldBeEmpty()
    }

    @Test
    fun `fake template repository - the catalogue is read - the read is recorded for the snapshot rule`() = runTest {
        // PRD 5: read once at creation and copied onto the employee. Nothing downstream may consult
        // it again for an in-flight hire, and counting reads is how that becomes assertable.
        val repository = FakeRequirementTemplateRepository().givenAssigned(Fixtures.EMPLOYMENT_TYPE_ID, aRequirementTemplate())

        repository.findActiveForEmploymentType(Fixtures.EMPLOYMENT_TYPE_ID)

        repository.employmentTypeReads shouldBe listOf(Fixtures.EMPLOYMENT_TYPE_ID)
    }

    // ── FakeReferenceDataRepository ─────────────────────────────────────────────────────────────

    @Test
    fun `fake reference data - a department id - does not exist as an employment type`() = runTest {
        // The whole reason the port carries two existence checks rather than one: both ids are
        // 12-character EntityIds and structurally indistinguishable, so a fake holding one combined
        // collection would answer `true` for a department id handed to the employment-type check --
        // PRD 8.2's defect, reached through the validator meant to prevent it. Asserted here so the
        // use-case test that proves the two checks are separate cannot be vacuous.
        val repository = FakeReferenceDataRepository(
            departments = listOf(aDepartment(id = entityId("DPT000000001"))),
            employmentTypes = listOf(anEmploymentType(id = entityId("EMT000000001"))),
        )

        repository.departmentExists(entityId("DPT000000001")) shouldBe true
        repository.employmentTypeExists(entityId("DPT000000001")) shouldBe false
        repository.departmentExists(entityId("EMT000000001")) shouldBe false
    }

    @Test
    fun `fake reference data - departments seeded out of order - come back in name order`() = runTest {
        // Three, in an order that is neither sorted nor its reverse. Two would leave too few
        // arrangements for a coincidence to be unlikely -- ERT-410 and ERT-420 each shipped an
        // ordering test that passed against no ordering at all for exactly that reason.
        val repository = FakeReferenceDataRepository().givenDepartments(
            aDepartment(id = entityId("DPT000000001"), name = "Logistics"),
            aDepartment(id = entityId("DPT000000002"), name = "Finance"),
            aDepartment(id = entityId("DPT000000003"), name = "Store Operations"),
        )

        repository.findDepartments().map { it.name } shouldBe
            listOf("Finance", "Logistics", "Store Operations")
    }

    @Test
    fun `fake reference data - an empty catalogue - reports that nothing exists`() = runTest {
        // An empty list is data, not a failure, as the port says -- and a seeded id must still be
        // refused, so a test seeding nothing cannot pass by accident.
        val repository = FakeReferenceDataRepository()

        repository.findDepartments().shouldBeEmpty()
        repository.findEmploymentTypes().shouldBeEmpty()
        repository.departmentExists(Fixtures.DEPARTMENT_ID) shouldBe false
    }

    // ── FakePortalSessionRepository ─────────────────────────────────────────────────────────────

    @Test
    fun `fake portal session repository - a session expiring exactly now - is not active`() = runTest {
        // The window has closed by the instant it names. Decided here so ERT-620's adapter has a
        // number to match rather than a preference to guess.
        val expiry = FixedClock.DEFAULT.plus(Duration.ofMinutes(45))
        val repository = FakePortalSessionRepository(aPortalSession(expiresAt = expiry))

        repository.findActive(Fixtures.SESSION_ID, now = expiry).shouldBeNull()
        repository.findActive(Fixtures.SESSION_ID, now = expiry.minusSeconds(1)).shouldNotBeNull()
    }

    @Test
    fun `fake portal session repository - an ended session - is not active even before its expiry`() = runTest {
        val repository = FakePortalSessionRepository(anEndedSession())

        repository.findActive(Fixtures.SESSION_ID, now = FixedClock.DEFAULT).shouldBeNull()
    }

    @Test
    fun `fake portal session repository - ending a session - records it and closes the session`() = runTest {
        val repository = FakePortalSessionRepository(aPortalSession())
        val endedAt = FixedClock.DEFAULT.plus(Duration.ofMinutes(10))

        repository.end(Fixtures.SESSION_ID, endedAt)

        repository.ended.single().endedAt shouldBe endedAt
        repository.findActive(Fixtures.SESSION_ID, now = FixedClock.DEFAULT).shouldBeNull()
    }

    @Test
    fun `fake portal session repository - ending a session that was never saved - fails loudly`() = runTest {
        assertFailsWith<IllegalStateException> {
            FakePortalSessionRepository().end(Fixtures.SESSION_ID, FixedClock.DEFAULT)
        }
    }

    @Test
    fun `fake portal session repository - findActiveForLink - excludes ended sessions only`() = runTest {
        // Documented limitation: the port hands this method no clock, so a lapsed session still
        // appears. See the fake's KDoc -- ERT-620 should decide whether the port grows a `now`.
        val repository = FakePortalSessionRepository(
            aPortalSession(),
            anEndedSession(id = entityId("SES000000002")),
        )

        repository.findActiveForLink(Fixtures.LINK_ID) shouldHaveSize 1
    }

    // ── FakeAppSettingsRepository ───────────────────────────────────────────────────────────────

    @Test
    fun `fake app settings repository - a policy never read - reports zero reads`() = runTest {
        // The quietest risk on the roadmap: if expiresAt comes from LinkPolicy's Kotlin defaults
        // rather than from this port, the numbers are identical and nothing else detects it.
        val repository = FakeAppSettingsRepository()

        repository.reads shouldBe 0
        repository.linkPolicy()
        repository.reads shouldBe 1
    }

    @Test
    fun `fake app settings repository - an update - records the actor and changes what is read`() = runTest {
        val repository = FakeAppSettingsRepository()

        repository.updateLinkPolicy(LinkPolicy(absoluteExpiryDays = 30), actor = Fixtures.HR_USER_ID)

        repository.updates.single().actor shouldBe Fixtures.HR_USER_ID
        repository.linkPolicy().ok().absoluteExpiryDays shouldBe 30
    }

    @Test
    fun `fake app settings repository - reading current for an assertion - does not count as a read`() = runTest {
        // Otherwise asserting on the policy would itself satisfy "the use case read the policy",
        // which is the one thing this fake's read count exists to detect.
        val repository = FakeAppSettingsRepository()

        repository.current.absoluteExpiryDays shouldBe 90

        repository.reads shouldBe 0
    }

    @Test
    fun `fake app settings repository - a policy outside the section 6 4 bounds - is accepted here`() = runTest {
        // Deliberate. Bounds enforcement is ERT-310's adapter; a fake that also enforced them would
        // accept a bounds bug in the adapter without complaint.
        val repository = FakeAppSettingsRepository()

        repository.updateLinkPolicy(LinkPolicy(absoluteExpiryDays = 3650), actor = Fixtures.HR_USER_ID)

        repository.linkPolicy().ok().absoluteExpiryDays shouldBe 3650
        (3650 in LinkPolicy.ABSOLUTE_EXPIRY_DAYS_RANGE) shouldBe false
    }

    @Test
    fun `fake app settings repository - a refusal - returns the error rather than throwing`() = runTest {
        // ERT-310 put DomainResult in this port's signature, so an unreadable settings row is a
        // value a use case must handle rather than an exception it can ignore.
        val repository = FakeAppSettingsRepository()
            .refuses(AppError.Validation("setting.missing", "link.absolute_expiry_days", "No row"))

        repository.linkPolicy().errCode() shouldBe "setting.missing"
        repository.updateLinkPolicy(LinkPolicy(), actor = Fixtures.HR_USER_ID).errCode() shouldBe "setting.missing"

        // A refusal is still a read: otherwise a use case that never asked would pass this path.
        repository.reads shouldBe 1
        repository.updates.shouldBeEmpty()
    }

    // ── FakeAuditLog ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fake audit log - entries for another entity - are not returned`() = runTest {
        val log = FakeAuditLog()

        log.record(anAuditEntry(entityId = Fixtures.EMPLOYEE_ID))
        log.record(anAuditEntry(id = entityId("AUD000000002"), entityId = Fixtures.LINK_ID, entity = "upload_link"))

        log.findFor(Fixtures.EMPLOYEE_ID) shouldHaveSize 1
        log.findFor(Fixtures.LINK_ID) shouldHaveSize 1
    }

    @Test
    fun `fake audit log - a person id and an entity id - never match each other`() = runTest {
        // audit_logs.entity_id is polymorphic and carries no foreign key, so the only thing keeping
        // an employee row apart from a link row is the identifier type.
        val log = FakeAuditLog()

        log.record(anAuditEntry(entityId = Fixtures.EMPLOYEE_ID))

        log.findFor(Fixtures.LINK_ID).shouldBeEmpty()
    }

    @Test
    fun `fake audit log - a recorded action - is findable by action`() = runTest {
        val log = FakeAuditLog()

        log.record(anAuditEntry(action = AuditAction.DUPLICATE_EMAIL_OVERRIDDEN))

        log.recorded(AuditAction.DUPLICATE_EMAIL_OVERRIDDEN) shouldBe true
        log.recorded(AuditAction.EMAIL_CHANGED) shouldBe false
        log.entriesFor(AuditAction.DUPLICATE_EMAIL_OVERRIDDEN) shouldHaveSize 1
    }

    @Test
    fun `fake audit log - the exposed entries list - is a copy the caller cannot append through`() = runTest {
        val log = FakeAuditLog()
        log.record(anAuditEntry())

        val snapshot = log.entries
        log.record(anAuditEntry(id = entityId("AUD000000002")))

        snapshot shouldHaveSize 1
        log.entries shouldHaveSize 2
    }

    // ── FakeDocumentStorage ─────────────────────────────────────────────────────────────────────

    @Test
    fun `fake document storage - a signed url is requested - the request is recorded`() = runTest {
        // Invariant 1 inverted: a portal use-case test asserts this list is empty; an HR one asserts
        // what it contains.
        val storage = FakeDocumentStorage().given("documents/EMP00001/v1")

        storage.signedUrlFor("documents/EMP00001/v1", Duration.ofMinutes(5))

        storage.signedUrlRequests.single().key shouldBe "documents/EMP00001/v1"
        storage.signedUrlRequests.single().validFor shouldBe Duration.ofMinutes(5)
    }

    @Test
    fun `fake document storage - a put with no signing - records no signed url request`() = runTest {
        val storage = FakeDocumentStorage()

        storage.put("documents/EMP00001/v1", "bytes".toByteArray(), "application/pdf")

        storage.signedUrlRequests.shouldBeEmpty()
        storage.holds("documents/EMP00001/v1") shouldBe true
    }

    @Test
    fun `fake document storage - a stored object - keeps its bytes and mime type`() = runTest {
        val storage = FakeDocumentStorage()

        storage.put("documents/EMP00001/v1", "scanned pdf".toByteArray(), "application/pdf")

        storage.stored.getValue("documents/EMP00001/v1").mimeType shouldBe "application/pdf"
        String(storage.stored.getValue("documents/EMP00001/v1").bytes) shouldBe "scanned pdf"
    }

    @Test
    fun `fake document storage - a put array mutated afterwards - does not change what was stored`() = runTest {
        // put() copies. A caller reusing one buffer across uploads would otherwise rewrite history.
        val buffer = "first".toByteArray()
        val storage = FakeDocumentStorage()
        storage.put("k", buffer, "text/plain")

        buffer[0] = 'X'.code.toByte()

        String(storage.stored.getValue("k").bytes) shouldBe "first"
    }

    @Test
    fun `fake document storage - a key that was never put - refuses to sign`() = runTest {
        // Signing a key nothing stored is a use case building the wrong key, and it should fail
        // where it happens rather than as a 404 much later.
        assertFailsWith<IllegalStateException> {
            FakeDocumentStorage().signedUrlFor("documents/does-not-exist", Duration.ofMinutes(5))
        }
    }

    @Test
    fun `fake document storage - a key marked infected - does not pass the scan gate`() = runTest {
        // PRD 12 makes scanning P0 and no scanner is chosen (roadmap E5). The gate is wired and
        // stubbed; this is the refusal path ERT-810 needs.
        val storage = FakeDocumentStorage().given("clean-key").given("bad-key").markInfected("bad-key")

        storage.isClean("clean-key") shouldBe true
        storage.isClean("bad-key") shouldBe false
    }

    @Test
    fun `fake document storage - a deleted key - is gone and the deletion is recorded`() = runTest {
        val storage = FakeDocumentStorage().given("documents/EMP00001/v1")

        storage.delete("documents/EMP00001/v1")

        storage.holds("documents/EMP00001/v1") shouldBe false
        storage.deleted shouldBe listOf("documents/EMP00001/v1")
    }

    // ── FakeFailure, and construction ───────────────────────────────────────────────────────────

    @Test
    fun `fake failure - configured to fail the next call - throws once and then behaves`() = runTest {
        val repository = FakeEmployeeRepository(anEmployee())
        repository.failure.failNextCall()

        assertFailsWith<IllegalStateException> { repository.findById(Fixtures.EMPLOYEE_ID) }
        repository.findById(Fixtures.EMPLOYEE_ID).shouldNotBeNull()
    }

    @Test
    fun `fake failure - configured to fail every call - keeps throwing until it is stopped`() = runTest {
        val repository = FakeEmployeeRepository(anEmployee())
        repository.failure.failEveryCall()

        assertFailsWith<IllegalStateException> { repository.findById(Fixtures.EMPLOYEE_ID) }
        assertFailsWith<IllegalStateException> { repository.findById(Fixtures.EMPLOYEE_ID) }

        repository.failure.stopFailing()
        repository.findById(Fixtures.EMPLOYEE_ID).shouldNotBeNull()
    }

    @Test
    fun `fake failure - a custom throwable - is the one that surfaces`() = runTest {
        val repository = FakeEmployeeRepository()
        repository.failure.failNextCall(with = IllegalArgumentException("connection reset"))

        assertFailsWith<IllegalArgumentException> { repository.findById(Fixtures.EMPLOYEE_ID) }
    }

    @Test
    fun `every fake - is constructible without a database a container or a dispatcher`() = runTest {
        // The acceptance criterion, stated as a test: a use case test needs no Koin, no H2, no
        // Docker and no dispatcher. If this ever stops compiling, the harness has grown a
        // dependency it was built to avoid.
        val owners = RequirementOwners()

        FakeEmployeeRepository(owners = owners)
        FakeRequirementTemplateRepository()
        FakeReferenceDataRepository()
        FakeUploadLinkRepository()
        FakeSubmissionRepository(owners = owners)
        FakePortalSessionRepository()
        FakeAppSettingsRepository()
        FakeAuditLog()
        FakePortalAccessTrail()
        FakeNotifier()
        FakeDocumentStorage()
    }
}
