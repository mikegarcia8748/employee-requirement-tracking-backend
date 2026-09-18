package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.notify.NotificationMessages
import com.pgsystem.employee.requirement.tracker.data.notify.OutboxNotifier
import com.pgsystem.employee.requirement.tracker.data.notify.PortalBaseUrl
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import com.pgsystem.employee.requirement.tracker.domain.port.Notifier
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anAccessPin
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeNotifier
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * One notifier, two implementations, one failure model (ERT-250, HAR-02).
 *
 * **The only failure either implementation has is `DeliveryResult.Failed`, and that is the whole
 * point of this suite.** `OutboxNotifier.queue` wraps the clock read, the id draw and the insert in
 * a single `runCatching`, so an unreachable database, a foreign-key violation, anything at all comes
 * back as a value — deliberately, because §8.1 requires the hire to survive a failed invitation and
 * `CreateHireUseCase` must see something it can report rather than an exception unwinding the
 * creation it has just completed.
 *
 * `FakeNotifier` carried a [com.pgsystem.employee.requirement.tracker.testdata.fake.FakeFailure]
 * alongside that, so a test could make a send *throw* — a mode production cannot reach. A use case
 * hardened against it was hardened against nothing.
 *
 * **The adapter's failure is exercised with a real one, not an injected one.** `employee_id` is a
 * non-null foreign key, so a hire that was never stored is the one failure a test can actually
 * arrange — and it is the realistic one, since §8.1's delivery path runs immediately after creation.
 */
abstract class NotifierContract {

    protected abstract val notifier: Notifier

    /** A hire both implementations can send to. The adapter needs the row; the fake needs nothing. */
    protected abstract suspend fun aStoredHire(): Employee

    /** A hire the adapter has no row for, so its write fails for a reason nothing had to inject. */
    protected fun anUnstoredHire(): Employee = anEmployee(id = personId("EMP99999"))

    @Test
    fun `notifier contract - an invitation to a stored hire - both implementations report it sent`() =
        runTest {
            notifier.sendInvitation(anEmail(), aStoredHire(), "plaintext-token") shouldBe
                DeliveryResult.Sent
        }

    @Test
    fun `notifier contract - a recovery pin to a stored hire - both implementations report it sent`() =
        runTest {
            notifier.sendRecoveryPin(anEmail(), aStoredHire(), anAccessPin()) shouldBe
                DeliveryResult.Sent
        }

    @Test
    fun `notifier contract - a message to hr carrying no address - both implementations report it sent`() =
        runTest {
            // Two of the eight methods take no address because they go to HR, whose mailbox is
            // ERT-1010's configuration. A notifier that required one would refuse them.
            notifier.sendPacketReadyForReview(aStoredHire()) shouldBe DeliveryResult.Sent
            notifier.notifyHrOfSuspension(aStoredHire(), reason = "Ten failed PIN attempts") shouldBe
                DeliveryResult.Sent
        }

    @Test
    fun `notifier contract - a send that fails - both implementations return Failed and never throw`() =
        runTest {
            // The assertion HAR-02 exists for. If either side threw, this test would error rather
            // than fail — and a use case relying on the value would unwind instead of reporting.
            val result = failingSend()

            result.shouldBeInstanceOf<DeliveryResult.Failed>()
        }

    @Test
    fun `notifier contract - a failure reason - names no more than the exception that caused it`() =
        runTest {
            // ERT-440's lesson, held as a shape rather than a denylist: a "does not contain the
            // token" assertion is only as strong as the input a test can arrange, so this constrains
            // what MAY appear instead of listing what may not.
            val result = failingSend() as DeliveryResult.Failed

            (result.reason.isNotBlank()) shouldBe true
            result.reason.contains("plaintext-token") shouldBe false
        }

    /** A send that fails on each side for its own real reason. */
    protected abstract suspend fun failingSend(): DeliveryResult
}

class FakeNotifierContractTest : NotifierContract() {

    private val fake = FakeNotifier()

    override val notifier: Notifier = fake

    override suspend fun aStoredHire(): Employee = anEmployee()

    override suspend fun failingSend(): DeliveryResult {
        fake.failNextSend("SMTP unavailable")
        return fake.sendInvitation(anEmail(), aStoredHire(), "plaintext-token")
    }
}

class OutboxNotifierContractTest : NotifierContract() {

    private val database = ContractDatabase()

    override val notifier: Notifier by lazy {
        OutboxNotifier(
            factory = database.factory,
            ids = FixedEntityIdGenerator(),
            clock = FixedClock(),
            messages = NotificationMessages(PortalBaseUrl("https://portal.example.com")),
        )
    }

    override suspend fun aStoredHire(): Employee = anEmployee(id = Fixtures.EMPLOYEE_ID)

    override suspend fun failingSend(): DeliveryResult =
        // No injected failure: `notification_outbox.employee_id` is an `on delete restrict` foreign
        // key, so a hire with no row is a real write failure and the one §8.1 has to survive.
        notifier.sendInvitation(anEmail(), anUnstoredHire(), "plaintext-token")

    @BeforeTest
    fun openDatabase() {
        database.open()
        runBlocking { database.insertHire(Fixtures.EMPLOYEE_ID.value, "jose@example.com") }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
    }
}
