package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aDepartment
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anEmploymentType
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.err
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.errField
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeEmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Hire creation, first sub-task (ERT-431, PRD §8.1).
 *
 * Covers the rules that decide whether a hire may be created at all: the email is well formed, both
 * reference ids exist, and a duplicate against an **active** hire carries a typed justification. The
 * requirement snapshot is ERT-432, the link and its expiry ERT-433, the invitation ERT-434 — so a
 * hire created here has an empty checklist and no link, deliberately.
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
    private val reference = FakeReferenceDataRepository(
        departments = listOf(aDepartment(id = Fixtures.DEPARTMENT_ID)),
        employmentTypes = listOf(anEmploymentType(id = Fixtures.EMPLOYMENT_TYPE_ID)),
    )

    private val useCase = CreateHireUseCase(
        employees = employees,
        reference = reference,
        audit = audit,
        clock = clock,
        ids = ids,
        personIds = personIds,
        tracer = NoOpUseCaseTracer,
    )

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
            val subject = CreateHireUseCase(repository, reference, audit, clock, ids, colliding, NoOpUseCaseTracer)

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
        audit.entries.shouldBeEmpty()
    }

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
    }
}
