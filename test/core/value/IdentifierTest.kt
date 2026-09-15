package com.pgsystem.employee.requirement.tracker.core.value

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Resolving a stored id back to its kind.
 *
 * `audit_logs.entity_id` holds the id of whatever was acted on, so it is the one column that can
 * carry either width. These tests pin the length-dispatch that makes that readable again.
 */
class IdentifierTest {

    @Test
    fun `identifier resolution - an 8 character id - resolves to a PersonId`() {
        Identifier.of("k7Qm2XbF").ok().shouldBeInstanceOf<PersonId>()
    }

    @Test
    fun `identifier resolution - a 12 character id - resolves to an EntityId`() {
        Identifier.of("Bn3vQ8sKtY6h").ok().shouldBeInstanceOf<EntityId>()
    }

    @Test
    fun `identifier resolution - a 10 character id - is rejected because no id kind has that length`() {
        Identifier.of("Bn3vQ8sKtY").errCode() shouldBe "id.unknown_kind"
    }

    @Test
    fun `identifier resolution - a well sized value with a bad character - fails the kind's own rule`() {
        // Length picks the kind; the kind still validates the characters.
        Identifier.of("k7Qm-XbF").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `identifier resolution - a UUID string - is rejected because no id kind is 36 characters`() {
        Identifier.of("c0000000-0000-4000-8000-00000000000e").errCode() shouldBe "id.unknown_kind"
    }

    @Test
    fun `identifier charset - the shared alphabet - contains exactly 62 distinct characters`() {
        Identifier.ALPHABET.length shouldBe 62
        Identifier.ALPHABET.toSet().size shouldBe 62
    }

    @Test
    fun `identifier charset - the shared alphabet - contains only A-Z a-z and 0-9`() {
        Identifier.ALPHABET.all { it.isLetterOrDigit() && it.code < 128 } shouldBe true
    }

    @Test
    fun `identifier equality - a PersonId and an EntityId are never equal - because the widths differ`() {
        val person: Identifier = PersonId.of("k7Qm2XbF").ok()
        val entity: Identifier = EntityId.of("Bn3vQ8sKtY6h").ok()

        (person == entity) shouldBe false
    }
}
