package com.pgsystem.employee.requirement.tracker.data.crypto

import at.favre.lib.crypto.bcrypt.BCrypt
import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher

/**
 * bcrypt for the access PIN. **Not for the link token** — see [HmacTokenDigest].
 *
 * Deliberately a slow hash rather than SHA-256. The PIN keyspace is only 10^6, so a leaked database
 * would fall to an offline sweep in seconds against a fast digest; a work factor makes that sweep
 * expensive per candidate. The online path is separately bounded by lockout and auto-suspend
 * (PRD 6.6) — the two controls cover different attacks and neither substitutes for the other.
 *
 * The same reasoning excludes the link token, in the opposite direction. `hashToString` generates a
 * random salt per call, so this returns a different string for the same input every time — fine for
 * a value that is verified, fatal for one that is looked up by its stored hash. A token also carries
 * 256 bits of entropy, so there is no offline sweep for the work factor to slow down.
 *
 * bcrypt's verify is constant-time with respect to the hash comparison.
 */
class BcryptHasher(private val cost: Int = DEFAULT_COST) : Hasher {

    override fun hash(plaintext: String): String =
        BCrypt.withDefaults().hashToString(cost, plaintext.toCharArray())

    override fun verify(plaintext: String, hash: String): Boolean =
        BCrypt.verifyer().verify(plaintext.toCharArray(), hash.toCharArray()).verified

    companion object {
        /** ~100ms per hash on current hardware. Raise as hardware improves. */
        const val DEFAULT_COST = 12
    }
}
