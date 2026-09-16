package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAuditLog
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The container actually builds what the ports name (ERT-310, ERT-330, ERT-320).
 *
 * Koin resolves lazily, so a binding with a wrong constructor arity compiles, starts, serves
 * `/health`, and fails on the first request that needs it. Resolving each port here moves that
 * failure to the build.
 *
 * No database is needed: `DatabaseFactory`'s constructor does not connect — `connect()` is called
 * from the application lifecycle — so this exercises wiring and nothing else.
 */
class DataModuleTest {

    @Test
    fun `wiring - the data module - resolves AppSettingsRepository to the Exposed adapter`() =
        withContainer { koin ->
            koin.get<AppSettingsRepository>().shouldBeInstanceOf<ExposedAppSettingsRepository>()
        }

    @Test
    fun `wiring - the data module - resolves AuditLog to the Exposed adapter`() = withContainer { koin ->
        koin.get<AuditLog>().shouldBeInstanceOf<ExposedAuditLog>()
    }

    @Test
    fun `wiring - the data module - resolves RequirementTemplateRepository to the Exposed adapter`() =
        withContainer { koin ->
            koin.get<RequirementTemplateRepository>()
                .shouldBeInstanceOf<ExposedRequirementTemplateRepository>()
        }

    @Test
    fun `wiring - the settings adapter and the audit log - share one DatabaseFactory`() =
        withContainer { koin ->
            // The settings adapter writes its audit row on its own transaction rather than through
            // the AuditLog port, which is only atomic while both reach the same database. Two
            // factories would mean two pools and two transactions.
            koin.get<DatabaseFactory>() shouldBeSameInstanceAs koin.get<DatabaseFactory>()
        }

    @Test
    fun `wiring - a port with no binding yet - still fails at resolution`() = withContainer { koin ->
        // WHEN THIS FAILS: ERT-410 landed and bound EmployeeRepository. Swap this for another
        // unbound port and move EmployeeRepository up into the tests above -- do not delete it.
        // Without a port that genuinely cannot resolve, the three tests above would pass just as
        // happily against a container that resolved anything at all.
        assertFailsWith<Exception> { koin.get<EmployeeRepository>() }

        // And the reason must be the missing binding, not a broken container.
        koin.getOrNull<AuditLog>() shouldBe koin.get<AuditLog>()
    }

    private fun withContainer(block: (org.koin.core.Koin) -> Unit) {
        val application = testKoinApplication()
        try {
            block(application.koin)
        } finally {
            application.close()
        }
    }
}
