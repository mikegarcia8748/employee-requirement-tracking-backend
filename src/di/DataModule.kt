package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseConfig
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAppSettingsRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedAuditLog
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.data.repository.ExposedRequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AppSettingsRepository
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
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

    // EmployeeRepository            -> ExposedEmployeeRepository
    // UploadLinkRepository          -> ExposedUploadLinkRepository
    // SubmissionRepository          -> ExposedSubmissionRepository
    // PortalSessionRepository       -> ExposedPortalSessionRepository
    // PortalAccessTrail             -> ExposedPortalAccessTrail
    // Notifier                      -> SmtpNotifier
    // DocumentStorage               -> ObjectStorageAdapter
}
