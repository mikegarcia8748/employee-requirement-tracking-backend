package com.pgsystem.employee.requirement.tracker.domain.port

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.*

/**
 * Persistence seams for the domain.
 *
 * Interfaces only — the domain names what it needs and the data layer supplies it. Every method is
 * `suspend` so the adapter chooses its own dispatcher; a use case never sees `Dispatchers.IO`.
 */

interface EmployeeRepository {
    suspend fun findById(id: PersonId): Employee?

    /**
     * Used for the PRD 8.1 duplicate check. Scoped to **active** hires: a completed or cancelled
     * hire sharing an address is not a collision worth warning about.
     */
    suspend fun findActiveByEmail(email: EmailAddress): List<Employee>

    suspend fun save(employee: Employee): Employee
    suspend fun requirementsOf(employeeId: PersonId): RequirementSet
    suspend fun saveRequirements(requirements: List<EmployeeRequirement>)
}

interface RequirementTemplateRepository {
    suspend fun findById(id: EntityId): RequirementTemplate?

    /**
     * The templates that make up the requirement set for an employment type.
     *
     * Read **once**, at hire creation, and copied onto the employee (PRD 5). Nothing downstream
     * may consult this again for an in-flight hire.
     */
    suspend fun findActiveForEmploymentType(employmentTypeId: EntityId): List<RequirementTemplate>

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

    suspend fun findActiveForEmployee(employeeId: PersonId): UploadLink?
    suspend fun save(link: UploadLink): UploadLink
}

interface SubmissionRepository {
    suspend fun findCurrentFor(employeeRequirementId: EntityId): Submission?
    suspend fun findVersions(employeeRequirementId: EntityId): List<Submission>
    suspend fun save(submission: Submission): Submission

    /**
     * Drop versions beyond the retention limit.
     *
     * Callers must not invoke this for a record with an open anomaly flag — the superseded version
     * is the evidence (PRD 7.1, SEC-13).
     */
    suspend fun purgeBeyondRetention(employeeRequirementId: EntityId, keep: Int)

    suspend fun totalBytesFor(employeeId: PersonId): Long
}

interface PortalSessionRepository {
    suspend fun findActive(sessionId: EntityId, now: java.time.Instant): PortalSession?
    suspend fun findActiveForLink(uploadLinkId: EntityId): List<PortalSession>
    suspend fun save(session: PortalSession): PortalSession

    /** Backs the P1 "HR can terminate active portal sessions" control. */
    suspend fun end(sessionId: EntityId, endedAt: java.time.Instant)
}

/**
 * The admin-configurable policy of PRD 6.4, stored in the database and read at runtime.
 *
 * There is no code path that hardcodes a duration; changing one must not need a deployment (8.10).
 *
 * **The only port here that returns a [DomainResult], because it is the only one whose stored data
 * can be wrong in a way that matters.** `app_setting` holds strings with a declared `value_type`, so
 * a row can be missing, unparseable, or outside its own stored bounds — and [LinkPolicy]'s Kotlin
 * defaults are *identical* to the seeded values, so an adapter that quietly fell back to them would
 * be indistinguishable from one that read the database. A corrupted `absolute_expiry_days` silently
 * becoming 90 is precisely the "well-meant edit turns a token into a permanent credential" case 6.4
 * warns about. The failure is therefore data a caller must handle, not an outcome it can miss.
 *
 * A caller must **not** recover by substituting a default. Propagate the error: refusing to issue a
 * link is the correct response to a policy nobody can read.
 */
interface AppSettingsRepository {
    suspend fun linkPolicy(): DomainResult<LinkPolicy>
    suspend fun updateLinkPolicy(policy: LinkPolicy, actor: String): DomainResult<Unit>
}
