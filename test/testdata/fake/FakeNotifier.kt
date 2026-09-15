package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import com.pgsystem.employee.requirement.tracker.domain.port.Notifier
import com.pgsystem.employee.requirement.tracker.domain.port.RejectedItem

/**
 * Outbound notifications, in memory.
 *
 * ### The PIN rule survives into the double
 *
 * [Notifier] encodes "no email but the invitation carries the PIN" in its *shape*: only
 * `sendInvitation` accepts an [AccessPin], so every other method is structurally incapable of
 * carrying the credential. [Sent] mirrors that exactly — **only [Sent.Invitation] declares a
 * `pin`** — so the same property holds for anything asserting against recorded sends. A test cannot
 * accidentally claim a rejection notice leaked a PIN, because there is nowhere for it to have been.
 *
 * ### Failure is a specified path, not an edge case
 *
 * PRD 8.1 requires the hire to survive a failed invitation and HR to get a retry action. That rule
 * is untestable against a notifier that always succeeds, so [failNextSend] makes one send fail and
 * [failEverySend] makes all of them. A failed send still lands in [attempts]: "returned Failed and
 * recorded the attempt" is one assertion, not two systems.
 *
 * ### Recording the token and PIN is what makes an end-to-end test possible
 *
 * The invitation is the only place either plaintext exists. Capturing both here lets a later test
 * create a hire, read the token and PIN out of this fake exactly as the employee would read them out
 * of their inbox, and present them to the portal — without any test needing a back door into
 * storage, which is precisely what the hashing design forbids.
 */
class FakeNotifier : Notifier {

    val failure = FakeFailure()

    private val log = mutableListOf<Attempt>()
    private val queuedFailures = ArrayDeque<String>()
    private var failingEverySend: String? = null

    /** Every send, delivered or failed, in order. */
    val attempts: List<Attempt> get() = log.toList()

    /** The sends that actually went out. */
    val delivered: List<Sent> get() = log.filter { it.result is DeliveryResult.Sent }.map { it.notification }

    /** The sends that failed. PRD 8.1 needs these visible, not swallowed. */
    val failed: List<Sent> get() = log.filter { it.result is DeliveryResult.Failed }.map { it.notification }

    data class Attempt(val notification: Sent, val result: DeliveryResult)

    /** One recorded send per [Notifier] method. Only [Invitation] can hold a credential. */
    sealed interface Sent {
        data class Invitation(
            val to: EmailAddress,
            val employee: Employee,
            val linkToken: String,
            val pin: AccessPin,
        ) : Sent

        data class PacketReadyForReview(val employee: Employee) : Sent

        data class Rejection(
            val to: EmailAddress,
            val employee: Employee,
            val rejectedRequirements: List<RejectedItem>,
        ) : Sent

        data class ExpiryWarning(val to: EmailAddress, val employee: Employee, val daysRemaining: Int) : Sent

        data class CompletionConfirmation(val to: EmailAddress, val employee: Employee) : Sent

        data class EmailChangedNotice(val to: EmailAddress, val employee: Employee) : Sent

        data class HrSuspensionNotice(val employee: Employee, val reason: String) : Sent
    }

    // ── The port ────────────────────────────────────────────────────────────────────────────────

    override suspend fun sendInvitation(
        to: EmailAddress,
        employee: Employee,
        linkToken: String,
        pin: AccessPin,
    ): DeliveryResult = deliver(Sent.Invitation(to, employee, linkToken, pin))

    override suspend fun sendPacketReadyForReview(employee: Employee): DeliveryResult =
        deliver(Sent.PacketReadyForReview(employee))

    override suspend fun sendRejection(
        to: EmailAddress,
        employee: Employee,
        rejectedRequirements: List<RejectedItem>,
    ): DeliveryResult = deliver(Sent.Rejection(to, employee, rejectedRequirements))

    override suspend fun sendExpiryWarning(
        to: EmailAddress,
        employee: Employee,
        daysRemaining: Int,
    ): DeliveryResult = deliver(Sent.ExpiryWarning(to, employee, daysRemaining))

    override suspend fun sendCompletionConfirmation(to: EmailAddress, employee: Employee): DeliveryResult =
        deliver(Sent.CompletionConfirmation(to, employee))

    override suspend fun sendEmailChangedNotice(to: EmailAddress, employee: Employee): DeliveryResult =
        deliver(Sent.EmailChangedNotice(to, employee))

    override suspend fun notifyHrOfSuspension(employee: Employee, reason: String): DeliveryResult =
        deliver(Sent.HrSuspensionNotice(employee, reason))

    /**
     * The one path every send takes.
     *
     * The attempt is recorded **before** the result is known to the caller, so a failure is never a
     * silent no-op. [failure] is separate and throws: a notifier that raises rather than returning
     * `Failed` is a different failure mode, and a use case that survives one may not survive the
     * other.
     */
    private fun deliver(notification: Sent): DeliveryResult {
        failure.check()

        val reason = queuedFailures.removeFirstOrNull() ?: failingEverySend
        val result = if (reason == null) DeliveryResult.Sent else DeliveryResult.Failed(reason)

        log += Attempt(notification, result)
        return result
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** The next send returns `Failed`; the one after it succeeds. Queues, so call it twice for two. */
    fun failNextSend(reason: String = "SMTP unavailable"): FakeNotifier = apply { queuedFailures += reason }

    /** Every send returns `Failed` until [stopFailingSends]. */
    fun failEverySend(reason: String = "SMTP unavailable"): FakeNotifier = apply { failingEverySend = reason }

    fun stopFailingSends(): FakeNotifier = apply {
        queuedFailures.clear()
        failingEverySend = null
    }

    // ── Assert ──────────────────────────────────────────────────────────────────────────────────

    val invitations: List<Sent.Invitation> get() = log.map { it.notification }.filterIsInstance<Sent.Invitation>()

    /** The one-liner: `notifier.sentInvitationTo(email) shouldBe true`. Delivered sends only. */
    fun sentInvitationTo(to: EmailAddress): Boolean =
        delivered.filterIsInstance<Sent.Invitation>().any { it.to == to }

    /** The invitation as the employee would receive it — the only place the plaintexts exist. */
    fun invitationTo(to: EmailAddress): Sent.Invitation? = invitations.lastOrNull { it.to == to }

    inline fun <reified T : Sent> sentOfType(): List<T> = attempts.map { it.notification }.filterIsInstance<T>()
}
