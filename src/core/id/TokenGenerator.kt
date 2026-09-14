package com.pgsystem.employee.requirement.tracker.core.id

/**
 * Issues the opaque upload-link token.
 *
 * The token is long, random and single-purpose (PRD 12). Only its hash is ever persisted, so the
 * plaintext returned here is the single opportunity to deliver it.
 */
fun interface TokenGenerator {
    fun newToken(): String
}
