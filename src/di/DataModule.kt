package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.data.db.DatabaseConfig
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import org.koin.dsl.module

/**
 * Adapters for the domain ports.
 *
 * Repository bindings are **deliberately absent** rather than stubbed with throwing placeholders.
 * An unbound port fails fast and loudly at wiring time; a placeholder that compiles fails at
 * runtime, in production, on the one path nobody exercised. Each binding lands in the same change
 * as the use case that needs it.
 */
val dataModule = module {
    single { DatabaseConfig.fromEnvironment() }
    single { DatabaseFactory(get()) }

    // EmployeeRepository            -> ExposedEmployeeRepository
    // RequirementTemplateRepository -> ExposedRequirementTemplateRepository
    // UploadLinkRepository          -> ExposedUploadLinkRepository
    // SubmissionRepository          -> ExposedSubmissionRepository
    // PortalSessionRepository       -> ExposedPortalSessionRepository
    // AppSettingsRepository         -> ExposedAppSettingsRepository
    // AuditLog                      -> ExposedAuditLog
    // PortalAccessTrail             -> ExposedPortalAccessTrail
    // Notifier                      -> SmtpNotifier
    // DocumentStorage               -> ObjectStorageAdapter
}
