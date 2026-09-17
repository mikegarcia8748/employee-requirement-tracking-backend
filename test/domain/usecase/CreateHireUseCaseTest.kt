package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementStatus
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aDepartment
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anEmploymentType
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementTemplate
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.err
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.errField
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeEmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeUploadLinkRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeNotifier
import com.pgsystem.employee.requirement.tracker.testdata.FixedTokenGenerator
import com.pgsystem.employee.requirement.tracker.data.crypto.HmacTokenDigest
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.UploadLink
import com.pgsystem.employee.requirement.tracker.testdata.ok
import com.pgsystem.employee.requirement.tracker.testdata.personId
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Hire creation, first sub-task (ERT-431, PRD §8.1).
 *
 * Covers the rules that decide whether a hire may be created at all — the email is well formed, both
 * reference ids exist, and a duplicate against an **active** hire carries a typed justification —
 * and, since ERT-432, the requirement set copied onto the hire from the catalogue. The link and its
 * expiry are ERT-433 and the invitation ERT-434, so a hire created here still has no link.
 *
 * ### Two id traps this file is arranged around
 *
 * `FixedPersonIdGenerator()` unscripted yields `EMP00001` first, and that is **also**
 * `Fixtures.EMPLOYEE_ID`, the default id of `anEmployee()`. A test that seeds a hire and then
 * creates one would therefore exercise the id-collision redraw path without saying so, and
 * `created.employee.id` would come back as a value the test never mentions. Every seeded hire here
 * carries an explicit id, and the generator is scripted with `NEWHIRE1`.
 *
 * The generator is also **shared** between the use case and `FakeEmployeeRepository`, which is
 * production-faithful: `ExposedEmployeeRepository` and the use case both resolve the same
 * `PersonIdGenerator` single from `CoreModule`.
 *
 * `anEmail()` has the same shape of trap — it defaults to `jose.delacruz@example.com`, which
 * `anEmployee()` also uses, so a test that seeds a hire and leaves the new address defaulted is
 * secretly a duplicate test. Every address here is stated.
 */
class CreateHireUseCaseTest {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val personIds = FixedPersonIdGenerator("NEWHIRE1")
    private val audit = FakeAuditLog()
    private val employees = FakeEmployeeRepository(ids = personIds)
    private val uploadLinks = FakeUploadLinkRepository()
    private val tokenGenerator = FixedTokenGenerator()
    private val tokenDigest = HmacTokenDigest("this-is-a-very-long-and-secure-test-pepper-32-chars")
    private val appSettings = FakeAppSettingsRepository()
    private val notifier = FakeNotifier()
    private val reference = FakeReferenceDataRepository(
        departments = listOf(aDepartment(id = Fixtures.DEPARTMENT_ID)),
        employmentTypes = listOf(anEmploymentType(id = Fixtures.EMPLOYMENT_TYPE_ID)),
    )

    /**
     * The catalogue a hire is snapshotted from (ERT-432).
     *
     * **Three templates, arranged so that no accident produces the right answer.** Sort order says
     * Birth, NBI, Medical; the names say Birth, Medical, NBI; the ids say Medical, Birth, NBI; and
     * they are seeded in that same id order. So a use case that ignored `sortOrder`, or reversed it,
     * or leaned on the order the fake happened to hold them in, names a different sequence than the
     * rule does — which is the arrangement ERT-320, ERT-350, ERT-410 and ERT-420 each shipped
     * *without*, four times in a row.
     *
     * Three rather than two, for the reason ERT-410's flag test needed three: two leave too few
     * arrangements for a coincidence to be unlikely.
     */
    private val templates = FakeRequirementTemplateRepository().givenAssigned(
        Fixtures.EMPLOYMENT_TYPE_ID,
        aRequirementTemplate(id = MEDICAL, name = "Medical certificate", sortOrder = 3),
        aRequirementTemplate(id = BIRTH, name = "Birth certificate", sortOrder = 1),
        aRequirementTemplate(id = NBI, name = "NBI clearance", sortOrder = 2),
    )

    private val useCase = useCaseWith()

    // ── The email ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hire creation - an invalid email format - fails with a field-level validation error`() = runTest {
        // EmailAddress.of already validates format and normalises case; the use case's job is to
        // surface that failure rather than re-implement the check.
        val result = useCase(createHire(email = "not-an-address"))

        result.errCode() shouldBe "email.invalid_format"
        result.errField() shouldBe "email"
        nothingHappened()
    }

    // ── The reference ids (E8: 422 naming which one, never 404) ─────────────────────────────────

    @Test
    fun `hire creation - a department id that does not exist - fails with a validation error naming which id`() =
        runTest {
            // Well formed and absent, not malformed: a malformed id would fail before the existence
            // check runs, and the test would prove nothing about the check it is named for.
            val result = useCase(createHire(departmentId = entityId("DPT000000999").value))

            result.errCode() shouldBe "department_unknown"
            result.errField() shouldBe "departmentId"
            nothingHappened()
        }

    @Test
    fun `hire creation - an employment type id that does not exist - fails with a validation error naming which id`() =
        runTest {
            val result = useCase(createHire(employmentTypeId = entityId("EMT000000999").value))

            result.errCode() shouldBe "employment_type_unknown"
            result.errField() shouldBe "employmentTypeId"
            nothingHappened()
        }

    @Test
    fun `hire creation - a department id supplied as the employment type - is rejected rather than accepted`() =
        runTest {
            // The reason ReferenceDataRepository carries two existence checks rather than one. Both
            // ids are 12-character EntityIds, so a single combined lookup would answer `true` here
            // and let a hire be created against a department in the employment-type slot — PRD §8.2's
            // defect reached through the validator meant to prevent it.
            val result = useCase(createHire(employmentTypeId = Fixtures.DEPARTMENT_ID.value))

            result.errCode() shouldBe "employment_type_unknown"
            result.errField() shouldBe "employmentTypeId"
            nothingHappened()
        }

    // ── The duplicate rule (SEC-11, C1: ReasonRequired is a 422, not a Conflict) ────────────────

    @Test
    fun `hire creation - email duplicates an active hire with no reason given - fails with ReasonRequired naming the reason field`() =
        runTest {
            employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

            val error = useCase(createHire(email = SHARED_EMAIL)).err()

            error.shouldBeInstanceOf<AppError.ReasonRequired>()
            error.code shouldBe "duplicate_email.reason_required"
            // `action` never reaches the wire, but it is what lets a client render the right field.
            // ErrorMappingTest pins these two strings against a hand-built error; this is the half
            // that connects them to the use case that actually emits them.
            error.action shouldBe "create_hire"
            nothingHappened()
        }

    @Test
    fun `hire creation - email duplicates an active hire with a typed reason - creates the hire and records the reason`() =
        runTest {
            employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

            val created = useCase(createHire(email = SHARED_EMAIL, duplicateReason = REASON)).ok()

            created.employee.email shouldBe anEmail(SHARED_EMAIL)
            employees.created.map { it.id } shouldBe listOf(personId("NEWHIRE1"))

            // The typed reason is the artefact §8.1 asks for — a boolean `force` flag would have
            // satisfied the flow and lost the only thing §8.13's exception report can read.
            val override = audit.entriesFor(AuditAction.DUPLICATE_EMAIL_OVERRIDDEN).single()
            override.metadata["reason"] shouldBe REASON
            override.metadata["duplicateOf"] shouldBe "EXISTING"
        }

    @Test
    fun `hire creation - a duplicate with a reason - flags the record as shared email`() = runTest {
        employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

        val created = useCase(createHire(email = SHARED_EMAIL, duplicateReason = REASON)).ok()

        // Exact set, not `shouldContain`: the latter passes against a use case that sets every flag.
        //
        // Nothing is asserted about `retentionFrozen`, and that is deliberate. `Employee` derives it
        // from `anomalyFlags.isNotEmpty()`, so this hire's retention *is* frozen — while the API
        // contract says SHARED_EMAIL must not freeze it (E3). `AnomalyFlag.freezesRetention` is named
        // by four documents and exists in none of the code; ERT-734 owns landing it. Asserting `true`
        // here would spell the defect as a requirement and turn ERT-734 into a red build for an
        // unrelated reason; asserting `false` would be red today.
        created.employee.anomalyFlags shouldBe setOf(AnomalyFlag.SHARED_EMAIL)
    }

    @Test
    fun `hire creation - email duplicates a cancelled hire - needs no reason`() = runTest {
        // A hire that fell through does not hold the address. The scoping itself lives in
        // findActiveByEmail rather than here — what this pins is that the use case asks *that*
        // question rather than re-deriving activeness for itself.
        employees.given(
            anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL), packetStatus = PacketStatus.CANCELLED)
        )

        val created = useCase(createHire(email = SHARED_EMAIL)).ok()

        created.employee.anomalyFlags.shouldBeEmpty()
        audit.entriesFor(AuditAction.DUPLICATE_EMAIL_OVERRIDDEN).shouldBeEmpty()
    }

    @Test
    fun `hire creation - a whitespace-only reason - is treated as no reason given`() = runTest {
        // Against a real duplicate, or the test passes for the wrong reason: with no duplicate
        // seeded, any implementation returns Ok whatever it does with the reason.
        employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

        val error = useCase(createHire(email = SHARED_EMAIL, duplicateReason = "   ")).err()

        error.shouldBeInstanceOf<AppError.ReasonRequired>()
        nothingHappened()
    }

    // ── The happy path, which the ticket's own nine tests never exercise ───────────────────────

    @Test
    fun `hire creation - a valid command - stores the hire through create and audits it`() = runTest {
        val created = useCase(createHire(email = "maria.santos@example.com")).ok()

        created.employee.id shouldBe personId("NEWHIRE1")
        created.employee.email shouldBe anEmail("maria.santos@example.com")
        created.employee.departmentId shouldBe Fixtures.DEPARTMENT_ID
        created.employee.employmentTypeId shouldBe Fixtures.EMPLOYMENT_TYPE_ID
        created.employee.createdBy shouldBe Fixtures.HR_USER_ID
        created.employee.createdAt shouldBe FixedClock.DEFAULT

        // Through create, never save: save reads an existing id as "update this row", so a colliding
        // draw would overwrite an unrelated hire instead of redrawing.
        employees.created shouldBe listOf(created.employee)
        employees.saved.shouldBeEmpty()

        val entry = audit.entriesFor(AuditAction.HIRE_CREATED).single()
        entry.actorUserId shouldBe Fixtures.HR_USER_ID
        entry.entityId shouldBe created.employee.id
        entry.entity shouldBe "employee"
        entry.timestamp shouldBe FixedClock.DEFAULT
    }

    @Test
    fun `hire creation - a new hire - starts in draft collecting with nothing submitted`() = runTest {
        val created = useCase(createHire(email = "maria.santos@example.com")).ok()

        created.employee.packetStatus shouldBe PacketStatus.DRAFT_COLLECTING
        created.employee.submittedAt.shouldBeNull()
        created.employee.submittedByHr shouldBe false
        created.employee.attestation.shouldBeNull()
        created.employee.completedAt.shouldBeNull()
        // §12 invariant 9: COMPLETE is not identity assurance, and originals-sighted starts absent.
        created.employee.originalsSightedAt.shouldBeNull()
    }

    @Test
    fun `hire creation - a unique email - carries no anomaly flag and records no override`() = runTest {
        // The anti-coincidence half of the two duplicate tests above. Without it, a use case that
        // set SHARED_EMAIL on every hire and recorded an override every time passes both of them.
        val created = useCase(createHire(email = "maria.santos@example.com")).ok()

        created.employee.anomalyFlags.shouldBeEmpty()
        audit.entries.map { it.action } shouldBe listOf(AuditAction.HIRE_CREATED)
    }

    @Test
    fun `hire creation - a generated id that collides with an existing hire - returns and audits the id stored`() =
        runTest {
            // create() redraws a taken PersonId and returns the hire AS STORED. A use case that wrote
            // the argument's id into the audit row would name a hire that does not exist — and would
            // pass every other test in this file.
            val colliding = FixedPersonIdGenerator("EXISTING", "REDRAWN1")
            val repository = FakeEmployeeRepository(ids = colliding)
                .given(anEmployee(id = personId("EXISTING"), email = anEmail("someone.else@example.com")))
            val subject = useCaseWith(employees = repository, personIds = colliding)

            val created = subject(createHire(email = "maria.santos@example.com")).ok()

            created.employee.id shouldBe personId("REDRAWN1")
            audit.entriesFor(AuditAction.HIRE_CREATED).single().entityId shouldBe personId("REDRAWN1")
        }

    // ── What the duplicate rule hides ───────────────────────────────────────────────────────────

    @Test
    fun `hire creation - a duplicate in another case - still needs a reason`() = runTest {
        // EmailAddress.of lower-cases on construction, and that normalisation is the whole of the
        // duplicate check's case-insensitivity. A future "optimisation" comparing the raw command
        // string would pass every other test here and let two hires share a mailbox.
        employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

        useCase(createHire(email = "Jose.DelaCruz@Example.COM")).err()
            .shouldBeInstanceOf<AppError.ReasonRequired>()
    }

    @Test
    fun `hire creation - a duplicate with a completed hire - needs no reason`() = runTest {
        // PacketStatus.isTerminal has two members and the ticket only tests CANCELLED, so an
        // implementation checking `== CANCELLED` passes it.
        employees.given(
            anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL), packetStatus = PacketStatus.COMPLETE)
        )

        useCase(createHire(email = SHARED_EMAIL)).ok().employee.anomalyFlags.shouldBeEmpty()
    }

    @Test
    fun `hire creation - a reason with no duplicate - is ignored and records no override`() = runTest {
        // Unspecified by the ticket, so decided here: refusing would 422 a valid request from an
        // officer who typed a reason and then corrected the address, and recording it anyway would
        // hand §8.13's exception report a false positive.
        val created = useCase(createHire(email = "maria.santos@example.com", duplicateReason = REASON)).ok()

        created.employee.anomalyFlags.shouldBeEmpty()
        audit.entriesFor(AuditAction.DUPLICATE_EMAIL_OVERRIDDEN).shouldBeEmpty()
    }

    @Test
    fun `hire creation - a duplicate with a reason - leaves the hire it duplicates unchanged`() = runTest {
        // Only the NEW hire is flagged. The hire it duplicates is equally a record sharing an
        // address and is deliberately not back-filled — that would be a second write nobody
        // specified, needing its own audit row. `duplicateOf` on the override entry is what lets
        // §8.13 recover the pair instead.
        employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

        useCase(createHire(email = SHARED_EMAIL, duplicateReason = REASON)).ok()

        employees.saved.shouldBeEmpty()
        employees.all.single { it.id == personId("EXISTING") }.anomalyFlags.shouldBeEmpty()
    }

    @Test
    fun `hire creation - a typed reason with surrounding whitespace - is recorded trimmed`() = runTest {
        employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

        useCase(createHire(email = SHARED_EMAIL, duplicateReason = "  $REASON  ")).ok()

        audit.entriesFor(AuditAction.DUPLICATE_EMAIL_OVERRIDDEN).single().metadata["reason"] shouldBe REASON
    }

    // ── The order of the guards, which is a rule rather than an accident ────────────────────────

    @Test
    fun `hire creation - both reference ids unknown - names the department first`() = runTest {
        // Pinned so a later reordering is a visible deliberate break. Only one failure is reported:
        // the ticket's criteria name a singular Validation, and accumulating the whole command into
        // ValidationFailed is ERT-450's decision to make on the wire, where the two render alike.
        val result = useCase(
            createHire(
                departmentId = entityId("DPT000000999").value,
                employmentTypeId = entityId("EMT000000999").value,
            )
        )

        result.errField() shouldBe "departmentId"
    }

    @Test
    fun `hire creation - an unknown department and a duplicate email - fails on the department`() = runTest {
        // References before the duplicate check, and not for tidiness: the duplicate branch asks a
        // human to type a justification that becomes a permanent audit artefact. Asking for one on a
        // request that was going to fail anyway also confirms the address is in use.
        employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

        val result = useCase(createHire(email = SHARED_EMAIL, departmentId = entityId("DPT000000999").value))

        result.errCode() shouldBe "department_unknown"
    }

    // ── Required fields, which nothing validates anywhere (review finding) ──────────────────────

    @Test
    fun `hire creation - a blank first name - is refused naming the field`() = runTest {
        val result = useCase(createHire(firstName = " "))

        result.errCode() shouldBe "first_name.required"
        result.errField() shouldBe "firstName"
        nothingHappened()
    }

    @Test
    fun `hire creation - a blank last name - is refused naming the field`() = runTest {
        val result = useCase(createHire(lastName = ""))

        result.errCode() shouldBe "last_name.required"
        result.errField() shouldBe "lastName"
        nothingHappened()
    }

    @Test
    fun `hire creation - a whitespace-only position - is refused naming the field`() = runTest {
        val result = useCase(createHire(position = "   "))

        result.errCode() shouldBe "position.required"
        result.errField() shouldBe "position"
        nothingHappened()
    }

    @Test
    fun `hire creation - names with surrounding whitespace - are stored trimmed`() = runTest {
        val created = useCase(
            createHire(firstName = "  Maria ", lastName = " Santos  ", position = " Store Associate ")
        ).ok()

        created.employee.firstName shouldBe "Maria"
        created.employee.lastName shouldBe "Santos"
        created.employee.position shouldBe "Store Associate"
    }

    @Test
    fun `hire creation - a blank middle initial - is stored as absent`() = runTest {
        // An empty string in a nullable column reads as "has a middle initial" to everything
        // downstream, which is a different claim from "has none".
        useCase(createHire(middleInitial = "   ")).ok().employee.middleInitial.shouldBeNull()
    }

    // ── The requirement snapshot (ERT-432, PRD §5) ──────────────────────────────────────────────

    @Test
    fun `requirement snapshot - a hire is created - one requirement per active template`() = runTest {
        val created = useCase(createHire(email = "maria.santos@example.com")).ok()

        created.requirements.requirements.map { it.templateId } shouldContainExactly
            listOf(BIRTH, NBI, MEDICAL)
        created.requirements.requirements.map { it.nameSnapshot } shouldContainExactly CATALOGUE_ORDER
        created.requirements.requirements.forEach { it.employeeId shouldBe created.employee.id }
    }

    @Test
    fun `requirement snapshot - a hire is created - the set is saved as one batch of distinct rows`() =
        runTest {
            // One batch rather than one call per requirement: PRD §5 snapshots the set as a unit,
            // and "saved the set once" is a different claim from "saved three requirements".
            //
            // Distinct ids because the obvious shortcut -- drawing one EntityId and reusing it --
            // produces a set that looks right in every other assertion here and collapses to a
            // single row the moment it reaches a table keyed by id.
            val created = useCase(createHire(email = "maria.santos@example.com")).ok()

            employees.savedRequirementBatches shouldHaveSize 1
            employees.savedRequirementBatches.single() shouldHaveSize 3
            employees.savedRequirementBatches.single() shouldBe created.requirements.requirements
            created.requirements.requirements.map { it.id }.toSet() shouldHaveSize 3
        }

    @Test
    fun `requirement snapshot - templates carrying a deliberate sort order - copy it rather than deriving one`() =
        runTest {
            // THE VALUES, not merely the order. A use case that numbered the rows 0, 1, 2 by their
            // position in the catalogue list produces the identical ORDER and a different snapshot
            // -- and would quietly re-derive tomorrow what §5 says must be copied today. Only an
            // assertion on the stored integers tells the two apart.
            val created = useCase(createHire(email = "maria.santos@example.com")).ok()

            created.requirements.requirements.map { it.sortOrderSnapshot } shouldContainExactly listOf(1, 2, 3)
        }

    @Test
    fun `requirement snapshot - a new hire - starts every requirement pending at zero progress`() = runTest {
        // §8.1: the hire appears in the list at 0% progress.
        val created = useCase(createHire(email = "maria.santos@example.com")).ok()

        created.requirements.requirements.forEach {
            it.status shouldBe RequirementStatus.PENDING
            it.rejectionCount shouldBe 0
        }
        created.requirements.total shouldBe 3
        created.requirements.submitted shouldBe 0
        created.requirements.approved shouldBe 0
        created.requirements.awaitingReview shouldBe 0
    }

    @Test
    fun `requirement snapshot - the template is renamed afterwards - the hire keeps the original name`() =
        runTest {
            // The test PRD §5 exists for. Without it the snapshot rule is only an intention, and a
            // future refactor that "simplifies" the copy into a join passes every other test here.
            val created = useCase(createHire(email = "maria.santos@example.com")).ok()

            templates.given(aRequirementTemplate(id = BIRTH, name = "Birth certificate (PSA)", sortOrder = 1))

            created.requirements.requirements.map { it.nameSnapshot } shouldContainExactly CATALOGUE_ORDER
            employees.requirementsOf(created.employee.id).requirements
                .map { it.nameSnapshot } shouldContainExactly CATALOGUE_ORDER
        }

    @Test
    fun `requirement snapshot - a template becomes optional afterwards - the hire keeps the original required flag`() =
        runTest {
            // §8.11: an admin edit must not change an in-flight hire. Here it would change the
            // DENOMINATOR -- three required becoming two -- so a hire at 2/3 would jump to 2/2 and
            // read as complete without anyone uploading anything.
            val created = useCase(createHire(email = "maria.santos@example.com")).ok()

            templates.given(
                aRequirementTemplate(id = NBI, name = "NBI clearance", isRequired = false, sortOrder = 2),
            )

            created.requirements.requirements.forEach { it.isRequiredSnapshot shouldBe true }
            employees.requirementsOf(created.employee.id).total shouldBe 3
        }

    @Test
    fun `requirement snapshot - the catalogue is reordered afterwards - the hire keeps the original order`() =
        runTest {
            // The reason `sort_order_snapshot` exists at all (ERT-410, architecture §7). Reaching
            // the catalogue's live sort_order would let an admin reordering a template today
            // reshuffle a checklist on a phone belonging to someone hired last month.
            val created = useCase(createHire(email = "maria.santos@example.com")).ok()

            templates.given(
                aRequirementTemplate(id = MEDICAL, name = "Medical certificate", sortOrder = 0),
                aRequirementTemplate(id = BIRTH, name = "Birth certificate", sortOrder = 9),
            )

            employees.requirementsOf(created.employee.id).requirements
                .map { it.nameSnapshot } shouldContainExactly CATALOGUE_ORDER
        }

    @Test
    fun `requirement snapshot - an optional template - is excluded from the denominator`() = runTest {
        // §6.5: optional requirements are outside numerator and denominator entirely. The row still
        // exists -- the hire is asked for the document -- it just does not gate completion.
        val catalogue = FakeRequirementTemplateRepository().givenAssigned(
            Fixtures.EMPLOYMENT_TYPE_ID,
            aRequirementTemplate(id = BIRTH, name = "Birth certificate", sortOrder = 1),
            aRequirementTemplate(id = NBI, name = "NBI clearance", sortOrder = 2),
            aRequirementTemplate(id = MEDICAL, name = "Company ID photo", isRequired = false, sortOrder = 3),
        )

        val created = useCaseWith(templates = catalogue)(createHire(email = "maria.santos@example.com")).ok()

        created.requirements.requirements shouldHaveSize 3
        created.requirements.requirements.map { it.isRequiredSnapshot } shouldContainExactly
            listOf(true, true, false)
        created.requirements.total shouldBe 2
    }

    @Test
    fun `requirement snapshot - the catalogue is read - it is read once at creation`() = runTest {
        // PRD §5: read once and copied. Nothing downstream may consult it again for an in-flight
        // hire, and a read counter is how that stops being a sentence and becomes a test.
        val created = useCase(createHire(email = "maria.santos@example.com")).ok()

        employees.requirementsOf(created.employee.id)

        templates.employmentTypeReads shouldBe listOf(Fixtures.EMPLOYMENT_TYPE_ID)
    }

    @Test
    fun `requirement snapshot - a generated id that collides - the requirements name the id stored`() =
        runTest {
            // create() may redraw, and returns the hire AS STORED. ERT-431 recorded this for the
            // audit row; the snapshot rows are the second place it bites, and here it is a FOREIGN
            // KEY -- requirements written against the drawn id point at a hire that does not exist.
            val colliding = FixedPersonIdGenerator("EXISTING", "REDRAWN1")
            val repository = FakeEmployeeRepository(ids = colliding)
                .given(anEmployee(id = personId("EXISTING"), email = anEmail("someone.else@example.com")))
            val subject = useCaseWith(employees = repository, personIds = colliding)

            val created = subject(createHire(email = "maria.santos@example.com")).ok()

            created.requirements.requirements.map { it.employeeId }.toSet() shouldBe
                setOf(personId("REDRAWN1"))
            repository.requirementsOf(personId("REDRAWN1")).requirements shouldHaveSize 3
            repository.requirementsOf(personId("EXISTING")).requirements.shouldBeEmpty()
        }

    @Test
    fun `requirement snapshot - a retired template still assigned to the type - is not snapshotted`() =
        runTest {
            // The port reads ACTIVE templates, and nothing in this use case restates that -- so the
            // realistic break is someone reaching for `findAll` to "see everything", which compiles,
            // reads as more thorough, and puts a retired document type on a new hire's checklist.
            // Paired with an inclusion so the test cannot pass by returning nothing.
            val catalogue = FakeRequirementTemplateRepository().givenAssigned(
                Fixtures.EMPLOYMENT_TYPE_ID,
                aRequirementTemplate(id = BIRTH, name = "Birth certificate", sortOrder = 1),
                aRequirementTemplate(id = NBI, name = "Retired clearance", sortOrder = 2, isActive = false),
            )

            val created = useCaseWith(templates = catalogue)(createHire(email = "maria.santos@example.com")).ok()

            created.requirements.requirements.map { it.nameSnapshot } shouldContainExactly
                listOf("Birth certificate")
        }

    @Test
    fun `requirement snapshot - a catalogue of only optional templates - creates a hire at zero of zero`() =
        runTest {
            // PINS TODAY'S BEHAVIOUR RATHER THAN ENDORSING IT (C27, opened by ERT-432's review step).
            //
            // The empty-catalogue guard's stated harm is "zero of zero required documents is
            // COMPLETE, so the record passes straight through the §8.5 validation loop without
            // anyone uploading anything" -- and a catalogue that is entirely OPTIONAL has exactly
            // that property while passing the guard, because the guard asks `isEmpty()`.
            //
            // Not tightened here. The defect is in the CATALOGUE, not in hire creation: the remedy
            // is the §8.11 admin screen refusing to publish an all-optional assignment, which is
            // Phase 2's, and refusing at creation would block HR for something only an admin can
            // fix. Unreachable today -- the V2 seed cross-joins all 14 templates and 10 are required
            // -- and reachable the moment Q2's real checklist lands or the Phase 2 screen ships.
            //
            // Asserting it rather than leaving it unstated, on ERT-431's C25 precedent: a trap with
            // a named test is visible, and a trap nobody wrote down is discovered in production.
            val catalogue = FakeRequirementTemplateRepository().givenAssigned(
                Fixtures.EMPLOYMENT_TYPE_ID,
                aRequirementTemplate(id = BIRTH, name = "Company ID photo", isRequired = false, sortOrder = 1),
            )

            val created = useCaseWith(templates = catalogue)(createHire(email = "maria.santos@example.com")).ok()

            created.requirements.requirements shouldHaveSize 1
            created.requirements.total shouldBe 0
            created.requirements.approved shouldBe 0
        }

    // ── An employment type nobody has configured ────────────────────────────────────────────────

    @Test
    fun `hire creation - an employment type with no templates - fails rather than creating an empty checklist`() =
        runTest {
            // A hire with an empty checklist is worse than a refused one: it is COMPLETE the moment
            // it exists -- zero of zero required documents -- so it passes straight through the §8.5
            // validation loop without anyone uploading anything.
            //
            // A Validation naming the field, not a Conflict (D1). Both remedies belong to the field
            // HR chose: pick another employment type, or have an admin configure this one. C1
            // settled that Conflict drops the `details` entry a picker needs to say which.
            reference.givenEmploymentTypes(anEmploymentType(id = UNSTAFFED_TYPE, name = "Project-Based"))

            val result = useCase(createHire(employmentTypeId = UNSTAFFED_TYPE.value))

            result.errCode() shouldBe "employment_type_no_requirements"
            result.errField() shouldBe "employmentTypeId"
            nothingHappened()
        }

    @Test
    fun `hire creation - an unknown employment type and an unconfigured one - are told apart`() = runTest {
        // Two failures on one field, and they are not the same remedy: "that id is not in the list"
        // is HR's mistake, "nothing is configured for it" is an admin's. A single code would send
        // both to the same place, which is E8's argument for two reference codes reached again one
        // guard later.
        reference.givenEmploymentTypes(anEmploymentType(id = UNSTAFFED_TYPE, name = "Project-Based"))

        useCase(createHire(employmentTypeId = entityId("EMT000000099").value)).errCode() shouldBe
            "employment_type_unknown"
        useCase(createHire(employmentTypeId = UNSTAFFED_TYPE.value)).errCode() shouldBe
            "employment_type_no_requirements"
    }

    @Test
    fun `hire creation - an unconfigured employment type and a duplicate email - is refused before a reason is demanded`() =
        runTest {
            // The ordering rule ERT-431 stated, applied to the guard ERT-432 adds. The duplicate
            // branch asks a human to type a justification that becomes a permanent audit artefact.
            // Demanding one on a request that is then going to fail on the catalogue is the worst
            // available ordering -- and it confirms an address is in use on a request that was never
            // going to succeed.
            reference.givenEmploymentTypes(anEmploymentType(id = UNSTAFFED_TYPE, name = "Project-Based"))
            employees.given(anEmployee(id = personId("EXISTING"), email = anEmail(SHARED_EMAIL)))

            val result = useCase(createHire(email = SHARED_EMAIL, employmentTypeId = UNSTAFFED_TYPE.value))

            result.errCode() shouldBe "employment_type_no_requirements"
            nothingHappened()
        }

    @Test
    fun `hire creation - the catalogue read fails - no hire is written to be left without a checklist`() =
        runTest {
            // The catalogue is read BEFORE the hire is written, which is what makes this survivable:
            // there is no transaction seam in the domain, so a read that failed after `create` would
            // leave a hire nobody can complete and nothing to roll it back.
            templates.failure.failEveryCall()

            assertFailsWith<IllegalStateException> { useCase(createHire(email = "maria.santos@example.com")) }
            nothingHappened()
        }

    // ── The link and its expiry (ERT-433) ───────────────────────────────────────────────────────

    @Test
    fun `link issue - a hire is created - stores a token digest and no pin`() = runTest {
        val result = useCase(createHire()).ok()

        val savedLink = uploadLinks.saved.single()
        savedLink.tokenHash shouldBe tokenDigest.digest("token-0000000001")
        savedLink.pinHash.shouldBeNull()
        savedLink.status shouldBe LinkStatus.ACTIVE
        savedLink.extendedCount shouldBe 0
        savedLink.failedPinCount shouldBe 0
        savedLink.lockedUntil.shouldBeNull()
        savedLink.warnedAt.shouldBeNull()
        savedLink.revokedAt.shouldBeNull()
        savedLink.revokedReason.shouldBeNull()
    }

    @Test
    fun `link issue - a link is issued - persists no plaintext credential`() = runTest {
        useCase(createHire()).ok()

        val savedLink = uploadLinks.saved.single()
        savedLink.tokenHash shouldNotBe "token-0000000001"
    }

    @Test
    fun `link expiry - policy of ninety days - stores an expiry ninety days after issue`() = runTest {
        appSettings.given(LinkPolicy(absoluteExpiryDays = 90, idleExpiryDays = 14))

        useCase(createHire()).ok()

        val savedLink = uploadLinks.saved.single()
        savedLink.issuedAt shouldBe clock.now()
        savedLink.expiresAt shouldBe clock.now().plus(java.time.Duration.ofDays(90))
        savedLink.idleExpiresAt shouldBe clock.now().plus(java.time.Duration.ofDays(14))
    }

    @Test
    fun `link expiry - the policy changes after issue - the stored expiry is unchanged`() = runTest {
        appSettings.given(LinkPolicy(absoluteExpiryDays = 90))
        useCase(createHire()).ok()

        appSettings.given(LinkPolicy(absoluteExpiryDays = 30))

        val savedLink = uploadLinks.saved.single()
        savedLink.expiresAt shouldBe clock.now().plus(java.time.Duration.ofDays(90))
    }

    @Test
    fun `link expiry - idle days of zero - stores no idle expiry`() = runTest {
        appSettings.given(LinkPolicy(absoluteExpiryDays = 90, idleExpiryDays = 0))

        useCase(createHire()).ok()

        val savedLink = uploadLinks.saved.single()
        savedLink.idleExpiresAt.shouldBeNull()
    }

    @Test
    fun `link issue - the policy read fails - refuses to create the hire and propagates the error`() = runTest {
        val policyError = AppError.Validation(
            code = "policy_unreadable",
            field = "linkPolicy",
            detail = "Database error reading settings",
        )
        appSettings.refuses(policyError)

        val result = useCase(createHire())

        result shouldBe DomainResult.Err(policyError)
        nothingHappened()
    }

    // ── There is no transaction here, and there cannot be one ──────────────────────────────────

    @Test
    fun `hire creation - the audit write fails - the failure surfaces rather than a silently unaudited hire`() =
        runTest {
            // The domain has no transaction seam, so a hire can be created and its audit row lost.
            // ERT-431 cannot fix that. What it can do is refuse to hide it: catching this and
            // returning Ok would produce an un-audited hire that every caller reads as a success,
            // which §12 would call a control failure rather than a rough edge.
            audit.failure.failEveryCall()

            assertFailsWith<IllegalStateException> { useCase(createHire(email = "maria.santos@example.com")) }
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** No hire was written and nothing was audited — the assertion every refusal shares. */
    private fun nothingHappened() {
        employees.created.shouldBeEmpty()
        employees.saved.shouldBeEmpty()
        // ERT-432: a refusal that still wrote a checklist would leave rows pointing at no hire.
        employees.savedRequirementBatches.shouldBeEmpty()
        uploadLinks.saved.shouldBeEmpty()
        notifier.attempts.shouldBeEmpty()
        audit.entries.shouldBeEmpty()
    }

    /**
     * The use case, with one collaborator swapped.
     *
     * Named parameters with defaults rather than a positional constructor call, because this class
     * built its second use case positionally and ERT-432 adding an eighth argument is exactly how
     * that silently becomes wrong. A helper means a ninth costs one edit here instead of one per
     * test.
     */
    private fun useCaseWith(
        employees: FakeEmployeeRepository = this.employees,
        templates: FakeRequirementTemplateRepository = this.templates,
        personIds: PersonIdGenerator = this.personIds,
        uploadLinks: FakeUploadLinkRepository = this.uploadLinks,
        appSettings: FakeAppSettingsRepository = this.appSettings,
    ) = CreateHireUseCase(
        employees = employees,
        reference = reference,
        templates = templates,
        uploadLinks = uploadLinks,
        tokenGenerator = tokenGenerator,
        tokenDigest = tokenDigest,
        appSettings = appSettings,
        notifier = notifier,
        audit = audit,
        clock = clock,
        ids = ids,
        personIds = personIds,
        tracer = NoOpUseCaseTracer,
    )

    private fun createHire(
        firstName: String = "Maria",
        middleInitial: String? = "L",
        lastName: String = "Santos",
        departmentId: String = Fixtures.DEPARTMENT_ID.value,
        position: String = "Store Associate",
        employmentTypeId: String = Fixtures.EMPLOYMENT_TYPE_ID.value,
        email: String = "maria.santos@example.com",
        duplicateReason: String? = null,
    ) = CreateHire(
        firstName = firstName,
        middleInitial = middleInitial,
        lastName = lastName,
        departmentId = departmentId,
        position = position,
        employmentTypeId = employmentTypeId,
        email = email,
        duplicateReason = duplicateReason,
        actingUserId = Fixtures.HR_USER_ID,
    )

    private companion object {
        const val SHARED_EMAIL = "jose.delacruz@example.com"
        const val REASON = "Rehire after a break in service"

        /** The catalogue's three templates. Id order is deliberately not sort order. */
        val MEDICAL = entityId("TPL000000001")
        val BIRTH = entityId("TPL000000002")
        val NBI = entityId("TPL000000003")

        /** In `employment_types` and in no `template_assignments` row — a real configuration gap. */
        val UNSTAFFED_TYPE = entityId("EMT000000009")

        /** What the catalogue above snapshots to, in the order it snapshots to. */
        val CATALOGUE_ORDER = listOf("Birth certificate", "NBI clearance", "Medical certificate")
    }
}
