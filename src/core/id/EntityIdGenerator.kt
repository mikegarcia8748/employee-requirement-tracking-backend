package com.pgsystem.employee.requirement.tracker.core.id

import com.pgsystem.employee.requirement.tracker.core.value.EntityId

/**
 * Issues the 12-character identifier every table but `employees` uses.
 *
 * Injected so tests get deterministic identifiers instead of random ones. Note that the Exposed
 * tables deliberately declare no client default, so an insert that does not call this fails rather
 * than quietly receiving an identifier nobody chose.
 */
fun interface EntityIdGenerator {
    fun newEntityId(): EntityId
}
