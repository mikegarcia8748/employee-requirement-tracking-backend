# ERT-400 · Epic: Hire creation

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-300 |
| **PRD** | §8.1, §5, §6.4, §6.6 |
| **Architecture** | §5, §8, §12 invariants 4 and 6 |

**Description**

The first business capability, and the one every other Phase 1 flow starts from. Creating a hire does
six things at once — validates an email, checks it against active hires, snapshots a requirement set,
issues a token and a PIN, computes an expiry from stored policy, and sends an invitation — which is
why [`CreateHireUseCase`](../../docs/architecture.md) is split into four sub-tasks rather than
attempted in one sitting.

Two of those six are load-bearing beyond this epic. The **snapshot** is what stops a later template
edit from moving an in-flight hire's progress (§5). The **computed `expiresAt`** is what stops a
later policy change from silently extending or killing links already in the wild (§6.4). Both are
copies, deliberately, not joins.

The invitation is the only message in the entire system that carries the access PIN. That is
enforced by the shape of [`Notifier`](../../src/domain/port/Notifier.kt): only `sendInvitation`
accepts an `AccessPin`, so §8.9's "no email but the invitation contains the PIN" is a compile-time
property rather than a review checklist item. Do not add an `AccessPin` parameter to any other
method.

**Goal**

HR can create a hire over HTTP; the hire appears at 0% progress with a snapshotted requirement set, a
hashed token and PIN, a stored expiry, and a recorded invitation — and a delivery failure does not
lose the record.

**Stories**
- As an HR Officer, I want a hire to exist the moment I save so that I can track it whether or not the
  invitation lands.
- As an HR Officer, I want a duplicate email to stop me and demand a reason so that two records for
  one person is a deliberate act rather than an accident.

**Out of scope**
- CSV import (§8.2) — Phase 3.
- Editing a hire or changing an email (§8.8) — Phase 2, and gated on Q16.

---

## ERT-410 — `EmployeeRepository` adapter and row↔domain mapper

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-240 |
| **PRD** | §8.1, §11, §7.1 |
| **Architecture** | §4, §7 |

**Description**

Five methods on [`EmployeeRepository`](../../src/domain/port/Repositories.kt). The mapping is
mostly mechanical, with three places where it is not.

`findActiveByEmail` is scoped to **active** hires, as the port's comment says: a completed or
cancelled hire sharing an address is not a collision worth warning about. `anomaly_flags` is a
delimited string in the schema and a `Set<AnomalyFlag>` in the domain, and it drives
[`Employee.retentionFrozen`](../../src/domain/model/Employee.kt) — get the round-trip wrong and
the §7.1 retention freeze silently stops working. And the three attestation columns map to one
nullable [`Attestation`](../../src/domain/model/Employee.kt) object: all three present or all three
absent, never a half-populated attestation.

**Goal**

An `Employee` round-trips through the database without loss, including flags and attestation, and
the active-email lookup honours its scope.

**Stories**
- As an engineer on the next session, I want employee persistence to be one call so that use cases
  never see a column.

**Acceptance criteria**
- [ ] `[derived]` Given an `Employee` with every field populated, when saved and re-read, then it is
      equal to the original
- [ ] `[derived]` Given a completed or cancelled hire sharing an email, when `findActiveByEmail` is
      called, then it is not returned
- [ ] `[derived]` Given an employee with two anomaly flags, when re-read, then both are present and
      `retentionFrozen` is true
- [ ] `[derived]` Given an employee with no attestation, then all three attestation columns are null
      and `attestation` is null on re-read
- [ ] `[derived]` Given `requirementsOf`, then a `RequirementSet` is returned whose progress
      arithmetic matches the stored rows
- [ ] `[derived]` Given a generated `PersonId` that collides with an existing row, when the insert is
      attempted, then a fresh id is drawn and the save succeeds rather than surfacing the conflict
- [ ] `[derived]` Given repeated collisions, then the retry gives up after a bounded number of
      attempts and fails loudly rather than looping

> **Why a retry is needed here and nowhere else.** A `PersonId` draws from 62^8, so the primary key
> is the collision backstop and a duplicate draw fails the insert. This matters most under §8.2 CSV
> bulk import, which creates many hires in one action and reports created, skipped and failed counts
> — a collision must be retried silently, never reported to HR as a failed row. An `EntityId` draws
> from 62^12, where a collision is negligible, so those inserts need no retry. `SecurePersonIdGenerator`
> cannot do this itself: a value object cannot know what the database already holds.

**Tests**
| Level | Test |
|---|---|
| Repository | `employee persistence - a fully populated hire - round-trips unchanged` |
| Repository | `id collision - the generated id is already taken - a fresh id is drawn and the save succeeds` |
| Repository | `active email lookup - a completed hire sharing the address - is not returned` |
| Repository | `anomaly flags - two flags stored - round-trip and report retention frozen` |
| Repository | `attestation mapping - an unattested packet - round-trips as null` |

**Files**
- create `src/data/repository/ExposedEmployeeRepository.kt`
- create `src/data/mapper/EmployeeMapper.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/repository/ExposedEmployeeRepositoryTest.kt`

**Out of scope**
- Search and filtering for the hire list. That is ERT-510, which extends this adapter.

---

## ERT-420 — `UploadLinkRepository` adapter, resolved by token hash

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-160, ERT-240 |
| **PRD** | §6.3, §6.4, §12 |
| **Architecture** | §4, §12 invariant 4 |

**Description**

[`findByTokenHash`](../../src/domain/port/Repositories.kt) takes a hash, never plaintext, and the
port says why: the plaintext token exists only in the invitation email, and a lookup by plaintext
would imply it was recoverable from storage. Do not add a by-plaintext overload for convenience.

ERT-160 resolved the primitive this adapter depends on: the token is digested with a keyed HMAC so
lookup is reproducible, while the PIN stays on bcrypt. Use `TokenDigest` here, not `Hasher` — using
bcrypt for `tokenHash` compiles, passes a round-trip test written against a single in-memory row,
and fails only when a *second* link exists.

`LinkScope` is a sealed type persisted into a string column. v1 always writes `All`, but `Only` must
round-trip, because Phase 4 renewal links depend on that seam being open (§9.3).

**Goal**

A link is resolvable by presented token without the plaintext ever being stored or recoverable, and
`LinkScope` round-trips in both forms.

**Stories**
- As a New Hire, I want my link to be unrecoverable from a stolen database so that a breach does not
  hand anyone my documents.

**Acceptance criteria**
- [ ] `[derived]` Given a link, when looked up by the hash of its token, then it is returned
- [ ] `[derived]` Given the adapter, then no method accepts a plaintext token
- [ ] `[derived]` Given the same plaintext token presented twice, then both lookups resolve to the
      same row — the token digest is deterministic
- [ ] `[derived]` Given a stored link, then neither the plaintext token nor the plaintext PIN is
      recoverable from any column
- [ ] `[derived]` Given `LinkScope.Only`, then it round-trips with its template ids intact
- [ ] `[derived]` Given an employee with a revoked link and a new active one, then
      `findActiveForEmployee` returns only the active one

**Tests**
| Level | Test |
|---|---|
| Repository | `link lookup - the hash of a presented token - resolves the link` |
| Repository | `link lookup - the same token presented twice - resolves to the same row` |
| Repository | `link persistence - a stored link - exposes no plaintext token or pin` |
| Repository | `link scope - Only with two template ids - round-trips intact` |
| Repository | `active link lookup - a revoked link and an active one - returns only the active one` |

**Files**
- create `src/data/repository/ExposedUploadLinkRepository.kt`
- create `src/data/mapper/UploadLinkMapper.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/repository/ExposedUploadLinkRepositoryTest.kt`

**Out of scope**
- Expiry evaluation and lockout logic. Those are use-case rules, in ERT-640 and ERT-1020.

---

## ERT-430 — `CreateHireUseCase`

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket — **split into ERT-431…434** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-210, ERT-230, ERT-310, ERT-320, ERT-350, ERT-410, ERT-420, ERT-440 |
| **PRD** | §8.1, §5, §6.4, §6.6 |
| **Architecture** | §5, §8 |

**Description**

One class, one `operator fun invoke`, returning `DomainResult`. Built test-first against the ERT-210
fakes with no container and no database. Four sub-tasks, each one red-green-refactor cycle; the class
grows across them rather than being written once.

Order the sub-tasks as listed. Each leaves the suite green, and 431 establishes the result type the
other three extend.

**Goal**

The use case satisfies every §8.1 acceptance criterion against fakes, before any route exists.

**Out of scope**
- HTTP. That is ERT-450.

---

### ERT-431 — Email validation and duplicate-on-active with typed reason

| | |
|---|---|
| **Parent** | ERT-430 |
| **Type** | Sub-task |
| **Depends on** | ERT-210, ERT-230 |
| **PRD** | §8.1 |

**Description**

[`EmailAddress`](../../src/core/value/EmailAddress.kt) already validates format and normalises case,
so the use case's job is to surface a failure rather than re-implement the check.

The duplicate rule is a security control, not a nicety. SEC-11 found that warn-and-proceed on a
duplicate address is how two hires end up sharing a mailbox, and how one person's documents reach
another's link. §8.1 makes proceeding require a **typed reason**, written to the audit log and
surfaced in the exception report. A boolean `force` flag would defeat the point entirely: the reason
is the artefact.

**Acceptance criteria**
- [ ] Given an invalid email format, then the form blocks submission with a field-level message
      (§8.1)
- [ ] Given a duplicate email on an active hire, then HR sees a warning and must enter a typed reason
      before proceeding, which is written to the audit log and surfaced in the exception report
      (§8.1)
- [ ] `[derived]` Given a duplicate and a supplied reason, then the hire is created and carries the
      `SHARED_EMAIL` anomaly flag
- [ ] `[derived]` Given a duplicate email belonging to a completed or cancelled hire, then no reason
      is required
- [ ] `[derived]` Given an empty or whitespace-only reason, then it is treated as absent

**Tests**
| Level | Test |
|---|---|
| Use case | `hire creation - an invalid email format - fails with a field-level validation error` |
| Use case | `hire creation - email duplicates an active hire with no reason given - fails with DuplicateEmailRequiresReason` |
| Use case | `hire creation - email duplicates an active hire with a typed reason - creates the hire and records the reason` |
| Use case | `hire creation - a duplicate with a reason - flags the record as shared email` |
| Use case | `hire creation - email duplicates a cancelled hire - needs no reason` |
| Use case | `hire creation - a whitespace-only reason - is treated as no reason given` |

**Files**
- create `src/domain/usecase/CreateHireUseCase.kt`
- create `test/domain/usecase/CreateHireUseCaseTest.kt`

---

### ERT-432 — Requirement-set snapshot from the template catalogue

| | |
|---|---|
| **Parent** | ERT-430 |
| **Type** | Sub-task |
| **Depends on** | ERT-431 |
| **PRD** | §5, §8.11, §6.5 |

**Description**

The requirement set is **copied** onto the employee at creation, never read live afterwards (§5).
`name_snapshot` and `is_required_snapshot` are the copies. Editing a template later must not change
the progress of anyone in flight, and must never make a completed hire retroactively incomplete.

The test that proves this is the one that mutates the catalogue **after** creation and asserts the
hire is unchanged. Without it the rule is only an intention, and a future refactor that "simplifies"
the snapshot into a join will pass every other test in the suite.

**Acceptance criteria**
- [ ] Given a hire is created, then the hire appears at 0% progress (§8.1)
- [ ] `[derived]` Given an employment type, then one `employee_requirement` is created per active
      template, each carrying a name and required-flag copy
- [ ] `[derived]` Given the catalogue is changed after creation, then the hire's requirement names and
      required flags are unchanged
- [ ] Given a requirement is optional, then it is excluded from the progress denominator (§8.11)
- [ ] `[derived]` Given every requirement starts at `PENDING`, then submission and approval progress
      are both zero
- [ ] `[derived]` Given an employment type with no templates assigned, then creation fails rather than
      producing a hire with an empty checklist

**Tests**
| Level | Test |
|---|---|
| Use case | `requirement snapshot - a hire is created - one requirement per active template` |
| Use case | `requirement snapshot - the template is renamed afterwards - the hire keeps the original name` |
| Use case | `requirement snapshot - a template becomes optional afterwards - the hire keeps the original required flag` |
| Use case | `requirement snapshot - an optional requirement - is excluded from the denominator` |
| Use case | `hire creation - an employment type with no templates - fails rather than creating an empty checklist` |

---

### ERT-433 — Token and PIN issue, hashed, with `expiresAt` computed from policy

| | |
|---|---|
| **Parent** | ERT-430 |
| **Type** | Sub-task |
| **Depends on** | ERT-432 |
| **PRD** | §6.4, §6.6, §8.1, §12 |

**Description**

The link is created with the hire. `TokenGenerator` and `PinGenerator` produce the plaintext,
`Hasher` and the ERT-420 token digest store it, and neither plaintext survives past the invitation.

`expiresAt` is computed from the policy read at this moment and **stored**, exactly like the
requirement snapshot. Changing `link.absolute_expiry_days` tomorrow must not move this link (§6.4).
The idle clock is the second of two clocks — when `idleExpiryDays` is 0 it is disabled and
`idleExpiresAt` is null, and where both apply the earlier one wins.

Six digits, not four: a million combinations instead of ten thousand, at no usability cost.

**Acceptance criteria**
- [ ] Given a hire is created, then a 6-digit access PIN is generated, stored hashed, and included in
      the invitation email (§8.1)
- [ ] `[derived]` Given a link is issued, then neither the plaintext token nor the plaintext PIN is
      persisted anywhere
- [ ] Given an admin changes an expiry setting, then links already issued keep their stored
      `expires_at` (§8.10)
- [ ] `[derived]` Given a policy with `absoluteExpiryDays = 90`, then `expiresAt` is 90 days after
      `issuedAt` as measured by the injected clock
- [ ] Given `link.idle_expiry_days` is `0`, then `idleExpiresAt` is null and only the absolute ceiling
      applies (§6.4)
- [ ] `[derived]` Given a new link, then its status is `ACTIVE`, `failedPinCount` is 0 and
      `extendedCount` is 0

**Tests**
| Level | Test |
|---|---|
| Use case | `link issue - a hire is created - stores a hashed token and a hashed six-digit pin` |
| Use case | `link issue - a link is issued - persists no plaintext credential` |
| Use case | `link expiry - policy of ninety days - stores an expiry ninety days after issue` |
| Use case | `link expiry - the policy changes after issue - the stored expiry is unchanged` |
| Use case | `link expiry - idle days of zero - stores no idle expiry` |

---

### ERT-434 — Invitation dispatch and surviving delivery failure

| | |
|---|---|
| **Parent** | ERT-430 |
| **Type** | Sub-task |
| **Depends on** | ERT-433, ERT-440 |
| **PRD** | §8.1, §8.9, §6.6 |

**Description**

Delivery is fallible and the hire is created regardless — §8.1 requires a failure indicator and a
retry action, not a lost record. [`DeliveryResult`](../../src/domain/port/Notifier.kt) models this
explicitly, so a `Failed` result must not roll back the creation.

The invitation is the one message carrying both halves of the credential. Every later message points
at the portal without restating the PIN, so a forwarded rejection notice or expiry warning carries
nothing useful. The `Notifier` shape enforces this; this sub-task must not work around it.

**Acceptance criteria**
- [ ] Given a hire is created, then the invite email is sent within 1 minute (§8.1)
- [ ] Given email delivery fails, then HR sees a failure indicator on the record and a retry action
      (§8.1)
- [ ] `[derived]` Given delivery fails, then the hire, its requirement set and its link still exist
      and are unchanged
- [ ] Given any email other than the invitation, then it never contains the access PIN (§8.9) —
      structurally guaranteed by the `Notifier` signature
- [ ] `[derived]` Given a successful creation, then an audit entry records the creating actor and
      timestamp

**Tests**
| Level | Test |
|---|---|
| Use case | `invitation - a hire is created - sends one invitation carrying the link and the pin` |
| Use case | `invitation - delivery fails - the hire and its link still exist` |
| Use case | `invitation - delivery fails - the result reports the failure so HR can retry` |
| Use case | `hire creation - a successful creation - records an audit entry naming the actor` |

---

## ERT-440 — `Notifier` dev adapter: outbox table, no SMTP

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-120 |
| **PRD** | §8.9 |
| **Architecture** | §4 |

**Description**

**Q12 — the email delivery mechanism and sending domain — is unanswered**, and no mail library is
declared. Rather than block hire creation on it, `Notifier` is implemented as an outbox: each send
writes a row with recipient, kind, body and status. ERT-1010 later drains that outbox through a real
transport, and no use case changes when it does.

The outbox is genuinely useful beyond being a placeholder. It gives the §8.1 "retry action" something
to retry, makes "was the invitation sent?" answerable, and survives a restart in a way an in-memory
queue does not.

One rule applies to the outbox as much as to the audit log: the PIN appears in the invitation body
and nowhere else. Storing rendered bodies means the invitation row contains a live credential, so
that row must be purged once delivered, or the PIN redacted from the stored copy. Decide it here and
write it down.

**Goal**

Every `Notifier` method persists a durable record of what would be sent, retryable and inspectable,
with no live credential left sitting in the table.

**Stories**
- As an HR Officer, I want a failed invitation to be retryable so that a transient mail problem does
  not mean re-creating the hire.
- As an engineer on the next session, I want email to be swappable so that Q12 costs an adapter, not
  a redesign.

**Acceptance criteria**
- [ ] `[derived]` Given each of the seven `Notifier` methods, when called, then a row is written with
      recipient, kind, payload and a pending status
- [ ] `[derived]` Given the invitation row after delivery, then it retains no usable PIN
- [ ] `[derived]` Given a row, then it can be marked sent or failed, and a failed row can be retried
- [ ] `[derived]` Given the adapter, then it returns `DeliveryResult.Sent` on a successful write and
      `Failed` when the write fails
- [ ] `[derived]` Given the outbox table, then it is added by a migration, not by `SchemaUtils`

**Tests**
| Level | Test |
|---|---|
| Repository | `outbox notifier - an invitation is sent - writes a pending row for the recipient` |
| Repository | `outbox notifier - a delivered invitation - retains no usable pin` |
| Repository | `outbox notifier - a failed row - can be retried` |

**Files**
- create `resources/db/migration/V4__notification_outbox.sql`
- create `src/data/notify/OutboxNotifier.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/notify/OutboxNotifierTest.kt`

**Out of scope**
- Actually sending mail, and the expiry-warning scheduler. ERT-1010 and ERT-1020.

---

## ERT-450 — `POST /api/employees` and its DTOs

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-140, ERT-430 |
| **PRD** | §8.1, Appendix B |
| **Architecture** | §3, §8, §9 |

**Description**

A thin adapter: parse the body, call `CreateHireUseCase`, map the sealed result onto a status. The
route makes no decision — every rule was settled and tested in ERT-431…434.

The response must not echo the PIN or the plaintext token. They travel in the invitation email and
nowhere else; returning them to the creating HR client would put a live credential into a browser,
a proxy log and the OpenAPI examples.

| Result | Status |
|---|---|
| created | 201 with the hire id and its requirement set |
| invalid email | 422 naming the field |
| duplicate with no reason | 409 signalling a reason is required |
| unknown department or employment type | 422 |
| created but delivery failed | 201 with a delivery-failure indicator on the body |

**Goal**

HR can create a hire over HTTP, the response says whether the invitation went out, and no credential
leaves the server except by email.

**Stories**
- As an HR Officer, I want the response to tell me the invitation failed so that I can retry without
  wondering whether the hire saved.

**Acceptance criteria**
- [ ] `[derived]` Given a valid body, then 201 is returned with the hire id and its requirement set
- [ ] `[derived]` Given a duplicate email and no reason, then 409 is returned identifying the rule
- [ ] `[derived]` Given an invalid email, then 422 is returned naming the field
- [ ] `[derived]` Given delivery failed, then 201 is still returned, carrying a failure indicator
- [ ] `[derived]` Given any response from this endpoint, then it contains neither the access PIN nor
      the plaintext token
- [ ] `[derived]` Given no credentials, then the request is refused
- [ ] `[derived]` Given the generated spec, then the endpoint appears with its 201, 409 and 422
      contract described

**Tests**
| Level | Test |
|---|---|
| Route | `create hire - a valid body - returns 201 with the hire id` |
| Route | `create hire - a duplicate email with no reason - returns 409` |
| Route | `create hire - an invalid email - returns 422 naming the field` |
| Route | `create hire - delivery failed - returns 201 carrying a failure indicator` |
| Route | `create hire - any response - carries no pin and no plaintext token` |
| Route | `create hire - no credentials - is refused` |

**Files**
- create `src/route/hr/EmployeeRoutes.kt`
- create `src/route/dto/EmployeeDto.kt`
- create `src/route/mapper/EmployeeDtoMapper.kt`
- modify [`src/route/Routing.kt`](../../src/route/Routing.kt)
- create `test/route/hr/EmployeeRoutesTest.kt`

**Out of scope**
- List and detail. ERT-510 and ERT-520.
