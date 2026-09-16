package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.model.PasswordPolicy
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.err
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A user changes their own password (ERT-190).
 *
 * The rule that earns most of these tests is the one that is easy to get subtly wrong: this is the
 * **only** route a user owing a password change may reach, so it has to work while every other gate
 * is refusing — and it has to actually clear the flag, or the bootstrap account is unusable forever.
 */
class ChangeHrPasswordUseCaseTest {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val hasher: Hasher = BcryptHasher(cost = 4)
    private val audit = FakeAuditLog()

    private val user = anHrUser(passwordHash = hasher.hash(CURRENT), passwordChangeRequired = true)
    private val users = FakeHrUserRepository(user)

    @Test
    fun `password change - a new password - is stored hashed and clears the change-required flag`() =
        runTest {
            useCase()(change(CURRENT, NEW)).ok()

            val stored = users.current(user.id)!!

            // Hashed, not stored: asserting `passwordHash != NEW` alone would pass against a use
            // case that stored the OLD hash, so verify the new password actually opens it.
            stored.passwordHash shouldNotBe NEW
            hasher.verify(NEW, stored.passwordHash) shouldBe true
            hasher.verify(CURRENT, stored.passwordHash) shouldBe false

            // Without this the bootstrap account can never be used for anything else.
            stored.passwordChangeRequired shouldBe false
        }

    @Test
    fun `password change - the current password is wrong - is refused and audited`() = runTest {
        useCase()(change("not-the-current-password", NEW)).err() shouldBe AppError.AuthenticationFailed

        users.saved.shouldBeEmpty()
        audit.entriesFor(AuditAction.PASSWORD_CHANGED).single().metadata["outcome"] shouldBe "refused"
    }

    @Test
    fun `password change - a successful change - is audited as changed and attributed to the user`() =
        runTest {
            useCase()(change(CURRENT, NEW)).ok()

            val entry = audit.entriesFor(AuditAction.PASSWORD_CHANGED).single()

            entry.metadata["outcome"] shouldBe "changed"
            entry.actorUserId shouldBe user.id
            entry.entityId shouldBe user.id
        }

    @Test
    fun `password change - a new password below the minimum length - is refused before anything is stored`() =
        runTest {
            val short = "a".repeat(PasswordPolicy.MIN_LENGTH - 1)

            useCase()(change(CURRENT, short)).errCode() shouldBe "password.too_short"
            users.saved.shouldBeEmpty()
        }

    @Test
    fun `password change - a new password beyond bcrypt's 72-byte limit - is refused rather than silently truncated`() =
        runTest {
            // bcrypt truncates at 72 bytes in silence, so a 100-character passphrase and its first
            // 72 characters would be the same password and nothing would say so. Refusing is better
            // than accepting a credential that is not the one the user chose.
            val long = "a".repeat(PasswordPolicy.MAX_BYTES + 1)

            useCase()(change(CURRENT, long)).errCode() shouldBe "password.too_long"
            users.saved.shouldBeEmpty()
        }

    @Test
    fun `password change - a multi-byte passphrase within the character limit but over 72 bytes - is refused`() =
        runTest {
            // The limit is bytes, not characters, which a length check alone would miss: 30 of these
            // are 30 characters and 90 bytes.
            val emoji = "🔐".repeat(30)

            useCase()(change(CURRENT, emoji)).errCode() shouldBe "password.too_long"
        }

    @Test
    fun `password change - the current password is wrong - is refused before the new one is validated`() =
        runTest {
            // Reversed, a caller who does not know the current password could still learn the length
            // rule by watching 422 turn into 401.
            useCase()(change("wrong", "short")).err() shouldBe AppError.AuthenticationFailed
        }

    @Test
    fun `password change - a user id with no row - is refused rather than throwing`() = runTest {
        // Not reachable with a live token -- there is no delete path for users -- but a corrupt-state
        // branch that threw would be a 500 on a credential path.
        useCase()(
            ChangePassword(
                userId = personId("NOSUCH01"),
                currentPassword = CURRENT,
                newPassword = NEW,
            )
        ).err() shouldBe AppError.AuthenticationFailed
    }

    @Test
    fun `password change - the stored user - keeps every other field`() = runTest {
        // A `copy` that dropped a field would be invisible here until a role silently reset.
        useCase()(change(CURRENT, NEW)).ok()

        val stored = users.current(user.id)!!

        stored shouldBe user.copy(
            passwordHash = stored.passwordHash,
            passwordChangeRequired = false,
        )
    }

    private fun useCase() = ChangeHrPasswordUseCase(
        users = users,
        hasher = hasher,
        audit = audit,
        clock = clock,
        ids = ids,
        tracer = NoOpUseCaseTracer,
    )

    private fun change(current: String, new: String) = ChangePassword(
        userId = user.id,
        currentPassword = current,
        newPassword = new,
    )

    private companion object {
        const val CURRENT = "the-current-password"
        const val NEW = "a-brand-new-password"
    }
}
