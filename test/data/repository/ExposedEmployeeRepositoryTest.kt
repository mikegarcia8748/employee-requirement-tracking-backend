package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anAttestation
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementSet
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Hires against real SQL (ERT-410).
 *
 * What is tested here is what only a database can answer: that every column round-trips through the
 * mapper, that the identifier redraw actually fires against a real primary key, and that the
 * active-email scope narrows in SQL rather than only in the fake. The use-case tests own the rules.
 *
 * **The migrated schema does not satisfy these foreign keys on its own.** `employees.created_by`
 * references `users(id)` since ERT-190, and `users` is empty after migration — the bootstrap admin
 * is created at application startup, not by V4. The seeded department and employment type exist but
 * under ids that are not the builder fixtures. [givenReferenceRows] supplies all three so that
 * `anEmployee()` defaults work unchanged; without it every test below would fail on a constraint
 * rather than on its own rule, which is the least informative way for a suite to go red.
 */
class ExposedEmployeeRepositoryTest : RepositoryTestBase() {

    private val employees by lazy { ExposedEmployeeRepository(factory, FixedPersonIdGenerator()) }

    // ── Round trip ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `employee persistence - a fully populated hire - round-trips unchanged`() = runTest {
        givenReferenceRows()
        val hire = anEmployee(
            packetStatus = PacketStatus.UNDER_REVIEW,
            submittedAt = FixedClock.DEFAULT,
            submittedByHr = true,
            attestation = anAttestation(),
            originalsSightedAt = FixedClock.DEFAULT,
            originalsSightedBy = Fixtures.HR_USER_ID,
            anomalyFlags = setOf(AnomalyFlag.SHARED_EMAIL),
            completedAt = FixedClock.DEFAULT,
        )

        employees.create(hire)

        employees.findById(hire.id) shouldBe hire
    }

    @Test
    fun `employee persistence - a modified hire saved - keeps every changed field`() = runTest {
        // Guards against the realistic drift: a column wired into the insert but forgotten in the
        // update, which a create-then-read test passes straight over.
        givenReferenceRows()
        val hire = employees.create(anEmployee())

        val edited = hire.copy(
            firstName = "Maria",
            email = anEmail("maria.santos@example.com"),
            packetStatus = PacketStatus.CHANGES_REQUESTED,
            anomalyFlags = setOf(AnomalyFlag.REPEATED_REJECTIONS),
            attestation = anAttestation(),
        )
        employees.save(edited)

        employees.findById(hire.id) shouldBe edited
    }

    @Test
    fun `employee persistence - a hire that does not exist - is refused rather than inserted`() = runTest {
        givenReferenceRows()

        assertFailsWith<IllegalStateException> { employees.save(anEmployee()) }

        employees.findById(Fixtures.EMPLOYEE_ID) shouldBe null
    }

    // ── Identifier collision ────────────────────────────────────────────────────────────────────

    @Test
    fun `id collision - the generated id is already taken - a fresh id is drawn and the save succeeds`() =
        runTest {
            givenReferenceRows()
            val taken = employees.create(anEmployee(id = personId("EMP00001")))

            val redrawing = ExposedEmployeeRepository(factory, FixedPersonIdGenerator("EMP00002"))
            val arrival = redrawing.create(
                anEmployee(id = personId("EMP00001"), email = anEmail("second.hire@example.com"))
            )

            arrival.id shouldBe personId("EMP00002")
            employees.findById(personId("EMP00002")) shouldBe arrival

            // And the hire already at that id is untouched. This is the half a single upsert would
            // have got wrong: it would have overwritten `taken` with `arrival`.
            employees.findById(personId("EMP00001")) shouldBe taken
        }

    @Test
    fun `id collision - every attempt collides - fails loudly rather than looping`() = runTest {
        givenReferenceRows()
        employees.create(anEmployee(id = personId("EMP00001")))
        employees.create(anEmployee(id = personId("EMP00002"), email = anEmail("second@example.com")))

        // Scripted to hand back a taken id on every draw -- a generator stuck on a constant, which is
        // the only realistic way to exhaust 62^8. It must surface, not spin.
        val stuck = ExposedEmployeeRepository(
            factory,
            FixedPersonIdGenerator("EMP00002", "EMP00002", "EMP00002", "EMP00002", "EMP00002", "EMP00002"),
        )

        assertFailsWith<IllegalStateException> {
            stuck.create(anEmployee(id = personId("EMP00001"), email = anEmail("third@example.com")))
        }
    }

    @Test
    fun `id collision - an existing hire is saved - is updated rather than given a new id`() = runTest {
        // The create/save split, stated as a test: `save` must never redraw, or an edit would fork
        // the record it was meant to change.
        givenReferenceRows()
        val hire = employees.create(anEmployee())

        val saved = employees.save(hire.copy(position = "Branch Supervisor"))

        saved.id shouldBe hire.id
        employees.findById(hire.id)?.position shouldBe "Branch Supervisor"
        countOf("employees") shouldBe 1
    }

    // ── Active email lookup ─────────────────────────────────────────────────────────────────────

    @Test
    fun `active email lookup - a completed hire sharing the address - is not returned`() = runTest {
        givenReferenceRows()
        val shared = anEmail("duplicate@example.com")
        employees.create(
            anEmployee(id = personId("EMP00001"), email = shared, packetStatus = PacketStatus.COMPLETE)
        )
        employees.create(
            anEmployee(id = personId("EMP00002"), email = shared, packetStatus = PacketStatus.CANCELLED)
        )
        val active = employees.create(
            anEmployee(id = personId("EMP00003"), email = shared, packetStatus = PacketStatus.DRAFT_COLLECTING)
        )

        employees.findActiveByEmail(shared) shouldContainExactly listOf(active)
    }

    @Test
    fun `active email lookup - an on-hold hire sharing the address - is returned`() = runTest {
        // Pins isTerminal as the scope rule rather than a hand-written status list. ON_HOLD is the
        // status that separates the two: it is neither collecting nor finished, and a list written
        // by hand is exactly where it gets forgotten.
        givenReferenceRows()
        val shared = anEmail("onhold@example.com")
        val paused = employees.create(
            anEmployee(id = personId("EMP00009"), email = shared, packetStatus = PacketStatus.ON_HOLD)
        )

        employees.findActiveByEmail(shared) shouldContainExactly listOf(paused)
    }

    @Test
    fun `active email lookup - an address nobody holds - is empty rather than absent`() = runTest {
        givenReferenceRows()

        employees.findActiveByEmail(anEmail("nobody@example.com")).shouldBeEmpty()
    }

    // ── Anomaly flags, which drive the PRD 7.1 retention freeze ─────────────────────────────────

    @Test
    fun `anomaly flags - two flags stored - round-trip and report retention frozen`() = runTest {
        givenReferenceRows()
        val flagged = anEmployee(
            anomalyFlags = setOf(AnomalyFlag.SUSPECTED_FRAUD, AnomalyFlag.ACCESS_ANOMALY),
        )

        employees.create(flagged)

        val read = employees.findById(flagged.id)
        read?.anomalyFlags shouldBe setOf(AnomalyFlag.ACCESS_ANOMALY, AnomalyFlag.SUSPECTED_FRAUD)
        read?.retentionFrozen shouldBe true
    }

    @Test
    fun `anomaly flags - a hire with no flags - round-trips as an empty set rather than one blank flag`() =
        runTest {
            // The empty string is the trap: "".split(",") is [""], not []. A mapper that missed it
            // either throws on a flag named nothing or -- the dangerous variant -- reports
            // retentionFrozen for every hire in the system and silently disables the version purge.
            givenReferenceRows()
            val unflagged = anEmployee(anomalyFlags = emptySet())

            employees.create(unflagged)

            val read = employees.findById(unflagged.id)
            read?.anomalyFlags.shouldBeEmpty()
            read?.retentionFrozen shouldBe false
        }

    // ── Attestation, which is all-or-nothing ────────────────────────────────────────────────────

    @Test
    fun `attestation mapping - an unattested packet - round-trips as null`() = runTest {
        givenReferenceRows()
        val collecting = anEmployee(attestation = null)

        employees.create(collecting)

        employees.findById(collecting.id)?.attestation shouldBe null
        strings("select count(*) from employees where attestation_version is null") shouldContainExactly
            listOf("1")
    }

    @Test
    fun `attestation mapping - a partially populated attestation - fails rather than returning a half-built one`() =
        runTest {
            // Only raw SQL can produce this row, which is the point: the writer cannot create it, so
            // the reader is the only place the corruption can be caught. PRD 7.2 makes the text
            // version the evidence, so a timestamp without one interprets to nothing.
            givenReferenceRows()
            execute(
                """
                insert into employees (id, first_name, last_name, department_id, "position",
                    employment_type_id, email, packet_status, submitted_by_hr, attested_at,
                    anomaly_flags, created_at, created_by)
                values ('EMP00007', 'Jose', 'Dela Cruz', 'DPT000000001', 'Store Associate',
                    'EMT000000001', 'jose@example.com', 'UNDER_REVIEW', false,
                    timestamp '2026-01-15 09:00:00', '', timestamp '2026-01-15 09:00:00', 'HRU00001')
                """.trimIndent()
            )

            assertFailsWith<IllegalStateException> { employees.findById(personId("EMP00007")) }
        }

    // ── Requirement set ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `requirement set - stored rows - report the progress arithmetic over required rows only`() = runTest {
        givenReferenceRows()
        val hire = employees.create(anEmployee())
        val set = aRequirementSet(required = 3, approved = 1, underReview = 1, optional = 1)

        employees.saveRequirements(set.requirements)

        val read = employees.requirementsOf(hire.id)
        read.requirements.size shouldBe 4
        read.total shouldBe 3
        read.approved shouldBe 1
        read.awaitingReview shouldBe 1
        read.submitted shouldBe 2
    }

    @Test
    fun `requirement set - a requirement saved twice - is updated rather than duplicated`() = runTest {
        givenReferenceRows()
        val hire = employees.create(anEmployee())
        val set = aRequirementSet(required = 2)
        employees.saveRequirements(set.requirements)

        val advanced = set.requirements.map { it.copy(rejectionCount = 3) }
        employees.saveRequirements(advanced)

        countOf("employee_requirements") shouldBe 2
        employees.requirementsOf(hire.id).requirements.map { it.rejectionCount } shouldContainExactly
            listOf(3, 3)
    }

    @Test
    fun `requirement set - a hire with no requirements - is an empty set rather than null`() = runTest {
        givenReferenceRows()
        val hire = employees.create(anEmployee())

        employees.requirementsOf(hire.id).requirements.shouldBeEmpty()
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The rows the foreign keys demand, at the ids the builders default to.
     *
     * The names deliberately avoid the V2 seed's own (`Unassigned`, `Regular`), because both columns
     * carry a unique index and a collision here would read as a failure of the test's own rule.
     */
    private suspend fun givenReferenceRows() {
        ExposedHrUserRepository(factory).save(anHrUser(id = Fixtures.HR_USER_ID))
        execute("insert into departments (id, \"name\") values ('DPT000000001', 'Store Operations')")
        execute("insert into employment_types (id, \"name\") values ('EMT000000001', 'Regular Full Time')")
        execute(
            "insert into requirement_templates (id, \"name\", instructions, is_required, expires, " +
                "is_active, sort_order) values ('TPL000000001', 'NBI Clearance', 'Upload it', true, " +
                "false, true, 1)"
        )
    }
}
