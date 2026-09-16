package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Creating, deactivating and resetting HR accounts (ERT-190, PRD §8.13).
 *
 * Three use cases in one file because they are one surface and share a fixture. Split them when one
 * grows a rule table of its own.
 *
 * The rule doing the most work here is the one nobody asks for until it bites: **an admin may not
 * deactivate themselves.** With a handful of staff and no self-registration, the last admin switching
 * themselves off leaves a system with no way to administer it and no path back that does not involve
 * a psql prompt.
 */
class HrUserAdministrationTest {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val personIds = FixedPersonIdGenerator("NEWUSER1")
    private val hasher: Hasher = BcryptHasher(cost = 4)
    private val audit = FakeAuditLog()

    private val admin = anHrAdmin()
    private val officer = anHrUser(email = anEmail("ana.reyes@example.com"))
    private val users = FakeHrUserRepository(admin, officer)

    // ── Create ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user creation - a new address - stores the account owing a password change`() = runTest {
        val created = create(email = "new.hire.officer@example.com").ok()

        created.id shouldBe personId("NEWUSER1")
        created.role shouldBe HrRole.HR_OFFICER
        created.isActive shouldBe true

        // Non-negotiable: the admin chose this password, so it must stop working at first use.
        created.passwordChangeRequired shouldBe true
        hasher.verify(PASSWORD, created.passwordHash) shouldBe true
    }

    @Test
    fun `hr user creation - an address that already has an account - is refused with a named conflict`() =
        runTest {
            // Unlike sign-in, this route is behind HR_ADMIN, and someone who can list every account
            // learns nothing from being told one exists. An admin who cannot be told is left
            // retrying a creation that will never work.
            create(email = officer.email.value).errCode() shouldBe "user.email_taken"
            users.saved.shouldBeEmpty()
        }

    @Test
    fun `hr user creation - the same address in another case - is still refused`() = runTest {
        create(email = "ANA.REYES@EXAMPLE.COM").errCode() shouldBe "user.email_taken"
    }

    @Test
    fun `hr user creation - a malformed address - is refused before anything is stored`() = runTest {
        create(email = "not-an-address").errCode() shouldBe "email.invalid_format"
        users.saved.shouldBeEmpty()
    }

    @Test
    fun `hr user creation - a blank full name - is refused`() = runTest {
        create(fullName = "   ").errCode() shouldBe "full_name.required"
    }

    @Test
    fun `hr user creation - an initial password below the minimum - is refused naming that field`() =
        runTest {
            // The field matters: this is an admin form with two password-shaped inputs across the
            // surface, and a client binds the error to one of them.
            val result = create(password = "short")

            result.errCode() shouldBe "password.too_short"
            (result as com.pgsystem.employee.requirement.tracker.core.error.DomainResult.Err)
                .let { (it.error as com.pgsystem.employee.requirement.tracker.core.error.AppError.Validation).field } shouldBe
                "initialPassword"
        }

    @Test
    fun `hr user creation - a successful create - is audited against the new account by the acting admin`() =
        runTest {
            val created = create(email = "new.hire.officer@example.com").ok()

            val entry = audit.entriesFor(AuditAction.USER_CREATED).single()

            entry.entityId shouldBe created.id
            entry.actorUserId shouldBe admin.id
            entry.metadata["role"] shouldBe HrRole.HR_OFFICER.name
        }

    @Test
    fun `hr user creation - the audit row - carries no password`() = runTest {
        create(email = "new.hire.officer@example.com").ok()

        audit.entriesFor(AuditAction.USER_CREATED).single().metadata.values.forEach {
            (it == PASSWORD) shouldBe false
        }
    }

    // ── Deactivate ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user deactivation - an officer - clears the active flag and audits it`() = runTest {
        val updated = setActive(officer.id, isActive = false).ok()

        updated.isActive shouldBe false
        audit.entriesFor(AuditAction.USER_DEACTIVATED).single().entityId shouldBe officer.id
    }

    @Test
    fun `hr user deactivation - an admin deactivating themselves - is refused`() = runTest {
        // The last admin switching themselves off leaves no way back that does not involve a psql
        // prompt. A rule, so it lives here rather than in the handler.
        setActive(admin.id, isActive = false).errCode() shouldBe "user.cannot_deactivate_self"
        users.current(admin.id)!!.isActive shouldBe true
    }

    @Test
    fun `hr user deactivation - an admin reactivating themselves - is allowed`() = runTest {
        // The rule is about removing your own access, not about touching your own row. Refusing
        // both would be a wider rule than the reason supports.
        val self = anHrAdmin(id = personId("SELFADM1"), isActive = false)
        val repository = FakeHrUserRepository(self)

        setActive(self.id, isActive = true, actingUserId = self.id, repository = repository)
            .ok().isActive shouldBe true
    }

    @Test
    fun `hr user deactivation - a state the account already has - writes no audit row`() = runTest {
        // Otherwise pressing Save twice produces two "deactivated" entries and the trail says the
        // account was disabled twice.
        setActive(officer.id, isActive = true).ok()

        audit.entries.shouldBeEmpty()
        users.saved.shouldBeEmpty()
    }

    @Test
    fun `hr user deactivation - an id with no account - is a not found`() = runTest {
        setActive(personId("NOSUCH01"), isActive = false).errCode() shouldBe "user_not_found"
    }

    // ── Reset ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr password reset - an admin resetting an officer - stores the hash and forces a change`() =
        runTest {
            resetPassword(officer.id).ok()

            val stored = users.current(officer.id)!!

            hasher.verify(NEW_PASSWORD, stored.passwordHash) shouldBe true

            // Unconditional: without it the admin is left holding a working password for someone
            // else's account indefinitely.
            stored.passwordChangeRequired shouldBe true
        }

    @Test
    fun `hr password reset - a new password below the minimum - is refused before anything is stored`() =
        runTest {
            resetPassword(officer.id, newPassword = "short").errCode() shouldBe "password.too_short"
            users.saved.shouldBeEmpty()
        }

    @Test
    fun `hr password reset - an id with no account - is a not found`() = runTest {
        resetPassword(personId("NOSUCH01")).errCode() shouldBe "user_not_found"
    }

    @Test
    fun `hr password reset - a successful reset - is audited against the account by the acting admin`() =
        runTest {
            resetPassword(officer.id).ok()

            val entry = audit.entriesFor(AuditAction.PASSWORD_RESET).single()

            entry.entityId shouldBe officer.id
            entry.actorUserId shouldBe admin.id
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun create(
        email: String = "new.hire.officer@example.com",
        fullName: String = "Nueva Oficial",
        password: String = PASSWORD,
    ) = CreateHrUserUseCase(users, hasher, audit, clock, ids, personIds, NoOpUseCaseTracer)(
        CreateHrUser(
            email = email,
            fullName = fullName,
            role = HrRole.HR_OFFICER,
            initialPassword = password,
            actingUserId = admin.id,
        )
    )

    private suspend fun setActive(
        userId: com.pgsystem.employee.requirement.tracker.core.value.PersonId,
        isActive: Boolean,
        actingUserId: com.pgsystem.employee.requirement.tracker.core.value.PersonId = admin.id,
        repository: FakeHrUserRepository = users,
    ) = SetHrUserActiveUseCase(repository, audit, clock, ids, NoOpUseCaseTracer)(
        SetHrUserActive(userId = userId, isActive = isActive, actingUserId = actingUserId)
    )

    private suspend fun resetPassword(
        userId: com.pgsystem.employee.requirement.tracker.core.value.PersonId,
        newPassword: String = NEW_PASSWORD,
    ) = ResetHrPasswordUseCase(users, hasher, audit, clock, ids, NoOpUseCaseTracer)(
        ResetHrPassword(userId = userId, newPassword = newPassword, actingUserId = admin.id)
    )

    private companion object {
        const val PASSWORD = "an-initial-password"
        const val NEW_PASSWORD = "a-reset-password-value"
    }
}
