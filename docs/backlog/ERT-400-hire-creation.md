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
issues a token, computes an expiry from stored policy, and sends an invitation — which is
why [`CreateHireUseCase`](../../docs/architecture.md) is split into four sub-tasks rather than
attempted in one sitting.

Two of those six are load-bearing beyond this epic. The **snapshot** is what stops a later template
edit from moving an in-flight hire's progress (§5). The **computed `expiresAt`** is what stops a
later policy change from silently extending or killing links already in the wild (§6.4). Both are
copies, deliberately, not joins.

The invitation is the only message in the entire system that carries a live credential — the link
itself. Since 2026-09-16 it carries **no PIN**: the link alone opens the portal, and the recovery PIN
is minted on demand by ERT-650 and returned to HR, never emailed. That is
enforced by the shape of [`Notifier`](../../src/domain/port/Notifier.kt): only `sendInvitation`
accepts an `AccessPin`, so §8.9's "no email carries a credential except the invitation" is a compile-time
property rather than a review checklist item. Do not add an `AccessPin` parameter to any other
method.

**Goal**

HR can create a hire over HTTP; the hire appears at 0% progress with a snapshotted requirement set, a
a digested token, a stored expiry, and a recorded invitation — and a delivery failure does not
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
| **Status** | Done |
| **Depends on** | ERT-190, ERT-240 |
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
- [x] `[derived]` Given an `Employee` with every field populated, when saved and re-read, then it is
      equal to the original
- [x] `[derived]` Given a completed or cancelled hire sharing an email, when `findActiveByEmail` is
      called, then it is not returned
- [x] `[derived]` Given an employee with two anomaly flags, when re-read, then both are present and
      `retentionFrozen` is true
- [x] `[derived]` Given an employee with no attestation, then all three attestation columns are null
      and `attestation` is null on re-read
- [x] `[derived]` Given `requirementsOf`, then a `RequirementSet` is returned whose progress
      arithmetic matches the stored rows
- [x] `[derived]` Given a generated `PersonId` that collides with an existing row, when the insert is
      attempted, then a fresh id is drawn and the save succeeds rather than surfacing the conflict
- [x] `[derived]` Given repeated collisions, then the retry gives up after a bounded number of
      attempts and fails loudly rather than looping

> **The port gained `create`, separate from `save`, and that was not optional.** Every other adapter
> spells `save` as read-then-insert-or-update keyed on the id, and **that shape cannot express the
> criterion above**: an id that already exists reads as *update this row*, so a new hire drawing a
> taken `PersonId` would not have been retried — it would have **overwritten the hire holding that
> id**, silently, losing a record rather than redrawing an identifier. Neither the ticket's own
> collision test nor any other could have been written against the one-method port.
>
> So `create` inserts and never updates, `save` updates and never inserts (`check(rows == 1)`), and
> the mix-up is unrepresentable rather than documented — the device that already keeps
> `ReferenceDataRepository`'s two existence checks apart. `create` returns the hire **as stored**,
> which may carry a different id than the argument; that return value was always in the port's
> signature and is now load-bearing.
>
> `saveRequirements` stays insert-or-update: an `EntityId` draws from 62^12 where a collision is
> negligible, and ERT-432 writes the snapshot while ERT-730 and ERT-910 move its statuses through the
> same call. **That asymmetry is the whole reason the two identifier widths are separate types.**
>
> `FakeEmployeeRepository` holds the same split, because a fake that accepted a creation through
> `save` would let a use case call the wrong method and still pass.

> **`requirementsOf` orders by `name_snapshot`, and the honest fix belongs to ERT-432.**
> `employee_requirements` carries **no `sort_order` snapshot column**, so the catalogue's order is
> unreachable without joining `requirement_templates` — which would be a live read of the catalogue
> for a hire already in flight, and reordering a template would reorder someone's checklist
> mid-onboarding. That is the shape §5 forbids. Name order is deterministic and comes entirely from
> the snapshot; it is not the order HR would choose, and that is the missing column speaking.
> **ERT-432 should add `sort_order_snapshot` when it writes these rows** — see its own block below.

> **Confirmed by breaking it, eight times.** Each of these was applied to the finished adapter and
> the suite re-run, in the manner ERT-320 established: dropping the `ORDER BY` from `requirementsOf`,
> widening the active filter to every `PacketStatus`, dropping `lowerCase()` from the email
> predicate, making `create` skip its taken-id check, dropping the blank filter from the flag
> decoder, and turning the half-populated attestation into a `null`. **Every one failed a named
> test.** Two more came out of the review step rather than the plan, and both were genuinely
> untested: the `ORDER BY` on `findActiveByEmail`, and the ordinal sort in the flag encoder.
>
> The flag sort is worth keeping in mind, because the first attempt to prove it was **vacuous**. With
> the ticket's two flags, replacing `sortedBy { it.ordinal }` with `reversed()` produced the sorted
> order anyway and the new assertion still passed — ERT-320's coincidence, one layer over. The test
> now uses **three** flags in an order that is neither the sorted one nor its reverse, and both
> breaks fail it.

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
- create [`src/data/repository/ExposedEmployeeRepository.kt`](../../src/data/repository/ExposedEmployeeRepository.kt)
- create [`src/data/mapper/EmployeeMapper.kt`](../../src/data/mapper/EmployeeMapper.kt)
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create [`test/data/repository/ExposedEmployeeRepositoryTest.kt`](../../test/data/repository/ExposedEmployeeRepositoryTest.kt)
- modify [`src/domain/port/Repositories.kt`](../../src/domain/port/Repositories.kt) — `create`, per the note above
- modify [`test/testdata/fake/FakeEmployeeRepository.kt`](../../test/testdata/fake/FakeEmployeeRepository.kt) and `FakesTest.kt` — the same split
- modify [`test/di/DataModuleTest.kt`](../../test/di/DataModuleTest.kt) — the unbound-port tripwire named
  this ticket and moves to `UploadLinkRepository`

> **A builder default cannot reach the database unaided, and nothing recorded it.** `anEmployee()`
> points at `Fixtures.DEPARTMENT_ID`, `EMPLOYMENT_TYPE_ID` and `HR_USER_ID` — `DPT000000001`,
> `EMT000000001`, `HRU00001` — and **none of the three exists after migration**: the V2 seed holds
> `d00000000001` and `e00000000001`..`4`, and `users` is empty because the bootstrap admin is a
> startup use case rather than a seed row. A naive `anEmployee()` → `create()` fails three foreign
> keys, each naming a constraint rather than the mismatch behind it.
>
> The repository test inserts rows under the `Fixtures` ids in a `@BeforeTest` rather than re-pointing
> every builder call, which keeps each test reading as the rule it is about. **Every repository test
> touching an employee from here on hits this** — ERT-420, ERT-610 and ERT-720 all do.

**Out of scope**
- Search and filtering for the hire list. That is ERT-510, which extends this adapter.

---

## ERT-420 — `UploadLinkRepository` adapter, resolved by token hash

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Done |
| **Depends on** | ERT-160, ERT-240 |
| **PRD** | §6.3, §6.4, §12 |
| **Architecture** | §4, §12 invariant 4 |

**Description**

[`findByTokenHash`](../../src/domain/port/Repositories.kt) takes a hash, never plaintext, and the
port says why: the plaintext token exists only in the invitation email, and a lookup by plaintext
would imply it was recoverable from storage. Do not add a by-plaintext overload for convenience.

ERT-160 resolved the primitive this adapter depends on: the token is digested with a keyed HMAC so
lookup is reproducible, while the recovery PIN stays on bcrypt. Use `TokenDigest` here, not `Hasher` — using
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
- [x] `[derived]` Given a link, when looked up by the hash of its token, then it is returned
- [x] `[derived]` Given the adapter, then no method accepts a plaintext token
- [x] `[derived]` Given the same plaintext token presented twice, then both lookups resolve to the
      same row — the token digest is deterministic
- [x] `[derived]` Given a stored link, then neither the plaintext token nor any plaintext PIN is
      recoverable from any column
- [x] `[derived]` Given `LinkScope.Only`, then it round-trips with its template ids intact
- [x] `[derived]` Given an employee with a revoked link and a new active one, then
      `findActiveForEmployee` returns only the active one

> **`upload_links.pin_hash` had to become nullable, and the ticket was not blocked by it — the
> *next* one was.** V1 wrote it `NOT NULL` under the access model that preceded 2026-09-16, where the
> URL opened nothing without the PIN and both were issued with the hire. The link alone now opens the
> portal and the PIN is an HR-issued recovery credential minted on demand by ERT-650, so **ERT-433
> issues a link that has none** — which the old column forbids.
>
> The adapter would have round-tripped a non-null `pinHash` perfectly well, which is exactly why this
> is recorded rather than assumed: the cost of deferring was not a failing test here but a *second*
> write of this ticket's mapper, builder, fake and repository tests one ticket later. The alternative
> — a bcrypt hash of a six-digit value nobody was told and nobody can redeem — is worse than null in
> the way that matters: it is a credential-shaped digest for a credential that does not exist,
> indistinguishable in the column from a live one, so "has this hire been given a recovery PIN?"
> stops being answerable from the data.
>
> `V5__recovery_pin_nullable.sql` is `alter column pin_hash drop not null` and nothing else. V4 had
> to drop and re-add its four actor columns because `ALTER COLUMN ... TYPE` is where H2 and
> PostgreSQL disagree; **nullability is not**, verified against H2 2.4.240 in PostgreSQL mode before
> the file was written. The two columns the recovery PIN still needs — when it expires and whether it
> has been used (§6.6: single-use, and expires) — are **not** here. They belong with ERT-650, which
> mints and redeems the PIN and therefore knows what to write in them, on the ERT-432 principle that
> a column belongs with the rows it describes.
>
> **ERT-440's outbox migration moves to V6.**

> **`LinkScope` had no serializer, and the ticket read as though it did.** The column has carried a
> literal `'ALL'` default since V1 with no writer and no reader, so "Given `LinkScope.Only`, then it
> round-trips" was undesigned work rather than a mapping. The encoding is now `ALL`, or `ONLY:` and
> the template ids, comma-separated and **sorted**.
>
> Three things about it are deliberate. `All` encodes to the column's **own default**, so a row
> written by a migration, an import or a psql prompt decodes as the scope it obviously means instead
> of as corruption. The ids are sorted for the reason `EmployeeMapper` sorts anomaly flags by
> ordinal: a `Set` has no order, so an unsorted join writes the same scope two different ways on two
> saves. And capacity is **39 ids** — `varchar(512)` holds `ONLY:` plus that many — which is recorded
> in the mapper rather than fixed by widening the column, because Appendix A's catalogue has 14.

> **`findActiveForEmployee` takes no clock, and that is C15's question answered rather than C15
> unfixed.** C15 gave `PortalSessionRepository.findActiveForLink` a `now` parameter because a session
> records only `started_at`, `expires_at` and `ended_at`, so "active" there could only mean "not
> explicitly ended". **A link carries a stored `LinkStatus`.** ERT-1020 states the rule this depends
> on: expiry is evaluated lazily at access time by ERT-644 against the stored dates, and the sweep
> exists only to send the warning and keep the status tidy for the HR list — so the portal is never
> blocked on a background job having run. Filtering on the stored status is the whole answer here,
> and a clock would put a second definition of expiry in the layer that must hold no business rules.
>
> It returns **`ACTIVE` only, not every status that opens the portal.** `COMPLETED` also has
> `opensPortal = true` (§6.3) and is the plausible mistake — but a completed packet's read-only
> confirmation is not a link `resend-link` should reuse. `FakeUploadLinkRepository` already drew that
> line; the adapter matches it, because a use case that passes against fakes and behaves differently
> against SQL is the failure the fakes exist to prevent.

> **Confirmed by breaking it, eight times — and two of the breaks survived the first pass.** Each
> was applied to the finished adapter and the suite re-run, in the manner ERT-410 established:
> dropping the `ACTIVE` filter, widening it to every `opensPortal` status, dropping the employee
> predicate, reversing the `ORDER BY`, dropping the `ORDER BY` entirely, dropping the blank filter
> from the scope decoder, dropping the sort from the scope encoder, and making `save` always insert.
>
> **Two of them passed a green suite, in a test class whose own comment claimed to have learned this
> lesson.** The ordering test first gave the newest link the *lowest* id, so dropping the `ORDER BY`
> still passed — H2 with no ordering returns the primary-key scan, and "lowest id" and "newest" were
> the same row. And the scope test first used **two** ids given as `[2, 1]`, so replacing `sorted()`
> with `reversed()` produced the sorted order anyway: ERT-410's exact coincidence, reproduced one
> epic later by someone who had just read about it. Both now arrange a case where every accident
> names a different row than the rule does, and all eight breaks fail a named test.
>
> The lesson ERT-410 recorded needs one more turn: **intending to write the anti-coincidence test is
> not the same as writing it, and writing it is not the same as checking that it works.** The
> mutation pass is the only step that can tell the three apart.

**Tests**
| Level | Test |
|---|---|
| Repository | `link lookup - the hash of a presented token - resolves the link` |
| Repository | `link lookup - the same token presented twice - resolves to the same row` |
| Repository | `link persistence - a stored link - exposes no plaintext token or pin` |
| Repository | `link persistence - a new link - stores no recovery pin` |
| Repository | `link scope - Only with three template ids - round-trips intact and in one stable order` |
| Repository | `link scope - Only with no template ids - round-trips as an empty set rather than one blank id` |
| Repository | `active link lookup - a revoked link and an active one - returns only the active one` |
| Repository | `active link lookup - a completed link - is not returned` |
| Repository | `active link lookup - three active links - returns the most recently issued` |

**Files**
- create [`src/data/repository/ExposedUploadLinkRepository.kt`](../../src/data/repository/ExposedUploadLinkRepository.kt)
- create [`src/data/mapper/UploadLinkMapper.kt`](../../src/data/mapper/UploadLinkMapper.kt)
- create [`resources/db/migration/V5__recovery_pin_nullable.sql`](../../resources/db/migration/V5__recovery_pin_nullable.sql) — per the note above
- modify [`src/domain/model/UploadLink.kt`](../../src/domain/model/UploadLink.kt) — `pinHash` nullable, and the KDoc that still described the pre-reversal model
- modify [`src/domain/model/LinkStatus.kt`](../../src/domain/model/LinkStatus.kt) — `ACTIVE` said "behind the PIN"
- modify [`src/data/db/table/Tables.kt`](../../src/data/db/table/Tables.kt) — the drift test forces this and the migration to move together
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create [`test/data/repository/ExposedUploadLinkRepositoryTest.kt`](../../test/data/repository/ExposedUploadLinkRepositoryTest.kt)
- modify [`test/testdata/Builders.kt`](../../test/testdata/Builders.kt) — `anUploadLink(pinHash = null)` by default
- modify [`test/di/DataModuleTest.kt`](../../test/di/DataModuleTest.kt) — the unbound-port tripwire named
  this ticket and moves to `SubmissionRepository`
- modify [`test/data/db/MigrationTest.kt`](../../test/data/db/MigrationTest.kt) — the portability sweep counts its own files

> **The `ONLY:` decoder is the ERT-410 blank-element trap, one file over.**
> `"ONLY:".removePrefix("ONLY:")` is the empty string, and `"".split(",")` yields one **blank**
> element rather than none — the same shape that would have reported an anomaly flag on every hire in
> the system and silently disabled the §7.1 purge. Here the blank reaches `EntityId.of` instead, so
> without the filter `Only(emptySet())` is unstorable and the failure names the id format rather than
> the encoding behind it. It has its own named test.

**Out of scope**
- Expiry evaluation and lockout logic. Those are use-case rules, in ERT-640 and ERT-1020.
- The recovery PIN's expiry and single-use columns. ERT-650 writes those rows.

---

## ERT-430 — `CreateHireUseCase`

| | |
|---|---|
| **Parent** | ERT-400 |
| **Type** | Ticket — **split into ERT-431…434** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-190, ERT-210, ERT-230, ERT-310, ERT-320, ERT-350, ERT-410, ERT-420, ERT-440 |
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
| **Status** | Not started |
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

**This sub-task settles two status codes that three documents disagreed about.** Both are decided in
`docs/api-contract.md` and implemented here; neither is re-argued anywhere else.

- **An unknown `departmentId` or `employmentTypeId` is a 422, not a 404** (E8). They arrive in the
  request body, and the API contract's rule is that a body field's failure is a validation error
  naming the field. ERT-350's wording said `NotFound`, which maps to 404; that wording was the loose
  one. Two codes, not one — `department_unknown` and `employment_type_unknown` — because both ids are
  12-character `EntityId`s and a single code could not tell HR which picker to fix.
- **A duplicate with no reason is `ReasonRequired` → 422, not `Conflict` → 409** (C1). The system does
  not refuse the request; it asks for a justification and then proceeds, which is a statement about an
  incomplete request rather than a conflicting resource. Routing it through `Conflict` would also drop
  the `details` entry naming `duplicateReason`, which is the entire reason `ReasonRequired` exists as
  a separate case.

> **`DuplicateEmailRequiresReason` is not an `AppError` case** and never has been. An earlier version
> of the test name below said it was, as did `CLAUDE.md` and architecture §5 — three documents
> prescribing a specification change nobody approved (`AppError.kt`: "Adding a case here is a
> specification change"). The rule is carried by `ReasonRequired(code, action)`. (C2, corrected
> 2026-09-16.)

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
- [ ] `[derived]` Given an unknown department id, then the failure is `Validation` naming
      `departmentId` with code `department_unknown` — **not** `NotFound` (E8)
- [ ] `[derived]` Given an unknown employment type id, then the failure names `employmentTypeId` with
      code `employment_type_unknown`
- [ ] `[derived]` Given a department id supplied in the `employmentTypeId` field, then it is rejected
      — the two existence checks are separate for exactly this reason (ERT-350)

**Tests**
| Level | Test |
|---|---|
| Use case | `hire creation - an invalid email format - fails with a field-level validation error` |
| Use case | `hire creation - email duplicates an active hire with no reason given - fails with ReasonRequired naming the reason field` |
| Use case | `hire creation - email duplicates an active hire with a typed reason - creates the hire and records the reason` |
| Use case | `hire creation - a duplicate with a reason - flags the record as shared email` |
| Use case | `hire creation - email duplicates a cancelled hire - needs no reason` |
| Use case | `hire creation - a whitespace-only reason - is treated as no reason given` |
| Use case | `hire creation - a department id that does not exist - fails with a validation error naming which id` |
| Use case | `hire creation - an employment type id that does not exist - fails with a validation error naming which id` |
| Use case | `hire creation - a department id supplied as the employment type - is rejected rather than accepted` |

**Files**
- create `src/domain/usecase/CreateHireUseCase.kt`
- create `test/domain/usecase/CreateHireUseCaseTest.kt`
- create `test/testdata/fake/FakeReferenceDataRepository.kt` — **the eleventh port has no fake.**
  ERT-210 built ten; `ReferenceDataRepository` arrived later with ERT-350, which needed only a local
  fake in its route test. This is the first use-case test that needs a shared one, and nothing
  currently records it as anyone's job (C9)

---

### ERT-432 — Requirement-set snapshot from the template catalogue

| | |
|---|---|
| **Parent** | ERT-430 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-431 |
| **PRD** | §5, §8.11, §6.5 |

**Description**

The requirement set is **copied** onto the employee at creation, never read live afterwards (§5).
`name_snapshot` and `is_required_snapshot` are the copies. Editing a template later must not change
the progress of anyone in flight, and must never make a completed hire retroactively incomplete.

The test that proves this is the one that mutates the catalogue **after** creation and asserts the
hire is unchanged. Without it the rule is only an intention, and a future refactor that "simplifies"
the snapshot into a join will pass every other test in the suite.

> **Added by ERT-410: this sub-task should also add `sort_order_snapshot`.** `name_snapshot` and
> `is_required_snapshot` are copied; **the catalogue's `sort_order` is not**, so nothing downstream
> can render a hire's checklist in the order HR arranged it without joining `requirement_templates`
> — which is exactly the live read §5 forbids, and would let a template reordered tomorrow reshuffle
> a checklist on a phone today. ERT-410's `requirementsOf` therefore orders by `name_snapshot`, which
> is deterministic and snapshot-pure but is not HR's order.
>
> A third snapshot column is the fix, and it belongs **here**, with the rows it describes, rather
> than in the adapter that reads them. It is a migration plus a column in `EmployeeRequirements` and
> `EmployeeRequirement`, and `MigrationTest`'s drift baseline moves with it. ERT-740 and ERT-510 are
> the tickets that would otherwise ship the wrong order.

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

### ERT-433 — Token issue, digested, with `expiresAt` computed from policy

| | |
|---|---|
| **Parent** | ERT-430 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-432 |
| **PRD** | §6.4, §6.6, §8.1, §12 |

**Description**

The link is created with the hire. `TokenGenerator` produces the plaintext and the ERT-420 token
digest stores it; the plaintext does not survive past the invitation, which is rendered at send time
and never persisted (ERT-440).

**No PIN is issued here (2026-09-16).** The link alone opens the portal, and the 6-digit PIN is now an
HR-issued recovery credential minted on demand by ERT-650 — not at creation. `PinGenerator` and
`Hasher` keep their place in the design; they are simply called from a different use case. A PIN
generated at creation could not serve its new purpose anyway: it would arrive in the same email whose
non-arrival it exists to remedy.

> **C23, opened by ERT-440 and owned by this sub-task: `Notifier.sendInvitation` still *requires* an
> `AccessPin`.** The paragraph above says the invitation carries none, and this epic's own
> description says the parameter is what makes "only the invitation may carry a credential" a
> compile-time property. Both cannot hold: ERT-433 has to pass *something*.
>
> ERT-440 implemented the method honestly — it stores no body and renders nothing from the `pin` —
> and deliberately left the shape alone, because changing a port is a specification change. Three
> options, none free: make the parameter nullable and lose the guarantee; mint a PIN nobody is told,
> which is exactly the credential-shaped-digest problem V5 removed from `upload_links`; or **split
> the port** so `sendInvitation` takes no PIN and a separate `sendRecoveryPin` does — the only one
> that keeps the guard, and the one ERT-650 will want anyway. Decide it here, in writing.

`expiresAt` is computed from the policy read at this moment and **stored**, exactly like the
requirement snapshot. Changing `link.absolute_expiry_days` tomorrow must not move this link (§6.4).
The idle clock is the second of two clocks — when `idleExpiryDays` is 0 it is disabled and
`idleExpiresAt` is null, and where both apply the earlier one wins.

**Acceptance criteria**
- [ ] Given a hire is created, then a link token is generated and stored as a keyed digest, and the
      invitation carries the link and **no PIN** (§8.1, §6.6)
- [ ] `[derived]` Given a link is issued, then the plaintext token is persisted nowhere, and no
      recovery PIN exists on the record until HR issues one
- [ ] Given an admin changes an expiry setting, then links already issued keep their stored
      `expires_at` (§8.10)
- [ ] `[derived]` Given a policy with `absoluteExpiryDays = 90`, then `expiresAt` is 90 days after
      `issuedAt` as measured by the injected clock
- [ ] Given `link.idle_expiry_days` is `0`, then `idleExpiresAt` is null and only the absolute ceiling
      applies (§6.4)
- [ ] `[derived]` Given a new link, then its status is `ACTIVE`, `failedPinCount` is 0,
      `extendedCount` is 0, and the recovery-PIN fields are null

**Tests**
| Level | Test |
|---|---|
| Use case | `link issue - a hire is created - stores a token digest and no pin` |
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
| **Status** | Not started |
| **Depends on** | ERT-433, ERT-440 |
| **PRD** | §8.1, §8.9, §6.6 |

**Description**

Delivery is fallible and the hire is created regardless — §8.1 requires a failure indicator and a
retry action, not a lost record. [`DeliveryResult`](../../src/domain/port/Notifier.kt) models this
explicitly, so a `Failed` result must not roll back the creation.

The invitation is the one message carrying both halves of the credential. Every later message points
at the portal without restating the link, so a forwarded rejection notice or expiry warning carries
nothing useful. The `Notifier` shape enforces this; this sub-task must not work around it.

**Acceptance criteria**
- [ ] Given a hire is created, then the invite email is sent within 1 minute (§8.1)
- [ ] Given email delivery fails, then HR sees a failure indicator on the record and a retry action
      (§8.1)
- [ ] `[derived]` Given delivery fails, then the hire, its requirement set and its link still exist
      and are unchanged
- [ ] Given any email at all, then it never contains an access PIN, and only the invitation carries a
      link (§8.9, §6.6) —
      structurally guaranteed by the `Notifier` signature
- [ ] `[derived]` Given a successful creation, then an audit entry records the creating actor and
      timestamp

**Tests**
| Level | Test |
|---|---|
| Use case | `invitation - a hire is created - sends one invitation carrying the link and no pin` |
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
| **Status** | Done |
| **Depends on** | ERT-120 |
| **PRD** | §8.9 |
| **Architecture** | §4 |

**Description**

**Q12 is answered (2026-09-16): an SMTP relay on internal mail.** No mail library is declared yet and
ERT-1010 lands the transport, so `Notifier` is still implemented here as an outbox: each queued send
writes a row with recipient, kind, status, attempts and last error. ERT-1010 drains it, and no use
case changes when it does.

The outbox is genuinely useful beyond being a staging post. It gives the §8.1 "retry action" something
to retry, makes "was the invitation sent?" answerable, and survives a restart in a way an in-memory
queue does not.

**Decided here, as this ticket asked: the invitation body is never stored.** A rendered invitation
contains a live credential, and "purge the row after delivery" only shrinks the window — it does not
remove the credential from a backup, a replica, or a write-ahead log. The only reason to keep it would
be to resend the *same* credential, and nothing needs that: `resend-link` reissues.

So the invitation renders **at send time**, from the token held in memory for the duration of
`CreateHireUseCase`, and its outbox row records recipient, kind, employee, status, attempts and last
error — enough to answer "was it sent?" and to drive §8.1's failure indicator, with nothing in it worth
stealing. The other six kinds carry no credential and queue normally, bodies included.

This is the `Notifier` port's own rule one layer down. Only `sendInvitation` may carry a credential,
and therefore only the invitation may not be stored. **Note the criterion below is testable at write
time, not after delivery** — the earlier wording, "retains no usable PIN *after delivery*", was
satisfiable by a row that held one for an hour first.

**`PORTAL_BASE_URL` is required and does not exist yet.** The invitation body contains a link, and
nothing among the thirteen documented environment variables configures the host it points at. Without
it this ticket cannot be executed as written: the body would carry a path with no origin.

**Goal**

Every `Notifier` method persists a durable record of what would be sent, retryable and inspectable,
with no live credential left sitting in the table.

**Stories**
- As an HR Officer, I want a failed invitation to be retryable so that a transient mail problem does
  not mean re-creating the hire.
- As an engineer on the next session, I want email to be swappable so that Q12 costs an adapter, not
  a redesign.

**Acceptance criteria**
- [x] `[derived]` Given each of the seven `Notifier` methods, when called, then a row is written with
      recipient, kind, payload and a pending status
- [x] `[derived]` Given an invitation outbox row **at any point in its life**, then it contains no
      plaintext token and no PIN — not before sending, not after, not in a failed row's last error
- [x] `[derived]` Given `PORTAL_BASE_URL` is unset outside dev, then startup fails rather than
      rendering a link with no origin
- [x] `[derived]` Given a row, then it can be marked sent or failed, and a failed row can be retried
- [x] `[derived]` Given the adapter, then it returns `DeliveryResult.Sent` on a successful write and
      `Failed` when the write fails
- [x] `[derived]` Given the outbox table, then it is added by a migration, not by `SchemaUtils`

> **`storesBody` is a constructor parameter on `NotificationKind`, not a `kind != INVITATION` check.**
> The rule "only the invitation's body is dropped" has to survive an eighth kind being added, and a
> comparison buried in the adapter would let one inherit `true` in silence. Spelling it as a
> parameter means the new kind **will not compile** until someone decides which side of the line it
> is on — the device `AnomalyFlag.freezesRetention` and `LinkStatus.opensPortal` already use, and the
> one invariant 8 asks for by name.

> **A failed invitation cannot be retried, and `retry` refuses it loudly.** That is the cost of not
> storing the body, stated from the other end: the token is not persisted anywhere, so there is
> nothing to rebuild the message from. Reissuing the credential is ERT-1030's `resend-link`. Failing
> here rather than silently re-queueing is what stops ERT-1010's drain from retrying an invitation
> forever against an empty body — a loop that would never terminate and never send anything.

> **`.env.example` already documented `PORTAL_BASE_URL`; the code that reads it did not exist.** The
> ticket's file list said "modify `.env.example`", and that half was done before this session. What
> was missing was `PortalBaseUrl.fromEnvironment(isDevMode())` and its place on `AppModule`'s
> eager-resolution line beside `TokenDigest` and `JwtConfig`.
>
> **Its case for failing closed is the sharpest of the three, and the KDoc says why.** The other two
> fail *recoverably*: fix the variable, restart, and the next request works. An invitation rendered
> without an origin has already left, and the token it carried is not stored — so correcting the
> variable does not correct the link. The remedy is reissuing the credential to every hire invited
> since the deploy, through the bulk-send path §8.2 makes hard on purpose.

> **Two of the seven methods take no address, and the column is nullable rather than holding a
> sentinel.** `sendPacketReadyForReview` and `notifyHrOfSuspension` go to HR, whose mailbox is
> ERT-1010's configuration rather than this ticket's. A string like `'HR'` in `recipient` would be a
> lie in a column other code reads; `kind` already names the audience, and null means "resolve it at
> send time". `employee_id`, by contrast, is **not** nullable — which is what lets §8.1's
> delivery-failure indicator be *derived* from the latest row for a hire (E4) instead of needing a
> column on `employees` that something has to remember to update.

> **Confirmed by breaking it, sixteen times — and one break survived.** Storing the invitation's
> body, dropping every body, flipping `INVITATION.storesBody`, throwing instead of returning
> `Failed`, overwriting the attempt count, retrying an invitation, clearing the count on retry,
> dropping the queue's `ORDER BY`, returning sent rows from the queue, putting the hire's address on
> an HR-bound row, allowing an unset `PORTAL_BASE_URL` outside dev, treating an empty one as
> configured, restating the link in the expiry warning, and passing silently on an update that
> matched nothing — all fail a named test.
>
> **The one that did not was "store the exception message verbatim".** The test asserted the failure
> reason does not contain the token, and it passed against the broken adapter — because the only
> write failure a test can construct is a foreign-key violation, whose message happens not to quote
> the body. It was testing H2's error text, not the adapter. It now asserts a **whitelist** — the
> reason matches `^[A-Za-z]+$`, a bare exception type — which is a rule about what may appear rather
> than a list of what may not, and therefore holds for the failure the test cannot construct. **A
> blacklist assertion is only as good as the failure you can reach**, which is ERT-420's vacuity
> lesson wearing different clothes.

**Tests**
| Level | Test |
|---|---|
| Repository | `outbox notifier - an invitation is sent - writes a pending row for the recipient` |
| Repository | `outbox notifier - each of the seven notifier methods - writes one row of its own kind` |
| Repository | `outbox notifier - an invitation at any point in its life - stores no token and no pin` |
| Repository | `outbox notifier - an invitation - stores its subject but never its body` |
| Repository | `outbox notifier - the six kinds carrying no credential - store their bodies` |
| Repository | `outbox notifier - a failed invitation - records the error without echoing the link` |
| Repository | `outbox notifier - a failed write of an invitation - reports only the exception type, not its message` |
| Repository | `outbox notifier - the write itself fails - returns Failed rather than throwing` |
| Repository | `outbox notifier - a failed row - can be retried` |
| Repository | `outbox notifier - a failed invitation - cannot be retried because its body was never stored` |
| Repository | `outbox notifier - the pending queue - holds only unsent rows, oldest first` |
| Plugin | `portal base url - unset outside dev - refuses to start` |
| Plugin | `portal base url - an empty string outside dev - is treated as unset rather than as configured` |

**Files**
- create [`resources/db/migration/V6__notification_outbox.sql`](../../resources/db/migration/V6__notification_outbox.sql) — V4 is taken by ERT-190's `users`, **V5 by ERT-420's nullable `pin_hash`**
- ~~modify [`.env.example`](../../.env.example) — `PORTAL_BASE_URL`~~ — already there; see the note above
- create [`src/data/notify/OutboxNotifier.kt`](../../src/data/notify/OutboxNotifier.kt)
- create [`src/data/notify/NotificationKind.kt`](../../src/data/notify/NotificationKind.kt) — `storesBody`, per the note above
- create [`src/data/notify/NotificationMessages.kt`](../../src/data/notify/NotificationMessages.kt) — the seven bodies; only the invitation restates the link
- create [`src/data/notify/PortalBaseUrl.kt`](../../src/data/notify/PortalBaseUrl.kt)
- modify [`src/data/db/table/Tables.kt`](../../src/data/db/table/Tables.kt) — `NotificationOutbox`, added to `allTables`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- modify [`src/di/AppModule.kt`](../../src/di/AppModule.kt) — `PortalBaseUrl` on the eager-resolution line
- create [`test/data/notify/OutboxNotifierTest.kt`](../../test/data/notify/OutboxNotifierTest.kt)
- create [`test/data/notify/PortalBaseUrlTest.kt`](../../test/data/notify/PortalBaseUrlTest.kt)
- modify [`test/data/db/MigrationTest.kt`](../../test/data/db/MigrationTest.kt) — three count guards move, and
  `notification_outbox.employee_id` joins the person-column list. **The width sweep failed on it
  first**, which is the guard working: an `EntityIdTable` whose foreign key points at `employees`
  carries an 8-wide column, so a new person-keyed column is a decision the list records.

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
| **Depends on** | ERT-140, ERT-190, ERT-430 |
| **PRD** | §8.1, Appendix B |
| **Architecture** | §3, §8, §9 |

**Description**

A thin adapter: parse the body, call `CreateHireUseCase`, map the sealed result onto a status. The
route makes no decision — every rule was settled and tested in ERT-431…434.

The response must not echo the plaintext token. It travels in the invitation email and
nowhere else; returning them to the creating HR client would put a live credential into a browser,
a proxy log and the OpenAPI examples.

| Result | Status |
|---|---|
| created | 201 with the hire id and its requirement set |
| invalid email | 422 naming the field |
| duplicate with no reason | 422 `ReasonRequired`, with a `details` entry naming `duplicateReason` (C1) |
| unknown department or employment type | 422 `department_unknown` / `employment_type_unknown` (E8) |
| created but delivery failed | 201 with a delivery-failure indicator on the body |

**Goal**

HR can create a hire over HTTP, the response says whether the invitation went out, and no credential
leaves the server except by email.

**Stories**
- As an HR Officer, I want the response to tell me the invitation failed so that I can retry without
  wondering whether the hire saved.

**Acceptance criteria**
- [ ] `[derived]` Given a valid body, then 201 is returned with the hire id and its requirement set
- [ ] `[derived]` Given a duplicate email and no reason, then **422** is returned with a `details`
      entry naming `duplicateReason` — not 409 (C1, settled in the API contract)
- [ ] `[derived]` Given an invalid email, then 422 is returned naming the field
- [ ] `[derived]` Given delivery failed, then 201 is still returned, carrying a failure indicator
- [ ] `[derived]` Given any response from this endpoint, then it contains neither a PIN nor
      the plaintext token
- [ ] `[derived]` Given no credentials, then the request is refused
- [ ] `[derived]` Given the generated spec, then the endpoint appears with its 201 and 422
      contract described

**Tests**
| Level | Test |
|---|---|
| Route | `create hire - a valid body - returns 201 with the hire id` |
| Route | `create hire - a duplicate email with no reason - returns 422 naming the reason field` |
| Route | `create hire - an invalid email - returns 422 naming the field` |
| Route | `create hire - a duplicate with no reason - returns 422 naming the reason field` |
| Route | `create hire - an unknown department - returns 422 naming which id` |
| Route | `create hire - a token minted by this application - reaches the handler` |
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
