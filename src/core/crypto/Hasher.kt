package com.pgsystem.employee.requirement.tracker.core.crypto

/**
 * One-way hashing for the **access PIN**, and nothing else.
 *
 * It once covered the link token too. It cannot: a token is resolved *by* its stored hash
 * (`UploadLinkRepository.findByTokenHash`, `upload_links.token_hash uniqueIndex`), and a salted hash
 * is different on every call, so no presented token would ever match. Tokens go through
 * [TokenDigest] instead (ERT-160).
 *
 * What is left here is the case a work factor is actually for: the PIN keyspace is 10^6, it is
 * verified against one already-located row, and it is never looked up. Stored hashed and never
 * logged (PRD 12). [verify] must be constant-time with respect to the secret, because PIN
 * verification is an attacker-reachable endpoint.
 */
interface Hasher {
    fun hash(plaintext: String): String
    fun verify(plaintext: String, hash: String): Boolean
}
