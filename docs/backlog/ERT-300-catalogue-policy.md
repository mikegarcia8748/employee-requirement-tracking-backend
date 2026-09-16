# ERT-300 · Epic: Requirement catalogue and link policy

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | **Done** |
| **Depends on** | ERT-130, ERT-240 |
| **PRD** | §6.4, §8.10, §8.11, §5 |
| **Architecture** | §4, §12 invariant 6 |

**Description**

Everything downstream reads from here. `CreateHireUseCase` cannot compute an `expiresAt` without the
policy, and cannot snapshot a requirement set without the catalogue. Both are the first real
adapters in the project, so this epic also sets the pattern — repository, mapper, Koin binding,
integration test — that ERT-410 onward follow.

`AuditLog` is bundled in because almost every HR-side rule from Phase 1 onward writes to it, and
building it alongside the first two adapters is cheaper than retrofitting it into four later
tickets.

**Goal**

The §6.4 policy and the requirement catalogue are readable from the database at runtime, an audit
entry can be recorded, and HR can list the catalogue over HTTP.

**Stories**
- As an HR Admin, I want the link durations read at runtime so that changing one never needs a
  deployment.
- As an engineer on the next session, I want the adapter pattern established once so that the
  remaining repositories are mechanical.

**Out of scope**
- Writing settings or editing templates. Both are Phase 2 admin screens (§8.10, §8.11); Phase 1 only
  reads.

---

## ERT-310 — `AppSettingsRepository` adapter with §6.4 bounds enforcement

| | |
|---|---|
| **Parent** | ERT-300 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | **Done** |
| **Depends on** | ERT-130, ERT-240 |
| **PRD** | §6.4, §8.10 |
| **Architecture** | §4 |

**Description**

[`AppSettingsRepository`](../../src/domain/port/Repositories.kt) reads the nine §6.4 values out of
the key-value `app_settings` table and assembles a [`LinkPolicy`](../../src/domain/model/LinkPolicy.kt).
Its doc comment states the rule plainly: there is no code path that hardcodes a duration.

The interesting part is failure. `app_settings` stores strings with a declared `value_type`, so a row
can be missing, unparseable, or outside its own stored bounds. Falling back to the `LinkPolicy`
default is the wrong instinct — a corrupted `absolute_expiry_days` silently becoming 90 is exactly
the "well-meant edit turns a token into a permanent credential" case §6.4 warns about. Refuse loudly
instead.

`updateLinkPolicy` is written here because the port declares it and the bounds check belongs with
the reader, but nothing calls it until the Phase 2 settings screen.

**Goal**

`linkPolicy()` returns the stored policy, and a value that is missing, unparseable or out of bounds
fails loudly rather than defaulting.

**Stories**
- As an HR Admin, I want a value outside the permitted range rejected with the range stated so that I
  can correct it rather than guess.
- As an engineer on the next session, I want policy reads to be one call so that no use case
  assembles settings itself.

**Acceptance criteria**
- [x] `[derived]` Given a seeded database, when `linkPolicy()` is called, then it returns the nine
      §6.4 values as stored
- [x] Given a value outside the allowed bounds, then it is rejected with a message stating the
      permitted range (§8.10)
- [x] `[derived]` Given a missing or unparseable row, then the call fails with a `Validation` error
      naming the key — it does not fall back to a compiled-in default
- [x] Given `link.idle_expiry_days` is `0`, then `LinkPolicy.idleClockEnabled` is false and only the
      absolute ceiling applies (§6.4)
- [x] Given any settings change, then it is written to the audit log with the old value, new value,
      actor and timestamp (§8.10) — actor and timestamp as **columns**, old and new in `metadata`

**Tests**
| Level | Test |
|---|---|
| Repository | `link policy read - a seeded database - returns the stored nine values` |
| Repository | `link policy read - absolute expiry stored as 400 - is rejected stating the 7 to 180 range` |
| Repository | `link policy read - a missing key - fails naming the key rather than defaulting` |
| Repository | `link policy read - idle expiry of zero - reports the idle clock disabled` |
| Repository | `link policy update - a valid change - records old and new value in the audit log` |

**Files**
- create `src/data/repository/ExposedAppSettingsRepository.kt`
- create `src/data/mapper/AppSettingMapper.kt` — also holds `LinkPolicySetting`, the one place the
  nine key strings are written, and `LINK_POLICY_ID`
- modify [`src/domain/port/Repositories.kt`](../../src/domain/port/Repositories.kt) — the port now
  returns `DomainResult`, because there was no channel for "this row cannot be read"
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/repository/ExposedAppSettingsRepositoryTest.kt`
- create `test/data/mapper/AppSettingMapperTest.kt` — the validation matrix, away from SQL
- create `test/di/DataModuleTest.kt`, `test/testdata/DomainResults.kt`, `test/testdata/SourceFiles.kt`
- modify `test/testdata/fake/FakeAppSettingsRepository.kt`, `test/testdata/fake/FakesTest.kt`
- modify [`test/data/db/SeedDataTest.kt`](../../test/data/db/SeedDataTest.kt) — reads
  `LinkPolicySetting.entries` rather than keeping a second copy of the nine keys

**Out of scope**
- `GET` / `PATCH /api/settings`. Phase 2.

---

## ERT-320 — `RequirementTemplateRepository` adapter

| | |
|---|---|
| **Parent** | ERT-300 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | **Done** |
| **Depends on** | ERT-130, ERT-240 |
| **PRD** | §5, §8.11 |
| **Architecture** | §4, §12 invariant 6 |

**Description**

The catalogue joins `requirement_templates` to `employment_types` through `template_assignments`.
[The port's own comment](../../src/domain/port/Repositories.kt) carries the rule that matters:
`findActiveForEmploymentType` is read **once**, at hire creation, and copied onto the employee.
Nothing downstream may consult it again for an in-flight hire.

That is what stops a template edit from changing the progress of someone already collecting, or from
making a completed hire retroactively incomplete. The snapshot itself happens in ERT-432; this
ticket supplies the read and must not offer a convenience method that tempts a caller into a live
lookup later.

`sort_order` matters: the portal checklist is what a new hire reads on a phone, and its ordering is
an HR decision, not an incidental consequence of insertion order.

**Goal**

An employment type resolves to its active templates in `sort_order`, and inactive templates never
reach a new hire.

**Stories**
- As an HR Admin, I want my template edits to leave in-flight hires alone so that nobody's progress
  moves under them.
- As a New Hire, I want the checklist in a deliberate order so that the list reads sensibly on a
  phone.

**Acceptance criteria**
- [x] `[derived]` Given an employment type, when its templates are read, then only `is_active` rows
      are returned, ordered by `sort_order`
- [x] `[derived]` Given an employment type with no assignments, then an empty list is returned rather
      than an error — the caller decides whether that is a problem
- [x] `[derived]` Given `findAll(includeInactive = true)`, then inactive templates are included, for
      the Phase 2 admin screen only
- [x] Given an admin edits a template, then in-progress hires are unaffected (§8.11) — verified here
      by the absence of any live-lookup path for an existing hire

**Tests**
| Level | Test |
|---|---|
| Repository | `template catalogue - an employment type - returns only active templates in sort order` |
| Repository | `template catalogue - an unassigned employment type - returns an empty list` |
| Repository | `template catalogue - findAll including inactive - returns inactive templates too` |

**Files**
- create `src/data/repository/ExposedRequirementTemplateRepository.kt`
- create `src/data/mapper/RequirementTemplateMapper.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/repository/ExposedRequirementTemplateRepositoryTest.kt`

**Out of scope**
- Template CRUD. Phase 2 (§8.11).

---

## ERT-330 — `AuditLog` adapter

| | |
|---|---|
| **Parent** | ERT-300 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | **Done** |
| **Depends on** | ERT-240 |
| **PRD** | §12, §8.10, §8.13 |
| **Architecture** | §4 |

**Description**

PRD §12 requires an audit log of every view, approve, reject, download, email change and reopen.
[`AuditEntry`](../../src/domain/model/AuditEntry.kt) already defines 17 actions and three
verification methods, and `audit_logs.metadata` is a JSON text column carrying reasons and
verification methods.

Two properties are the point of the table. It is append-only — nothing updates or deletes a row.
And the metadata column **must never carry a credential**: not a PIN, not a plaintext token, not a
password. §12 says the PIN is never logged, and an audit log is still a log. A test should assert
that, because the column is free-form and the rule is otherwise only a convention.

The Phase 3 exception report (§8.13) reads these rows to detect one officer creating, altering and
approving the same record, so `actor`, `action` and `entity_id` need to be queryable rather than
buried in the JSON.

**Goal**

An HR-side action can be recorded and read back for one entity, with no path that mutates history
and no route by which a credential reaches the metadata.

**Stories**
- As an HR Officer, I want every consequential action recorded with who and when so that a disputed
  change has an answer.
- As an engineer on the next session, I want the audit write to be one call so that no use case
  assembles log rows itself.

**Acceptance criteria**
- [x] `[derived]` Given an `AuditEntry`, when recorded, then it is readable by `entity_id` with
      actor, action, timestamp and metadata intact
- [x] `[derived]` Given entries for one entity, then `findFor` returns them in chronological order
- [x] `[derived]` Given the adapter, then it exposes no update or delete path
- [x] Given any recorded entry, then its metadata contains no PIN, plaintext token or password
      (§12)
- [x] `[derived]` Given the metadata is malformed JSON, then the write fails rather than storing an
      unreadable row — made *unrepresentable*: the port takes a `Map`, the adapter owns the encoding

**Tests**
| Level | Test |
|---|---|
| Repository | `audit log - an entry is recorded - is readable by entity id with actor and timestamp` |
| Repository | `audit log - several entries for one entity - are returned in chronological order` |
| Repository | `audit log - metadata carrying a pin-shaped value - is refused` |

**Files**
- create `src/data/repository/ExposedAuditLog.kt` — also `JdbcTransaction.insertAuditEntry`, the
  shared writer ERT-310 calls from inside its own transaction
- create `src/data/mapper/AuditEntryMapper.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/repository/ExposedAuditLogTest.kt`
- create `test/data/mapper/AuditEntryMapperTest.kt` — the credential refusal and its limits

**Out of scope**
- Surfacing the audit log in the API. Phase 2.
- The exception report's queries. Phase 3 (§8.13).

---

## ERT-340 — `GET /api/requirement-templates`

| | |
|---|---|
| **Parent** | ERT-300 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | **Done** |
| **Depends on** | ERT-140, ERT-320 |
| **PRD** | §8.11, Appendix B |
| **Architecture** | §3, §9 |

**Description**

The first HR route, and therefore the first exercise of the whole HR path: `authenticate(HR_AUTH)`,
a DTO in `route/dto/`, a mapper in `route/mapper/`, and a `describe { }` block feeding the generated
OpenAPI spec. Small enough that getting the shape right here is cheap.

Two things this route establishes for everything after it. Domain models never reach the wire — a
serialised model publishes whatever fields it happens to carry, and §8.6 forbids the portal
returning an original filename, so the DTO boundary is a security control rather than tidiness. And
the spec is generated from the live route tree, so `describe { }` beside the handler is the only
place documentation is written.

**Goal**

An authenticated HR caller receives the active catalogue, and the endpoint appears in the generated
spec.

**Stories**
- As an HR Officer, I want to see which requirements a hire will be assigned before I save so that a
  wrong employment type is visible before the invitation goes out.

**Acceptance criteria**
- [x] `[derived]` Given an authenticated HR caller, when the catalogue is requested, then active
      templates are returned in `sort_order`
- [x] `[derived]` Given no credentials, then the request is refused
- [x] `[derived]` Given the response, then it carries name, instructions, required flag and sort
      order — and no internal identifiers beyond the template id
- [x] `[derived]` Given the generated OpenAPI spec, then this endpoint appears in it with a
      description

**Tests**
| Level | Test |
|---|---|
| Route | `requirement templates - an authenticated caller - returns active templates in sort order` |
| Route | `requirement templates - no credentials - is refused` |
| Route | `api docs - the requirement templates route is mounted - appears in the generated spec` |

**Files**
- create `src/route/hr/RequirementTemplateRoutes.kt`
- create `src/route/dto/RequirementTemplateDto.kt`
- create `src/route/mapper/RequirementTemplateDtoMapper.kt`
- modify [`src/route/Routing.kt`](../../src/route/Routing.kt)
- create `test/route/hr/RequirementTemplateRoutesTest.kt`

**Out of scope**
- Filtering by employment type. Not required until the add-hire screen needs it; add then.

---

## ERT-350 — `ReferenceDataRepository` for departments and employment types

| | |
|---|---|
| **Parent** | ERT-300 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | **Done** |
| **Depends on** | ERT-130, ERT-140, ERT-240 |
| **PRD** | §8.1, §8.2, §11 |
| **Architecture** | §4 |

**Description**

A genuine gap in the port set rather than a missing implementation.
[`Employee`](../../src/domain/model/Employee.kt) requires `departmentId` and `employmentTypeId` as
`EntityId`s, and the `departments` and `employment_types` tables exist — but **no port exposes
either**.
So HR has no way to discover valid values before creating a hire, and `CreateHireUseCase` has no way
to reject an id that does not exist.

§8.2 states the rule for CSV import — "given an unrecognized department or employment type, then the
row is flagged rather than silently creating a new one" — and it is the same rule §8.1 needs one row
at a time. Left unaddressed, the first hire is created against a fabricated id and fails at the
foreign key, or worse, succeeds against a stale one. Note that `EntityId.of` only proves an id is
well **formed**; proving it **exists** is exactly what this ticket adds.

`RequirementTemplate.kt` already declares `Department` and `EmploymentType` models, so this adds a
port and an adapter, not new domain types.

**Goal**

HR can list the valid departments and employment types, and creating a hire against an unknown one
fails as a domain error rather than a constraint violation.

**Stories**
- As an HR Officer, I want to pick a department and employment type from the real list so that I
  cannot create a hire against an id that does not exist.

**Acceptance criteria**
- [x] `[derived]` Given the seeded reference data, when departments are read, then all are returned
      in name order
- [ ] `[derived]` Given an employment type id that does not exist, then hire creation fails with
      `NotFound` naming which one — **carried forward to ERT-430, see below**
- [x] Given an unrecognized department or employment type, then it is flagged rather than silently
      creating a new one (§8.2, the same rule applied to single creation) — the *check* lands here
      as `departmentExists` / `employmentTypeExists`; the flagging is ERT-431's
- [x] `[derived]` Given the reference routes, then they require HR authentication

> **Carried forward to ERT-430.** The port and both existence checks land here, covered by
> `reference data - an employment type id that does not exist - is reported as absent`. Turning a
> `false` into a failure that **names which id was wrong** belongs to `CreateHireUseCase`, which does
> not exist — `src/domain/usecase/` is empty by design until ERT-430, and writing the use case here
> would deliver ERT-430 under this ticket's number and flip `ArchitectureTest`'s use-case vacuity
> tripwire. ERT-430's `Depends on` now names ERT-350 so the criterion is not lost. ERT-430 will also
> want a `FakeReferenceDataRepository` in `test/testdata/fake/`; ERT-350 has only a local fake in its
> route test, because a shared fake exists for use-case tests and there is no use case yet.

> **Escalated, not resolved: is an unknown reference id a 404 or a 422?** This ticket says
> `AppError.NotFound`, which `AppErrorMapper` maps to **404**. ERT-450 and `docs/api-contract.md`
> both say **422**. These cannot both hold, and nothing detects the disagreement until ERT-450 writes
> its route test. `PathIds.kt` is explicit that a body-field id belongs to `Validation` → 422 and
> that `orNotFound` exists for *path* ids, because the path/body distinction is what stops an
> endpoint becoming an enumeration oracle — so the 422 is very likely right and this ticket's wording
> is the loose one. **ERT-431 settles it in one place.** Recorded as E8 in the roadmap.

**Tests**
| Level | Test |
|---|---|
| Repository | `reference data - the seeded catalogue - returns departments and employment types` |
| Use case | `hire creation - an employment type id that does not exist - fails with NotFound rather than creating one` |
| Route | `reference data - no credentials - is refused` |

**Files**
- modify [`src/domain/port/Repositories.kt`](../../src/domain/port/Repositories.kt) — add
  `ReferenceDataRepository`
- create `src/data/repository/ExposedReferenceDataRepository.kt`
- create `src/route/hr/ReferenceRoutes.kt`
- create `src/route/dto/ReferenceDto.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it

**Out of scope**
- CRUD for departments and employment types. Reference data is seeded; editing it is not a Phase 1
  need.
