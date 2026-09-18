package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.mapper.LinkPolicySetting
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * One policy store, two implementations, one read-write rule (ERT-250).
 *
 * **This suite deliberately does not test the §6.4 bounds, and that is an argued exclusion rather
 * than an oversight.** `FakeAppSettingsRepository` does **not** enforce them, because a fake that
 * filtered an out-of-range value would accept a bounds bug in the adapter without complaint — the
 * bounds are the adapter's job and `ExposedAppSettingsRepositoryTest` is where they are proven. A
 * contract suite that "fixed" that asymmetry would delete a control.
 *
 * What both sides must agree on is narrower and is what a use case actually depends on: a read
 * answers `Ok` with the stored policy, and a write is visible to the next read.
 *
 * **The two start equal for a reason worth knowing.** `LinkPolicy`'s Kotlin defaults are *identical*
 * to the V3 seeded rows — which is exactly why `AppSettingsRepository` is the only port returning
 * `DomainResult`: a silent fallback to the defaults would return precisely what a correct read
 * returns, and no behavioural test could tell them apart. ERT-433 propagates the `Err` rather than
 * recovering from it for the same reason.
 */
abstract class AppSettingsRepositoryContract {

    protected abstract val settings: AppSettingsRepository

    @Test
    fun `settings contract - a read - both implementations answer Ok with the default policy`() =
        runTest {
            // The fake defaults to LinkPolicy(); the adapter reads the V3 seed. They agree because
            // the seed was written from these defaults, and this test is what holds that.
            settings.linkPolicy().ok() shouldBe LinkPolicy()
        }

    @Test
    fun `settings contract - a policy change - both implementations answer the next read with it`() =
        runTest {
            val changed = LinkPolicy(absoluteExpiryDays = 60, idleExpiryDays = 14)

            settings.updateLinkPolicy(changed, Fixtures.HR_USER_ID).ok()

            settings.linkPolicy().ok() shouldBe changed
        }

    @Test
    fun `settings contract - a change to one value - both implementations leave the rest alone`() =
        runTest {
            // Only the moved key is written on the adapter side, so an untouched setting keeps the
            // `updated_at` that says when IT last changed. The observable half of that rule is that
            // nothing else moves.
            val changed = LinkPolicy(absoluteExpiryDays = 60)

            settings.updateLinkPolicy(changed, Fixtures.HR_USER_ID).ok()

            val stored = settings.linkPolicy().ok()
            stored.absoluteExpiryDays shouldBe 60
            stored.idleExpiryDays shouldBe LinkPolicy().idleExpiryDays
            stored.sessionMinutes shouldBe LinkPolicy().sessionMinutes
        }

    @Test
    fun `settings contract - a policy identical to the stored one - both implementations accept it`() =
        runTest {
            // The adapter writes nothing at all in this case, audit row included. Both sides must
            // still report success: "no change" is not a failure.
            settings.updateLinkPolicy(LinkPolicy(), Fixtures.HR_USER_ID).ok()

            settings.linkPolicy().ok() shouldBe LinkPolicy()
        }

    @Test
    fun `settings contract - a change to every settings key in turn - both implementations accept it`() =
        runTest {
            // HAR-19. Four of the nine keys were exercised by the tests above, and SEC-38 lived in
            // two of the five that were not: `updateLinkPolicy` derives its audit metadata keys from
            // the setting key, and two of them contain `pin`, which the credential guard refuses as
            // a substring. Fifteen `updateLinkPolicy` exercises across this suite and the adapter
            // test never touched either field.
            //
            // Driven off `LinkPolicySetting.entries` so it covers all nine by construction and a
            // tenth the day it is added. `changing` is exhaustive over the enum, so a tenth setting
            // is a compile error here rather than a key this loop silently skips.
            //
            // **Still not a bounds test.** Every value below sits inside its own declared bounds and
            // satisfies every cross-field rule, so the deliberate exclusion argued above is intact:
            // this asks whether both implementations accept a change to each key, not whether either
            // enforces a range.
            var checked = 0

            LinkPolicySetting.entries.forEach { setting ->
                val base = LinkPolicy()
                val changed = base.changing(setting)

                // Anti-vacuity: a `changing` that returned the policy unmoved would make the adapter
                // write nothing, reach no audit row, and pass this test green before the fix.
                setting.read(changed) shouldNotBe setting.read(base)

                settings.updateLinkPolicy(changed, Fixtures.HR_USER_ID).ok()
                settings.linkPolicy().ok() shouldBe changed

                settings.updateLinkPolicy(base, Fixtures.HR_USER_ID).ok()
                checked++
            }

            checked shouldBe LinkPolicySetting.entries.size
        }

    /**
     * This policy with exactly [setting] moved to another legal value.
     *
     * A `when` over the enum with no `else`, used as an expression: a tenth setting will not
     * compile until someone chooses a value for it. A writer lambda on [LinkPolicySetting] itself
     * would read better and is declined — nothing in `src/` would use it until the Phase 2 settings
     * screen, and an unused production API is worse than an exhaustive `when` in the one test that
     * needs it.
     */
    private fun LinkPolicy.changing(setting: LinkPolicySetting): LinkPolicy = when (setting) {
        LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS -> copy(absoluteExpiryDays = 120)
        LinkPolicySetting.IDLE_EXPIRY_DAYS -> copy(idleExpiryDays = 60)
        LinkPolicySetting.EXTEND_ON_REJECTION_DAYS -> copy(extendOnRejectionDays = 60)
        LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS -> copy(warnBeforeExpiryDays = 14)
        LinkPolicySetting.COMPLETED_GRACE_DAYS -> copy(completedGraceDays = 21)
        LinkPolicySetting.SESSION_MINUTES -> copy(sessionMinutes = 120)
        LinkPolicySetting.PIN_ATTEMPTS_BEFORE_LOCKOUT -> copy(pinAttemptsBeforeLockout = 6)
        LinkPolicySetting.LOCKOUT_MINUTES -> copy(lockoutMinutes = 30)
        LinkPolicySetting.PIN_FAILURES_BEFORE_SUSPEND -> copy(pinFailuresBeforeSuspend = 20)
    }
}

class FakeAppSettingsRepositoryContractTest : AppSettingsRepositoryContract() {
    override val settings: AppSettingsRepository = FakeAppSettingsRepository()
}

class ExposedAppSettingsRepositoryContractTest : AppSettingsRepositoryContract() {

    private val database = ContractDatabase()

    override val settings: AppSettingsRepository by lazy {
        ExposedAppSettingsRepository(database.factory, FixedClock(), FixedEntityIdGenerator())
    }

    @BeforeTest
    fun openDatabase() {
        // `app_settings.updated_by` is a foreign key to `users`, so the acting admin has to exist
        // before a policy can be written. ContractDatabase seeds Fixtures.HR_USER_ID.
        database.open()
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
    }
}
