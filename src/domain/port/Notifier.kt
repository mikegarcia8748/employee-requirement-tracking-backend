package com.pgsystem.employee.requirement.tracker.domain.port

import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.domain.model.Employee

/**
 * Outbound notifications (PRD 8.9).
 *
 * Note the shape of this interface: **only [sendInvitation] accepts an [AccessPin].** Every other
 * method is structurally incapable of carrying the credential, so the rule "no email other than the
 * invitation ever contains the PIN" is enforced by the type system rather than by reviewer
 * vigilance. A forwarded rejection notice or expiry warning carries nothing useful.
 */
interface Notifier {
    suspend fun sendInvitation(to: EmailAddress, employee: Employee, linkToken: String, pin: AccessPin): DeliveryResult

    suspend fun sendPacketReadyForReview(employee: Employee): DeliveryResult

    suspend fun sendRejection(to: EmailAddress, employee: Employee, rejectedRequirements: List<RejectedItem>): DeliveryResult

    suspend fun sendExpiryWarning(to: EmailAddress, employee: Employee, daysRemaining: Int): DeliveryResult

    suspend fun sendCompletionConfirmation(to: EmailAddress, employee: Employee): DeliveryResult

    /**
     * Sent to the **previous** address on an email change. It may be dead — but if it is live and
     * the change was not legitimate, this is the only signal the real employee will ever get
     * (PRD 7.4).
     */
    suspend fun sendEmailChangedNotice(to: EmailAddress, employee: Employee): DeliveryResult

    suspend fun notifyHrOfSuspension(employee: Employee, reason: String): DeliveryResult
}

data class RejectedItem(val requirementName: String, val reason: String)

/**
 * Delivery is fallible and the hire is created regardless — PRD 8.1 requires a failure indicator
 * and a retry action rather than a lost record.
 */
sealed interface DeliveryResult {
    data object Sent : DeliveryResult
    data class Failed(val reason: String) : DeliveryResult
}
