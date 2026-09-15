package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.core.time.Clock
import java.time.Instant

/**
 * Time the test owns.
 *
 * Almost every Phase 1 rule is a time question — does this link expire, is the lockout over, is the
 * idle clock earlier than the ceiling — and none of them is testable at its boundary against
 * `Instant.now()`. [advance] exists because pinning time is not enough: "the lockout expires after
 * fifteen minutes" requires moving time forward, not merely holding it still.
 *
 * Mutable and deliberately not thread-safe. A use case under test runs on the test's own thread;
 * synchronising here would suggest a concurrency property this class does not have.
 */
class FixedClock(private var instant: Instant = DEFAULT) : Clock {

    override fun now(): Instant = instant

    /** `clock.advance(15.minutes)`, `clock.advance(91.days)`. Returns the new instant. */
    fun advance(by: kotlin.time.Duration): Instant = advance(by.toJavaDuration())

    /**
     * The `java.time` overload, for durations that come from the domain itself — `LinkPolicy`
     * durations and [com.pgsystem.employee.requirement.tracker.domain.port.DocumentStorage.signedUrlFor]
     * both speak `java.time.Duration`.
     */
    fun advance(by: java.time.Duration): Instant {
        instant = instant.plus(by)
        return instant
    }

    /** Jump to an absolute instant, for a rule stated as a date rather than an offset. */
    fun set(to: Instant) {
        instant = to
    }

    companion object {
        /**
         * The instant every builder default derives from.
         *
         * Public because that is what makes "builders take their timestamps from the fixed clock
         * rather than from `Instant.now()`" an assertable fact rather than a convention someone has
         * to keep. A Thursday morning in UTC, chosen only so that failures quote a memorable value.
         */
        val DEFAULT: Instant = Instant.parse("2026-01-15T09:00:00Z")
    }
}

/** `kotlin.time` to `java.time`, kept here so the conversion has one spelling. */
private fun kotlin.time.Duration.toJavaDuration(): java.time.Duration =
    java.time.Duration.ofNanos(inWholeNanoseconds)
