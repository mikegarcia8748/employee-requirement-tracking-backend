package com.pgsystem.employee.requirement.tracker.testdata

import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The harness testing itself.
 *
 * Worth the lines because every expiry, lockout and idle-window rule in Phase 1 will be asserted
 * through this class. A clock that silently failed to advance would make those rules pass for the
 * wrong reason, and the failure would look like a domain bug.
 */
class FixedClockTest {

    @Test
    fun `fixed clock - advanced by fifteen minutes - reports the later instant`() {
        val clock = FixedClock()

        clock.advance(15.minutes)

        clock.now() shouldBe FixedClock.DEFAULT.plusSeconds(15 * 60)
    }

    @Test
    fun `fixed clock - advanced twice - accumulates rather than resetting`() {
        // The bug this catches is `instant = DEFAULT.plus(by)` instead of `instant.plus(by)`, which
        // passes the test above and quietly makes every multi-step time test wrong.
        val clock = FixedClock()

        clock.advance(10.minutes)
        clock.advance(5.minutes)

        clock.now() shouldBe FixedClock.DEFAULT.plusSeconds(15 * 60)
    }

    @Test
    fun `fixed clock - not advanced - returns the same instant on every read`() {
        val clock = FixedClock()

        clock.now() shouldBe clock.now()
        clock.now() shouldBe FixedClock.DEFAULT
    }

    @Test
    fun `fixed clock - advanced by ninety one days - passes the absolute expiry ceiling`() {
        // The two-line test the ticket asks for: PRD 6.4 caps a link at 90 days.
        val clock = FixedClock()

        val ninetyOneDaysOn = clock.advance(91.days)

        ninetyOneDaysOn.isAfter(FixedClock.DEFAULT.plus(java.time.Duration.ofDays(90))) shouldBe true
    }

    @Test
    fun `fixed clock - advanced by a java time duration - reports the later instant`() {
        // Domain durations are java.time. Both overloads must move the same clock.
        val clock = FixedClock()

        clock.advance(java.time.Duration.ofHours(2))

        clock.now() shouldBe FixedClock.DEFAULT.plusSeconds(2 * 60 * 60)
    }

    @Test
    fun `fixed clock - set to an absolute instant - reports that instant`() {
        val clock = FixedClock()

        clock.set(Instant.parse("2027-03-01T00:00:00Z"))

        clock.now() shouldBe Instant.parse("2027-03-01T00:00:00Z")
    }

    @Test
    fun `fixed clock - constructed at an instant - starts there rather than at the default`() {
        FixedClock(Instant.parse("2020-01-01T00:00:00Z")).now() shouldBe Instant.parse("2020-01-01T00:00:00Z")
    }
}
