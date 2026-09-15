package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PinGenerator
import com.pgsystem.employee.requirement.tracker.core.id.TokenGenerator
import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId

/**
 * Randomness the test owns.
 *
 * Each of these substitutes for a `fun interface` already bound in `CoreModule`, so this is
 * substitution and not redesign. Two behaviours matter beyond "returns something predictable":
 *
 *  - **Readable output.** `EMP00001` in a failure message says which hire; a random draw says
 *    nothing and differs on the next run.
 *  - **Repeating on demand.** A [PersonId] draws from 62^8, so a duplicate is unlikely but real at
 *    scale, and the hire-creation insert must retry (ERT-400). Scripting the same value twice is how
 *    that retry gets a test:
 *    `FixedPersonIdGenerator("EMP00001", "EMP00001", "EMP00002")`.
 *
 * Each generator takes an optional script and falls back to its sequence once the script runs out,
 * so a test that does not care about identifiers constructs one with no arguments and never thinks
 * about it again.
 *
 * There is no fake for `Hasher` or `TokenDigest`, deliberately. Both already take a constructor
 * argument — `BcryptHasher(cost = 4)` keeps the suite fast without changing the algorithm, and
 * `HmacTokenDigest("test-pepper")` is reproducible by design — and hashing is exactly what must not
 * be stubbed out of a credential rule.
 */

class FixedPersonIdGenerator(private vararg val scripted: String) : PersonIdGenerator {
    private var drawn = 0

    override fun newPersonId(): PersonId =
        personId(scripted.getOrElse(drawn) { sequential(PREFIX, drawn, PersonId.LENGTH) }).also { drawn++ }

    private companion object {
        const val PREFIX = "EMP"
    }
}

class FixedEntityIdGenerator(private vararg val scripted: String) : EntityIdGenerator {
    private var drawn = 0

    override fun newEntityId(): EntityId =
        entityId(scripted.getOrElse(drawn) { sequential(PREFIX, drawn, EntityId.LENGTH) }).also { drawn++ }

    private companion object {
        const val PREFIX = "ENT"
    }
}

/**
 * The upload-link token.
 *
 * Opaque by contract, so nothing here validates it. The value is still worth making recognisable:
 * a test that reads a token out of [com.pgsystem.employee.requirement.tracker.testdata.fake.FakeNotifier]
 * and presents it to the portal reads better when the token says what it is.
 */
class FixedTokenGenerator(private vararg val scripted: String) : TokenGenerator {
    private var drawn = 0

    override fun newToken(): String =
        scripted.getOrElse(drawn) { "token-" + (drawn + 1).toString().padStart(10, '0') }.also { drawn++ }
}

/**
 * The 6-digit access PIN.
 *
 * Every value — scripted or generated — is validated through the real [AccessPin.of] at
 * construction. The point is that a test which scripts `"12345"` fails in its own setup, naming the
 * mistake, rather than handing a five-digit string to a use case that will reject it for reasons the
 * test was not about.
 *
 * The sequence starts at `100001` rather than `000001` only so that the six digits are visible in a
 * failure message; `AccessPin` accepts leading zeros and the keyspace really is 10^6.
 */
class FixedPinGenerator(private vararg val scripted: String) : PinGenerator {
    private var drawn = 0

    init {
        scripted.forEach { it.orFailAsPin() }
    }

    override fun newPin(): String =
        scripted.getOrElse(drawn) { (100_001 + drawn).toString() }.also { drawn++ }.orFailAsPin()

    private fun String.orFailAsPin(): String = when (val result = AccessPin.of(this)) {
        is DomainResult.Ok -> this
        is DomainResult.Err -> error("'$this' is not a valid PIN: ${result.error.code}")
    }
}

/**
 * `EMP` + a zero-padded ordinal, at exactly the width the value class demands.
 *
 * The width is passed in rather than inferred so that a generator cannot quietly emit an identifier
 * of the wrong length — `personId` and `entityId` would throw, but at the call site rather than
 * here, which is a worse place to read the message.
 */
private fun sequential(prefix: String, ordinal: Int, length: Int): String =
    prefix + (ordinal + 1).toString().padStart(length - prefix.length, '0')
