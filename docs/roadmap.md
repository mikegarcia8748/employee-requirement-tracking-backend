# Delivery roadmap

**Next ticket: [ERT-340 — `GET /api/requirement-templates`](backlog/ERT-300-catalogue-policy.md#ert-340--get-apirequirement-templates)**

The full board is [docs/backlog/README.md](backlog/README.md). This file holds sequencing, the
decision register, and the pointer above. Each session updates that pointer on the way out.

---

## Where the code is

| | |
|---|---|
| Built | `core/` value objects and error types · 12 domain models with status logic · 11 ports · 12 Exposed tables · bcrypt for PINs, an HMAC token digest, clock and secure generators · a use case tracer behind `TRACE_USECASES`, with per-request correlation · 6 Ktor plugins · generated OpenAPI · an architecture test that fails the build on a layer violation, **on a portal DTO leaking document content**, or **on an untraced use case** · a test harness of 10 in-memory fakes, an advanceable `FixedClock`, deterministic generators and a builder per domain model · a `RepositoryTestBase` giving one migrated, seeded, isolated H2 database per test · **two Exposed adapters, bound and resolved by a wiring test** — the §6.4 link policy and the append-only audit trail |
| Empty | `domain/usecase/` · `route/hr/` · `route/portal/` |
| Mapping | one `AppError` → HTTP mapping in `route/mapper/`, so a route returns a domain failure and makes no decision |
| Endpoints | `/health`, `/openapi`, `/swagger`, `/metrics`. PRD Appendix B specifies ~38. |

The three foundational gaps Phase 0 opened with are closed: `DatabaseFactory.connect()` runs from the
application lifecycle (ERT-110), Flyway applies a baseline guarded by a drift test (ERT-120), and
reference data and policy defaults are seeded (ERT-130). ERT-180 then replaced every UUID identifier
with a validated `PersonId` or `EntityId`, and ERT-140 gave the route layer one mapping from an
`AppError` to a status — with `Denied` made a `data object` so invariant 3 holds by construction, and
a path id decided to be a 404 rather than a 422. ERT-145 then wrapped every `/api` response in one
envelope (`result`, `data`, `meta`, `error`), taken now because `/health` was still the only route
mounted; it also established that **the OpenAPI generator infers nothing from `call.respond`**, so
every route from here on must declare its response schema in `describe { }` or publish an operation
a client cannot generate from.

**ERT-100 is now closed** apart from ERT-190, which stays blocked on Q4. ERT-150 exposed the
Prometheus registry on a `/metrics` route — hidden from the spec, open in dev and HR-gated
otherwise — mounted from `Monitoring.kt` rather than `Routing.kt`, because the gate needs `HR_AUTH`
and `isDevMode()` and the `plugin` → `route` arrow does not reverse. ERT-160 split the two portal
credentials onto the primitives each actually needs: an HMAC-SHA-256 `TokenDigest` keyed by
`TOKEN_PEPPER` for the link and session tokens, which are **looked up** and so must digest
reproducibly, and bcrypt for the PIN, which is **verified**. Before it, `findByTokenHash` could never
have matched anything. ERT-170 then made the write-mostly rule mechanical: a portal DTO declaring a
document handle, a portal route importing `DocumentStorage`, or any route importing Exposed or
`plugin` now fails the build — and the guard reports *vacuous* rather than passing while the portal
directories are still empty.

ERT-195 then gave the layer below the route a voice. `CallLogging` reported `POST /api/employees ->
422` and stopped, so which rule rejected a request could only be answered with a debugger — and on a
portal path even the route line collapses to `/api/portal/[redacted]`. A `UseCaseTracer` port in
`core/` now emits one line per invocation — name, outcome, duration, and nothing else — behind
`TRACE_USECASES`, which defaults to off. It was taken **before** ERT-430 writes the first use case,
for the reason ERT-170 gives about the portal guards: a seam laid first is one every use case is
written against, and one the architecture test can hold. That test now fails the build on a use case
that takes no tracer, traces under a name copied from another file, or imports `org.slf4j` to log by
hand. Correlation came free: Ktor already wraps the call pipeline in an MDC context, so a generated
`requestId` reaches every suspend frame a request opens and ties a trace line to its access-log line.

Two things were recorded rather than fixed, and both want an owner: **the token pepper cannot be
rotated** (rotation invalidates every live link and there is no re-issue flow), and **`APP_ENV`
defaults to dev**, so a deployment that forgets to set it silently gets open docs, open metrics, an
ephemeral JWT key and an ephemeral pepper — which is why ERT-195 took its own variable rather than
becoming a fifth control on that one. `.env.example` now documents all thirteen variables.

A third is now on the list: **`StatusPages` logs `call.request.local.uri` unredacted** at two call
sites, bypassing the portal-token redaction `Monitoring.kt` applies three files over. Harmless while
`route/portal/` is empty; a token in a log file the moment it is not.

ERT-210, ERT-220 and ERT-230 then built the in-memory half of the test harness: ten fakes, a
`FixedClock` that advances rather than merely pins, deterministic id, token and PIN generators, and a
builder per domain model. The three were taken in one session because ERT-210's own tests need six
`Submission`s, an `Employee` and an `UploadLink` — precisely the fixtures ERT-230 exists to remove,
and ERT-230 needs ERT-220's clock. The suite went from 153 tests to 279, and the deliberately-failing
probe was re-run once against a new test to confirm these are discovered rather than silently
skipped, which under this toolchain is the only way to tell the two apart.

Three fakes carry more than their signature. `FakeNotifier`'s recorded sends mirror the port's own
shape, so **only the invitation variant can hold an `AccessPin`** and a test cannot claim a rejection
notice leaked one — there is nowhere for it to have been. `FakePortalAccessTrail` has no update or
delete path at all, so a use case that tried to amend the trail could not compile against it.
`FakeSubmissionRepository` purges whenever it is asked and records every call, because the retention
freeze belongs to the **use case** and not the repository: asserting that `purges` is empty is how
ERT-734 proves the freeze was honoured, and a fake that checked the flag itself would cover for a use
case that forgot it entirely.

Two semantics were decided here rather than assumed, and each binds a later ticket:

- **`countRecentFailures` counts `PortalOutcome.DENIED` only.** `LOCKED_OUT` and `SUSPENDED` are
  consequences of failures already counted, so including them would count one burst twice and
  auto-suspend a link early; `EXPIRED` is not a credential attempt at all. **ERT-610's SQL adapter
  must match this**, or the §6.6 suspend threshold fires at a different count in production than in
  every test that asserts it.
- **`PortalSessionRepository.findActiveForLink` cannot filter by expiry**, because the port hands it
  no clock — so "active" there can only mean "not explicitly ended". The P1 control "HR can terminate
  active portal sessions" reads that list and will show sessions that have quietly lapsed. Recorded
  rather than papered over: ERT-620 should decide whether the port grows a `now` parameter.

ERT-240 then closed Phase 0 apart from ERT-190, which stays blocked on Q4. It is the half of the
harness the fakes cannot supply: fakes prove a use case obeys its rules and prove nothing about SQL.
`RepositoryTestBase` hands a subclass a migrated, seeded, isolated database reached through the
production `DatabaseFactory.transaction`, and it unblocks eight tickets at once — ERT-310, 320, 330,
350, 410, 420, 610 and 720. **No repository, use case or business route exists yet**, so nothing
writes rows outside the tests.

Three things there were decided rather than assumed, and a later reader would otherwise reverse the
first of them:

- **Isolation is a brand-new in-memory database per test, not truncation.** Truncation is faster in
  principle but would have to know the foreign-key order of every table added from here on, and
  would have to restore the seeded reference rows that ERT-310's bounds tests deliberately corrupt —
  and a table forgotten there leaks silently between tests. Measured rather than argued: the suite
  went 5494 ms for 279 tests to 5936 ms for 288, about **40 ms per migrated database**. Revisit only
  against a number, not a hunch.
- **Closing the pool does not release the database.** `DB_CLOSE_DELAY=-1` — which is there so an
  in-memory schema survives Flyway's own short-lived DataSource — also keeps every test's database
  resident until the JVM exits. Teardown issues `SHUTDOWN` on a fresh connection.
- **`Database.connect()` registers every instance in a companion-object map nothing prunes.**
  Teardown calls `TransactionManager.closeAndUnregister`. Neither leak is visible in a green suite,
  so both have a named test rather than a comment.

`MigrationTest` and `SeedDataTest` now share the base's `freshDatabase()` and `migrate()` so that
"how a test gets a database" has one answer, but they deliberately do **not** extend it: they test
the migration itself and so need a database *before* it is migrated, which is the one state the base
will not hand out.

ERT-310 and ERT-330 then opened `data/repository/` and `data/mapper/` and set the pattern the seven
remaining adapters copy. They were taken together because ERT-310's §8.10 criterion is that a
settings change is audited, and the epic already says building the audit log alongside the first
adapters is cheaper than retrofitting it into four later tickets — taken apart, the settings write
and its audit row would have committed separately. The suite went from 288 tests to 359.

Four things there were decided rather than assumed, and the first two bind later tickets:

- **`AppSettingsRepository` now returns `DomainResult`** — the only repository port that does. It is
  the only one whose stored data can be wrong in a way that matters, and `LinkPolicy`'s Kotlin
  defaults are *identical* to the seeded rows, so a silent fallback would return exactly what a
  correct read returns and no behavioural test could tell them apart. **ERT-433 must handle the
  `Err`**, not substitute a default: refusing to issue a link is the right answer to a policy nobody
  can read. `LinkPolicySetting` is now the single source of the nine key strings, and `SeedDataTest`
  reads it rather than keeping a second copy.
- **A settings change is one audit row under a singleton id, not nine.** `audit_logs.entity_id` is
  12 characters and `Identifier.of` recovers an id's kind from that length alone, so a setting key
  cannot go in it. That forced the better model anyway: the link policy *is* the entity — one domain
  object stored as nine rows and saved by the Phase 2 screen as one form. `metadata` carries only
  the keys that moved, with the **raw** old string, because an admin correcting a corrupt value is
  when the trail matters most. Actor and timestamp stay columns, since §8.13's exception report has
  to query them.
- **Exposed retries a failed transaction, re-running the whole block.** Found, not assumed: an audit
  insert that violated a primary key rolled back, retried, drew a *fresh* id and committed. Anything
  non-transactional inside a `factory.transaction { }` runs again — an id generator, a clock read.
  ERT-400 wants exactly this for a duplicate `PersonId`, so it is useful; it is recorded because a
  test that expects a transaction to fail must make it fail on *every* attempt.
- **A nested `factory.transaction { }` joins the outer one**, so `updateLinkPolicy` could have
  written its audit row through the `AuditLog` port and still been atomic. This was checked by
  making the change and re-running the suite, which stayed green — **no test distinguishes the two
  designs, and the code says so rather than claiming otherwise.** The adapter writes on the
  transaction it already holds because the port hop is atomic only while `useNestedTransactions`
  stays false and the `Dispatchers.IO` hop preserves the transaction's context element, and neither
  is this codebase's decision.

Two smaller findings, both recorded in place. `app_settings.updated_at` is `timestamp` without a
time zone, so its raw column text is the JVM default zone's rendering — a test asserting on that
string passes locally and fails in CI, and the repository test reads it back as an `Instant`
instead. And Exposed 1.3 makes the `SqlExpressionBuilder.eq` import a **compile error**: adapters
import the top-level `org.jetbrains.exposed.v1.core.eq`.

Two risks are accepted rather than fixed, and both are in the adapter's KDoc. `updateLinkPolicy` is a
read-modify-write under `READ_COMMITTED`, so two admins saving at once are last-writer-wins with a
trail that reads as sequential — there is no writer at all until Phase 2, and `forUpdate()` is the
fix. And the audit metadata's credential guard catches a credential-shaped *key* completely but
catches a credential-shaped *value* only as a tripwire; a bare six-digit rule was considered and
rejected because ERT-810's `size_bytes` will collide with it, and a test pins that limit so nobody
"adds the obvious missing check".

ERT-320 then added the catalogue adapter, and its lesson is about the **seed rather than the SQL**.
`template_assignments` is seeded as a cross join — four employment types x fourteen templates — and
`sort_order` was assigned 1..14 in the same order as the ids. Both coincidences hide a defect from
the ticket's own named tests, and this was measured rather than argued: the adapter was temporarily
broken twice and the suite re-run. Dropping the `employment_type_id` predicate entirely fails three
tests, but **dropping the `ORDER BY` entirely fails only one** — the test written specifically to
break the coincidence, which reverses one row's `sort_order` before reading. Without it ERT-320
would have shipped green with no ordering at all, and the first symptom would have been a checklist
that reordered itself on a new hire's phone. **Every adapter from here on reads the same seed**, so
a test that passes against seeded data has proved less than it looks; ERT-350's departments are the
next instance, where a single seeded row makes "in name order" vacuous.

Two smaller things settled there. Exposed 1.3's join-on-explicit-columns is the **top-level**
`org.jetbrains.exposed.v1.core.innerJoin` — the member `ColumnSet.innerJoin` takes only the other
table, so without that import the named-argument form fails with "no parameter with name
'onColumn'", which reads as a typo rather than a missing import. And `and` is the same top-level
trap already recorded for `eq`; `findActiveForEmploymentType` is the first two-predicate `where` in
the codebase.

---

## Phases

Phases follow PRD [§15](employee-requirements-tracker-prd_1.md). Phase 0 is additional — it is the
runtime and test scaffolding Phase 1 assumes.

| Phase | Theme | Epics | State |
|---|---|---|---|
| **0** | Foundations | ERT-100, ERT-200 | Specified |
| **1** | Core loop and access model | ERT-300 … ERT-1000 | Specified |
| **2** | Validation loop and accountability | see below | Epic-level |
| **3** | Scale and efficiency | see below | Epic-level |
| **4** | Document lifecycle | see below | Gated on Q1 |

> **Priority and phase are different axes.** §8.10, §8.11, §8.12 and §8.13 are all P0 but land in
> Phases 2–3. P0 means "must exist before launch", not "must be built first".

### Phase 1 scope

PRD §15: §8.1 (create hire), §8.3 (list with progress), §8.4 (detail with preview), §8.6 (upload
portal), §8.7 (upload limits and versioning), §8.9 (notifications), plus a configurable requirement
list.

Three items look deferrable and are not. The PRD is explicit that each is expensive to retrofit
rather than merely inconvenient:

- **The write-mostly portal** (§8.6) — a rule about what the API returns. Deciding it later means
  unbuilding a preview feature and re-testing every portal endpoint.
- **The PIN and session model** (§6.6) — retrofitting authentication onto a live public endpoint is a
  rewrite, not an addition.
- **Review-and-submit with attestation** (§7.2) — it changes the data model, and every packet status
  depends on it.

> If Phase 1 must be trimmed, cut the validation workflow before cutting the upload portal. Do not
> cut the access model or the review-and-submit phase.

---

## Sequencing

```
ERT-100 ──► ERT-200 ──► ERT-300 ──┬─► ERT-400 ──┬─► ERT-500
foundations  test harness  policy  │  hire       │  HR read side
                                   │  creation   │
                                   │             └─► ERT-600 ──► ERT-700 ──┬─► ERT-900 ──► ERT-1000
                                   │                portal      document   │  review &     notifications
                                   │                access      upload     │  submit       & link lifecycle
                                   │                                       │
                                   └───────────────────────────────────────┴─► ERT-800
                                                                              HR document access
```

**Critical path:** ERT-100 → ERT-200 → ERT-300 → ERT-400 → ERT-600 → ERT-700 → ERT-900.

ERT-500 and ERT-800 hang off the path and can be taken whenever their dependencies are met — useful
when you want a shorter session. ERT-1000 closes Phase 1.

### Orderings that cause rework if reversed

| Do this | Not that | Why |
|---|---|---|
| **ERT-160 before ERT-433 and ERT-420** | Hash the link token with bcrypt | bcrypt is salted, so a token hashed at issue cannot be recomputed at lookup. Ship it and **every live link becomes unresolvable** — recovery means re-issuing every credential and re-inviting every hire through a bulk send path §8.2 deliberately makes hard. |
| **ERT-170 before ERT-740** | Write the first portal DTO, guard it later | The realistic failure is not a deliberate preview — it is `mimeType` "for the icon" and `originalFilename` "for the confirmation toast", both of which read as reasonable in review. Removing them later means re-testing every portal endpoint (§8.6, SEC-02). |
| ERT-600 before ERT-700 | Upload first, PIN gate later | Retrofitting a session model onto a live public upload endpoint is a rewrite (§6.6, SEC-01). The trail gap it leaves is worse: an append-only history cannot be backfilled. |
| ERT-900 inside Phase 1 | Defer attestation to Phase 2 | Attestation changes the data model and every packet status depends on it (§7.2, SEC-07) |
| ERT-310 before ERT-433 | Hardcode durations, read policy later | If `expiresAt` comes from `LinkPolicy`'s Kotlin defaults rather than `app_setting`, §8.10 is violated from the first row and **nothing detects it** — the numbers are identical. The quietest of these risks. |
| ERT-610 before ERT-630 | Add the trail once endpoints exist | Invariant 7 admits no gaps, and adding logging to five handlers afterwards means auditing each for early returns — which is exactly where a denied attempt goes. |

---

## Decision register

Nine of the PRD's [§14](employee-requirements-tracker-prd_1.md) open questions are marked blocking
and none has an owner date. Phase 1 does not wait on them: each runs behind a port that already
exists, so answering the question later costs an adapter swap rather than a redesign.

| # | Question | Owner | Blocks | Proceeding meanwhile |
|---|---|---|---|---|
| Q4 | Who are the HR users, how do they authenticate, does SSO exist? | Stakeholder / IT | The HR auth epic, the real protection of every `authenticate(HR_AUTH)` route, and [ERT-190](backlog/ERT-100-foundations.md#ert-190--hr-user-accounts-and-the-persona-model) | The marked-placeholder JWT verifier in [Security.kt](../src/plugin/Security.kt). HR routes are written behind it now. Architecture §14 says it should be **replaced, not extended**. A local `users` table is deliberately **not** built ahead of the answer: if identity lives in an IdP it would be a mirror, not a source of truth. Actors stay free-text `varchar(128)` meanwhile. |
| Q12 | Email delivery mechanism and sending domain | Engineering / IT | ERT-1010 | ERT-440 writes to an outbox table. Rows become real sends when the adapter lands; no use case changes. |
| Q2 | The actual requirement checklist, and whether it differs by employment type | HR stakeholder | ERT-130 seed *content* (not its mechanism) | Appendix A seeded and clearly marked illustrative. Templates are data, so replacing them is a seed change. |
| Q3 | Which documents have validity periods, and how long | HR stakeholder | Phase 4 | Columns already exist and are nullable. |
| Q5 | Data-protection regime and portal consent notice | Legal / compliance | ERT-912 attestation **text** | Versioned placeholder text. The versioning mechanism is the part that is expensive to add later. |
| Q16 | Is a phone number available for out-of-band verification of email changes? | HR | Phase 2 `ChangeHireEmailUseCase` | **Genuinely blocked.** Without a channel, SEC-03 is unremediated in practice regardless of what §7.4 says. Flagged, not worked around. |
| — | Object storage target — no question number in the PRD | Engineering | ERT-710, ERT-810 | Local filesystem adapter behind `DocumentStorage`. Malware scanning (`isClean`) returns a documented stub until a scanner is chosen. |
| Q1 | How do tenured employees enter the system? | HR / IT | All of Phase 4 | Phase 4 is not scheduled. The §9.3 seams are already open. |
| Q6 | How does `COMPLETE` reach account provisioning? | IT | The handoff seam | `COMPLETE` is recorded; nothing consumes it yet. |
| Q9, Q17 | Confirm the §7.1 upload limits and §6.4 / §6.6 defaults | HR / IT | Nothing | Defaults are in the PRD and stored in `app_setting`, changeable without a deployment. |

---

## Phase 2 — Validation loop and accountability

Not yet expanded into tickets. Expand when Phase 1 closes.

| Epic | Covers | PRD |
|---|---|---|
| Approve and reject with identity binding | `ApproveSubmissionUseCase`, `RejectSubmissionUseCase`, name-match and photo-match confirmation, `CHANGES_REQUESTED` handling, link extension on rejection, the 3-rejection flag, originals-sighted | §8.5, §7.3, §7.1 |
| Email change | `ChangeHireEmailUseCase`, out-of-band verification method as a required field, second approver where approved documents exist, token and PIN rotation, notice to the old address | §7.4, §8.8 — **needs Q16** |
| Reopen and completed states | `ReopenRecordUseCase` issuing fresh credentials, the `COMPLETED` read-only confirmation page, grace window into `CLOSED`, `RequestNewLinkUseCase` | §7.3, SEC-08, SEC-09 |
| Admin catalogue and settings | Requirement template CRUD, employment-type mapping, the §6.4 settings surface with bounds and audit | §8.10, §8.11 |
| Accountability surfaced | Access trail on the hire record, anomaly flags visible in the list, PIN-failure burst notification, audit log exposed | §8.12 |

## Phase 3 — Scale and efficiency

| Epic | Covers | PRD |
|---|---|---|
| CSV bulk import | validate → preview → confirm, with invitation sending as a **separate explicit action** | §8.2, SEC-12 |
| Reminders and dashboard | escalating nudges at day 3/7/14 with opt-out; counts by status, awaiting review, overdue, links nearing expiry | P1 |
| Exception report | separation-of-duties detection, shared-email records, anomaly records; names an owner and a cadence | §8.13, SEC-10 |
| HR convenience | bulk download as ZIP, activity timeline, internal notes, target completion dates, force-submit, session management | P1 |

## Phase 4 — Document lifecycle

**Gated on Q1.** Roughly doubles the domain and introduces a new persona. Validity windows, an
expiry-monitoring dashboard with configurable lead time, scoped renewal links, per-employee document
history, and an existing-employee roster (§9). The v1 seams are already open — submissions are
versioned with nullable `validFrom`/`validUntil`, the catalogue is independent of the onboarding
flow, `LinkScope.Only` exists, and nothing assumes one packet per employee — so this costs a
migration rather than a redesign.

## Cross-cutting, outside the phases

| Epic | Covers | Gate |
|---|---|---|
| HR authentication | Replace the placeholder JWT scheme with the real model; add a user store or SSO integration | Q4 |
| Security hardening | Malware scanning before a file becomes previewable; retention policy with scheduled deletion, suspended while an anomaly flag is open | Q7, Q18 |
| Documentation hygiene | The root README is still stock Ktor generator boilerplate and advertises deleted features | — |

---

## Contradictions and gaps to escalate

Found while writing the backlog. Each is a specification question, not an implementation choice, and
none should be resolved silently in code. Owners are suggestions.

| # | Issue | Where | Suggested owner |
|---|---|---|---|
| E1 | **§7.2 asks for thumbnails on the review screen; §8.6 forbids the portal returning a preview.** A direct contradiction between two P0 sections. §8.6 carries the audit disposition for SEC-02 and §15 names the write-mostly portal as non-negotiable, so §8.6 should win and §7.2 should be amended. The backlog builds to §8.6. | PRD §7.2 line 230 vs §8.6 line 355 | PRD owner |
| E2 | **The upload MIME allowlist is never stated.** §12 requires "file type allowlist … enforced server-side"; §8.4 names JPG, PNG, HEIC and PDF for *preview* and says other formats "offer download only", implying they are accepted. There is no question number for this. ERT-732 starts from the §8.4 preview set as configuration. | PRD §12 line 548, §8.4 | HR / Engineering — open a question next to Q11 |
| E3 | **A duplicate-email override silently freezes retention.** `Employee.retentionFrozen` derives from `anomalyFlags.isNotEmpty()`, so the `SHARED_EMAIL` flag set by an §8.1 override suspends version purging for that hire. §7.1 scopes the freeze to "a fraud or anomaly flag" — whether a shared address counts is undefined. ERT-734 implements the freeze as written and flags it. | `Employee.kt:35`, PRD §7.1, §8.1 | PRD owner |
| E4 | **§8.1 requires an invite-delivery failure indicator; §11 models no column for it.** Recommendation is to derive it from the audit log rather than add a column, but that is an inference, not the PRD's instruction. | PRD §8.1 vs §11 | PRD owner |
| E5 | **Malware scanning is a P0 control in §12 with no library, no owner and no question number.** ERT-710 wires the `isClean` gate and stubs it to `true`; ERT-810 refuses to serve anything that fails it. That makes it a **named Phase 1 exit risk, not a delivered control.** | PRD §12 line 550 | Engineering / Security |
| E6 | **No object-storage target has been chosen and the PRD asks no question about it.** ERT-710 uses a filesystem adapter with an opaque key scheme so the swap stays a binding change. | PRD §11, §12 | Engineering |
| E7 | **The audit doc says 14 findings; the dispositions table lists SEC-01 through SEC-15.** Cosmetic, but the count is quoted in the PRD's own changelog. | `2026-09-09-security-audit.md` | Audit author |
