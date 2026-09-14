package com.pgsystem.employee.requirement.tracker.data.time

import com.pgsystem.employee.requirement.tracker.core.time.Clock
import java.time.Instant

/** The one place `Instant.now()` is allowed to be called. */
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
