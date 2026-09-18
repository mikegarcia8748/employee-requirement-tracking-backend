package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.testdata.ok
import io.kotest.matchers.shouldBe
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
