package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.repository.ExposedEmployeeRepository
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.anEmployeeRequirement
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeEmployeeRepository
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * One hire store, two implementations, one set of rules (ERT-250).
 *
 * **(a) `FakeEmployeeRepository.findActiveByEmail` returned `LinkedHashMap` insertion order.** The
 * adapter has ordered by `created_at ASC, id ASC` since ERT-410, with a test added in review because
 * SEC-11's duplicate list must not reshuffle between two reads of the same page. `CreateHireUseCase`
 * reads only `duplicates.isEmpty()` today, so the divergence is latent — and it becomes live the
 * moment ERT-450 renders that list to HR, which is the next ticket but one.
 *
 * The ordering arrangement follows ERT-432's rule rather than ERT-320's accident: three hires whose
 * creation order, id order and insertion order each name a different sequence.
 */
abstract class EmployeeRepositoryContract {

    protected abstract val hires: EmployeeRepository

    /** Arrange through each side's own door — the fake's seed helper, the adapter's insert. */
    protected abstract suspend fun given(vararg employee: Employee)

    protected abstract suspend fun givenRequirements(vararg requirement: EmployeeRequirement)

    /** A template id the adapter's foreign key will accept. The V2 seed holds `c00000000001`..`14`. */
    protected fun seededTemplate(index: Int) = entityId("c0000000000$index")

    // ── findActiveByEmail ───────────────────────────────────────────────────────────────────────

    @Test
    fun `employee contract - two active hires on one address - both implementations order by creation then id`() =
        runTest {
            // The oldest carries the MIDDLE id and is seeded SECOND, so id order and insertion order
            // each name a different first row than `created_at ASC` does.
            given(
                hireAt(id = "EMP00009", minutesOld = 10),
                hireAt(id = "EMP00005", minutesOld = 30),
                hireAt(id = "EMP00001", minutesOld = 0),
            )

            hires.findActiveByEmail(anEmail("shared@example.com")).map { it.id.value } shouldBe
                listOf("EMP00005", "EMP00009", "EMP00001")
        }

    @Test
    fun `employee contract - two hires created in the same instant - both implementations break the tie by id`() =
        runTest {
            // Every builder creates at FixedClock.DEFAULT, so this is the harness's default case
            // rather than an edge one. Seeded with the higher id first.
            given(
                hireAt(id = "EMP00009", minutesOld = 0),
                hireAt(id = "EMP00002", minutesOld = 0),
            )

            hires.findActiveByEmail(anEmail("shared@example.com")).map { it.id.value } shouldBe
                listOf("EMP00002", "EMP00009")
        }

    @Test
    fun `employee contract - a completed hire on the address - both implementations exclude it`() =
        runTest {
            // "Active" is read off PacketStatus.isTerminal rather than by listing statuses, on both
            // sides. Paired with an active hire so a filter returning nothing cannot satisfy this.
            given(
                hireAt(id = "EMP00001", minutesOld = 0),
                hireAt(id = "EMP00002", minutesOld = 0).copy(packetStatus = PacketStatus.COMPLETE),
            )

            hires.findActiveByEmail(anEmail("shared@example.com")).map { it.id.value } shouldBe
                listOf("EMP00001")
        }

    @Test
    fun `employee contract - an address nobody holds - both implementations answer empty`() = runTest {
        given(hireAt(id = "EMP00001", minutesOld = 0))

        hires.findActiveByEmail(anEmail("shared@example.com")).size shouldBe 1
        hires.findActiveByEmail(anEmail("nobody@example.com")).shouldBeEmpty()
    }

    // ── findById ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `employee contract - an id nobody holds - both implementations answer null`() = runTest {
        given(hireAt(id = "EMP00001", minutesOld = 0))

        (hires.findById(personId("EMP00001")) != null) shouldBe true
        hires.findById(personId("EMP99999")).shouldBeNull()
    }

    // ── create and save ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `employee contract - a create on a taken id - both implementations redraw rather than overwrite`() =
        runTest {
            // The reason the port splits create from save at all: an id that already exists reads
            // as "update this row" to the house save, so a new hire drawing a taken id would
            // silently overwrite the hire holding it. Both sides redraw from the injected generator.
            given(hireAt(id = "EMP00001", minutesOld = 0))

            val stored = hires.create(
                anEmployee(id = personId("EMP00001"), email = anEmail("second@example.com"))
            )

            // The return value is load-bearing: it is the hire AS STORED, which may carry a
            // different id than the argument.
            stored.id.value shouldBe "EMP00077"
            hires.findById(personId("EMP00001"))?.email?.value shouldBe "shared@example.com"
            hires.findById(personId("EMP00077"))?.email?.value shouldBe "second@example.com"
        }

    @Test
    fun `employee contract - a save on a hire that was never stored - both implementations refuse it`() =
        runTest {
            assertFails { hires.save(anEmployee(id = personId("EMP00042"))) }
        }

    @Test
    fun `employee contract - a save on a stored hire - both implementations update it`() = runTest {
        given(hireAt(id = "EMP00001", minutesOld = 0))

        hires.save(
            hireAt(id = "EMP00001", minutesOld = 0).copy(packetStatus = PacketStatus.UNDER_REVIEW)
        )

        hires.findById(personId("EMP00001"))?.packetStatus shouldBe PacketStatus.UNDER_REVIEW
    }

    // ── requirementsOf ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `employee contract - a snapshotted set - both implementations order by sort order then name then id`() =
        runTest {
            given(hireAt(id = "EMP00001", minutesOld = 0))

            // Sort order decides first; where it ties, the name; where that ties, the id. Seeded in
            // an order that is none of the three, and every key names a different sequence.
            givenRequirements(
                requirement(id = "REQ000000004", template = 4, sortOrder = 2, name = "Birth Certificate"),
                requirement(id = "REQ000000001", template = 1, sortOrder = 1, name = "NBI Clearance"),
                requirement(id = "REQ000000003", template = 3, sortOrder = 2, name = "Aadhaar Card"),
                requirement(id = "REQ000000002", template = 2, sortOrder = 2, name = "Aadhaar Card"),
            )

            hires.requirementsOf(personId("EMP00001")).requirements.map { it.id.value } shouldBe
                listOf("REQ000000001", "REQ000000002", "REQ000000003", "REQ000000004")
        }

    @Test
    fun `employee contract - a hire with no requirements - both implementations answer an empty set`() =
        runTest {
            given(hireAt(id = "EMP00001", minutesOld = 0))
            givenRequirements(requirement(id = "REQ000000001", template = 1, sortOrder = 1, name = "NBI"))

            hires.requirementsOf(personId("EMP00001")).requirements.size shouldBe 1
            hires.requirementsOf(personId("EMP00002")).requirements.shouldBeEmpty()
        }

    private fun hireAt(id: String, minutesOld: Long): Employee = anEmployee(
        id = personId(id),
        email = anEmail("shared@example.com"),
        createdAt = FixedClock.DEFAULT.minus(Duration.ofMinutes(minutesOld)),
    )

    private fun requirement(id: String, template: Int, sortOrder: Int, name: String) =
        anEmployeeRequirement(
            id = entityId(id),
            employeeId = personId("EMP00001"),
            templateId = seededTemplate(template),
            nameSnapshot = name,
            sortOrderSnapshot = sortOrder,
        )
}

class FakeEmployeeRepositoryContractTest : EmployeeRepositoryContract() {

    private val fake = FakeEmployeeRepository(ids = FixedPersonIdGenerator("EMP00077"))

    override val hires: EmployeeRepository = fake

    override suspend fun given(vararg employee: Employee) {
        fake.given(*employee)
    }

    override suspend fun givenRequirements(vararg requirement: EmployeeRequirement) {
        fake.givenRequirements(*requirement)
    }
}

class ExposedEmployeeRepositoryContractTest : EmployeeRepositoryContract() {

    private val database = ContractDatabase()

    override val hires: EmployeeRepository by lazy {
        ExposedEmployeeRepository(database.factory, FixedPersonIdGenerator("EMP00077"))
    }

    override suspend fun given(vararg employee: Employee) {
        employee.forEach { hires.create(it) }
    }

    override suspend fun givenRequirements(vararg requirement: EmployeeRequirement) {
        hires.saveRequirements(requirement.toList())
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
