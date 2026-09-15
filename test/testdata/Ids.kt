package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId

/**
 * Identifiers for fixtures and builders.
 *
 * The value classes have private constructors on purpose, so every test that needs a literal id
 * would otherwise unwrap a `DomainResult` by hand. These throw instead: a malformed literal in a
 * test is a mistake in the test, not a case worth branching on.
 */
fun personId(raw: String): PersonId = PersonId.of(raw).orThrow(raw)

fun entityId(raw: String): EntityId = EntityId.of(raw).orThrow(raw)

private fun <T> DomainResult<T>.orThrow(raw: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> throw IllegalArgumentException("'$raw' is not a valid id: ${error.code}")
}
