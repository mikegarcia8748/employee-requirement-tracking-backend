package com.pgsystem.employee.requirement.tracker.data.notify

import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.port.RejectedItem

/** A rendered message. [body] reaches a row only when [NotificationKind.storesBody] says so. */
data class RenderedMessage(val subject: String, val body: String)

/**
 * The seven message bodies (ERT-440, PRD §8.9).
 *
 * Plain text, deliberately: ERT-1010 lands the transport and can add a multipart alternative then,
 * and a template engine chosen now would be chosen without a single real message to judge it by.
 *
 * **Only [invitation] restates the link.** Every other message points the hire at the portal without
 * repeating the credential, so a forwarded rejection notice or expiry warning carries nothing
 * useful. That is the `Notifier` interface's own rule one layer down, and it is why
 * [PortalBaseUrl.linkTo] is reachable from exactly one function here.
 *
 * **No message contains a PIN.** The recovery PIN travels by phone or in person and never by email
 * (§6.6) — it is the first genuinely out-of-band factor the design has — so there is no `pin`
 * parameter anywhere below, including on [invitation]. See [OutboxNotifier.sendInvitation] for what
 * happens to the one the port still hands it.
 */
class NotificationMessages(private val portalBaseUrl: PortalBaseUrl) {

    fun invitation(employee: Employee, linkToken: String) = RenderedMessage(
        subject = "Your pre-employment requirements",
        body = """
            Hi ${employee.firstName},

            Welcome to the team. Before your first day we need a few documents from you.

            Open your checklist here and upload them from your phone:
            ${portalBaseUrl.linkTo(linkToken)}

            The link is personal to you — please do not forward it. If it stops working, or if you
            did not expect this message, contact HR and we will sort it out.
        """.trimIndent()
    )

    fun packetReadyForReview(employee: Employee) = RenderedMessage(
        subject = "Requirements submitted: ${employee.firstName} ${employee.lastName}",
        body = """
            ${employee.firstName} ${employee.lastName} has submitted their pre-employment
            requirements and the packet is ready for review.
        """.trimIndent()
    )

    fun rejection(employee: Employee, rejectedRequirements: List<RejectedItem>) = RenderedMessage(
        subject = "Some of your documents need attention",
        body = """
            Hi ${employee.firstName},

            We could not accept the following, and have reopened them so you can replace them:

            ${rejectedRequirements.joinToString("\n") { "  - ${it.requirementName}: ${it.reason}" }}

            Open your checklist from the link in your invitation email to upload again. Your deadline
            has been extended so that our review time does not come out of yours.
        """.trimIndent()
    )

    fun expiryWarning(employee: Employee, daysRemaining: Int) = RenderedMessage(
        subject = "Your upload link expires in $daysRemaining days",
        body = """
            Hi ${employee.firstName},

            Your requirements are not complete yet, and the link in your invitation email stops
            working in $daysRemaining days.

            If you have lost the link, contact HR and we will send you a new one.
        """.trimIndent()
    )

    fun completionConfirmation(employee: Employee) = RenderedMessage(
        subject = "Your requirements are complete",
        body = """
            Hi ${employee.firstName},

            Everything we needed has been received and accepted. Nothing further is required from
            you. Thank you.
        """.trimIndent()
    )

    fun emailChangedNotice(employee: Employee) = RenderedMessage(
        subject = "The email address on your record was changed",
        body = """
            Hi ${employee.firstName},

            The email address on your pre-employment record was changed, and any previous upload link
            has stopped working.

            If you did not ask for this, contact HR immediately.
        """.trimIndent()
    )

    fun hrSuspensionNotice(employee: Employee, reason: String) = RenderedMessage(
        subject = "Upload link suspended: ${employee.firstName} ${employee.lastName}",
        body = """
            The upload link for ${employee.firstName} ${employee.lastName} has been suspended.

            Reason: $reason

            Review the access trail on the hire's record before issuing a new link.
        """.trimIndent()
    )
}
