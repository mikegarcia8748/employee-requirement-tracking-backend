package com.pgsystem.employee.requirement.tracker.domain.model

/**
 * The admin-configurable link and portal settings of PRD 6.4.
 *
 * Every value here is stored in the database and read at runtime — never a constant in code, so a
 * duration can change without a deployment (PRD 8.10).
 *
 * Two clocks, not one: a 90-day ceiling alone leaves an abandoned link live for three months
 * holding access to birth certificates and medical records; an idle clock alone means a link
 * touched periodically never dies. Both run, and the earlier one wins.
 *
 * Values are snapshotted onto the link when it is issued. Changing a setting must never silently
 * extend or kill links already in the wild.
 */
data class LinkPolicy(
    /** Hard ceiling from issue. The link dies here regardless of activity. */
    val absoluteExpiryDays: Int = 90,

    /** Link dies this long after the last employee activity. `0` disables the idle clock. */
    val idleExpiryDays: Int = 30,

    /** Pushes absolute expiry out on rejection, so HR's review time never eats the employee's window. */
    val extendOnRejectionDays: Int = 30,

    /** How long before lapse the employee is warned. */
    val warnBeforeExpiryDays: Int = 7,

    /** How long the read-only confirmation stays reachable after COMPLETE. */
    val completedGraceDays: Int = 14,

    /** How long a PIN-verified portal session lasts before the PIN is required again. */
    val sessionMinutes: Int = 45,

    /** Failed PIN entries before a temporary lockout. */
    val pinAttemptsBeforeLockout: Int = 5,

    /** Length of that lockout. */
    val lockoutMinutes: Int = 15,

    /** Cumulative failures before the link auto-suspends and HR is notified. */
    val pinFailuresBeforeSuspend: Int = 10,
) {
    /** Whether the idle clock is in play at all (PRD 6.4: set to 0 to rely on the ceiling alone). */
    val idleClockEnabled: Boolean get() = idleExpiryDays > 0

    companion object {
        /**
         * Bounds the settings UI enforces, so a well-meant edit cannot turn a token into a
         * permanent credential (PRD 6.4).
         */
        val ABSOLUTE_EXPIRY_DAYS_RANGE = 7..180
    }
}
