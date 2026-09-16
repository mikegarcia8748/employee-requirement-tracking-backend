package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseConfig
import com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig
import com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAuditLog
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedEmployeeRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedHrUserRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AccessTokenIssuer
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.plugin.isDevMode
import org.koin.dsl.module

/**
 * Adapters for the domain ports.
 *
 * Bindings that are **not** here are deliberately absent rather than stubbed with throwing
 * placeholders. An unbound port fails fast and loudly at wiring time; a placeholder that compiles
 * fails at runtime, in production, on the one path nobody exercised.
 *
 * Each binding lands in the same change as the use case that needs it — **or** in the ticket that
 * establishes the adapter, which is what happened for the two below. ERT-300's whole purpose is to
 * set the adapter pattern before ERT-410 onward make it mechanical, and an adapter nothing can
 * resolve has not established anything. `DataModuleTest` resolves both, so a binding that is wrong
 * fails the build rather than the first request that needs it.
 *
 * Both adapters share one [DatabaseFactory], because it is a `single`. That is not incidental: it is
 * what lets `ExposedAppSettingsRepository` write a settings change and its audit row in one
 * transaction.
 */
val dataModule = module {
    single { DatabaseConfig.fromEnvironment() }
    single { DatabaseFactory(get()) }

    single<AuditLog> { ExposedAuditLog(get()) }
    single<AppSettingsRepository> { ExposedAppSettingsRepository(get(), get(), get()) }
    single<RequirementTemplateRepository> { ExposedRequirementTemplateRepository(get()) }
    single<ReferenceDataRepository> { ExposedReferenceDataRepository(get()) }
    single<HrUserRepository> { ExposedHrUserRepository(get()) }
    single<EmployeeRepository> { ExposedEmployeeRepository(get(), get()) }

    /**
     * One [JwtConfig] for both halves of the scheme (ERT-190).
     *
     * `plugin/Security.kt` verifies with it and [JwtIssuer] signs with it. Read from the environment
     * twice instead, the two would agree in every test that mints and verifies in one process and
     * differ only in a deployment nobody can reproduce locally.
     *
     * It must stay a `single` for a second, sharper reason: in dev the secret is generated per
     * instance, so a `factory` would sign with one key and verify with another, and every token this
     * application issued would be refused by the request that presented it.
     *
     * Constructed eagerly in `AppModule` alongside `TokenDigest`, because it is the other binding
     * whose construction can fail on configuration — outside dev a missing `JWT_SECRET` must abort
     * startup rather than become a 401 on the first sign-in.
     */
    single<JwtConfig> {
        JwtConfig.fromEnvironment(isDevMode()).also { configured ->
            configured.warnings.forEach { warning -> jwtWarnings += warning }
        }.config
    }

    single<AccessTokenIssuer> { JwtIssuer(get()) }

    // UploadLinkRepository          -> ExposedUploadLinkRepository
    // SubmissionRepository          -> ExposedSubmissionRepository
    // PortalSessionRepository       -> ExposedPortalSessionRepository
    // PortalAccessTrail             -> ExposedPortalAccessTrail
    // Notifier                      -> SmtpNotifier
    // DocumentStorage               -> ObjectStorageAdapter
}

/**
 * Configuration warnings raised while building [JwtConfig], drained by `AppModule` at startup.
 *
 * [JwtConfig] is a value object in `data/` and must not hold a logger — `org.slf4j` is the wrong
 * dependency for one, and the caller has an `Application.log` that tags the line with the right
 * logger anyway. A module body has no `Application` either, hence this hand-off rather than a direct
 * call. Written once during graph construction and read once at boot, both on the startup thread.
 */
internal val jwtWarnings = mutableListOf<String>()
