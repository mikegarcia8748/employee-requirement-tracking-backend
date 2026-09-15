package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.domain.model.PortalAction
import com.pgsystem.employee.requirement.tracker.domain.model.PortalOutcome
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aDeniedAttempt
import com.pgsystem.employee.requirement.tracker.testdata.aPortalAccessLog
import com.pgsystem.employee.requirement.tracker.testdata.aSuccessfulAccess
import com.pgsystem.employee.requirement.tracker.testdata.accessLogId
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test

/**
 * The trail fake against PRD 8.12 and the §6.6 auto-suspend threshold.
 *
 * `countRecentFailures` is the one that matters most. Its definition here is the contract ERT-610's
 * SQL adapter has to match, and a mismatch would move the count at which a link auto-suspends —
 * silently, and only in production.
 */
class FakePortalAccessTrailTest {

    private val anotherLink = entityId("LNK000000002")

    @Test
    fun `fake access trail - three attempts from two addresses - distinctIpsFor returns two`() = runTest {
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(id = accessLogId(0), ip = "203.0.113.10"))
        trail.record(aDeniedAttempt(id = accessLogId(1), ip = "198.51.100.4"))
        trail.record(aDeniedAttempt(id = accessLogId(2), ip = "203.0.113.10"))

        trail.distinctIpsFor(Fixtures.LINK_ID) shouldBe setOf("203.0.113.10", "198.51.100.4")
    }

    @Test
    fun `fake access trail - addresses seen against another link - are not counted as this link's`() = runTest {
        // The multi-IP anomaly flag is per link. Leaking another link's addresses into the set would
        // flag innocent records.
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(id = accessLogId(0), ip = "203.0.113.10"))
        trail.record(aDeniedAttempt(id = accessLogId(1), uploadLinkId = anotherLink, ip = "198.51.100.4"))

        trail.distinctIpsFor(Fixtures.LINK_ID) shouldHaveSize 1
    }

    @Test
    fun `fake access trail - a denied attempt exactly at the since boundary - is counted`() = runTest {
        // Inclusive, matching a SQL `timestamp >= ?`. The boundary has to be decided somewhere, and
        // an off-by-one here shifts the window every lockout rule is measured against.
        val since = FixedClock.DEFAULT
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(timestamp = since))

        trail.countRecentFailures(Fixtures.LINK_ID, since) shouldBe 1
    }

    @Test
    fun `fake access trail - a denied attempt before the window - is not counted`() = runTest {
        val since = FixedClock.DEFAULT
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(timestamp = since.minus(Duration.ofSeconds(1))))

        trail.countRecentFailures(Fixtures.LINK_ID, since) shouldBe 0
    }

    @Test
    fun `fake access trail - a lockout outcome - does not count toward the failure total`() = runTest {
        // LOCKED_OUT is the consequence of failures already counted. Counting it too would double
        // count the same burst and suspend the link early (PRD 6.6).
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(id = accessLogId(0)))
        trail.record(
            aPortalAccessLog(
                id = accessLogId(1),
                action = PortalAction.VERIFY_PIN,
                outcome = PortalOutcome.LOCKED_OUT,
            )
        )

        trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT) shouldBe 1
    }

    @Test
    fun `fake access trail - an expired or rate limited outcome - does not count toward the failure total`() = runTest {
        // Neither is a failed credential attempt. An expired link accumulating toward auto-suspend
        // would be simply wrong.
        val trail = FakePortalAccessTrail()

        trail.record(aPortalAccessLog(id = accessLogId(0), outcome = PortalOutcome.EXPIRED))
        trail.record(aPortalAccessLog(id = accessLogId(1), outcome = PortalOutcome.RATE_LIMITED))
        trail.record(aPortalAccessLog(id = accessLogId(2), outcome = PortalOutcome.SUSPENDED))

        trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT) shouldBe 0
    }

    @Test
    fun `fake access trail - a successful access - does not count toward the failure total`() = runTest {
        val trail = FakePortalAccessTrail()

        trail.record(aSuccessfulAccess())

        trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT) shouldBe 0
    }

    @Test
    fun `fake access trail - denials against another link - are not counted`() = runTest {
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(id = accessLogId(0), uploadLinkId = anotherLink))

        trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT) shouldBe 0
        trail.countRecentFailures(anotherLink, FixedClock.DEFAULT) shouldBe 1
    }

    @Test
    fun `fake access trail - entries for a link - come back oldest first as a history`() = runTest {
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt(id = accessLogId(1), timestamp = FixedClock.DEFAULT.plus(Duration.ofMinutes(5))))
        trail.record(aDeniedAttempt(id = accessLogId(0), timestamp = FixedClock.DEFAULT))

        trail.findFor(Fixtures.LINK_ID).map { it.id } shouldBe listOf(accessLogId(0), accessLogId(1))
    }

    @Test
    fun `fake access trail - the exposed entries list - is a copy the caller cannot append through`() = runTest {
        // Append-only means no caller-side mutation either. A live view would let a test "correct"
        // the trail, which is exactly the property SEC-05 turns on.
        val trail = FakePortalAccessTrail()
        trail.record(aDeniedAttempt())

        val snapshot = trail.entries
        trail.record(aDeniedAttempt(id = accessLogId(1)))

        snapshot shouldHaveSize 1
        trail.entries shouldHaveSize 2
    }

    @Test
    fun `fake access trail - seeded entries - are counted exactly as recorded ones are`() = runTest {
        // Seeding exists so a test can arrange a burst without nine record() calls. If the two paths
        // diverged, an arranged burst would not reach the threshold a recorded one does.
        val trail = FakePortalAccessTrail(aDeniedAttempt(id = accessLogId(0)))
            .given(aDeniedAttempt(id = accessLogId(1)))

        trail.record(aDeniedAttempt(id = accessLogId(2)))

        trail.countRecentFailures(Fixtures.LINK_ID, FixedClock.DEFAULT) shouldBe 3
        trail.entries shouldHaveSize 3
    }

    @Test
    fun `fake access trail - a recorded denial - does not say whether the pin or the token was wrong`() = runTest {
        // PRD 6.6: the trail is shown to HR, and recording which would make it an oracle. There is
        // one DENIED value, so this holds by construction -- asserted because it is load-bearing.
        val trail = FakePortalAccessTrail()

        trail.record(aDeniedAttempt())

        trail.outcomesFor(Fixtures.LINK_ID) shouldBe listOf(PortalOutcome.DENIED)
    }
}
