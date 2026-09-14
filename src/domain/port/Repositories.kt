package com.pgsystem.employee.requirement.tracker.domain.port

import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.domain.model.*
import java.util.UUID

/**
 * Persistence seams for the domain.
 *
 * Interfaces only — the domain names what it needs and the data layer supplies it. Every method is
 * `suspend` so the adapter chooses its own dispatcher; a use case never sees `Dispatchers.IO`.
 */

interface EmployeeRepository {
    suspend fun findById(id: UUID): Employee?

    /**
     * Used for the PRD 8.1 duplicate check. Scoped to **active** hires: a completed or cancelled
     * hire sharing an address is not a collision worth warning about.
     */
    suspend fun findActiveByEmail(email: EmailAddress): List<Employee>

    suspend fun save(employee: Employee): Employee
    suspend fun requirementsOf(employeeId: UUID): RequirementSet
    suspend fun saveRequirements(requirements: List<EmployeeRequirement>)
}

interface RequirementTemplateRepository {
    suspend fun findById(id: UUID): RequirementTemplate?

    /**
     * The templates that make up the requirement set for an employment type.
     *
     * Read **once**, at hire creation, and copied onto the employee (PRD 5). Nothing downstream
     * may consult this again for an in-flight hire.
     */
    suspend fun findActiveForEmploymentType(employmentTypeId: UUID): List<RequirementTemplate>

    suspend fun findAll(includeInactive: Boolean = false): List<RequirementTemplate>
}

interface UploadLinkRepository {
    /**
     * Resolve a link by the hash of a presented token.
     *
     * Takes a hash, not plaintext: the plaintext token exists only in the invitation email, and a
     * lookup by plaintext would imply it was recoverable from storage.
     */
    suspend fun findByTokenHash(tokenHash: String): UploadLink?

    suspend fun findActiveForEmployee(employeeId: UUID): UploadLink?
    suspend fun save(link: UploadLink): UploadLink
}

interface SubmissionRepository {
    suspend fun findCurrentFor(employeeRequirementId: UUID): Submission?
    suspend fun findVersions(employeeRequirementId: UUID): List<Submission>
    suspend fun save(submission: Submission): Submission

    /**
     * Drop versions beyond the retention limit.
     *
     * Callers must not invoke this for a record with an open anomaly flag — the superseded version
     * is the evidence (PRD 7.1, SEC-13).
     */
    suspend fun purgeBeyondRetention(employeeRequirementId: UUID, keep: Int)

    suspend fun totalBytesFor(employeeId: UUID): Long
}

interface PortalSessionRepository {
    suspend fun findActive(sessionId: UUID, now: java.time.Instant): PortalSession?
    suspend fun findActiveForLink(uploadLinkId: UUID): List<PortalSession>
    suspend fun save(session: PortalSession): PortalSession

    /** Backs the P1 "HR can terminate active portal sessions" control. */
    suspend fun end(sessionId: UUID, endedAt: java.time.Instant)
}

/**
 * The admin-configurable policy of PRD 6.4, stored in the database and read at runtime.
 *
 * There is no code path that hardcodes a duration; changing one must not need a deployment (8.10).
 */
interface AppSettingsRepository {
    suspend fun linkPolicy(): LinkPolicy
    suspend fun updateLinkPolicy(policy: LinkPolicy, actor: String)
}
