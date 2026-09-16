package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig
import com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAuditLog
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedEmployeeRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedHrUserRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.DocumentStorage
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.usecase.AuthenticateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ChangeHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.EnsureBootstrapHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ResetHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.SetHrUserActiveUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The container actually builds what the ports name (ERT-310, ERT-320, ERT-330, ERT-350, ERT-190).
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
    fun `wiring - the data module - resolves ReferenceDataRepository to the Exposed adapter`() =
        withContainer { koin ->
            koin.get<ReferenceDataRepository>().shouldBeInstanceOf<ExposedReferenceDataRepository>()
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
    fun `wiring - the data module - resolves HrUserRepository to the Exposed adapter`() =
        withContainer { koin ->
            koin.get<HrUserRepository>().shouldBeInstanceOf<ExposedHrUserRepository>()
        }

    @Test
    fun `wiring - the data module - resolves AccessTokenIssuer to the JWT adapter`() =
        withContainer { koin ->
            koin.get<AccessTokenIssuer>().shouldBeInstanceOf<JwtIssuer>()
        }

    @Test
    fun `wiring - the verifier and the issuer - share one JwtConfig`() = withContainer { koin ->
        // It must be a `single`, and not only to keep issue and verify agreeing about the audience.
        // In dev the secret is generated per instance, so a `factory` would sign with one key and
        // verify with another -- and every token this application issued would be refused by the
        // request that presented it.
        koin.get<JwtConfig>() shouldBeSameInstanceAs koin.get<JwtConfig>()
    }

    @Test
    fun `wiring - the domain module - resolves every HR use case`() = withContainer { koin ->
        // Koin resolves lazily, so a use case with a wrong constructor arity compiles, starts,
        // serves /health, and fails on the first sign-in. Resolving each here moves that to the
        // build -- which matters more for these than for a repository, because five of the six are
        // reachable only through a route that needs a token.
        koin.get<AuthenticateHrUserUseCase>()
        koin.get<ChangeHrPasswordUseCase>()
        koin.get<CreateHrUserUseCase>()
        koin.get<SetHrUserActiveUseCase>()
        koin.get<ResetHrPasswordUseCase>()
        koin.get<EnsureBootstrapHrUserUseCase>()
    }

    @Test
    fun `wiring - a use case - is a factory rather than a shared instance`() = withContainer { koin ->
        // A use case holds no state worth sharing: every field is a port or an injected clock, all
        // of which are singles themselves. A `single` here would buy one allocation per request and
        // cost the guarantee that two concurrent calls cannot interfere.
        (koin.get<AuthenticateHrUserUseCase>() === koin.get<AuthenticateHrUserUseCase>()) shouldBe false
    }

    @Test
    fun `wiring - the data module - resolves EmployeeRepository to the Exposed adapter`() =
        withContainer { koin ->
            koin.get<EmployeeRepository>().shouldBeInstanceOf<ExposedEmployeeRepository>()
        }

    @Test
    fun `wiring - a port with no binding yet - still fails at resolution`() = withContainer { koin ->
        // WHEN THIS FAILS: ERT-710 landed and bound DocumentStorage. Swap this for another unbound
        // port and move DocumentStorage up into the tests above -- do not delete it. Without a port
        // that genuinely cannot resolve, the tests above would pass just as happily against a
        // container that resolved anything at all.
        //
        // ERT-410 bound EmployeeRepository and moved it up, as this comment then instructed.
        // DocumentStorage is deliberately the furthest-out unbound port rather than the next one, so
        // ERT-420 does not have to move this again.
        assertFailsWith<Exception> { koin.get<DocumentStorage>() }

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
