package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.err
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * HR sign-in (ERT-190, PRD §14 Q4).
 *
 * **`BcryptHasher(cost = 4)` rather than a stub**, per `DeterministicGenerators`' note: hashing is
 * exactly what must not be faked out of a credential rule. Cost 4 keeps the suite fast without
 * changing the algorithm — a stub that compared plaintext would pass every test here while the real
 * adapter rejected every password.
 *
 * The indistinguishability tests assert on the **`AppError` instance**, not on a code string.
 * `AppError.AuthenticationFailed` is a `data object`, so `shouldBe` on the instance is the strongest
 * statement available: two failures that are the same object cannot render differently anywhere
 * downstream.
 */
class AuthenticateHrUserUseCaseTest {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val hasher: Hasher = BcryptHasher(cost = 4)
    private val audit = FakeAuditLog()
    private val tokens = FakeAccessTokenIssuer()

    private val officer = anHrUser(
        email = anEmail("ana.reyes@example.com"),
        passwordHash = hasher.hash(PASSWORD),
        role = HrRole.HR_OFFICER,
    )

    // ── The happy path ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr sign in - a correct password - returns a token naming the user and role`() = runTest {
        val session = useCase(officer)(signIn(officer.email.value, PASSWORD)).ok()

        session.user shouldBe officer
        session.expiresAt shouldBe FixedClock.DEFAULT.plusSeconds(3600)

        // The issuer was reached exactly once, with this user. Asserting only on the returned token
        // would pass against a use case that minted one for somebody else and returned it.
        tokens.issuedFor shouldBe listOf(officer)
    }

    @Test
    fun `hr sign in - an address in another case - matches the stored account`() = runTest {
        // EmailAddress.of lower-cases on construction, so this is the property that makes typing
        // ANA.REYES@EXAMPLE.COM into a phone keyboard work. It is worth a test because the
        // alternative failure is indistinguishable from a wrong password by design.
        useCase(officer)(signIn("ANA.Reyes@Example.COM", PASSWORD)).ok().user shouldBe officer
    }

    // ── One failure, four ways to reach it (§14 Q4, §6.6's reasoning) ───────────────────────────

    @Test
    fun `hr sign in - an unknown email - fails identically to a wrong password`() = runTest {
        val unknown = useCase(officer)(signIn("nobody@example.com", PASSWORD)).err()
        val wrongPassword = useCase(officer)(signIn(officer.email.value, "not-the-password")).err()

        unknown shouldBe wrongPassword
        unknown shouldBe com.pgsystem.employee.requirement.tracker.core.error.AppError.AuthenticationFailed
    }

    @Test
    fun `hr sign in - a deactivated user with the correct password - fails identically to an unknown email`() =
        runTest {
            val deactivated = anHrUser(
                email = anEmail("gone@example.com"),
                passwordHash = hasher.hash(PASSWORD),
                isActive = false,
            )

            val refused = useCase(deactivated)(signIn(deactivated.email.value, PASSWORD)).err()
            val unknown = useCase(deactivated)(signIn("nobody@example.com", PASSWORD)).err()

            refused shouldBe unknown
        }

    @Test
    fun `hr sign in - a malformed address - fails identically to an unknown one`() = runTest {
        // Answering 422 here would put a second response shape on the one endpoint whose whole
        // design is having exactly one.
        useCase(officer)(signIn("not-an-address", PASSWORD)).err() shouldBe
            useCase(officer)(signIn("nobody@example.com", PASSWORD)).err()
    }

    @Test
    fun `hr sign in - any failure - never reaches the token issuer`() = runTest {
        val use = useCase(officer)

        use(signIn("nobody@example.com", PASSWORD))
        use(signIn(officer.email.value, "wrong"))
        use(signIn("not-an-address", PASSWORD))

        // A token minted and then discarded is still a token that existed, and the sign-in path is
        // the only place one may be created.
        tokens.issuedFor.shouldBeEmpty()
    }

    @Test
    fun `hr sign in - a deactivated user - is refused before the password is even right`() = runTest {
        // Guards against a reordering that checks the password first and returns early on success:
        // the outcome would be the same for a wrong password but WRONG for a right one.
        val deactivated = anHrUser(passwordHash = hasher.hash(PASSWORD), isActive = false)

        useCase(deactivated)(signIn(deactivated.email.value, PASSWORD)).errCode() shouldBe
            "authentication_failed"
    }

    // ── Timing (the other half of "identical") ──────────────────────────────────────────────────

    @Test
    fun `hr sign in - an unknown email - still verifies a password so the timing does not separate it`() =
        runTest {
            // Byte-identical bodies are worth nothing if one branch returns in 1ms and the other in
            // 100ms. Asserted structurally rather than by measuring a clock: a counting Hasher says
            // "verify was called" deterministically, where a wall-clock assertion would be flaky on
            // a loaded CI box and would prove nothing on a fast one.
            val counting = CountingHasher(hasher)
            val use = useCase(officer, hasher = counting)

            use(signIn("nobody@example.com", PASSWORD))

            counting.verifications shouldBe 1
        }

    @Test
    fun `hr sign in - a deactivated user - still verifies a password so the timing does not separate it`() =
        runTest {
            val counting = CountingHasher(hasher)
            val deactivated = anHrUser(passwordHash = hasher.hash(PASSWORD), isActive = false)

            useCase(deactivated, hasher = counting)(signIn(deactivated.email.value, PASSWORD))

            counting.verifications shouldBe 1
        }

    // ── The audit trail ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr sign in - any attempt - is written to the audit trail with its outcome`() = runTest {
        val use = useCase(officer)

        use(signIn(officer.email.value, PASSWORD))
        use(signIn(officer.email.value, "wrong"))

        audit.entries.map { it.action } shouldBe
            listOf(AuditAction.SIGN_IN_SUCCEEDED, AuditAction.SIGN_IN_FAILED)
        audit.entries.forEach { it.metadata["ip"] shouldBe Fixtures.IP }
    }

    @Test
    fun `hr sign in - a failure for a real account - is audited identically to one for an unknown address`() =
        runTest {
            // The oracle this closes is inside the audit table rather than on the wire: recording
            // the real user id on a wrong-password failure would let anyone who can read the trail
            // separate "wrong password" from "no such account".
            val use = useCase(officer)

            use(signIn(officer.email.value, "wrong"))
            use(signIn("nobody@example.com", "wrong"))

            val (real, unknown) = audit.entriesFor(AuditAction.SIGN_IN_FAILED)

            real.actorUserId shouldBe null
            unknown.actorUserId shouldBe null
            real.entityId shouldBe HrUser.NO_SUBJECT
            unknown.entityId shouldBe HrUser.NO_SUBJECT
            real.copy(id = unknown.id, actor = unknown.actor) shouldBe unknown
        }

    @Test
    fun `hr sign in - a successful attempt - attributes the audit row to the user`() = runTest {
        useCase(officer)(signIn(officer.email.value, PASSWORD))

        val entry = audit.entriesFor(AuditAction.SIGN_IN_SUCCEEDED).single()

        entry.actorUserId shouldBe officer.id
        entry.entityId shouldBe officer.id
        entry.actor shouldBe officer.email.value
    }

    @Test
    fun `hr sign in - any audit row - carries no password and no password hash`() = runTest {
        val use = useCase(officer)

        use(signIn(officer.email.value, PASSWORD))
        use(signIn(officer.email.value, PASSWORD + "-wrong"))

        audit.entries.forEach { entry ->
            entry.renderedForSearch() shouldNotContain PASSWORD
            entry.renderedForSearch() shouldNotContain officer.passwordHash
        }
    }

    @Test
    fun `hr user - rendered as a string - does not expose the password hash`() {
        // The tracer is handed no arguments, so the realistic leak is an HrUser interpolated into a
        // diagnostic by hand. Cheap to close, and the acceptance criterion is explicit.
        anHrAdmin(passwordHash = "a-very-recognisable-hash").toString() shouldNotContain
            "a-very-recognisable-hash"
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun useCase(vararg seed: HrUser, hasher: Hasher = this.hasher) =
        AuthenticateHrUserUseCase(
            users = FakeHrUserRepository(*seed),
            hasher = hasher,
            tokens = tokens,
            audit = audit,
            clock = clock,
            ids = ids,
            tracer = NoOpUseCaseTracer,
        )

    private fun signIn(email: String, password: String) =
        SignIn(email = email, password = password, ip = Fixtures.IP)

    /** Every field an audit row could carry a credential in, as one searchable string. */
    private fun AuditEntry.renderedForSearch(): String =
        "$actor|$entity|${entityId.value}|${metadata.entries.joinToString("|")}"

    /**
     * Counts [verify] calls, delegating the actual work.
     *
     * A `FakeHasher` that skipped bcrypt would make the timing tests vacuous — they exist precisely
     * because the real work is slow — so this wraps rather than replaces.
     */
    private class CountingHasher(private val delegate: Hasher) : Hasher {
        var verifications = 0
            private set

        override fun hash(plaintext: String): String = delegate.hash(plaintext)

        override fun verify(plaintext: String, hash: String): Boolean {
            verifications++
            return delegate.verify(plaintext, hash)
        }
    }

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
    }
}
