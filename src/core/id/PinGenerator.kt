package com.pgsystem.employee.requirement.tracker.core.id

/**
 * Issues the 6-digit portal access PIN.
 *
 * Six digits, not four: a million combinations rather than ten thousand at no usability cost.
 * Four is defensible only with aggressive lockout, and lockout is itself a denial of service
 * against the employee, who then cannot submit anything (PRD 6.6).
 */
fun interface PinGenerator {
    fun newPin(): String
}
