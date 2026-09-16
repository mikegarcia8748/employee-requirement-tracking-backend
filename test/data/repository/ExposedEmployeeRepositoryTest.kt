package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementStatus
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aCompletedPacket
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementSet
import com.pgsystem.employee.requirement.tracker.testdata.anAttestation
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anEmployeeRequirement
import com.pgsystem.employee.requirement.tracker.testdata.personId
import com.pgsystem.employee.requirement.tracker.testdata.requirementId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Hires and their requirement sets against real SQL (ERT-410).
 *
 * What is tested here is everything only a database can answer: that eighteen fields round-trip
 * through the mapper, that the two encodings with no column type behind them — the anomaly-flag set
 * and the attestation triple — survive both directions, that `create` redraws a taken identifier
 * instead of overwriting a hire, and that the checklist comes back in a stable order. The rules
 * about *when* any of this happens belong to `CreateHireUseCase` (ERT-430), which does not exist
 * yet.
 *
 * **H2 in PostgreSQL mode is not PostgreSQL** — `RepositoryTestBase` says why at length.
 *
 * ### Two things here are guards against a passing test proving nothing
 *
 * ERT-320 found that `template_assignments` was seeded as a cross join with `sort_order` running in
 * id order, so dropping the `ORDER BY` entirely failed only the one test written to break the
 * coincidence. ERT-350 hit it again: one seeded department makes "in name order" vacuous. **That is
 * twice in one epic**, so it is assumed here rather than rediscovered — every ordering and scoping
 * test below inserts rows whose natural order differs from their insertion order, and every
 * exclusion test is paired with an inclusion that proves the filter is not simply returning nothing.
 *
 * The second is the retry test's own trap, recorded by ERT-310: **Exposed re-runs a failed
 * transaction block**, including non-transactional work inside it such as an id generator. A
 * collision test scripting one duplicate id can therefore pass through the framework's retry rather
 * than the adapter's, so the give-up test below scripts the taken id many times over.
 */
class ExposedEmployeeRepositoryTest : RepositoryTestBase() {

    private val employees by lazy { repositoryDrawing(FixedPersonIdGenerator()) }

    private fun repositoryDrawing(ids: PersonIdGenerator) = ExposedEmployeeRepository(factory, ids)

    /**
     * The three foreign keys a builder default cannot satisfy on its own.
     *
     * `anEmployee()` points at `DPT000000001`, `EMT000000001` and `HRU00001`, and **none of them
     * exists after migration**: the V2 seed holds `d00000000001` and `e00000000001`..`4`, and
     * `users` is empty because the bootstrap admin is a startup use case rather than a seed row. So
     * a naive `anEmployee()` → `create()` fails three foreign keys at once, and the failure names a
     * constraint rather than the mismatch behind it.
     *
     * Inserting rows under the `Fixtures` ids rather than re-pointing every builder call keeps each
     * test below reading as the rule it is about. The names are deliberately unlike the seed's:
     * `departments` and `employment_types` both carry a unique name constraint.
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
            execute(
                """
                insert into requirement_templates (id, "name", instructions, is_required, expires,
                                                   is_active, sort_order)
                values ('${Fixtures.TEMPLATE_ID.value}', 'Fixture Template', 'Upload it.', true,
                        false, true, 1)
                """.trimIndent()
            )
        }
    }

    // ── Round trip ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `employee persistence - a fully populated hire - round-trips unchanged`() = runTest {
        // Every nullable column carries a value, so a mapper that dropped one on either side fails
        // here rather than in the first Phase 2 screen that reads it.
        val hire = anEmployee(
            middleInitial = "P",
            packetStatus = PacketStatus.COMPLETE,
            submittedAt = FixedClock.DEFAULT,
            attestation = anAttestation(textVersion = "v3", attestedIp = "198.51.100.7"),
            originalsSightedAt = FixedClock.DEFAULT,
            originalsSightedBy = Fixtures.HR_USER_ID,
            anomalyFlags = setOf(AnomalyFlag.SUSPECTED_FRAUD, AnomalyFlag.ACCESS_ANOMALY),
            completedAt = FixedClock.DEFAULT,
        )

        employees.create(hire)

        employees.findById(hire.id) shouldBe hire
    }

    @Test
    fun `employee persistence - a hire with every nullable empty - round-trips unchanged`() = runTest {
        // The other end of the same mapping. A `when` that got its null branch backwards passes the
        // test above and fails this one.
        val hire = anEmployee(middleInitial = null)

        employees.create(hire)

        employees.findById(hire.id) shouldBe hire
    }

    @Test
    fun `employee persistence - a hire nobody has created - is not found`() = runTest {
        employees.findById(personId("EMP99999")).shouldBeNull()
    }

    @Test
    fun `employee persistence - an update - writes every mutable column`() = runTest {
        // The write side is shared by insert and update through one `writeTo`. Two hand-written
        // column lists drift, and the realistic failure is a field wired into the insert only -- so
        // an HR force-submit would silently keep the old packet status. Department and employment
        // type are left alone: changing them needs a second reference row and says nothing extra.
        val hire = anEmployee()
        employees.create(hire)

        val changed = hire.copy(
            firstName = "Maria",
            middleInitial = null,
            lastName = "Santos",
            position = "Cashier",
            email = anEmail("maria.santos@example.com"),
            packetStatus = PacketStatus.UNDER_REVIEW,
            submittedAt = FixedClock.DEFAULT,
            submittedByHr = true,
            attestation = anAttestation(textVersion = "v2"),
            originalsSightedAt = FixedClock.DEFAULT,
            originalsSightedBy = Fixtures.HR_USER_ID,
            anomalyFlags = setOf(AnomalyFlag.REPEATED_REJECTIONS),
            completedAt = FixedClock.DEFAULT,
        )
        employees.save(changed)

        employees.findById(hire.id) shouldBe changed
        countOf("employees") shouldBe 1
    }

    @Test
    fun `employee persistence - saving a hire that was never created - fails loudly`() = runTest {
        // save() updates and never inserts, which is what stops a use case reaching for the wrong
        // method and getting a row anyway. An update matching zero rows is silent in SQL.
        assertFailsWith<Exception> { employees.save(anEmployee()) }

        countOf("employees") shouldBe 0
    }

    // ── Identifier collision ────────────────────────────────────────────────────────────────────

    @Test
    fun `id collision - the generated id is already taken - a fresh id is drawn and the save succeeds`() =
        runTest {
            // THE ASSERTION THAT MATTERS IS THE THIRD ONE. A read-then-insert-or-update `save`
            // would pass the first two -- the call returns, no exception -- while having quietly
            // overwritten an unrelated hire with this one's details. That is the failure the
            // create/save split exists to make unrepresentable, and only re-reading the first hire
            // catches it.
            val first = anEmployee(id = personId("EMP00001"))
            employees.create(first)

            val second = repositoryDrawing(FixedPersonIdGenerator("EMP00042")).create(
                anEmployee(id = personId("EMP00001"), email = anEmail("second@example.com")),
            )

            second.id shouldBe personId("EMP00042")
            employees.findById(personId("EMP00042"))?.email shouldBe anEmail("second@example.com")
            employees.findById(personId("EMP00001")) shouldBe first
            countOf("employees") shouldBe 2
        }

    @Test
    fun `id collision - every draw is taken - gives up after a bounded number of attempts`() = runTest {
        // Scripted well past the adapter's own bound, because Exposed re-runs a failed transaction
        // block and the generator is not transactional: a shorter script would run out mid-retry
        // and let a fresh id through, which would look like the retry working.
        employees.create(anEmployee(id = personId("EMP00001")))
        val stuck = repositoryDrawing(FixedPersonIdGenerator(*Array(32) { "EMP00001" }))

        assertFailsWith<Exception> {
            stuck.create(anEmployee(id = personId("EMP00001"), email = anEmail("second@example.com")))
        }

        countOf("employees") shouldBe 1
    }

    @Test
    fun `id collision - a free id - is used as offered without drawing`() = runTest {
        // Non-vacuity: without this, an adapter that redrew on every insert would pass both tests
        // above. The generator is scripted with a value that must not appear.
        val drawn = repositoryDrawing(FixedPersonIdGenerator("EMP00042"))
            .create(anEmployee(id = personId("EMP00001")))

        drawn.id shouldBe personId("EMP00001")
    }

    // ── The active-email lookup ─────────────────────────────────────────────────────────────────

    @Test
    fun `active email lookup - a hire still collecting - is returned`() = runTest {
        // The inclusion half. Every exclusion below is worthless without it: a predicate returning
        // nothing at all would satisfy them.
        val shared = anEmail("shared@example.com")
        employees.create(anEmployee(id = personId("EMP00001"), email = shared))

        employees.findActiveByEmail(shared) shouldHaveSize 1
    }

    @Test
    fun `active email lookup - a completed hire sharing the address - is not returned`() = runTest {
        val shared = anEmail("shared@example.com")
        employees.create(aCompletedPacket(id = personId("EMP00002")).copy(email = shared))

        employees.findActiveByEmail(shared).shouldBeEmpty()
    }

    @Test
    fun `active email lookup - a cancelled hire sharing the address - is not returned`() = runTest {
        // Both terminal states, not just the one. A filter naming only COMPLETE passes the test
        // above and fails this one, and PacketStatus.isTerminal is what keeps the rule single.
        val shared = anEmail("shared@example.com")
        employees.create(
            anEmployee(id = personId("EMP00003"), email = shared, packetStatus = PacketStatus.CANCELLED),
        )

        employees.findActiveByEmail(shared).shouldBeEmpty()
    }

    @Test
    fun `active email lookup - a hire on hold sharing the address - is returned`() = runTest {
        // ON_HOLD is not terminal: a deferred start date does not release the address. This is what
        // catches a filter written as "DRAFT_COLLECTING only".
        val shared = anEmail("shared@example.com")
        employees.create(
            anEmployee(id = personId("EMP00004"), email = shared, packetStatus = PacketStatus.ON_HOLD),
        )

        employees.findActiveByEmail(shared) shouldHaveSize 1
    }

    @Test
    fun `active email lookup - an address stored in another case - resolves the same hire`() = runTest {
        // EmailAddress.of lower-cases, so this row cannot come from the application -- and unlike
        // `users`, `employees` carries no `email = lower(email)` check constraint, so a migration or
        // an import genuinely can write it. A missed duplicate here is how two hires share a
        // mailbox (SEC-11), which is exactly what the duplicate check exists to prevent.
        insertRawHire(id = "EMP00005", email = "Shared@Example.COM")

        employees.findActiveByEmail(anEmail("shared@example.com")) shouldHaveSize 1
    }

    @Test
    fun `active email lookup - two active hires sharing an address - are returned oldest first`() =
        runTest {
            // Found in review: the adapter declares an ORDER BY that no test reached, which is the
            // ERT-320 coincidence one layer over. Ids run opposite to creation order here, so
            // neither id order nor insertion order can produce a pass. It matters because SEC-11's
            // duplicate warning shows HR a list, and a list that reshuffles between two reads of
            // the same page reads as two different answers.
            val shared = anEmail("shared@example.com")
            employees.create(
                anEmployee(
                    id = personId("EMP00002"),
                    email = shared,
                    createdAt = FixedClock.DEFAULT.plus(Duration.ofDays(30)),
                ),
            )
            employees.create(anEmployee(id = personId("EMP00001"), email = shared))

            employees.findActiveByEmail(shared).map { it.id } shouldContainExactly
                listOf(personId("EMP00001"), personId("EMP00002"))
        }

    @Test
    fun `active email lookup - a different address - is not returned`() = runTest {
        employees.create(anEmployee(email = anEmail("someone.else@example.com")))

        employees.findActiveByEmail(anEmail("shared@example.com")).shouldBeEmpty()
    }

    // ── Anomaly flags ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `anomaly flags - two flags stored - round-trip and report retention frozen`() = runTest {
        // Both flags are EVIDENTIARY ones on purpose. E3 settled that retentionFrozen should read
        // AnomalyFlag.freezesRetention rather than `anomalyFlags.isNotEmpty()`, and ERT-734 owns
        // that change -- so choosing SHARED_EMAIL here would make this test fail the day it lands.
        val hire = anEmployee(
            anomalyFlags = setOf(AnomalyFlag.SUSPECTED_FRAUD, AnomalyFlag.ACCESS_ANOMALY),
        )

        employees.create(hire)

        val stored = employees.findById(hire.id)!!
        stored.anomalyFlags shouldBe setOf(AnomalyFlag.SUSPECTED_FRAUD, AnomalyFlag.ACCESS_ANOMALY)
        stored.retentionFrozen shouldBe true
    }

    @Test
    fun `anomaly flags - flags given out of order - are stored in one stable order`() = runTest {
        // Found in review: the encoding sorts by ordinal and nothing asserted it. A Set has no
        // order, so an unsorted join writes the same flags as different text on two saves, which
        // makes every column diff noise and this very assertion flaky.
        //
        // THREE flags, not two, and given in an order that is neither the sorted one nor its
        // reverse. Checked by breaking it: with two flags, replacing the sort with `reversed()`
        // produced the sorted order anyway and the assertion still passed -- the same class of
        // coincidence ERT-320 found in `sort_order`. Three flags leave no such accident available.
        val hire = anEmployee(
            anomalyFlags = setOf(
                AnomalyFlag.SUSPECTED_FRAUD,
                AnomalyFlag.REPEATED_REJECTIONS,
                AnomalyFlag.PIN_FAILURE_SUSPENSION,
            ),
        )

        employees.create(hire)

        strings("select anomaly_flags from employees where id = '${hire.id.value}'").single() shouldBe
            "REPEATED_REJECTIONS,PIN_FAILURE_SUSPENSION,SUSPECTED_FRAUD"
    }

    @Test
    fun `anomaly flags - no flags - store an empty string and read back an empty set`() = runTest {
        // The boundary the encoding gets wrong: `"".split(",")` is a list holding one empty string,
        // so a decoder without the blank filter returns a set containing garbage rather than an
        // empty one -- and retentionFrozen then reports true for every unflagged hire in the system.
        val hire = anEmployee(anomalyFlags = emptySet())

        employees.create(hire)

        val stored = employees.findById(hire.id)!!
        stored.anomalyFlags.shouldBeEmpty()
        stored.retentionFrozen shouldBe false
        strings("select anomaly_flags from employees where id = '${hire.id.value}'").single() shouldBe ""
    }

    @Test
    fun `anomaly flags - one flag - is stored as its enum name and nothing else`() = runTest {
        // Pins the column format rather than only the round trip. A round trip is satisfied by any
        // self-consistent encoding, including one no other reader can parse.
        val hire = anEmployee(anomalyFlags = setOf(AnomalyFlag.SHARED_EMAIL))

        employees.create(hire)

        strings("select anomaly_flags from employees where id = '${hire.id.value}'").single() shouldBe
            "SHARED_EMAIL"
    }

    @Test
    fun `anomaly flags - a name that is not a flag - is rejected as a corrupt row`() = runTest {
        insertRawHire(id = "EMP00006", email = "flagged@example.com", anomalyFlags = "NOT_A_FLAG")

        failureFrom { employees.findById(personId("EMP00006")) } shouldContainMessage "anomaly_flags"
    }

    // ── Attestation ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `attestation mapping - an unattested packet - round-trips as null`() = runTest {
        val hire = anEmployee(attestation = null)

        employees.create(hire)

        employees.findById(hire.id)?.attestation.shouldBeNull()
        strings(
            """
            select count(*) from employees
            where id = '${hire.id.value}'
              and attestation_version is null and attested_at is null and attested_ip is null
            """.trimIndent()
        ).single() shouldBe "1"
    }

    @Test
    fun `attestation mapping - an attested packet - round-trips all three fields`() = runTest {
        // Non-vacuity for the test above: a mapper that always returned null would pass it.
        val attestation = anAttestation(textVersion = "v7", attestedIp = "198.51.100.42")
        val hire = anEmployee(attestation = attestation, submittedAt = FixedClock.DEFAULT)

        employees.create(hire)

        employees.findById(hire.id)?.attestation shouldBe attestation
    }

    @Test
    fun `attestation mapping - a half-populated attestation - is rejected as a corrupt row`() = runTest {
        // No database CHECK ties the three columns together, so the mapper is the only thing
        // enforcing "all three or none". Reading this row as an unattested packet would be worse
        // than failing: it would discard the record that someone attested at all (PRD 7.2).
        insertRawHire(id = "EMP00007", email = "half@example.com", attestationVersion = "v1")

        failureFrom { employees.findById(personId("EMP00007")) } shouldContainMessage "half-populated"
    }

    // ── The requirement set ─────────────────────────────────────────────────────────────────────

    @Test
    fun `requirement set - the stored rows - report the progress arithmetic of the snapshot`() = runTest {
        employees.create(anEmployee())

        employees.saveRequirements(
            aRequirementSet(required = 4, approved = 1, underReview = 1, optional = 2).requirements,
        )

        val set = employees.requirementsOf(Fixtures.EMPLOYEE_ID)
        set.requirements shouldHaveSize 6
        set.total shouldBe 4
        set.approved shouldBe 1
        set.awaitingReview shouldBe 1
        set.submitted shouldBe 2
    }

    @Test
    fun `requirement set - requirements whose names sort against insertion order - are returned in name order`() =
        runTest {
            // Inserted in an order that is neither alphabetical nor the order asserted, and with ids
            // running the opposite way to the names -- so neither insertion order nor id order can
            // produce a pass. Without this, dropping the ORDER BY entirely fails nothing, which is
            // the coincidence ERT-320 found and ERT-350 hit again.
            employees.create(anEmployee())

            employees.saveRequirements(
                listOf(
                    anEmployeeRequirement(id = requirementId(0), nameSnapshot = "Medical certificate"),
                    anEmployeeRequirement(id = requirementId(1), nameSnapshot = "Birth certificate"),
                    anEmployeeRequirement(id = requirementId(2), nameSnapshot = "NBI clearance"),
                ),
            )

            employees.requirementsOf(Fixtures.EMPLOYEE_ID).requirements.map { it.nameSnapshot } shouldContainExactly
                listOf("Birth certificate", "Medical certificate", "NBI clearance")
        }

    @Test
    fun `requirement set - another hire's requirements - are not returned`() = runTest {
        val other = personId("EMP00008")
        employees.create(anEmployee())
        employees.create(anEmployee(id = other, email = anEmail("other@example.com")))

        employees.saveRequirements(aRequirementSet(required = 3).requirements)
        employees.saveRequirements(
            aRequirementSet(required = 2, employeeId = other, firstId = 3).requirements,
        )

        employees.requirementsOf(Fixtures.EMPLOYEE_ID).total shouldBe 3
        employees.requirementsOf(other).total shouldBe 2
    }

    @Test
    fun `requirement set - a requirement saved again - updates rather than inserting`() = runTest {
        // saveRequirements IS insert-or-update, unlike the employee itself: ERT-432 writes the
        // snapshot and the upload and review use cases move each status through this same call. An
        // EntityId draws from 62^12, so it needs no redraw and gets none.
        employees.create(anEmployee())
        val requirement = anEmployeeRequirement(status = RequirementStatus.PENDING)
        employees.saveRequirements(listOf(requirement))

        employees.saveRequirements(
            listOf(requirement.copy(status = RequirementStatus.APPROVED, rejectionCount = 2)),
        )

        countOf("employee_requirements") shouldBe 1
        val stored = employees.requirementsOf(Fixtures.EMPLOYEE_ID).requirements.single()
        stored.status shouldBe RequirementStatus.APPROVED
        stored.rejectionCount shouldBe 2
    }

    @Test
    fun `requirement set - a hire with no requirements - reports zero rather than failing`() = runTest {
        employees.create(anEmployee())

        employees.requirementsOf(Fixtures.EMPLOYEE_ID).total shouldBe 0
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A hire written past [com.pgsystem.employee.requirement.tracker.domain.model.Employee] and its
     * mapper, for the rows this application cannot produce but another writer can.
     */
    private suspend fun insertRawHire(
        id: String,
        email: String,
        anomalyFlags: String = "",
        attestationVersion: String? = null,
    ) = execute(
        """
        insert into employees (id, first_name, last_name, department_id, "position",
                               employment_type_id, email, packet_status, submitted_by_hr,
                               attestation_version, anomaly_flags, created_at, created_by)
        values ('$id', 'Jose', 'Dela Cruz', '${Fixtures.DEPARTMENT_ID.value}', 'Store Associate',
                '${Fixtures.EMPLOYMENT_TYPE_ID.value}', '$email', 'DRAFT_COLLECTING', false,
                ${attestationVersion?.let { "'$it'" } ?: "null"}, '$anomalyFlags',
                current_timestamp, '${Fixtures.HR_USER_ID.value}')
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
