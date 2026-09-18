package com.pgsystem.employee.requirement.tracker.data.notify

import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.NotificationOutbox
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import com.pgsystem.employee.requirement.tracker.domain.port.Notifier
import com.pgsystem.employee.requirement.tracker.domain.port.RejectedItem
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * `Notifier` as a durable outbox, with no transport behind it (ERT-440, PRD §8.9).
 *
 * PRD §14 Q12 was answered on 2026-09-16 — an SMTP relay on internal mail — and ERT-1010 lands it.
 * Until then every send is a row here and **nothing is transmitted**. The outbox is not merely a
 * staging post for that: it gives §8.1's "retry action" something to retry, makes "was the
 * invitation sent?" answerable, and survives a restart in a way an in-memory queue does not. No use
 * case changes when the transport arrives.
 *
 * ### What `DeliveryResult.Sent` means here, and what it does not
 *
 * This adapter returns [DeliveryResult.Sent] when the row is **durably queued**, and
 * [DeliveryResult.Failed] when the write itself fails — which is the ticket's criterion, and worth
 * stating plainly because the row it just wrote says `PENDING`. Nothing has reached a mailbox. The
 * distinction the caller actually needs is the one §8.1 asks for: did the record of this send
 * survive, so HR sees an indicator and a retry action rather than a lost hire? That question this
 * answers honestly. "Did it arrive?" becomes answerable when ERT-1010 drains the table and calls
 * [markSent] or [markFailed].
 *
 * ### The invitation's body is never stored, at any point in its life
 *
 * A rendered invitation carries a live credential — since 2026-09-16 the link *is* the whole of
 * authentication — and "purge the row after delivery" only shrinks the window: it does not remove
 * the credential from a backup, a replica, or a write-ahead log. The earlier wording of this
 * ticket's criterion, "retains no usable PIN *after delivery*", was satisfiable by a row that held
 * one for an hour first, which is why the test asserts at **write** time.
 *
 * So the invitation renders here, its subject is stored, and **its body is dropped**. What is left
 * on the row — recipient, kind, employee, subject, status, attempts, last error — answers "was it
 * sent?" and drives §8.1's failure indicator with nothing in it worth stealing.
 * [NotificationKind.storesBody] is what decides, so an eighth kind must choose rather than inherit.
 *
 * **The consequence is deliberate: a failed invitation cannot be re-rendered from this table.** The
 * token is not stored anywhere, by design, so retrying one means reissuing the link — ERT-1030's
 * `resend-link`. [retry] is for the six kinds that keep their body. That is not a gap; it is the
 * reason the body is absent, stated from the other end.
 *
 * ### The `AccessPin` is now on its own port method
 *
 * [sendRecoveryPin]'s signature carries one, and since 2026-09-16 the invitation carries **no PIN**:
 * the PIN became a recovery credential HR issues out of band. Splitting the port makes the
 * invitation method clean, while keeping the compile-time property of the `Notifier` interface
 * where only the dedicated recovery pin method handles the PIN.
 */
class OutboxNotifier(
    private val factory: DatabaseFactory,
    private val ids: EntityIdGenerator,
    private val clock: Clock,
    private val messages: NotificationMessages,
) : Notifier {

    override suspend fun sendInvitation(
        to: EmailAddress,
        employee: Employee,
        linkToken: String,
    ): DeliveryResult = queue(
        kind = NotificationKind.INVITATION,
        to = to,
        employee = employee,
        // Rendered so that a missing PORTAL_BASE_URL or a malformed link fails HERE, on the call
        // that holds the token, rather than in ERT-1010 when the token is long gone. The body then
        // goes no further: see this class's own note.
        message = messages.invitation(employee, linkToken),
    )

    override suspend fun sendRecoveryPin(
        to: EmailAddress,
        employee: Employee,
        pin: AccessPin,
    ): DeliveryResult = queue(
        kind = NotificationKind.RECOVERY_PIN,
        to = to,
        employee = employee,
        message = messages.recoveryPin(employee, pin),
    )

    override suspend fun sendPacketReadyForReview(employee: Employee): DeliveryResult = queue(
        kind = NotificationKind.PACKET_READY_FOR_REVIEW,
        // No address: this goes to HR, whose mailbox is ERT-1010's configuration. See the table.
        to = null,
        employee = employee,
        message = messages.packetReadyForReview(employee),
    )

    override suspend fun sendRejection(
        to: EmailAddress,
        employee: Employee,
        rejectedRequirements: List<RejectedItem>,
    ): DeliveryResult = queue(
        kind = NotificationKind.REJECTION,
        to = to,
        employee = employee,
        message = messages.rejection(employee, rejectedRequirements),
    )

    override suspend fun sendExpiryWarning(
        to: EmailAddress,
        employee: Employee,
        daysRemaining: Int,
    ): DeliveryResult = queue(
        kind = NotificationKind.EXPIRY_WARNING,
        to = to,
        employee = employee,
        message = messages.expiryWarning(employee, daysRemaining),
    )

    override suspend fun sendCompletionConfirmation(
        to: EmailAddress,
        employee: Employee,
    ): DeliveryResult = queue(
        kind = NotificationKind.COMPLETION_CONFIRMATION,
        to = to,
        employee = employee,
        message = messages.completionConfirmation(employee),
    )

    override suspend fun sendEmailChangedNotice(
        to: EmailAddress,
        employee: Employee,
    ): DeliveryResult = queue(
        kind = NotificationKind.EMAIL_CHANGED_NOTICE,
        to = to,
        employee = employee,
        message = messages.emailChangedNotice(employee),
    )

    override suspend fun notifyHrOfSuspension(employee: Employee, reason: String): DeliveryResult =
        queue(
            kind = NotificationKind.HR_SUSPENSION_NOTICE,
            to = null,
            employee = employee,
            message = messages.hrSuspensionNotice(employee, reason),
        )

    // ── The drain surface ERT-1010 will use ─────────────────────────────────────────────────────

    /** Queued rows, oldest first, so a backlog drains in the order it built up. */
    suspend fun pending(limit: Int = DEFAULT_DRAIN): List<OutboxEntry> = factory.transaction {
        NotificationOutbox.selectAll()
            .where { NotificationOutbox.status eq OutboxStatus.PENDING.name }
            .orderBy(
                NotificationOutbox.queuedAt to SortOrder.ASC,
                NotificationOutbox.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toOutboxEntry() }
    }

    suspend fun markSent(id: EntityId) {
        val now = clock.now()
        factory.transaction {
            val rows = NotificationOutbox.update({ NotificationOutbox.id eq id.value }) {
                it[status] = OutboxStatus.SENT.name
                it[sentAt] = now
                it[lastAttemptAt] = now
                it[lastError] = null
            }
            checkOneRow(rows, id)
        }
    }

    /**
     * Records a delivery failure against the row.
     *
     * **[error] is stored verbatim and the caller must not pass one echoing the message body.** An
     * SMTP rejection naming the recipient is fine; one quoting the invitation would put the link
     * back in the table the body was kept out of, which would undo the whole of this class's note.
     * The invitation's own test asserts the failure text carries no link.
     */
    suspend fun markFailed(id: EntityId, error: String) {
        val now = clock.now()
        factory.transaction {
            // Read and increment inside one transaction. Two calls would let a concurrent drain
            // lose a count, and the attempt count is what an ERT-1010 backoff will schedule on.
            val current = NotificationOutbox.selectAll()
                .where { NotificationOutbox.id eq id.value }
                .singleOrNull()
                ?.get(NotificationOutbox.attempts)
                ?: 0

            val rows = NotificationOutbox.update({ NotificationOutbox.id eq id.value }) {
                it[status] = OutboxStatus.FAILED.name
                it[lastError] = error
                it[lastAttemptAt] = now
                it[attempts] = current + 1
            }
            checkOneRow(rows, id)
        }
    }

    /**
     * Puts a failed row back in the queue.
     *
     * **Refuses an invitation**, and the refusal is the design rather than a missing feature: its
     * body was never stored, so there is nothing to re-send and the token that would rebuild it is
     * gone by construction. Reissuing the credential is ERT-1030's `resend-link`. Failing loudly
     * here is what stops a future drain loop from retrying invitations forever against an empty body.
     */
    suspend fun retry(id: EntityId) {
        val entry = find(id) ?: error("No outbox row '${id.value}' to retry.")

        check(entry.kind.storesBody) {
            "Outbox row '${id.value}' is a ${entry.kind}, whose body is never stored. There is " +
                "nothing to re-send: reissue the link with resend-link (ERT-1030) instead."
        }

        factory.transaction {
            val rows = NotificationOutbox.update({ NotificationOutbox.id eq id.value }) {
                it[status] = OutboxStatus.PENDING.name
                it[lastError] = null
            }
            checkOneRow(rows, id)
        }
    }

    suspend fun find(id: EntityId): OutboxEntry? = factory.transaction {
        NotificationOutbox.selectAll()
            .where { NotificationOutbox.id eq id.value }
            .singleOrNull()
            ?.toOutboxEntry()
    }

    /** Every row for one hire, newest first. Backs §8.1's derived delivery-failure indicator (E4). */
    suspend fun forEmployee(employeeId: PersonId): List<OutboxEntry> = factory.transaction {
        NotificationOutbox.selectAll()
            .where { NotificationOutbox.employeeId eq employeeId.value }
            .orderBy(
                NotificationOutbox.queuedAt to SortOrder.DESC,
                NotificationOutbox.id to SortOrder.DESC,
            )
            .map { it.toOutboxEntry() }
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    /**
     * The one path every send takes.
     *
     * A failure here is returned as [DeliveryResult.Failed] rather than thrown, because §8.1 requires
     * the hire to survive a failed invitation: `CreateHireUseCase` must see a value it can report,
     * not an exception that unwinds the creation it has just completed.
     *
     * **A cancellation is not a delivery failure, and is re-thrown (SEC-37, 2026-09-18).** This was
     * one `runCatching`, which catches [Throwable] — so a cancelled request came back as
     * `Failed("CancellationException")`, ERT-434 recorded a delivery failure and offered HR a retry
     * for a request nobody was waiting for, and structured concurrency lost a cancellation it was
     * entitled to. On Cloud Run a client disconnect or an instance drain is the realistic producer.
     *
     * **Narrowing the catch to [Exception] does not fix it on its own**, which is why both clauses
     * are here: on the JVM `CancellationException` extends `IllegalStateException`, so it *is* an
     * `Exception` and the narrowing alone changes nothing. The narrowing is still worth having —
     * `runCatching` also turned an `OutOfMemoryError` into a delivery failure.
     *
     * `NotifierContract` cannot catch this class of defect: it asserts that a failure is a *value*,
     * which is exactly what the bug did. `OutboxNotifierTest` holds the test that can.
     */
    private suspend fun queue(
        kind: NotificationKind,
        to: EmailAddress?,
        employee: Employee,
        message: RenderedMessage,
    ): DeliveryResult = try {
        val now = clock.now()
        val rowId = ids.newEntityId()

        factory.transaction {
            NotificationOutbox.insert {
                it[id] = rowId.value
                it[NotificationOutbox.kind] = kind.name
                it[recipient] = to?.value
                it[employeeId] = employee.id.value
                it[subject] = message.subject
                it[body] = message.body.takeIf { kind.storesBody }
                it[status] = OutboxStatus.PENDING.name
                it[attempts] = 0
                it[queuedAt] = now
            }
        }

        DeliveryResult.Sent
    } catch (cancellation: CancellationException) {
        // Must come first: CancellationException IS an Exception on the JVM, so the clause below
        // would otherwise swallow it exactly as `runCatching` did. See this method's KDoc.
        throw cancellation
    } catch (failure: Exception) {
        // The message, never the body: a failed INSERT can echo the row it was given, and for an
        // invitation that row is the one place a live link exists in this process.
        DeliveryResult.Failed(failure::class.simpleName ?: "write failed")
    }

    /**
     * An update that matched nothing is a caller naming a row that does not exist, and silence there
     * would report a drained queue that still holds the message.
     */
    private fun checkOneRow(rows: Int, id: EntityId) {
        check(rows == 1) {
            "Updating outbox row '${id.value}' matched $rows rows, expected exactly 1."
        }
    }

    private companion object {
        const val DEFAULT_DRAIN = 50
    }
}

/**
 * One queued notification.
 *
 * A `data/` type rather than a domain model: nothing in `domain/` knows this table exists, and the
 * port it implements speaks only in `DeliveryResult`. ERT-1010 is its only caller.
 *
 * [body] is null for the kinds whose [NotificationKind.storesBody] is false — `INVITATION` and,
 * since C23 split the port, `RECOVERY_PIN`. See [OutboxNotifier]'s own note. (Corrected 2026-09-18,
 * C37: this said "`INVITATION` and nothing else", which was true until `RECOVERY_PIN` arrived.)
 */
data class OutboxEntry(
    val id: EntityId,
    val kind: NotificationKind,
    val recipient: EmailAddress?,
    val employeeId: PersonId,
    val subject: String,
    val body: String?,
    val status: OutboxStatus,
    val attempts: Int,
    val lastError: String?,
    val queuedAt: Instant,
    val lastAttemptAt: Instant?,
    val sentAt: Instant?,
)

private fun ResultRow.toOutboxEntry(): OutboxEntry = OutboxEntry(
    id = EntityId.of(this[NotificationOutbox.id].value).orCorrupt("notification_outbox.id"),
    kind = this[NotificationOutbox.kind].toNotificationKind(),
    recipient = this[NotificationOutbox.recipient]
        ?.let { EmailAddress.of(it).orCorrupt("notification_outbox.recipient") },
    employeeId = PersonId.of(this[NotificationOutbox.employeeId].value)
        .orCorrupt("notification_outbox.employee_id"),
    subject = this[NotificationOutbox.subject],
    body = this[NotificationOutbox.body],
    status = this[NotificationOutbox.status].toOutboxStatus(),
    attempts = this[NotificationOutbox.attempts],
    lastError = this[NotificationOutbox.lastError],
    queuedAt = this[NotificationOutbox.queuedAt],
    lastAttemptAt = this[NotificationOutbox.lastAttemptAt],
    sentAt = this[NotificationOutbox.sentAt],
)

private fun String.toNotificationKind(): NotificationKind =
    NotificationKind.entries.firstOrNull { it.name == this }
        ?: error(
            "notification_outbox.kind holds '$this', which is not a NotificationKind. " +
                "A kind was removed from the enum without a migration."
        )

private fun String.toOutboxStatus(): OutboxStatus =
    OutboxStatus.entries.firstOrNull { it.name == this }
        ?: error(
            "notification_outbox.status holds '$this', which is not an OutboxStatus. " +
                "A status was removed from the enum without a migration."
        )

private fun <T> com.pgsystem.employee.requirement.tracker.core.error.DomainResult<T>.orCorrupt(
    column: String,
): T = when (this) {
    is com.pgsystem.employee.requirement.tracker.core.error.DomainResult.Ok -> value
    is com.pgsystem.employee.requirement.tracker.core.error.DomainResult.Err ->
        error("$column holds a value the domain rejects: ${error.code}")
}
