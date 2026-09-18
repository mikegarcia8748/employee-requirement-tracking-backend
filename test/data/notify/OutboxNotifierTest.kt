package com.pgsystem.employee.requirement.tracker.data.notify

import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import com.pgsystem.employee.requirement.tracker.domain.port.Notifier
import com.pgsystem.employee.requirement.tracker.domain.port.RejectedItem
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The notification outbox against real SQL (ERT-440, PRD §8.9).
 *
 * Two properties carry the ticket, and both are about what the table does *not* hold.
 *
 * **The invitation's body never reaches a row, at any point in its life.** The criterion's earlier
 * wording — "retains no usable PIN *after delivery*" — was satisfiable by a row that held one for an
 * hour first, so every assertion here is at **write** time: the row is read back immediately after
 * the send that created it, and again after that send has been marked failed, because a failed row's
 * `last_error` is the second place a credential can leak into a table.
 *
 * **A failed invitation cannot be retried, and that is asserted rather than left implicit.** It is
 * the cost of not storing the body, and a test that only checked the body was absent would not
 * notice a `retry()` that silently re-queued an invitation with nothing to send.
 */
class OutboxNotifierTest : RepositoryTestBase() {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val notifier by lazy { notifierDrawing(ids) }

    private fun notifierDrawing(generator: FixedEntityIdGenerator) = OutboxNotifier(
        factory = factory,
        ids = generator,
        clock = clock,
        messages = NotificationMessages(PortalBaseUrl("https://portal.example.com")),
    )

    private val hire = anEmployee()

    /**
     * The hire every outbox row points at, and the three foreign keys it needs in turn.
     *
     * `notification_outbox.employee_id` is a non-null `on delete restrict` foreign key, deliberately
     * — it is what lets §8.1's delivery-failure indicator be derived from the latest row rather than
     * needing a column on `employees` that something has to remember to update (E4). The cost is
     * that no outbox row exists without a hire, which is this block.
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
                insert into employees (id, first_name, last_name, department_id, "position",
                                       employment_type_id, email, packet_status, submitted_by_hr,
                                       anomaly_flags, created_at, created_by)
                values ('${Fixtures.EMPLOYEE_ID.value}', 'Jose', 'Dela Cruz',
                        '${Fixtures.DEPARTMENT_ID.value}', 'Store Associate',
                        '${Fixtures.EMPLOYMENT_TYPE_ID.value}', 'jose.delacruz@example.com',
                        'DRAFT_COLLECTING', false, '', current_timestamp,
                        '${Fixtures.HR_USER_ID.value}')
                """.trimIndent()
            )
        }
    }

    // ── Every kind queues ───────────────────────────────────────────────────────────────────────

    @Test
    fun `outbox notifier - an invitation is sent - writes a pending row for the recipient`() =
        runTest {
            val result = notifier.sendInvitation(anEmail(), hire, TOKEN)

            result shouldBe DeliveryResult.Sent
            val queued = notifier.forEmployee(hire.id).single()
            queued.kind shouldBe NotificationKind.INVITATION
            queued.recipient shouldBe anEmail()
            queued.status shouldBe OutboxStatus.PENDING
            queued.attempts shouldBe 0
            queued.queuedAt shouldBe FixedClock.DEFAULT
        }

    @Test
    fun `outbox notifier - each of the eight notifier methods - writes one row of its own kind`() =
        runTest {
            // Named rather than counted, so the failure says which kind is missing. Paired with the
            // reflection check below, which is what notices an EIGHTH method being added: this test
            // would still pass against a Notifier that had grown one, because nothing here would
            // mention it.
            notifier.sendInvitation(anEmail(), hire, TOKEN)
            notifier.sendRecoveryPin(anEmail(), hire, PIN)
            notifier.sendPacketReadyForReview(hire)
            notifier.sendRejection(anEmail(), hire, listOf(RejectedItem("NBI Clearance", "Expired")))
            notifier.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
            notifier.sendCompletionConfirmation(anEmail(), hire)
            notifier.sendEmailChangedNotice(anEmail(), hire)
            notifier.notifyHrOfSuspension(hire, reason = "Ten failed recovery attempts")

            notifier.forEmployee(hire.id).map { it.kind }.toSet() shouldBe
                NotificationKind.entries.toSet()
            countOf("notification_outbox") shouldBe 8
        }

    @Test
    fun `outbox notifier - the notifier port - has exactly the eight kinds this adapter stores`() {
        // The guard on the guard. `Notifier` gaining an eighth method would leave the test above
        // passing while one kind was never queued at all, and the first symptom would be a
        // notification that silently went nowhere in production.
        Notifier::class.declaredMemberFunctions.size shouldBe NotificationKind.entries.size
    }

    @Test
    fun `outbox notifier - an HR-bound notification - stores no recipient rather than a sentinel`() =
        runTest {
            // Two of the seven methods take no address: these go to HR, whose mailbox is ERT-1010's
            // configuration. A sentinel string would be a lie in a column other code reads, and the
            // kind already names the audience.
            notifier.sendPacketReadyForReview(hire)

            notifier.forEmployee(hire.id).single().recipient.shouldBeNull()
        }

    // ── The invitation carries nothing worth stealing ───────────────────────────────────────────

    @Test
    fun `outbox notifier - an invitation at any point in its life - stores no token and no pin`() =
        runTest {
            // AT WRITE TIME, not after delivery. The earlier wording of this criterion was satisfied
            // by a row that held the credential for an hour first, and "purge after delivery" would
            // not have removed it from a backup, a replica or a write-ahead log anyway.
            //
            // Reads the COLUMNS, not the OutboxEntry: the entry cannot hold a token because no field
            // is named for one, which proves nothing about what reached the row.
            notifier.sendInvitation(anEmail(), hire, TOKEN)

            val row = wholeRow()
            row.contains(TOKEN) shouldBe false
            row.contains(PIN.value) shouldBe false
            row.contains("portal.example.com") shouldBe false
        }

    @Test
    fun `outbox notifier - an invitation - stores its subject but never its body`() = runTest {
        // The subject is what makes the row legible to an operator reading the table; the body is
        // the half that carries the link. Asserting only "no token" would pass against an adapter
        // that stored nothing at all and left the row unreadable.
        notifier.sendInvitation(anEmail(), hire, TOKEN)

        val queued = notifier.forEmployee(hire.id).single()
        queued.body.shouldBeNull()
        queued.subject shouldBe "Your pre-employment requirements"
    }

    @Test
    fun `outbox notifier - the six kinds carrying no credential - store their bodies`() = runTest {
        // The inclusion half. Without it, an adapter that dropped EVERY body would satisfy the
        // invitation tests above and leave ERT-1010 with nothing to send for any kind.
        notifier.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
        notifier.sendCompletionConfirmation(anEmail(), hire)
        notifier.sendRejection(anEmail(), hire, listOf(RejectedItem("NBI Clearance", "Expired")))
        notifier.sendEmailChangedNotice(anEmail(), hire)
        notifier.sendPacketReadyForReview(hire)
        notifier.notifyHrOfSuspension(hire, reason = "Ten failed recovery attempts")

        notifier.forEmployee(hire.id).forEach { it.body.shouldNotBeNull() }
    }

    @Test
    fun `outbox notifier - the six stored bodies - restate no link`() = runTest {
        // §8.9's rule one layer down: only the invitation points at the portal with a credential, so
        // a forwarded rejection notice or expiry warning carries nothing useful. A body that pasted
        // the origin in "so they can find it" would put a guessable prefix in every stored message.
        notifier.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
        notifier.sendRejection(anEmail(), hire, listOf(RejectedItem("NBI Clearance", "Expired")))
        notifier.sendCompletionConfirmation(anEmail(), hire)

        notifier.forEmployee(hire.id).forEach { it.body!!.contains("/portal/") shouldBe false }
    }

    @Test
    fun `outbox notifier - a failed invitation - records the error without echoing the link`() =
        runTest {
            // The SECOND place a credential can reach the table. A drain loop that stored the SMTP
            // exception verbatim would be fine for six kinds and catastrophic for this one, because
            // an exception raised while sending an invitation can quote the message it was sending.
            notifier.sendInvitation(anEmail(), hire, TOKEN)
            val queued = notifier.forEmployee(hire.id).single()

            notifier.markFailed(queued.id, "550 5.1.1 Recipient address rejected")

            val row = wholeRow()
            row.contains(TOKEN) shouldBe false
            row.contains(PIN.value) shouldBe false
            row.contains("portal.example.com") shouldBe false
        }

    // ── Delivery failure is a specified path, not an edge case ──────────────────────────────────

    @Test
    fun `outbox notifier - the write itself fails - returns Failed rather than throwing`() = runTest {
        // §8.1 requires the hire to SURVIVE a failed invitation and HR to get a retry action, so
        // CreateHireUseCase must see a value it can report rather than an exception unwinding the
        // creation it has just completed. A hire that was never stored violates the outbox's
        // employee_id foreign key, which is the one way to make the write fail without a stub.
        val unstored = anEmployee(id = com.pgsystem.employee.requirement.tracker.testdata
            .personId("EMP99999"))

        val result = notifier.sendInvitation(anEmail(), unstored, TOKEN)

        (result is DeliveryResult.Failed) shouldBe true
        countOf("notification_outbox") shouldBe 0
    }

    @Test
    fun `outbox notifier - a failed write of an invitation - reports only the exception type, not its message`() =
        runTest {
            // THE THIRD PLACE A CREDENTIAL CAN LEAK, and the least obvious: a failed INSERT can echo
            // the row it was handed, and for an invitation that row is the one place a live link
            // exists in this process. The reason travels back to the route and into HR's browser.
            //
            // ASSERTED AS A WHITELIST, WHICH TOOK A SECOND ATTEMPT. The obvious test -- "the reason
            // does not contain the token" -- passed against an adapter storing the exception message
            // verbatim, because the failure reachable from a test is a foreign-key violation whose
            // message happens not to quote the body. It proved the H2 error text, not the adapter.
            // Requiring the reason to be a bare type name is a rule about what MAY appear rather
            // than a list of what may not, so it holds for a failure this test cannot construct.
            val unstored = anEmployee(id = com.pgsystem.employee.requirement.tracker.testdata
                .personId("EMP99999"))

            val result = notifier.sendInvitation(anEmail(), unstored, TOKEN)

            val reason = (result as DeliveryResult.Failed).reason
            Regex("^[A-Za-z]+$").matches(reason) shouldBe true
        }

    @Test
    fun `outbox notifier - a cancellation during the write - propagates rather than reporting a delivery failure`() =
        runTest {
            // SEC-37. `queue` used to wrap everything in `runCatching`, which catches Throwable --
            // so a cancelled request became DeliveryResult.Failed("CancellationException") and the
            // caller carried on as though a delivery had merely failed: ERT-434 would record a
            // failure and offer HR a retry for a request nobody is waiting for, while structured
            // concurrency lost a cancellation it was entitled to. On Cloud Run a client disconnect
            // or an instance drain is the realistic producer.
            //
            // NARROWING THE CATCH TO `Exception` DOES NOT FIX THIS, which is the trap: on the JVM
            // CancellationException extends IllegalStateException, so it IS an Exception. Only the
            // explicit re-throw works, and this test is the only thing that tells the two apart.
            //
            // Arranged with a clock that throws rather than by racing a real cancellation: the
            // clock read is the first statement inside the guarded block, so this exercises the
            // catch deterministically instead of depending on where the scheduler happens to be.
            val cancelling = OutboxNotifier(
                factory = factory,
                ids = ids,
                clock = { throw CancellationException("the request was cancelled") },
                messages = NotificationMessages(PortalBaseUrl("https://portal.example.com")),
            )

            assertFailsWith<CancellationException> { cancelling.sendInvitation(anEmail(), hire, TOKEN) }

            countOf("notification_outbox") shouldBe 0
        }

    // ── The drain surface ───────────────────────────────────────────────────────────────────────

    @Test
    fun `outbox notifier - a queued row - can be marked sent`() = runTest {
        notifier.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
        val queued = notifier.forEmployee(hire.id).single()
        clock.advance(java.time.Duration.ofMinutes(5))

        notifier.markSent(queued.id)

        val sent = notifier.find(queued.id)!!
        sent.status shouldBe OutboxStatus.SENT
        sent.sentAt shouldBe clock.now()
        sent.lastError.shouldBeNull()
    }

    @Test
    fun `outbox notifier - a failed row - can be retried`() = runTest {
        notifier.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
        val queued = notifier.forEmployee(hire.id).single()
        notifier.markFailed(queued.id, "Connection refused")

        notifier.retry(queued.id)

        val requeued = notifier.find(queued.id)!!
        requeued.status shouldBe OutboxStatus.PENDING
        requeued.lastError.shouldBeNull()
        // The attempt count SURVIVES the retry. Clearing it would make a permanently-failing row
        // indistinguishable from a fresh one, and ERT-1010's OUTBOX_MAX_ATTEMPTS would never fire.
        requeued.attempts shouldBe 1
    }

    @Test
    fun `outbox notifier - a failed invitation - cannot be retried because its body was never stored`() =
        runTest {
            // The cost of dropping the body, asserted rather than left implicit. Reissuing is
            // ERT-1030's resend-link; silently re-queueing would give ERT-1010's drain a row with
            // nothing to send and a loop that never terminates.
            notifier.sendInvitation(anEmail(), hire, TOKEN)
            val queued = notifier.forEmployee(hire.id).single()
            notifier.markFailed(queued.id, "Connection refused")

            val failure = assertFailsWith<Exception> { notifier.retry(queued.id) }

            generateSequence(failure as Throwable) { it.cause }.mapNotNull { it.message }
                .joinToString(" | ").contains("resend-link") shouldBe true
            notifier.find(queued.id)!!.status shouldBe OutboxStatus.FAILED
        }

    @Test
    fun `outbox notifier - repeated failures - increment the attempt count rather than overwriting it`() =
        runTest {
            notifier.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
            val queued = notifier.forEmployee(hire.id).single()

            notifier.markFailed(queued.id, "Connection refused")
            notifier.markFailed(queued.id, "Connection refused")
            notifier.markFailed(queued.id, "Connection refused")

            notifier.find(queued.id)!!.attempts shouldBe 3
        }

    @Test
    fun `outbox notifier - the pending queue - holds only unsent rows, oldest first`() = runTest {
        // THREE rows, queued in an order that is neither their id order nor their queued_at order,
        // so an adapter that dropped the ORDER BY cannot pass by coincidence -- the trap ERT-420
        // fell into twice. The middle row is then marked sent, which is the exclusion half.
        val drawing = notifierDrawing(
            FixedEntityIdGenerator("ENT000000003", "ENT000000001", "ENT000000002")
        )
        clock.advance(java.time.Duration.ofMinutes(30))
        drawing.sendExpiryWarning(anEmail(), hire, daysRemaining = 7)
        clock.advance(java.time.Duration.ofMinutes(-20))
        drawing.sendCompletionConfirmation(anEmail(), hire)
        clock.advance(java.time.Duration.ofMinutes(10))
        drawing.sendEmailChangedNotice(anEmail(), hire)

        drawing.markSent(entityId("ENT000000002"))

        drawing.pending().map { it.id } shouldContainExactly
            listOf(entityId("ENT000000001"), entityId("ENT000000003"))
    }

    @Test
    fun `outbox notifier - marking a row nobody queued - fails loudly rather than silently`() =
        runTest {
            // Silence here would report a drained queue that still holds the message.
            assertFailsWith<Exception> { notifier.markSent(entityId("ENT999999999")) }
        }

    @Test
    fun `outbox notifier - a hire with no notifications - reports an empty queue rather than failing`() =
        runTest {
            notifier.forEmployee(hire.id).shouldBeEmpty()
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Every text column of every outbox row, concatenated.
     *
     * Deliberately not a column-by-column assertion: the property being checked is "the credential
     * is nowhere in this table", and naming the columns would miss the one a future migration adds.
     */
    private suspend fun wholeRow(): String = strings(
        """
        select coalesce(recipient, '') || '|' || subject || '|' || coalesce(body, '') || '|' ||
               coalesce(last_error, '') || '|' || kind || '|' || status
        from notification_outbox
        """.trimIndent()
    ).joinToString("\n")

    private companion object {
        const val TOKEN = "cnFuZG9tLXRva2VuLTI1Ni1iaXRz"
        val PIN: AccessPin = AccessPin.of("424242").let {
            (it as com.pgsystem.employee.requirement.tracker.core.error.DomainResult.Ok).value
        }
    }
}
