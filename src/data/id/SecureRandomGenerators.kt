package com.pgsystem.employee.requirement.tracker.data.id

import com.pgsystem.employee.requirement.tracker.core.id.IdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PinGenerator
import com.pgsystem.employee.requirement.tracker.core.id.TokenGenerator
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class RandomIdGenerator : IdGenerator {
    override fun newId(): UUID = UUID.randomUUID()
}

/**
 * 256 bits of entropy, URL-safe and unpadded.
 *
 * [SecureRandom] rather than [kotlin.random.Random]: this value is a credential, and a predictable
 * token is a guessable one (PRD 12 — long, random, single-purpose).
 */
class SecureTokenGenerator(private val random: SecureRandom = SecureRandom()) : TokenGenerator {
    override fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}

/**
 * A uniformly distributed 6-digit PIN.
 *
 * Uses [SecureRandom.nextInt] with a bound rather than a modulo of a wider draw, which would bias
 * the low digits. Zero-padded, so `000123` is a legitimate PIN and the keyspace really is 10^6 —
 * dropping leading zeros would quietly shrink it.
 */
class SecurePinGenerator(private val random: SecureRandom = SecureRandom()) : PinGenerator {
    override fun newPin(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}
