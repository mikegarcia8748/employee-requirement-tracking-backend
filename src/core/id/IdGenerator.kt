package com.pgsystem.employee.requirement.tracker.core.id

import java.util.UUID

/** Injected so tests get deterministic identifiers instead of random ones. */
fun interface IdGenerator {
    fun newId(): UUID
}
