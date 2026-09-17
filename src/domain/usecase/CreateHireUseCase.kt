package com.pgsystem.employee.requirement.tracker.domain.usecase

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.error.flatMap
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.AnomalyFlag
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.model.Employee
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.HireCreated
import com.pgsystem.employee.requirement.tracker.domain.model.PacketStatus
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementSet
import com.pgsystem.employee.requirement.tracker.domain.model.RequirementStatus
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import com.pgsystem.employee.requirement.tracker.domain.port.EmployeeRepository
import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import java.time.Instant

/**
 * What HR fills in (PRD §8.1).
 *
 * **The reference ids and the email arrive raw, and the acting user does not.** `EntityId.of` reports
 * `field = "id"`, but the API contract requires a body field's failure to *name that field* — so if
 * this command took `EntityId`, the route would have to parse and re-map, which is a business
 * decision in the one layer that must make none. [actingUserId] is different in kind: it comes from
 * the verified token rather than the body, so it is already typed by the time a handler has it. Same
 * split as `CreateHrUser.email` and `SetHrUserActive.userId`.
 *
 * [duplicateReason] is the typed §8.1 justification for proceeding past a duplicate. Blank is absent:
 * a space bar is not a justification.
 */
data class CreateHire(
    val firstName: String,
    val middleInitial: String?,
    val lastName: String,
    val departmentId: String,
    val position: String,
    val employmentTypeId: String,
    val email: String,
    val duplicateReason: String?,
    val actingUserId: PersonId,
)

/**
 * HR creates a hire (ERT-431, PRD §8.1).
 *
 * The first two sub-tasks of ERT-430: the rules that decide whether a hire may be created at all
 * (ERT-431), and the requirement set copied onto it from the catalogue (ERT-432). The link and its
 * stored expiry are ERT-433 and the invitation ERT-434 — so a hire created here still has no link,
 * deliberately, and the class grows across the four rather than being written once.
 *
 * ### The duplicate rule is a security control, not a nicety
 *
 * SEC-11 found that warn-and-proceed on a duplicate address is how two hires end up sharing a
 * mailbox, and how one person's documents reach another's link. §8.1 makes proceeding require a
 * **typed reason**, written to the audit log and surfaced in the §8.13 exception report. A boolean
 * `force` flag would satisfy the flow and destroy the point: the reason *is* the artefact.
 *
 * It is [AppError.ReasonRequired] and not [AppError.Conflict] (C1). The system does not refuse the
 * request — it asks for a justification and then proceeds, which is a statement about the request
 * being incomplete rather than about the resource being in a conflicting state. Routing it through
 * `Conflict` would also drop the `details` entry naming the reason field, which is the entire reason
 * `ReasonRequired` exists as a separate case.
 *
 * **[AppError] gained no case for this.** An earlier revision of this ticket, of `CLAUDE.md` and of
 * architecture §5 all named a `DuplicateEmailRequiresReason` that has never existed — three documents
 * prescribing a specification change nobody approved (C2, corrected 2026-09-16).
 *
 * ### The two reference checks are spelled out twice on purpose
 *
 * A shared `requireReference(raw, field, code, exists = ...)` helper reads better and would let a
 * caller pass `reference::departmentExists` for both ids — which is exactly the defect
 * [ReferenceDataRepository] split into two methods to make unrepresentable. Here the duplication is
 * the control.
 *
 * A malformed id folds into the same `*_unknown` failure rather than earning a second code. From
 * HR's side a 13-character id and a well-formed absent one have one remedy — pick from the list —
 * and two codes for one remedy is two branches in a picker that does not need them.
 *
 * ### The requirement set is a copy, and so is its order (ERT-432, PRD §5)
 *
 * [RequirementTemplateRepository.findActiveForEmploymentType] is read **once**, before anything is
 * written, and every field the checklist needs is copied onto the row: the name, the required flag,
 * and — since ERT-432 — the catalogue's `sortOrder`. Editing a template later must not change the
 * progress of anyone in flight, and reordering one must not reshuffle a checklist on a phone
 * mid-onboarding. A later reader reaching back to `requirement_templates` for any of the three
 * would undo that, which is why nothing downstream is given a way to.
 *
 * The sort order is **copied verbatim rather than re-derived** from the position in the catalogue
 * list. Numbering the rows 0, 1, 2 produces the identical order today and a different snapshot, and
 * a snapshot copies. It also makes the stored order reproduce the catalogue's own `sort_order, name`
 * exactly, which is what lets `EmployeeRepository.requirementsOf` return what was copied rather than
 * an approximation of it.
 *
 * ### An employment type with no active templates is refused, before the duplicate is examined
 *
 * A hire with an empty checklist is worse than a refused one: zero of zero required documents is
 * *complete*, so the record passes straight through the §8.5 validation loop without anyone
 * uploading anything.
 *
 * It is an [AppError.Validation] naming `employmentTypeId`, not an [AppError.Conflict]. Both
 * remedies belong to the field HR chose — pick another employment type, or have an admin configure
 * this one (§8.11) — and C1 settled that `Conflict` renders no `details` entry, so a form could not
 * say which picker to fix. Its own code rather than reusing `employment_type_unknown`, for E8's
 * reason one guard later: "that id is not in the list" is HR's mistake and "nothing is configured
 * for it" is an admin's, and one code would send both to the same place.
 *
 * **The guard runs before the duplicate check**, on the ordering rule this class already states: the
 * duplicate branch asks a human to type a justification that becomes a permanent audit artefact, and
 * asking for one on a request that is then going to fail on the catalogue is the worst available
 * ordering. Both orderings have a named test.
 *
 * ### There is still no transaction, and now it costs more
 *
 * [EmployeeRepository.create], [EmployeeRepository.saveRequirements] and [AuditLog.record] are three
 * calls with no seam between them, so a failure after the first leaves a hire whose checklist was
 * never written — a record nobody can complete. That is not fixable here: a transaction boundary in
 * the domain would mean a framework type in the layer that must hold none.
 *
 * What is fixable is the *order*. Everything that can refuse — the catalogue read included — happens
 * **before** the first write, so the reachable failure is a hire with no checklist rather than a
 * checklist with no hire, and every refusal leaves the repository untouched. The failure surfaces
 * rather than being swallowed, for the reason ERT-431 gives about the audit row: an `Ok` hiding it
 * would produce an unusable hire that every caller reads as a success.
 *
 * ### `create`, never `save`, and the return value is load-bearing
 *
 * A [PersonId] draws from 62^8, so the primary key is the collision backstop and
 * [EmployeeRepository.create] may redraw. It returns the hire **as stored**, which may carry a
 * different id than the argument, and the audit row, the result **and every requirement row** must
 * use it — writing the argument's id would name a hire that does not exist. On the requirement rows
 * that is a foreign key, so it is the second place this bites and it fails harder. `save` would be
 * worse than wrong: it reads an existing id as *update this row*, so a colliding draw would silently
 * overwrite someone else's hire.
 */
class CreateHireUseCase(
    private val employees: EmployeeRepository,
    private val reference: ReferenceDataRepository,
    private val templates: RequirementTemplateRepository,
    private val audit: AuditLog,
    private val clock: Clock,
    private val ids: EntityIdGenerator,
    private val personIds: PersonIdGenerator,
    private val tracer: UseCaseTracer,
) {
    suspend operator fun invoke(command: CreateHire): DomainResult<HireCreated> =
        tracer.trace("CreateHireUseCase") { execute(command) }

    private suspend fun execute(command: CreateHire): DomainResult<HireCreated> =
        EmailAddress.of(command.email).flatMap { email -> create(command, email) }

    /**
     * The ordered guards, then the write.
     *
     * **The reference ids are checked before the duplicate**, and the order is a rule rather than a
     * preference: the duplicate branch asks a human to type a justification that becomes a permanent
     * audit artefact. Asking for one on a request that is then going to fail on a bad department is
     * the worst available ordering — and it would confirm an address is in use on a request that was
     * never going to succeed. **The empty-catalogue guard sits in the same position for the same
     * reason**, which is why the catalogue is read here rather than after the duplicate is resolved:
     * an employment type nobody has configured is the same class of doomed request. Email comes
     * first of all because [EmployeeRepository.findActiveByEmail] cannot be called until it has an
     * [EmailAddress].
     */
    private suspend fun create(command: CreateHire, email: EmailAddress): DomainResult<HireCreated> {
        missing(command.firstName, "first_name.required", "firstName", "first name")?.let { return it.asErr() }
        missing(command.lastName, "last_name.required", "lastName", "last name")?.let { return it.asErr() }
        missing(command.position, "position.required", "position", "position")?.let { return it.asErr() }

        val departmentId = (EntityId.of(command.departmentId) as? DomainResult.Ok)?.value
        if (departmentId == null || !reference.departmentExists(departmentId)) {
            return AppError.Validation(
                code = "department_unknown",
                field = "departmentId",
                detail = "No department with that id",
            ).asErr()
        }

        val employmentTypeId = (EntityId.of(command.employmentTypeId) as? DomainResult.Ok)?.value
        if (employmentTypeId == null || !reference.employmentTypeExists(employmentTypeId)) {
            return AppError.Validation(
                code = "employment_type_unknown",
                field = "employmentTypeId",
                detail = "No employment type with that id",
            ).asErr()
        }

        // Read once and held, which is the whole of PRD 5's snapshot rule on this side: nothing
        // downstream may consult the catalogue again for a hire already in flight.
        val catalogue = templates.findActiveForEmploymentType(employmentTypeId)
        if (catalogue.isEmpty()) {
            return AppError.Validation(
                code = "employment_type_no_requirements",
                field = "employmentTypeId",
                detail = "No active requirements are configured for that employment type",
            ).asErr()
        }

        val duplicates = employees.findActiveByEmail(email)

        // Non-null exactly when a duplicate was overridden, and it carries the reason. One value
        // rather than two, so the SHARED_EMAIL flag and the DUPLICATE_EMAIL_OVERRIDDEN row cannot
        // disagree about whether an override happened -- a property rather than a pair of tests that
        // happen to agree. A reason supplied where there is no duplicate is simply ignored: refusing
        // would 422 a valid request from an officer who typed one and then corrected the address,
        // and recording it anyway would give §8.13's report a false positive.
        val override = if (duplicates.isEmpty()) {
            null
        } else {
            command.duplicateReason?.trim()?.takeIf { it.isNotEmpty() }
                ?: return AppError.ReasonRequired(
                    code = "duplicate_email.reason_required",
                    action = "create_hire",
                ).asErr()
        }

        // Read once, so the hire's createdAt and both audit rows cannot drift apart. No test can
        // enforce that under a fixed clock; it is a rule the code has to keep on its own.
        val now = clock.now()

        val stored = employees.create(
            Employee(
                id = personIds.newPersonId(),
                firstName = command.firstName.trim(),
                middleInitial = command.middleInitial?.trim()?.takeIf { it.isNotEmpty() },
                lastName = command.lastName.trim(),
                departmentId = departmentId,
                position = command.position.trim(),
                employmentTypeId = employmentTypeId,
                email = email,
                packetStatus = PacketStatus.DRAFT_COLLECTING,
                submittedAt = null,
                submittedByHr = false,
                attestation = null,
                originalsSightedAt = null,
                originalsSightedBy = null,
                // SHARED_EMAIL freezes version retention today, because Employee derives
                // retentionFrozen from anomalyFlags.isNotEmpty() -- while the API contract says this
                // flag must not (E3). AnomalyFlag.freezesRetention is named by four documents and
                // exists in none of the code; ERT-734 owns landing it, and this is the first code
                // that ever sets the flag, so it now has a record to exercise.
                anomalyFlags = if (override != null) setOf(AnomalyFlag.SHARED_EMAIL) else emptySet(),
                completedAt = null,
                createdAt = now,
                createdBy = command.actingUserId,
            )
        )

        // Built from the id `create` returned, never from the one that was drawn -- on these rows
        // it is a foreign key. Each row draws its own EntityId: reusing one produces a set that
        // reads correctly here and collapses to a single row in a table keyed by id.
        val requirements = catalogue.map { template ->
            EmployeeRequirement(
                id = ids.newEntityId(),
                employeeId = stored.id,
                templateId = template.id,
                nameSnapshot = template.name,
                isRequiredSnapshot = template.isRequired,
                sortOrderSnapshot = template.sortOrder,
                status = RequirementStatus.PENDING,
                rejectionCount = 0,
            )
        }
        employees.saveRequirements(requirements)

        audit.record(
            entry(
                action = AuditAction.HIRE_CREATED,
                actor = command.actingUserId,
                subject = stored.id,
                at = now,
                // The address is the §8.13 join key, and it stays true as history after an ERT-460
                // email change. Nothing else from the row is copied here: a second copy of a column
                // is the drift E4 names.
                metadata = mapOf("email" to stored.email.value),
            )
        )

        if (override != null) {
            audit.record(
                entry(
                    action = AuditAction.DUPLICATE_EMAIL_OVERRIDDEN,
                    actor = command.actingUserId,
                    subject = stored.id,
                    at = now,
                    // A separate action rather than an outcome in HIRE_CREATED's metadata, for the
                    // reason SIGN_IN_FAILED is separate from SIGN_IN_SUCCEEDED: §8.13 asks "which
                    // records share an address", and that is a filter on `action` rather than a
                    // substring scan of every creation row's unindexed metadata text.
                    //
                    // `duplicateOf` is not decoration. Only the NEW hire carries SHARED_EMAIL -- the
                    // hire it duplicates is equally a record sharing an address and is deliberately
                    // left untouched, so this is what lets the report recover the pair.
                    metadata = mapOf(
                        "email" to stored.email.value,
                        "reason" to override,
                        "duplicateOf" to duplicates.joinToString(",") { it.id.value },
                    ),
                )
            )
        }

        return HireCreated(stored, RequirementSet(requirements)).asOk()
    }

    /**
     * A required text field that was not supplied.
     *
     * **Nothing else in the system enforces this.** `employees.first_name`, `last_name` and
     * `position` are `not null` with no check constraint, so `""` stores cleanly and the hire appears
     * in HR's list as a blank row. ERT-433/434 are the token and the invitation; ERT-450 is a thin
     * route that makes no decisions; the next candidate owner is Phase 2. So the rule was nobody's,
     * which is the roadmap's own recurring lesson about a gap without a number.
     *
     * Taken here because `CreateHrUserUseCase` already runs exactly this rule on exactly this kind
     * of field (`full_name.required`), so omitting it in the sibling use case would be an
     * inconsistency rather than a boundary. Added to the ticket's criteria as `[derived]`.
     *
     * A helper with parameters is safe where the reference checks' would not have been: the code and
     * the field travel together in one call, so there is no way to name one field and check another.
     */
    private fun missing(value: String, code: String, field: String, label: String): AppError.Validation? =
        if (value.isBlank()) AppError.Validation(code = code, field = field, detail = "A $label is required") else null

    /** One spelling of an employee audit row, because this use case writes two. */
    private fun entry(
        action: AuditAction,
        actor: PersonId,
        subject: PersonId,
        at: Instant,
        metadata: Map<String, String>,
    ): AuditEntry = AuditEntry(
        id = ids.newEntityId(),
        actor = actor.value,
        actorUserId = actor,
        action = action,
        entity = Employee.AUDIT_ENTITY,
        entityId = subject,
        timestamp = at,
        metadata = metadata,
    )
}
