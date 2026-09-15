package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementStatus
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The builders held against the PRD 6.1-6.3 tables they claim to encode.
 *
 * The progress tests are the ones that earn their keep: [aRequirementSet] is the fixture ERT-511's
 * arithmetic will be asserted through, so a builder that miscounts would make a broken denominator
 * look correct. The consistency tests exist for the opposite reason -- a shortcut that set only an
 * enum would let a use case reading instants pass without ever being exercised.
 */
class BuildersTest {

    // ── Progress arithmetic (PRD 6.5) ───────────────────────────────────────────────────────────

    @Test
    fun `requirement set builder - five required with two approved - reports approval progress of two of five`() {
        val set = aRequirementSet(required = 5, approved = 2)

        set.total shouldBe 5
        set.approved shouldBe 2
    }

    @Test
    fun `requirement set builder - an optional requirement - is excluded from the denominator`() {
        // The rule most easily broken by a count() that forgets to filter: an optional document
        // must not make a finished hire look unfinished.
        val set = aRequirementSet(required = 3, optional = 2)

        set.total shouldBe 3
        set.requirements shouldHaveSize 5
    }

    @Test
    fun `requirement set builder - an optional requirement - is excluded from the numerator too`() {
        // Asserting the denominator alone would miss a submitted() that counted every requirement.
        val set = aRequirementSet(required = 3, approved = 3, optional = 2)

        set.approved shouldBe 3
        set.submitted shouldBe 3
    }

    @Test
    fun `requirement set builder - two approved and one under review - counts both as submitted`() {
        // PRD 6.5 keeps two figures: what the employee has done, and what HR has validated.
        val set = aRequirementSet(required = 5, approved = 2, underReview = 1)

        set.submitted shouldBe 3
        set.approved shouldBe 2
        set.awaitingReview shouldBe 1
    }

    @Test
    fun `requirement set builder - nothing uploaded - reports no progress against a real denominator`() {
        val set = aRequirementSet(required = 4)

        set.total shouldBe 4
        set.submitted shouldBe 0
        set.approved shouldBe 0
    }

    @Test
    fun `requirement set builder - more approved than required - fails at construction`() {
        // Describes no reachable state. A builder that clamped it would make a nonsense test pass.
        assertFailsWith<IllegalArgumentException> { aRequirementSet(required = 2, approved = 3) }
    }

    @Test
    fun `requirement set builder - every requirement - carries a distinct identifier`() {
        // Shared ids would make a repository fake collapse five requirements into one, and the
        // progress figures would still look right.
        val set = aRequirementSet(required = 5, optional = 2)

        set.requirements.map { it.id }.toSet() shouldHaveSize 7
    }

    // ── Link states (PRD 6.3) ───────────────────────────────────────────────────────────────────

    @Test
    fun `link builder - an expired link - reports a status that does not open the portal`() {
        anExpiredLink().status.opensPortal shouldBe false
    }

    @Test
    fun `link builder - an expired link - has an expiry before the fixed clock`() {
        // The other half of the same rule. A use case comparing instants and one reading the enum
        // must both see an expired link, or whichever is wrong passes by accident.
        anExpiredLink().expiresAt.isBefore(FixedClock.DEFAULT) shouldBe true
    }

    @Test
    fun `link builder - an active link - has both clocks ahead of the fixed clock`() {
        val link = anActiveLink()

        link.status shouldBe LinkStatus.ACTIVE
        link.status.opensPortal shouldBe true
        link.expiresAt.isAfter(FixedClock.DEFAULT) shouldBe true
        link.idleExpiresAt!!.isAfter(FixedClock.DEFAULT) shouldBe true
    }

    @Test
    fun `link builder - an active link - has the idle clock earlier than the absolute ceiling`() {
        // Two clocks, and the earlier one wins (PRD 6.4). A fixture with them the wrong way round
        // would make the idle rule untestable while looking valid.
        val link = anActiveLink()

        link.idleExpiresAt!!.isBefore(link.expiresAt) shouldBe true
    }

    @Test
    fun `link builder - a locked out link - is locked until an instant after the fixed clock`() {
        val link = aLockedOutLink()

        link.lockedUntil.shouldNotBeNull()
        link.lockedUntil!!.isAfter(FixedClock.DEFAULT) shouldBe true
        link.failedPinCount shouldBe 5
    }

    @Test
    fun `link builder - a locked out link - is still active because lockout is not a link state`() {
        // Conflating the two is the mistake LinkStatus exists to prevent (PRD 6.3, 6.6).
        aLockedOutLink().status shouldBe LinkStatus.ACTIVE
    }

    @Test
    fun `link builder - a suspended link - does not open the portal and carries the failure count`() {
        val link = aSuspendedLink()

        link.status.opensPortal shouldBe false
        link.failedPinCount shouldBe 10
    }

    @Test
    fun `link builder - a revoked link - records both the instant and the reason`() {
        val link = aRevokedLink()

        link.status shouldBe LinkStatus.REVOKED
        link.revokedAt.shouldNotBeNull()
        link.revokedReason.shouldNotBeNull()
    }

    // ── Packet states (PRD 6.2, 7.2) ────────────────────────────────────────────────────────────

    @Test
    fun `employee builder - a submitted packet - carries an attestation and a submission timestamp`() {
        val employee = aSubmittedPacket()

        employee.packetStatus shouldBe PacketStatus.UNDER_REVIEW
        employee.submittedAt.shouldNotBeNull()
        employee.attestation.shouldNotBeNull()
        employee.submittedByHr shouldBe false
    }

    @Test
    fun `employee builder - a completed packet - is terminal but is not identity assurance`() {
        // PRD 1, SEC-04: COMPLETE means the paperwork is in. originalsSightedAt stays separate, and
        // the default fixture must not quietly supply it.
        val employee = aCompletedPacket()

        employee.packetStatus.isTerminal shouldBe true
        employee.originalsSightedAt shouldBe null
    }

    @Test
    fun `employee builder - a flagged employee - freezes retention`() {
        // PRD 7.1, SEC-13. retentionFrozen derives from the flag set, so this is the fixture the
        // purge rule is tested against.
        aFlaggedEmployee().retentionFrozen shouldBe true
        aFlaggedEmployee(flag = AnomalyFlag.SHARED_EMAIL).retentionFrozen shouldBe true
    }

    @Test
    fun `employee builder - the default hire - is collecting and has no flags`() {
        val employee = anEmployee()

        employee.packetStatus shouldBe PacketStatus.DRAFT_COLLECTING
        employee.retentionFrozen shouldBe false
        employee.attestation shouldBe null
    }

    // ── Submissions ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `submission builder - a chain of three versions - marks only the newest current`() {
        val chain = aVersionChain(count = 3)

        chain.map { it.version } shouldBe listOf(1, 2, 3)
        chain.count { it.isCurrent } shouldBe 1
        chain.single { it.isCurrent }.version shouldBe 3
    }

    @Test
    fun `submission builder - a chain of versions - each is uploaded later than the last`() {
        // "The oldest" must be unambiguous however a purge chooses to order.
        val chain = aVersionChain(count = 4)

        chain.zipWithNext().all { (older, newer) -> older.uploadedAt.isBefore(newer.uploadedAt) } shouldBe true
    }

    @Test
    fun `submission builder - a chain of zero versions - fails at construction`() {
        assertFailsWith<IllegalArgumentException> { aVersionChain(count = 0) }
    }

    @Test
    fun `submission builder - the default submission - is uploaded and not yet reviewed`() {
        val submission = aSubmission()

        submission.status shouldBe RequirementStatus.UPLOADED
        submission.reviewedBy shouldBe null
        submission.sizeBytes shouldBe 512L * 1024
    }

    // ── Sessions and the trail ──────────────────────────────────────────────────────────────────

    @Test
    fun `session builder - an ended session - ends before it would have expired`() {
        val session = anEndedSession()

        session.endedAt.shouldNotBeNull()
        session.endedAt!!.isBefore(session.expiresAt) shouldBe true
    }

    @Test
    fun `session builder - an expired session - lapsed rather than being ended`() {
        // Two different reasons a session is unusable, and a use case must tell them apart.
        val session = anExpiredSession()

        session.endedAt shouldBe null
        session.expiresAt.isBefore(FixedClock.DEFAULT) shouldBe true
    }

    @Test
    fun `access log builder - a denied attempt - records the outcome that does not name its cause`() {
        // PRD 6.6: DENIED covers a wrong PIN and an unknown token, and the trail does not say which.
        val entry = aDeniedAttempt()

        entry.outcome.name shouldBe "DENIED"
        entry.sessionId shouldBe null
    }

    // ── Time (ERT-220 boundary) ─────────────────────────────────────────────────────────────────

    @Test
    fun `builders - default timestamps - derive from the fixed clock and not from now`() {
        // A fixture anchored to wall-clock time passes today and fails in ninety days, and the
        // failure looks like a domain bug rather than a fixture one.
        anEmployee().createdAt shouldBe FixedClock.DEFAULT
        anUploadLink().issuedAt shouldBe FixedClock.DEFAULT
        aSubmission().uploadedAt shouldBe FixedClock.DEFAULT
        aPortalSession().startedAt shouldBe FixedClock.DEFAULT
        aPortalAccessLog().timestamp shouldBe FixedClock.DEFAULT
        anAuditEntry().timestamp shouldBe FixedClock.DEFAULT
        anAttestation().attestedAt shouldBe FixedClock.DEFAULT
    }

    @Test
    fun `builders - a clock advanced after a build - does not move the object already built`() {
        // Defaults are a constant, not a live read. Otherwise advancing the clock in the act step
        // would silently rewrite the arrange step.
        val clock = FixedClock()
        val employee = anEmployee()

        clock.advance(java.time.Duration.ofDays(30))

        employee.createdAt shouldBe FixedClock.DEFAULT
    }

    @Test
    fun `builders - an overridden field - is the only thing that changes`() {
        // The whole point: a test states the field it is about, and nothing else moves.
        val employee = anEmployee(packetStatus = PacketStatus.ON_HOLD)

        employee.packetStatus shouldBe PacketStatus.ON_HOLD
        employee.id shouldBe anEmployee().id
        employee.email shouldBe anEmployee().email
    }
}
