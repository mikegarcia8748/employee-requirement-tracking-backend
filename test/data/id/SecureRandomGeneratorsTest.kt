package com.pgsystem.employee.requirement.tracker.data.id

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.security.SecureRandom
import kotlin.test.Test

/**
 * The identifier generators.
 *
 * The charset is proved by injecting a counting random rather than by sampling, so the assertion is
 * exact rather than statistical. The uniqueness tests are statistical but not meaningfully flaky —
 * see the comment on each.
 */
class SecureRandomGeneratorsTest {

    @Test
    fun `person id generation - a generated id - is exactly 8 characters`() {
        SecurePersonIdGenerator().newPersonId().value.length shouldBe PersonId.LENGTH
    }

    @Test
    fun `person id generation - a generated id - satisfies PersonId validation`() {
        val generated = SecurePersonIdGenerator().newPersonId()

        PersonId.of(generated.value) shouldBe DomainResult.Ok(generated)
    }

    @Test
    fun `person id generation - a random drawing 0 1 2 and so on - maps each index to the matching character`() {
        val generator = SecurePersonIdGenerator(CountingRandom())

        generator.newPersonId().value shouldBe "ABCDEFGH"
    }

    @Test
    fun `person id generation - ten thousand draws - produces no duplicate`() {
        // 62^8 is about 2.2e14, so a collision in 10,000 draws has probability around 2e-7.
        // The production retry on a duplicate belongs with the insert, not here.
        val generator = SecurePersonIdGenerator()
        val drawn = List(10_000) { generator.newPersonId().value }

        drawn.size - drawn.toSet().size shouldBe 0
    }

    @Test
    fun `entity id generation - a generated id - is exactly 12 characters`() {
        SecureEntityIdGenerator().newEntityId().value.length shouldBe EntityId.LENGTH
    }

    @Test
    fun `entity id generation - a generated id - satisfies EntityId validation`() {
        val generated = SecureEntityIdGenerator().newEntityId()

        EntityId.of(generated.value) shouldBe DomainResult.Ok(generated)
    }

    @Test
    fun `entity id generation - a random drawing 0 1 2 and so on - maps each index to the matching character`() {
        val generator = SecureEntityIdGenerator(CountingRandom())

        generator.newEntityId().value shouldBe "ABCDEFGHIJKL"
    }

    @Test
    fun `entity id generation - ten thousand draws - produces no duplicate`() {
        val generator = SecureEntityIdGenerator()
        val drawn = List(10_000) { generator.newEntityId().value }

        drawn.size - drawn.toSet().size shouldBe 0
    }

    @Test
    fun `id generation - many draws - uses every character in the alphabet`() {
        // Guards against a draw that silently never reaches the end of the alphabet -- the symptom
        // of an off-by-one bound, which would also be the symptom of a biased draw.
        val generator = SecureEntityIdGenerator()
        val seen = (1..5_000).flatMap { generator.newEntityId().value.toList() }.toSet()

        (Identifier.ALPHABET.toSet() - seen).shouldBeEmpty()
    }
}

/**
 * Returns 0, 1, 2 ... from [nextInt], so a generated id spells the start of the alphabet.
 *
 * [SecureRandom] inherits a non-final `nextInt(bound)` from [java.util.Random], so overriding it
 * here is enough to make the draw deterministic without touching the generator's own logic.
 */
private class CountingRandom : SecureRandom() {
    private var next = 0
    override fun nextInt(bound: Int): Int = (next++) % bound
}
