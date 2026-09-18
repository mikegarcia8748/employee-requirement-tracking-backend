# ERT-500 · Epic: HR read side

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-450 |
| **PRD** | §8.3, §6.5, §3 |
| **Architecture** | §4, §9 |

**Description**

PRD Goal 3 — HR sees completion percentage for every in-progress hire on one screen, without opening
each record. This epic branches off the critical path after hire creation and shares no files with
the portal epics, so it is the natural parallel track when two people are working, or a shorter
session when the portal work needs a longer run.

The §6.5 arithmetic already exists in
[`RequirementSet`](../../src/domain/model/EmployeeRequirement.kt) — total, submitted, approved,
awaiting review, with optional requirements excluded from the denominator. **It has no tests.** So
this epic is wiring plus finally proving the arithmetic, not writing it.

One design point decides whether the list survives contact with fifty hires.
[`EmployeeRepository`](../../src/domain/port/Repositories.kt) has `findById` and
`findActiveByEmail` and nothing that lists — and loading each hire's full requirement set to render
one row is an N+1 by construction. A separate query port returning per-status **counts** computed in
SQL is the fix, feeding those counts into the same §6.5 arithmetic so the two cannot drift.

**Goal**

HR can list hires with correct progress and open one to see every requirement and its status.

**Stories**
- As an HR Officer, I want one screen showing who is behind so that I stop reconciling attachments by
  hand.
- As an HR Officer, I want submitted-but-unvalidated work shown separately so that a hire does not
  look finished when nothing has been checked.

**Out of scope**
- Approve and reject (§8.5) — Phase 2.
- Document preview and version history — ERT-800, which needs storage.
- Dashboard, bulk download, activity timeline — Phase 3.

---

## ERT-510 — Hire list with progress

| | |
|---|---|
| **Parent** | ERT-500 |
| **Type** | Ticket — **split into ERT-511 and ERT-512** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-410, ERT-450 |
| **PRD** | §8.3, §6.5 |
| **Architecture** | §4, §9 |

**Description**

> ### PERF-13 — `findActiveByEmail` is unbounded (2026-09-18, second review)
>
> `limit` appears in exactly two reads in the whole data layer, and this is one of the twelve without
> it. Most are bounded by the domain and are fine; this one is bounded by how many hires share an
> address — and SEC-11 exists because that is not always one. Decide a page size **with** the
> renderer: a limit with no renderer is a guess at a page size, which is why this is recorded here
> rather than given a ticket of its own.
>
> The ordering that makes paging safe is already in place and now under contract:
> `created_at ASC, id ASC` on both implementations, so two reads of the same page cannot reshuffle.
> ERT-250 closed the fake's half of that; the adapter has had it since ERT-410's review step.

Two separable pieces that are tempting to write as one and should not be. The §6.5 arithmetic already
exists and is untested; the list query does not exist and has an N+1 trap in it. Proving the
arithmetic first means the query has something correct to feed.

**Goal**

An authenticated HR caller gets the working set with correct dual progress, searchable and
filterable, without loading every requirement row.

---

### ERT-511 — §6.5 progress arithmetic, tested and extracted

| | |
|---|---|
| **Parent** | ERT-510 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-230 |
| **PRD** | §6.5, §8.11 |

**Description**

[`RequirementSet`](../../src/domain/model/EmployeeRequirement.kt) already implements §6.5 — total,
submitted, approved, awaiting review, optional requirements excluded from the denominator. **It has
no tests**, and it is about to acquire a second implementation in SQL.

Two figures, not one. Submission progress is what the employee has done; approval progress is what HR
has validated. The bar fills with approval progress and shows submitted-but-unvalidated as a lighter
segment, so a row reads `6/10 validated · 2 awaiting review`. Returning only the first is what lets a
hire look finished when HR has checked nothing.

Extract the figures into a value the list query and `RequirementSet` both produce, so a SQL-side count
and an in-memory count cannot disagree.

**Acceptance criteria**
- [ ] Given an optional requirement, then it is excluded from both numerator and denominator (§6.5)
- [ ] `[derived]` Given a mix of statuses, then submission progress counts `UPLOADED`, `UNDER_REVIEW`
      and `APPROVED`, and approval progress counts only `APPROVED`
- [ ] `[derived]` Given a hire with no required requirements at all, then progress does not divide by
      zero
- [ ] `[derived]` Given the extracted value, then both the in-memory set and the list query produce it,
      so the two cannot drift

**Tests**
| Level | Test |
|---|---|
| Use case | `progress - an optional requirement - is excluded from both numerator and denominator` |
| Use case | `progress - a mix of statuses - submission and approval progress differ as §6.5 defines` |
| Use case | `progress - no required requirements at all - does not divide by zero` |
| Use case | `progress - every required requirement approved - reports complete` |

**Files**
- modify [`src/domain/model/EmployeeRequirement.kt`](../../src/domain/model/EmployeeRequirement.kt)
- create `test/domain/model/RequirementSetTest.kt`

---

### ERT-512 — `GET /api/employees`: default view, search and filters

| | |
|---|---|
| **Parent** | ERT-510 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-511 |
| **PRD** | §8.3 |

**Description**

[`EmployeeRepository`](../../src/domain/port/Repositories.kt) has `findById` and
`findActiveByEmail` and nothing that lists. Loading every hire's requirement set to render one row is
an N+1 by construction, so this adds a query port returning per-status **counts** computed in SQL,
fed into the ERT-511 value.

The default view is `DRAFT_COLLECTING`, `UNDER_REVIEW` and `CHANGES_REQUESTED`, most recent first. A
hire reaching `COMPLETE` leaves that view but stays findable by filter — not deleted, not hidden, just
out of the working set.

**Acceptance criteria**
- [ ] Given hires exist, then each row renders a progress bar per §6.5 (§8.3)
- [ ] Given a hire reaches `COMPLETE`, then they leave the default view but remain findable via
      filter (§8.3)
- [ ] Given a packet is `UNDER_REVIEW`, then the row is visually distinct so HR can find its review
      queue (§8.3) — the response carries packet status and awaiting-review count; the visual
      distinction is the client's
- [ ] Given no hires exist, then an empty state with an "Add hire" action is shown (§8.3) — the API
      returns an empty list, never a 404
- [ ] `[derived]` Given search by name or email and filters on department, employment type and status,
      then each narrows the result and they combine
- [ ] `[derived]` Given fifty hires, then the list is rendered without a per-row requirement query
- [ ] `[derived]` Given any row, then it carries no token hash, PIN hash or storage key

**Tests**
| Level | Test |
|---|---|
| Repository | `hire list - the default view - shows collecting, under review and changes requested, most recent first` |
| Repository | `hire list - a completed hire - is absent from the default view but present when filtered for` |
| Repository | `hire list - fifty hires - issues no per-row requirement query` |
| Route | `hire list - no hires exist - returns an empty list rather than a 404` |
| Route | `hire list - a row - carries the validated fraction and the awaiting-review count` |
| Route | `hire list - any row - carries no token hash, pin hash or storage key` |

**Files**
- create `src/domain/port/HireListQuery.kt`
- create `src/data/repository/ExposedHireListQuery.kt`
- modify `src/route/hr/EmployeeRoutes.kt`
- create `src/route/dto/HireListDto.kt`

**Out of scope**
- An expiry column on the list. P1, Phase 3 — though ERT-520 puts expiry on the detail record, which
  §8.10 requires in Phase 1.
- Anomaly flags in the list (§8.12). Phase 2.

---

## ERT-520 — `GET /api/employees/{id}`: detail with requirements

| | |
|---|---|
| **Parent** | ERT-500 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-510 |
| **PRD** | §8.4, §8.5, §8.10, §1 |
| **Architecture** | §12 invariant 9 |

**Description**

> ### PERF-13 — `AuditLog.findFor` has no ceiling at all (2026-09-18, second review)
>
> It is the one unbounded read in the data layer with no domain bound behind it. `audit_logs` is
> append-only, §8.12 renders the history of one entity, and an entity edited for two years returns
> two years of rows. Take a page size here, with the screen that first renders it.
>
> The ordering is already contracted on both implementations — `timestamp ASC, id ASC` — so a page
> boundary is stable. ERT-250 closed the fake's half; `ExposedAuditLog` has had it since ERT-330.
> PERF-10/ERT-1190 adds the index that makes the paged read cheap, so land that first.

The hire record: details, overall progress, and every requirement with its status. Document preview
and version history arrive in ERT-800, once storage exists — this ticket returns the requirement
list and the submission metadata around it.

Two fields here are not incidental. The link's expiry date and remaining days belong on the record
(§8.10), because a lapsing link is otherwise invisible until someone is stranded. And **originals
sighted** must display plainly, including when absent — PRD §1 is emphatic that `COMPLETE` means the
paperwork is in and looks right, not that the person has been verified, and no downstream process
should read it that way. A record showing `COMPLETE` with no visible statement about originals is
exactly how that misreading happens.

Unlike portal DTOs, an HR DTO **may** carry `originalFilename`, `sizeBytes` and `mimeType` — §8.4
requires them. The ERT-170 guard scopes to `route/dto/portal/` precisely so this ticket is not
blocked by it.

**Goal**

HR can open a hire and see every requirement, its status, its submission metadata, the link's
remaining life, and whether originals have been sighted.

**Stories**
- As an HR Officer, I want to see days remaining on the link so that I can extend it before someone
  is stranded.
- As an HR Officer, I want `COMPLETE` never to imply identity was verified so that provisioning does
  not treat it as assurance.

**Acceptance criteria**
- [ ] Given a requirement has no submission, then it shows as pending with no broken preview area
      (§8.4)
- [ ] Given a requirement was rejected, then the reason and date are visible (§8.4) — the fields are
      rendered in Phase 1 and populated by the Phase 2 reject flow
- [ ] Given HR views a hire, then the link's expiry date and remaining days are visible on the record
      (§8.10)
- [ ] Given originals have not been sighted, then the hire record displays that plainly, so
      `COMPLETE` is not mistaken for identity assurance (§8.5, §1)
- [ ] `[derived]` Given the response, then it carries submission timestamp, filename, size and version
      number per §8.4 — permitted here, forbidden on the portal
- [ ] `[derived]` Given the response, then it carries no token hash and no PIN hash
- [ ] `[derived]` Given an unknown id, then 404 is returned

**Tests**
| Level | Test |
|---|---|
| Route | `hire detail - a requirement with no submission - is reported pending with no submission metadata` |
| Route | `hire detail - a hire record - reports the link expiry date and days remaining` |
| Route | `hire detail - a record with no originals sighted - says so explicitly` |
| Route | `hire detail - the response - carries no token hash and no pin hash` |
| Route | `hire detail - an unknown id - returns 404` |

**Files**
- create `src/domain/usecase/GetHireDetailUseCase.kt`
- modify `src/route/hr/EmployeeRoutes.kt`
- create `src/route/dto/HireDetailDto.kt`

**Out of scope**
- Document URLs and version history. ERT-810 and ERT-820.
- The access trail on the record (§8.12). Phase 2.
