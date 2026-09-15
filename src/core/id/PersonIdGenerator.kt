package com.pgsystem.employee.requirement.tracker.core.id

import com.pgsystem.employee.requirement.tracker.core.value.PersonId

/**
 * Issues the 8-character employee identifier.
 *
 * A separate port rather than a second method on [EntityIdGenerator]: the widths differ, and a
 * caller that reaches for the wrong one should fail to compile rather than fail at insert time
 * against a narrower column.
 */
fun interface PersonIdGenerator {
    fun newPersonId(): PersonId
}
