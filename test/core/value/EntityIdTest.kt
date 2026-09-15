package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The 12-character identifier every table but `employees` uses. */
class EntityIdTest {

    @Test
    fun `entity id - exactly 12 alphanumeric characters - is accepted`() {
        EntityId.of("Bn3vQ8sKtY6h").ok().value shouldBe "Bn3vQ8sKtY6h"
    }

    @Test
    fun `entity id - 11 characters - is rejected with entity_id invalid_format`() {
        EntityId.of("Bn3vQ8sKtY6").errCode() shouldBe "entity_id.invalid_format"
    }

    @Test
    fun `entity id - 13 characters - is rejected with entity_id invalid_format`() {
        EntityId.of("Bn3vQ8sKtY6hW").errCode() shouldBe "entity_id.invalid_format"
    }

    @Test
    fun `entity id - an 8 character person id - is rejected because the widths are distinct`() {
        // The disjoint widths are what let Identifier.of resolve a stored id by length alone.
        EntityId.of("k7Qm2XbF").errCode() shouldBe "entity_id.invalid_format"
    }

    @Test
    fun `entity id - an underscore - is rejected because only A-Z a-z and 0-9 are allowed`() {
        EntityId.of("Bn3vQ8sKtY_h").errCode() shouldBe "entity_id.invalid_format"
    }

    @Test
    fun `entity id - a UUID string - is rejected so an old identifier cannot be pasted back in`() {
        EntityId.of("c0000000-0000-4000-8000-00000000000e").errCode() shouldBe "entity_id.invalid_format"
    }

    @Test
    fun `entity id - surrounding whitespace - is rejected rather than being trimmed into shape`() {
        EntityId.of(" Bn3vQ8sKtY6 ").errCode() shouldBe "entity_id.invalid_format"
    }

    @Test
    fun `entity id - a rejection - names the id field so the 422 response can point at it`() {
        val error = (EntityId.of("nope") as DomainResult.Err).error as AppError.Validation

        error.field shouldBe "id"
    }

    @Test
    fun `entity id - the seeded catalogue literals - satisfy the rule`() {
        // These exact values are written into V2__reference_data.sql.
        EntityId.of("d00000000001").ok().value shouldBe "d00000000001"
        EntityId.of("e00000000004").ok().value shouldBe "e00000000004"
        EntityId.of("c00000000014").ok().value shouldBe "c00000000014"
    }

    @Test
    fun `entity id - toString - renders the id itself unlike AccessPin`() {
        EntityId.of("Bn3vQ8sKtY6h").ok().toString() shouldBe "Bn3vQ8sKtY6h"
    }

    @Test
    fun `entity id - the declared length - is 12`() {
        EntityId.LENGTH shouldBe 12
    }
}
