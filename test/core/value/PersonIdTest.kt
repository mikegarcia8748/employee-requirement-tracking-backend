package com.pgsystem.employee.requirement.tracker.core.value

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.ok

/**
 * The 8-character employee identifier.
 *
 * The rejection cases matter more than the acceptance one: this type exists so that a malformed id
 * cannot reach a query, and every test below names a specific way one could.
 */
class PersonIdTest {

    @Test
    fun `person id - exactly 8 alphanumeric characters - is accepted`() {
        PersonId.of("k7Qm2XbF").ok().value shouldBe "k7Qm2XbF"
    }

    @Test
    fun `person id - 7 characters - is rejected with person_id invalid_format`() {
        PersonId.of("k7Qm2Xb").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `person id - 9 characters - is rejected with person_id invalid_format`() {
        PersonId.of("k7Qm2XbFz").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `person id - a hyphen in the middle - is rejected because only A-Z a-z and 0-9 are allowed`() {
        PersonId.of("k7Qm-XbF").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `person id - a UUID string - is rejected so an old identifier cannot be pasted back in`() {
        PersonId.of("d0000000-0000-4000-8000-000000000001").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `person id - an empty string - is rejected rather than producing a blank id`() {
        PersonId.of("").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `person id - surrounding whitespace - is rejected rather than being trimmed into shape`() {
        // Deliberately unlike EmailAddress.of, which trims. Trimming an id would turn a malformed
        // path segment into a successful lookup.
        PersonId.of(" k7Qm2XbF ").errCode() shouldBe "person_id.invalid_format"
    }

    @Test
    fun `person id - a rejection - names the id field so the 422 response can point at it`() {
        val error = (PersonId.of("nope") as DomainResult.Err).error as AppError.Validation

        error.field shouldBe "id"
    }

    @Test
    fun `person id - mixed case characters - are preserved rather than normalised`() {
        PersonId.of("AbCdEfGh").ok().value shouldBe "AbCdEfGh"
    }

    @Test
    fun `person id - an all digit value - is accepted because digits are alphanumeric`() {
        // '00123456' is a legal id. Anything exporting ids to CSV must quote them; Excel would
        // otherwise strip the leading zeros and produce an id that no longer resolves.
        PersonId.of("00123456").ok().value shouldBe "00123456"
    }

    @Test
    fun `person id - two ids with the same characters - are equal`() {
        PersonId.of("k7Qm2XbF").ok() shouldBe PersonId.of("k7Qm2XbF").ok()
    }

    @Test
    fun `person id - toString - renders the id itself unlike AccessPin`() {
        // An id is not a credential. It belongs in logs and error messages.
        PersonId.of("k7Qm2XbF").ok().toString() shouldBe "k7Qm2XbF"
    }

    @Test
    fun `person id - the declared length - is 8`() {
        PersonId.LENGTH shouldBe 8
    }
}
