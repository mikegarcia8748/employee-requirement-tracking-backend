package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.AccessPin
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DeterministicGeneratorsTest {

    // ── Identifiers ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `id generators - generate - a person id is eight characters and an entity id is twelve`() {
        // The widths are the whole reason these are two ports rather than one. A generator emitting
        // the wrong width would fail its own value class here rather than at an insert.
        FixedPersonIdGenerator().newPersonId().value.length shouldBe PersonId.LENGTH
        FixedEntityIdGenerator().newEntityId().value.length shouldBe EntityId.LENGTH
    }

    @Test
    fun `id generators - generate repeatedly - each draw differs from the last`() {
        val generator = FixedPersonIdGenerator()

        val first = generator.newPersonId()
        val second = generator.newPersonId()

        first shouldNotBe second
    }

    @Test
    fun `id generators - two instances - draw the same sequence so a run is repeatable`() {
        FixedEntityIdGenerator().newEntityId() shouldBe FixedEntityIdGenerator().newEntityId()
    }

    @Test
    fun `id generators - scripted with a repeated value - returns the same id twice`() {
        // PersonId draws from 62^8, so a duplicate is unlikely but real at scale and the
        // hire-creation insert has to retry (ERT-400). That retry cannot be tested without this.
        val generator = FixedPersonIdGenerator("EMP00001", "EMP00001", "EMP00002")

        val first = generator.newPersonId()
        val second = generator.newPersonId()
        val third = generator.newPersonId()

        first shouldBe second
        third shouldNotBe second
    }

    @Test
    fun `id generators - a script shorter than the number of draws - falls back to the sequence`() {
        // Most tests script the one id they assert on and want the rest to keep working.
        val generator = FixedEntityIdGenerator("REQ000000042")

        generator.newEntityId().value shouldBe "REQ000000042"
        generator.newEntityId().value.length shouldBe EntityId.LENGTH
    }

    @Test
    fun `id generators - scripted with a malformed value - fails at the draw`() {
        // Seven characters is not a PersonId. Failing here names the test's own mistake.
        assertFailsWith<IllegalArgumentException> { FixedPersonIdGenerator("TOOSHRT").newPersonId() }
    }

    // ── Tokens ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `token generator - generates - returns a recognisable and repeatable token`() {
        FixedTokenGenerator().newToken() shouldBe "token-0000000001"
    }

    @Test
    fun `token generator - scripted - returns the scripted token so a portal flow can be driven`() {
        FixedTokenGenerator("the-invited-token").newToken() shouldBe "the-invited-token"
    }

    // ── PINs ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fixed pin generator - generates - satisfies AccessPin validation`() {
        // The real value object, not a length check: tests must exercise what production runs.
        val pin = FixedPinGenerator().newPin()

        (AccessPin.of(pin) is DomainResult.Ok) shouldBe true
    }

    @Test
    fun `fixed pin generator - generates repeatedly - every draw satisfies AccessPin validation`() {
        val generator = FixedPinGenerator()

        repeat(5) { (AccessPin.of(generator.newPin()) is DomainResult.Ok) shouldBe true }
    }

    @Test
    fun `fixed pin generator - scripted with five digits - fails at construction`() {
        // Not at the draw. A test that mistypes a PIN should fail in its arrange step, where the
        // mistake is, rather than inside the use case it was trying to exercise.
        assertFailsWith<IllegalStateException> { FixedPinGenerator("12345") }
    }

    @Test
    fun `fixed pin generator - scripted with a valid pin - returns it so a wrong pin can be contrasted`() {
        FixedPinGenerator("424242").newPin() shouldBe "424242"
    }
}
