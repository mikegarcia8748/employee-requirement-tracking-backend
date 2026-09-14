package com.pgsystem.employee.requirement.tracker.core.crypto

/**
 * One-way hashing for the two portal credentials: the link token and the access PIN.
 *
 * Both are stored hashed and never logged (PRD 12). [verify] must be constant-time with respect to
 * the secret, because PIN verification is an attacker-reachable endpoint.
 */
interface Hasher {
    fun hash(plaintext: String): String
    fun verify(plaintext: String, hash: String): Boolean
}
