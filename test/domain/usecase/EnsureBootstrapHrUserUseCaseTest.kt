package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The first `HR_ADMIN` (ERT-190).
 *
 * The property every test here circles is **"only when the table is empty"**. Deciding on the count
 * rather than on "does this email exist" is what stops the bootstrap admin being silently re-created
 * on every boot after an operator deactivated it — which is exactly what an operator does once they
 * have finished setting up.
 *
 * The configuration half — refusing to boot outside dev when the variables are unset — lives in
 * `plugin/HrBootstrap.kt` and has its own test. Keeping them apart is what lets this run against
 * fakes with no environment at all.
 */
class EnsureBootstrapHrUserUseCaseTest {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val personIds = FixedPersonIdGenerator("BOOTADM1")
    private val hasher: Hasher = BcryptHasher(cost = 4)
    private val audit = FakeAuditLog()

    @Test
    fun `hr bootstrap - an empty users table - creates one HR_ADMIN owing a password change`() =
        runTest {
            val users = FakeHrUserRepository()

            val created = useCase(users)(command()).ok()!!

            created.role shouldBe HrRole.HR_ADMIN
            created.isActive shouldBe true

            // The password is sitting in a deployment environment, a shell history and probably a
            // chat message. It must stop working at first use.
            created.passwordChangeRequired shouldBe true
            hasher.verify(PASSWORD, created.passwordHash) shouldBe true
        }

    @Test
    fun `hr bootstrap - a table that already holds an account - creates nothing`() = runTest {
        val users = FakeHrUserRepository(anHrUser())

        useCase(users)(command()).ok() shouldBe null
        users.saved.shouldBeEmpty()
        audit.entries.shouldBeEmpty()
    }

    @Test
    fun `hr bootstrap - a deactivated bootstrap admin and a second boot - does not resurrect it`() =
        runTest {
            // The realistic sequence: an operator finishes setting up, creates their own account,
            // deactivates the bootstrap one, and restarts. Deciding on "does this email exist"
            // instead of on the count would hand the account straight back, live, with a password
            // that lives in the deployment environment.
            val disabled = anHrUser(role = HrRole.HR_ADMIN, isActive = false)
            val users = FakeHrUserRepository(disabled)

            useCase(users)(command(email = disabled.email.value)).ok() shouldBe null
            users.current(disabled.id)!!.isActive shouldBe false
        }

    @Test
    fun `hr bootstrap - a changed password variable on a populated table - rewrites nothing`() =
        runTest {
            // A startup path that can rewrite a live credential from an environment variable is a
            // backdoor with a nice name. Recovery is ResetHrPasswordUseCase.
            val existing = anHrUser(role = HrRole.HR_ADMIN, passwordHash = hasher.hash("the-real-password"))
            val users = FakeHrUserRepository(existing)

            useCase(users)(command(email = existing.email.value, password = "a-different-password"))

            hasher.verify("the-real-password", users.current(existing.id)!!.passwordHash) shouldBe true
        }

    @Test
    fun `hr bootstrap - a malformed bootstrap address - is refused rather than creating an unusable account`() =
        runTest {
            val users = FakeHrUserRepository()

            useCase(users)(command(email = "not-an-address")).errCode() shouldBe "email.invalid_format"
            users.saved.shouldBeEmpty()
        }

    @Test
    fun `hr bootstrap - a bootstrap password below the minimum - is refused naming the variable`() =
        runTest {
            // The field name is the environment variable, because that is what the operator has to
            // go and change -- "newPassword" would send them looking for a form.
            val users = FakeHrUserRepository()
            val result = useCase(users)(command(password = "short"))

            result.errCode() shouldBe "password.too_short"
            (result as com.pgsystem.employee.requirement.tracker.core.error.DomainResult.Err)
                .let { (it.error as com.pgsystem.employee.requirement.tracker.core.error.AppError.Validation).field } shouldBe
                "HR_BOOTSTRAP_PASSWORD"
        }

    @Test
    fun `hr bootstrap - the created account - is audited with no acting user`() = runTest {
        // Nothing created this but the application itself, which is the case audit_logs.actor stayed
        // free text for. A trail that could not write this row would be worse than one carrying a
        // string.
        val users = FakeHrUserRepository()

        val created = useCase(users)(command()).ok()!!
        val entry = audit.entriesFor(AuditAction.USER_CREATED).single()

        entry.actorUserId shouldBe null
        entry.actor shouldBe "system.bootstrap"
        entry.entity shouldBe HrUser.AUDIT_ENTITY
        entry.entityId shouldBe created.id
        entry.metadata["source"] shouldBe "bootstrap"
    }

    private fun useCase(users: FakeHrUserRepository) = EnsureBootstrapHrUserUseCase(
        users = users,
        hasher = hasher,
        audit = audit,
        clock = clock,
        ids = ids,
        personIds = personIds,
        tracer = NoOpUseCaseTracer,
    )

    private fun command(email: String = "bootstrap@example.com", password: String = PASSWORD) =
        BootstrapHrUser(email = email, password = password)

    private companion object {
        const val PASSWORD = "the-bootstrap-password"
    }
}
