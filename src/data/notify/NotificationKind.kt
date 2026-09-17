package com.pgsystem.employee.requirement.tracker.data.notify

/**
 * The seven `Notifier` methods, as stored values (ERT-440, PRD §8.9).
 *
 * **[storesBody] is a choice each kind makes, not a property it inherits**, and that is the whole
 * design. `INVITATION` is the only message in the system that carries a live credential — since
 * 2026-09-16 the link *is* the whole of authentication — so its rendered body must never reach a
 * row, a backup, a replica or a write-ahead log. The other six point at the portal without restating
 * the link and are ordinary text.
 *
 * Spelling it as a constructor parameter is the device [com.pgsystem.employee.requirement.tracker
 * .domain.model.AnomalyFlag.freezesRetention] and
 * [com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus.opensPortal] already use: an
 * eighth kind added later **will not compile** until someone decides which side of the line it is
 * on. A `kind != INVITATION` check somewhere in the adapter would have let it inherit `true` in
 * silence, which is the failure this shape exists to prevent.
 */
enum class NotificationKind(val storesBody: Boolean) {
    /** The only message carrying a credential, and therefore the only one whose body is dropped. */
    INVITATION(storesBody = false),

    PACKET_READY_FOR_REVIEW(storesBody = true),
    REJECTION(storesBody = true),
    EXPIRY_WARNING(storesBody = true),
    COMPLETION_CONFIRMATION(storesBody = true),
    EMAIL_CHANGED_NOTICE(storesBody = true),
    HR_SUSPENSION_NOTICE(storesBody = true),
}

/**
 * Where a queued notification has got to.
 *
 * `PENDING` is what every row is written as, including the ones whose `sendInvitation` call returned
 * [com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult.Sent] — see
 * [OutboxNotifier]'s note on what `Sent` means while no transport exists.
 */
enum class OutboxStatus {
    PENDING,
    SENT,
    FAILED,
}
