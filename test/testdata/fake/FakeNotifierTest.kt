package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import com.pgsystem.employee.requirement.tracker.domain.port.RejectedItem
import com.pgsystem.employee.requirement.tracker.testdata.anAccessPin
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anEmployee
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The notifier fake against the rules it exists to make testable.
 *
 * Delivery failure is a specified path in PRD 8.1 — the hire is created regardless and HR gets a
 * retry action — so a notifier that cannot fail leaves half of that ticket unwritable.
 */
class FakeNotifierTest {

    @Test
    fun `fake notifier - a send is configured to fail - returns Failed and records the attempt`() = runTest {
        // Both halves in one assertion, because a fake that returned Failed and then dropped the
        // attempt would make "HR gets a retry action" impossible to test.
        val notifier = FakeNotifier().failNextSend(reason = "mailbox full")

        val result = notifier.sendInvitation(anEmail(), anEmployee(), "the-token")

        result shouldBe DeliveryResult.Failed("mailbox full")
        notifier.attempts shouldHaveSize 1
        notifier.failed shouldHaveSize 1
        notifier.delivered.shouldBeEmpty()
    }

    @Test
    fun `fake notifier - only the next send is configured to fail - the send after it succeeds`() = runTest {
        // The one-shot is what lets a test assert recovery. A sticky failure would prove only that
        // the use case gave up.
        val notifier = FakeNotifier().failNextSend()

        val first = notifier.sendInvitation(anEmail(), anEmployee(), "t1")
        val second = notifier.sendInvitation(anEmail(), anEmployee(), "t2")

        (first is DeliveryResult.Failed) shouldBe true
        second shouldBe DeliveryResult.Sent
        notifier.attempts shouldHaveSize 2
    }

    @Test
    fun `fake notifier - two sends configured to fail - both fail and the third succeeds`() = runTest {
        // The KDoc says failNextSend queues. An implementation that overwrote instead would fail
        // only once, and a test about a retry that gives up after two attempts would pass wrongly.
        val notifier = FakeNotifier().failNextSend("first").failNextSend("second")

        val results = List(3) { notifier.sendCompletionConfirmation(anEmail(), anEmployee()) }

        results[0] shouldBe DeliveryResult.Failed("first")
        results[1] shouldBe DeliveryResult.Failed("second")
        results[2] shouldBe DeliveryResult.Sent
    }

    @Test
    fun `fake notifier - every send is configured to fail - none is delivered`() = runTest {
        val notifier = FakeNotifier().failEverySend()

        repeat(3) { notifier.sendCompletionConfirmation(anEmail(), anEmployee()) }

        notifier.delivered.shouldBeEmpty()
        notifier.failed shouldHaveSize 3
    }

    @Test
    fun `fake notifier - sends stop being configured to fail - delivery resumes`() = runTest {
        val notifier = FakeNotifier().failEverySend()
        notifier.sendCompletionConfirmation(anEmail(), anEmployee())

        notifier.stopFailingSends()
        val afterwards = notifier.sendCompletionConfirmation(anEmail(), anEmployee())

        afterwards shouldBe DeliveryResult.Sent
    }

    @Test
    fun `fake notifier - an invitation is sent - records the address and token`() = runTest {
        // The invitation is the only place the plaintext token exists. Capturing it is what lets a
        // later test present it to the portal exactly as the employee would.
        val notifier = FakeNotifier()
        val to = anEmail("maria.santos@example.com")

        notifier.sendInvitation(to, anEmployee(), "the-invited-token")

        val invitation = notifier.invitationTo(to)
        invitation.shouldNotBeNull()
        invitation.to shouldBe to
        invitation.linkToken shouldBe "the-invited-token"
    }

    @Test
    fun `fake notifier - a recovery pin is sent - records the address and pin`() = runTest {
        val notifier = FakeNotifier()
        val to = anEmail("maria.santos@example.com")

        notifier.sendRecoveryPin(to, anEmployee(), anAccessPin("424242"))

        val recovery = notifier.sentOfType<FakeNotifier.Sent.RecoveryPin>().single()
        recovery.to shouldBe to
        recovery.pin shouldBe anAccessPin("424242")
    }

    @Test
    fun `fake notifier - a delivered invitation - is findable by address in one line`() = runTest {
        val notifier = FakeNotifier()
        val to = anEmail("maria.santos@example.com")

        notifier.sendInvitation(to, anEmployee(), "t")

        notifier.sentInvitationTo(to) shouldBe true
        notifier.sentInvitationTo(anEmail("someone.else@example.com")) shouldBe false
    }

    @Test
    fun `fake notifier - an invitation that failed to deliver - does not count as sent to the address`() = runTest {
        // sentInvitationTo reads delivered sends only. A failed send that answered true here would
        // make "the hire survived but the invitation did not go out" indistinguishable from success.
        val notifier = FakeNotifier().failNextSend()
        val to = anEmail()

        notifier.sendInvitation(to, anEmployee(), "t")

        notifier.sentInvitationTo(to) shouldBe false
        notifier.invitations shouldHaveSize 1
    }

    @Test
    fun `fake notifier - a rejection notice - is recorded as a type that cannot carry a pin`() = runTest {
        // The structural half of PRD 8.9. Notifier.sendRejection takes no AccessPin, and Sent
        // mirrors that: there is nowhere on Sent.Rejection for a credential to have been recorded,
        // so a forwarded rejection notice carries nothing useful by construction.
        val notifier = FakeNotifier()

        notifier.sendRejection(anEmail(), anEmployee(), listOf(RejectedItem("NBI Clearance", "Illegible")))

        notifier.sentOfType<FakeNotifier.Sent.Rejection>() shouldHaveSize 1
        notifier.invitations.shouldBeEmpty()
    }

    @Test
    fun `fake notifier - every notification kind - is recorded under its own type`() = runTest {
        val notifier = FakeNotifier()
        val employee = anEmployee()

        notifier.sendInvitation(anEmail(), employee, "t")
        notifier.sendRecoveryPin(anEmail(), employee, anAccessPin())
        notifier.sendPacketReadyForReview(employee)
        notifier.sendRejection(anEmail(), employee, emptyList())
        notifier.sendExpiryWarning(anEmail(), employee, daysRemaining = 7)
        notifier.sendCompletionConfirmation(anEmail(), employee)
        notifier.sendEmailChangedNotice(anEmail(), employee)
        notifier.notifyHrOfSuspension(employee, reason = "Ten failed PIN attempts")

        notifier.attempts shouldHaveSize 8
        notifier.sentOfType<FakeNotifier.Sent.ExpiryWarning>().single().daysRemaining shouldBe 7
        notifier.sentOfType<FakeNotifier.Sent.HrSuspensionNotice>().single().reason shouldBe "Ten failed PIN attempts"
    }

    @Test
    fun `fake notifier - the notifier itself throws - is a different path from a failed delivery`() = runTest {
        // A use case may survive Failed and still fall over on an exception. The two are separate
        // failure modes and the fake keeps them separate.
        val notifier = FakeNotifier()
        notifier.failure.failNextCall()

        assertFailsWith<IllegalStateException> {
            notifier.sendInvitation(anEmail(), anEmployee(), "t")
        }

        notifier.attempts.shouldBeEmpty()
    }
}
