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

/**
 * HR staff accounts (ERT-190, PRD §14 Q4).
 *
 * **[findByEmail] is the sign-in path and it is the only lookup that takes an address.** It must
 * match case-insensitively, which costs nothing here because [EmailAddress.of] lower-cases on
 * construction — every address that reaches this port is already normalised. The adapter still holds
 * a case-insensitive unique index, because normalising in Kotlin protects rows this application
 * writes and nothing else: a row inserted by a migration, an import, or a person at a psql prompt
 * would otherwise let one address exist twice and make "which account signs in" a race.
 *
 * **[countAll] rather than `isEmpty`.** Bootstrap asks "is the table empty", and a count answers that
 * plus the question an operator asks next. It is called once at startup, so the scan is not a cost
 * worth designing around.
 *
 * Returns nullable rather than [DomainResult]: an absent user is an ordinary answer on the sign-in
 * path and must stay indistinguishable from a wrong password, so it cannot be an error the caller is
 * pushed to render differently. See `AppSettingsRepository` for the one case that does differ.
 */
interface HrUserRepository {
    suspend fun findById(id: PersonId): HrUser?

    /** The sign-in lookup. [EmailAddress] is already lower-cased; the index is case-insensitive too. */
    suspend fun findByEmail(email: EmailAddress): HrUser?

    /** Every account, in email order, for the administration surface. Deactivated ones included. */
    suspend fun findAll(): List<HrUser>

    /** Insert or update by [HrUser.id]. Returns what was stored. */
    suspend fun save(user: HrUser): HrUser

    /** How many accounts exist. Read once at startup to decide whether to bootstrap. */
    suspend fun countAll(): Long
}

interface EmployeeRepository {
    suspend fun findById(id: PersonId): Employee?

    /**
     * Used for the PRD 8.1 duplicate check. Scoped to **active** hires: a completed or cancelled
     * hire sharing an address is not a collision worth warning about.
     */
    suspend fun findActiveByEmail(email: EmailAddress): List<Employee>

    /**
     * Insert a new hire, **redrawing the identifier if it is already taken**.
     *
     * Returns what was stored, which may carry a *different* [Employee.id] than the one passed in.
     * That is the whole reason this returns an `Employee` rather than `Unit`: the caller generated a
     * candidate id, and only the database can say whether it survived.
     *
     * **Why a retry lives here and nowhere else.** A [PersonId] draws from 62^8, so the primary key
     * is the collision backstop rather than a formality. It matters most under PRD 8.2 CSV bulk
     * import, which creates many hires in one action and reports created, skipped and failed counts
     * — a collision must be retried silently and never surface to HR as a failed row. An [EntityId]
     * draws from 62^12, where a collision is negligible, so those inserts need no retry.
     * `SecurePersonIdGenerator` cannot do this for itself: a value object cannot know what the
     * database already holds.
     *
     * **Separate from [save] so the adapter never has to guess.** With one upsert, a brand-new hire
     * whose id collided would be indistinguishable from an edit of the hire already at that id, and
     * the adapter would overwrite a stranger's record rather than redraw. Creation and modification
     * are different operations and the port now says so.
     */
    suspend fun create(employee: Employee): Employee

    /**
     * Persist changes to a hire that already exists. Never inserts, and never changes an id.
     *
     * An id with no row behind it is a programming error rather than a new hire — see [create].
     */
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

/**
 * The seeded reference data a hire is created against (ERT-350, PRD 8.1, 8.2, 11).
 *
 * `Employee` names a `departmentId` and an `employmentTypeId`, and the tables have existed since the
 * baseline migration, but nothing exposed them — so HR had no way to offer the real list, and hire
 * creation had no way to reject an id that does not exist. [EntityId.of] proves an id is well
 * **formed**; proving it **exists** is what this port adds.
 *
 * **Two existence checks rather than one, deliberately.** A department id and an employment type id
 * are both 12-character [EntityId]s and structurally indistinguishable, so a single
 * `exists(id: EntityId)` scanning both tables would answer `true` for a department id handed in the
 * `employmentTypeId` slot — the exact defect 8.2 is about ("flagged rather than silently creating a
 * new one"), reached through the validator meant to prevent it. Separate methods make the mix-up
 * unrepresentable, the same device `Notifier.sendInvitation` and
 * `UploadLinkRepository.findByTokenHash` use. It is also what lets a caller *name* which id was
 * wrong, which the 8.1 acceptance criterion requires.
 *
 * They return `Boolean` rather than the entity. A caller holding a `Department` will eventually
 * denormalise its name onto the employee, and 11 models that as a foreign key; the rule here needs
 * the fact, not the row. Resolution **by name** is a different method, and belongs with 8.2's CSV
 * import rather than being built ahead of it.
 */
interface ReferenceDataRepository {
    /** Every department, in name order. An empty list is data, not a failure. */
    suspend fun findDepartments(): List<Department>

    /**
     * Every employment type, in **name** order.
     *
     * `employment_types` carries no `sort_order` column, so a deliberate HR ordering is
     * unrepresentable — name order is the only deterministic choice that is not insertion order
     * wearing a disguise. Recorded against 8.11, which is where a sort column would be added.
     */
    suspend fun findEmploymentTypes(): List<EmploymentType>

    suspend fun departmentExists(id: EntityId): Boolean

    suspend fun employmentTypeExists(id: EntityId): Boolean
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
    /**
     * [actor] is a [PersonId] rather than free text since ERT-190.
     *
     * `app_settings.updated_by` is one of the four actor columns that became a `users(id)` foreign
     * key: "who last raised the absolute expiry ceiling" is a question about a person, and a typed-in
     * name cannot answer it. The database refuses an id with no row behind it.
     */
    suspend fun updateLinkPolicy(policy: LinkPolicy, actor: PersonId): DomainResult<Unit>
}
