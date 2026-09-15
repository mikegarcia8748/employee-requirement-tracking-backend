package com.pgsystem.employee.requirement.tracker.data.id

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PinGenerator
import com.pgsystem.employee.requirement.tracker.core.id.TokenGenerator
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import java.security.SecureRandom
import java.util.Base64

class SecureEntityIdGenerator(private val random: SecureRandom = SecureRandom()) : EntityIdGenerator {
    override fun newEntityId(): EntityId = EntityId.of(alphanumeric(random, EntityId.LENGTH)).orFail()
}

class SecurePersonIdGenerator(private val random: SecureRandom = SecureRandom()) : PersonIdGenerator {
    override fun newPersonId(): PersonId = PersonId.of(alphanumeric(random, PersonId.LENGTH)).orFail()
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

/**
 * One independent draw per character from [Identifier.ALPHABET].
 *
 * [SecureRandom.nextInt] with a bound discards the uneven tail of the draw range, so every character
 * is equally likely. The two tempting shortcuts are both wrong: `nextInt() % 62` favours the first
 * two letters, because 2^31 leaves a remainder of 2 when divided by 62 and can also go negative;
 * and Base64 of random bytes is unbiased but emits `-` and `_`, which are not in the alphabet.
 *
 * [SecureRandom] rather than [kotlin.random.Random] for the same reason [SecurePinGenerator] uses
 * it — identifiers are not credentials, but a predictable identifier is still worth avoiding, and it
 * is the property `UUID.randomUUID()` already had.
 */
private fun alphanumeric(random: SecureRandom, length: Int): String {
    val alphabet = Identifier.ALPHABET
    return String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
}

/**
 * Unwraps an identifier the generator has just built from [Identifier.ALPHABET] at the value class's
 * own declared length, so this cannot fail today.
 *
 * It exists so that an edit desynchronising the alphabet from a validation pattern fails loudly here
 * rather than producing identifiers that nothing downstream can parse. Going through `of` also keeps
 * the value classes' private constructors intact — an `internal` unchecked constructor would put a
 * hole in the one invariant they exist to hold.
 */
private fun <T> DomainResult<T>.orFail(): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("Generated identifier failed its own validation: ${error.code}")
}
