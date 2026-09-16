# Delivery roadmap

**Next ticket: [ERT-420 — `UploadLinkRepository` adapter, resolved by token hash](backlog/ERT-400-hire-creation.md#ert-420--uploadlinkrepository-adapter-resolved-by-token-hash)**

> **ERT-410 landed: the first business adapter, and the first ticket to change a port.** Hires and
> their snapshotted requirement sets now round-trip through real SQL — eighteen fields, the
> anomaly-flag set and the attestation triple. The suite went from 518 tests to 549. **ERT-720
> unblocks**, and ERT-430 now waits only on ERT-420 and ERT-440.
>
> **`EmployeeRepository` gained `create` beside `save`, and the ticket could not have been delivered
> without it.** Read that section before taking ERT-420, which is the next adapter with a `save`.
> Nothing on the board is `Blocked`.
>
> **ERT-190 closed Phase 0** before it: HR accounts, two roles, sign-in, the real JWT scheme, the
> bootstrap admin, and the four actor foreign keys. **`domain/usecase/` is no longer empty** — six use
> cases — so `ArchitectureTest`'s tracing tripwire fired as designed and was flipped from `Vacuous` to
> `Checked`. **A test can mint a token this application accepts** (`testdata/HrTokens.kt`), which
> closed the gap ERT-340 recorded in its own test class.
>
> **Q12** — an SMTP relay on internal mail — unblocks **ERT-1010**.
>
> **The portal access model changed: the link alone now opens the portal**, and the 6-digit PIN
> becomes an HR-issued out-of-band recovery credential for invitations that never arrive. That
> rewrites PRD §6.6, three of the nine invariants, and most of ERT-600. SEC-01 returns to Critical
> and is accepted in writing in PRD §12, dated. **Read that acceptance before taking any ERT-600
> ticket.**
>
> **ERT-1110 is a hard gate before ERT-630** and is in its `Depends on` row rather than in a
> sentence. ERT-410, ERT-420, ERT-440, ERT-610, ERT-660 and ERT-710 remain ready; ERT-720 unblocks
> when ERT-410 lands, ERT-430 once ERT-320/350/410/420/440 are in.

The full board is [docs/backlog/README.md](backlog/README.md). This file holds sequencing, the
decision register, and the pointer above. Each session updates that pointer on the way out.

---

## Where the code is

| | |
|---|---|
| Built | `core/` value objects and error types · 13 domain models with status logic · 13 ports · 13 Exposed tables · bcrypt for PINs, an HMAC token digest, clock and secure generators · a use case tracer behind `TRACE_USECASES`, with per-request correlation · 6 Ktor plugins · generated OpenAPI · an architecture test that fails the build on a layer violation, **on a portal DTO leaking document content**, or **on an untraced use case** · a test harness of 10 in-memory fakes, an advanceable `FixedClock`, deterministic generators and a builder per domain model · a `RepositoryTestBase` giving one migrated, seeded, isolated H2 database per test · **six Exposed adapters plus a JWT issuer, bound and resolved by a wiring test** — the §6.4 link policy, the append-only audit trail, the requirement catalogue, the reference data, HR accounts and **hires with their requirement sets** · **six use cases** (sign-in, change password, create/activate/reset a user, bootstrap the first admin) · **the real HR auth scheme**: local `users`, two roles, bcrypt, tokens signed against a row, a bootstrap admin that refuses to start a non-dev deployment with no way in, and `testdata/HrTokens` minting a token any route test can present |
| Empty | `route/portal/` |
| Mapping | one `AppError` → HTTP mapping in `route/mapper/`, so a route returns a domain failure and makes no decision |
| Endpoints | `/health`, `/openapi`, `/swagger`, `/metrics` · `POST /api/auth/login` (the only public `/api` route) · `/api/auth/change-password`, `/api/auth/me` · four `HR_ADMIN`-only routes under `/api/users` · three HR reads — `/api/requirement-templates`, `/api/departments`, `/api/employment-types`. Appendix B specifies the rest. |

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

**ERT-100 is now closed.** ERT-150 exposed the
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

Three things were recorded rather than fixed here, and for three epics they had no owner and no gate.
**They now have both, in [ERT-1100](backlog/ERT-1100-operability-hardening.md)** — which is the point:
a risk written in prose is a risk nobody is assigned.

| Recorded | Ticket | Gate |
|---|---|---|
| **The token pepper cannot be rotated.** Rotation invalidates every live link and session, and there is no re-issue flow | **ERT-1130** | Phase 2, after ERT-1030 |
| **`APP_ENV` defaults to dev**, so a deployment that forgets it silently gets open docs, open metrics, an ephemeral JWT key and an ephemeral pepper — which is why ERT-195 took its own variable rather than becoming a fifth control on that one | **ERT-1120** | Phase 1 exit |
| **`StatusPages` logs `call.request.local.uri` unredacted** at two call sites, bypassing the portal-token redaction `Monitoring.kt` applies three files over. Harmless while `route/portal/` is empty; a live credential in a log file the moment it is not — and since 2026-09-16 the token is the *whole* credential | **ERT-1110** | **Before ERT-630**, in its `Depends on` row |

`.env.example` documents every variable the code reads.

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

ERT-240 then built the half of the harness the fakes cannot supply: fakes prove a use case obeys its rules and prove nothing about SQL.
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

ERT-340 then opened `route/hr/` with the first business endpoint, and settled three things every HR
route after it copies. **The auth scheme name arrives as a parameter.** `HR_AUTH` lives in
`plugin/Security.kt` and the architecture test fails the build on a route importing `plugin`, so
`Application.kt` passes it to `configureRouting` the way `Monitoring.kt` already passes it to
`metricsRoutes`. **The `authenticate` block sits in `Routing.kt`, not in the route file**, which
keeps each handler auth-agnostic and is what lets a route test mount one against a fake with no
security plugin — the only way to test the payload at all while `configureSecurity` still signs with
a random key and **no test can mint a token this application accepts**. That gap is stated in the
test class rather than worked around with a second `jwt(HR_AUTH)` provider, which would prove the
duplicate; it closes with Q4.

The third is a live risk retired: `jsonSchema<ApiResponse<List<Dto>>>()` **does** survive the generic
envelope, so the ERT-145 convention holds for enveloped list responses and no concrete per-route
wrapper type is needed. That was checked rather than assumed, by deleting the `responses { }` block
and re-running — the "appears in the generated spec" test **still passes** against an operation with
no body type at all, and only the test asserting a field name in the document catches it. Any route
that declares `describe { }` without `responses { }` publishes an operation a client cannot generate
from, and the obvious test will not say so.

One nuisance worth recording because it reads as nonsense: **Kotlin block comments nest**, so a
literal `/*` inside a KDoc — writing a path glob like `route/hr` with a star — opens a comment that
never closes, and the compiler reports "unclosed comment" against the end of the file.

ERT-350 closed the epic and filled a **gap in the port set rather than a missing implementation**:
`Employee` has always required a `departmentId` and an `employmentTypeId`, and both tables have
existed since the baseline, but nothing exposed either — so HR could not offer the real list and hire
creation could not reject an id that does not exist. `EntityId.of` proves an id is well *formed*;
this proves it *exists*.

The port carries **two existence checks rather than one**, and the reason is worth keeping: a
department id and an employment type id are both 12-character `EntityId`s and structurally
indistinguishable, so a single `exists(id)` scanning both tables would answer `true` for a department
id handed in the `employmentTypeId` slot — §8.2's exact defect, reached through the validator meant
to prevent it. It is also what lets a caller *name* which id was wrong. Confirmed by breaking it:
pointing `employmentTypeExists` at `departments` fails only two tests, one of which exists solely to
ask the cross question. And as predicted, `findDepartments` with **no `ORDER BY` at all** fails only
the test that inserts three more departments — the seed holds exactly one, so the ticket's own named
test proves nothing about ordering. **That is now twice in one epic** that a named test was vacuous
against seeded data; ERT-410 onward should assume it rather than rediscover it.

Two decisions are recorded rather than assumed. `employment_types` has **no `sort_order` column**, so
the list is alphabetical — which is not the order HR would choose, and that is the missing column
speaking; adding one is an §8.11 change. And the reference endpoints are **not in PRD Appendix B at
all** — two resources (`/api/departments`, `/api/employment-types`) rather than one combined payload,
because `meta.total` is meaningless over a heterogeneous body; both rows are now in the API contract.

**2026-09-16 — the access model was reversed, and the documentation set was closed out.** Two things
happened in one session and they are worth separating.

The first is a **product decision**: the link alone now opens the portal, and the 6-digit PIN becomes
an HR-issued recovery credential for invitations that never arrive. It buys an onboarding flow with
no code to find or mistype — for a population with no company account, often on a borrowed phone,
who get one chance to find this easy — and it costs every threat where the URL reaches the wrong
person. SEC-01 returns to Critical, accepted in writing in PRD §12 with a dated statement that
supersedes rather than overwrites the 2026-09-09 one. The audit's original reasoning is preserved
deliberately: it is the argument the compensating controls now have to carry alone. **The write-mostly
portal stopped being the cheapest control and became the only one**, which is why §12's acceptance is
explicitly void if document preview is ever added back.

One small gain, recorded so the trade does not read as pure loss: the recovery PIN travels by phone or
in person and never by email, so it is the **first genuinely out-of-band factor** the design has had —
which is what SEC-01 asked for in the first place, on a narrower path than it wanted.

The second is **hygiene, and there was more of it than the escalation list knew.** Q4 and Q12 were
answered, unblocking the only two blocked tickets. Five decisions that had been escalated to
"engineering's call" were made. A cross-document sweep found **22 contradictions**, of which two — C1
and C2 — were on no list at all and both sat inside the next epic: a 409/422 disagreement that the API
contract had with *itself*, and an `AppError` case that three documents named and the code has never
had. Three risks that had lived in prose since Phase 0 became ERT-1110, ERT-1120 and ERT-1130, and the
first of them now gates ERT-630 through a dependency rather than a sentence.

The lesson worth keeping is the one E5, E6 and C22 share: **a gap without a number is invisible.**
Malware scanning, the storage target and the token-in-logs defect were all known, all written down,
and all unowned for three epics — because prose has no status field. Every one of them now has a
ticket, an owner and a gate.

**ERT-410 opened Phase 1's first business epic, and it is the first ticket that had to change a
port.** Hires and their snapshotted requirement sets round-trip through real SQL; the suite went from
518 tests to 549. `data/repository/` now holds six adapters, and ERT-720 unblocks.

The port change is the thing to carry forward, because **the ticket was not deliverable without it.**
ERT-410's own acceptance criterion asks that a `PersonId` colliding with an existing row be redrawn
rather than surfaced — and the house `save`, read-then-insert-or-update keyed on the id, **cannot
express that**. An id that already exists reads as *update this row*. A new hire drawing a taken id
would not have been retried; it would have overwritten the hire holding that id, silently, losing a
record rather than redrawing an identifier. No test could have been written to catch it, because
`save` has no way to tell a collision from an update.

So `EmployeeRepository` grew `create` beside `save`: `create` inserts and never updates, `save`
updates and never inserts. It is the device the port set already uses three times — separate
existence checks on `ReferenceDataRepository`, `findByTokenHash` taking a hash, `sendInvitation` as
the only method accepting an `AccessPin` — applied to the one place the write path could confuse two
operations. The return value was always in the signature and is now load-bearing: `create` returns
the hire **as stored**, which may carry a different id than the argument. `saveRequirements` keeps
insert-or-update, because an `EntityId` draws from 62^12 where a collision is negligible — **that
asymmetry is the whole reason the two identifier widths are separate types**, and this is the first
code to depend on it.

Three more things were decided rather than assumed, and two of them bind later tickets:

- **`requirementsOf` orders by `name_snapshot`, because `employee_requirements` has no
  `sort_order_snapshot`.** The catalogue's order is unreachable without joining
  `requirement_templates`, which is the live read §5 forbids: a template reordered tomorrow would
  reshuffle a checklist on a phone today. Name order is deterministic and snapshot-pure, and it is
  not the order HR would choose — that is the missing column speaking, exactly as ERT-350 found with
  `employment_types`. **The third snapshot column belongs to ERT-432**, with the rows it describes,
  and its block now says so. ERT-510 and ERT-740 are what would otherwise ship the wrong order.
- **`AnomalyFlag.freezesRetention` is named by four documents and exists in none of the code.**
  `Employee.retentionFrozen` is still `anomalyFlags.isNotEmpty()` — the E3 defect, closed in prose
  and never landed. ERT-734 owns it and has a gate, so it was left alone; ERT-410's flag test uses
  two **evidentiary** flags so that it stays correct the day ERT-734 changes the rule. Recorded here
  because E3 reads as closed and is not.
- **A builder default cannot reach the database unaided.** `anEmployee()` points at
  `Fixtures.DEPARTMENT_ID`, `EMPLOYMENT_TYPE_ID` and `HR_USER_ID`; the V2 seed holds
  `d00000000001` and `e00000000001`..`4`, and `users` is empty because the bootstrap admin is a
  startup use case rather than a seed row. Three foreign keys refuse the insert, each naming a
  constraint rather than the mismatch behind it. **ERT-420, ERT-610 and ERT-720 all hit this**; the
  fix is four rows in a `@BeforeTest`, not re-pointing every builder call.

And the vacuity lesson got a third instance, which is the one worth reading twice. ERT-320 found it
in `sort_order`, ERT-350 in a single seeded department, and ERT-410 found it **in a test written
specifically to prevent it**. The flag encoder sorts by ordinal; the review step noticed nothing
asserted the sort, so an assertion on the stored column text was added using the ticket's two flags —
and replacing `sortedBy { it.ordinal }` with `reversed()` produced the sorted order *anyway*, so the
new test still passed. Two flags leave too few arrangements for a coincidence to be unlikely. The
test now uses **three**, in an order that is neither the sorted one nor its reverse, and both breaks
fail it. **Writing the anti-coincidence test is not the same as checking that it works.**

Eight deliberate breaks were applied to the finished adapter and the suite re-run each time — the
`ORDER BY` on `requirementsOf` and on `findActiveByEmail`, the active-status filter, the email
`lowerCase()`, `create`'s taken-id check, the flag decoder's blank filter, the flag encoder's sort,
and the half-populated attestation branch. Every one failed a named test; two of the eight were found
by the review step rather than the plan, and both were genuinely untested until it ran.

One smaller trap, in the family already recorded for `eq`, `and` and `innerJoin`: **`inList` is the
same top-level import**, and ERT-410 is its first use. The member `ISqlExpressionBuilder.inList` is
*deprecated* in Exposed 1.3.0 with a `ReplaceWith` naming `org.jetbrains.exposed.v1.core.inList`, so
the fix is an import rather than a rewrite — but the compiler's deprecation notice reads as advice
rather than as the missing-import message it actually is.

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
list — and, since Q4 was answered, HR sign-in (§2).

Three items look deferrable and are not. The PRD is explicit that each is expensive to retrofit
rather than merely inconvenient:

- **The write-mostly portal** (§8.6) — a rule about what the API returns. Deciding it later means
  unbuilding a preview feature and re-testing every portal endpoint.
- **The session model and the recovery path** (§6.6) — retrofitting a session boundary onto a live
  public endpoint is a rewrite, not an addition, and the access trail it feeds cannot be backfilled.
- **Review-and-submit with attestation** (§7.2) — it changes the data model, and every packet status
  depends on it.

> If Phase 1 must be trimmed, cut the validation workflow before cutting the upload portal. Do not
> cut the access model or the review-and-submit phase.

---

## Sequencing

```
ERT-100 ──► ERT-190 ──► ERT-200 ──► ERT-300 ──┬─► ERT-400 ──┬─► ERT-500
foundations  HR auth     test harness  policy  │  hire       │  HR read side
                                               │  creation   │
                                               │             └─► ERT-600 ──► ERT-700 ──┬─► ERT-900 ──► ERT-1000
                                               │                portal      document   │  review &     notifications
                                               │                access      upload     │  submit       & link lifecycle
                                               │                  ▲                    │
                                               │            ERT-1110                   │
                                               │            (hard gate)                │
                                               └───────────────────────────────────────┴─► ERT-800
                                                                                          HR document access
```

**Critical path:** ERT-100 → ~~ERT-190~~ → ERT-200 → ERT-300 → **ERT-400** → ERT-600 → ERT-700 → ERT-900.

ERT-190 joined the path on 2026-09-16 when Q4 was answered. It is Phase 0 work and delays Phase 1 by
roughly a session — worth it, because all three costs it avoids are *rework* rather than delay, which
is what the table below exists to prevent.

ERT-500 and ERT-800 hang off the path and can be taken whenever their dependencies are met — useful
when you want a shorter session. ERT-1000 closes Phase 1. ERT-1100 is cross-cutting: **ERT-1110 gates
ERT-630**, and the rest are on the Phase 1 exit checklist or later.

### ERT-190 — the last of Phase 0

Q4 was answered on 2026-09-16 and ERT-190 replaced the placeholder scheme rather than extending it,
which is what architecture §14 said should happen. The scheme no longer signs with a per-run random
key outside dev, and `sub` names a `users` row. Six use cases landed with it — sign-in, change
password, create, activate, reset, and the bootstrap admin — so **`domain/usecase/` stopped being
empty**, `ArchitectureTest`'s tracing tripwire fired exactly as ERT-195 predicted it would, and was
flipped from `Vacuous` to `Checked` rather than deleted.

**The thing worth carrying forward is that a test can finally mint a token this application accepts.**
ERT-340 recorded that gap in its own test class and ERT-350 repeated it: `configureSecurity` read the
environment itself, so no test could configure both halves, and "requires HR auth" had never been
tested in the positive direction on any route. The fix was not a bigger test — it was making
`configureSecurity` take its `JwtConfig` as a parameter, the same shape `configureRouting` already
took `HR_AUTH`. `testdata/HrTokens` then signs through the real `JwtIssuer`, so a route test breaks
when the token shape changes instead of passing against a token production never mints.

Five things were decided rather than assumed:

- **Four of the five actor columns became foreign keys; `audit_logs.actor` did not.** The trail must
  record actors that are not users — the V2 seed, the ERT-1020 expiry sweep, a future import job — and
  an append-only trail that can refuse a write because it cannot name a user is worse than one carrying
  a string. It keeps its free text and gained a **nullable** `actor_user_id` beside it.
- **Case-insensitive email uniqueness is a `check` plus a plain unique index, not an expression
  index.** H2 rejects `create unique index ... (lower(email))` outright, so the suite would have run
  against a schema production could not have. The pair is standard SQL in both engines and is strictly
  stronger: every stored address is forced into canonical lower case, so two casings cannot coexist
  *and* every value is already in the form the sign-in lookup compares against.
- **Sign-in is uniform in elapsed time, not only in body.** Every branch verifies a password against
  some hash — the absent-user case against a decoy produced by the injected `Hasher`, so it carries
  whatever work factor is actually bound. Byte-identical bodies are worth nothing if one branch returns
  in 1 ms and the other in 100. The audit row is uniform too: a `SIGN_IN_FAILED` entry never names the
  account, **even when one was found**, or the trail becomes the oracle the 401 denies.
- **The bootstrap admin is created only when `users` is empty**, decided on the row count rather than
  on "does this email exist". The latter would resurrect the account every boot after an operator
  deactivated it. It also means changing `HR_BOOTSTRAP_PASSWORD` and restarting rewrites nothing: a
  startup path that can rewrite a live credential from an environment variable is a backdoor with a
  nice name.
- **Two roles differ in configuration rights, not validation rights**, and that does **not** close
  SEC-10. §8.13 keeps one effective role for v1 and mitigates it with the exception report.

One thing was found rather than decided, and it is the kind that costs an afternoon:

> **A JWT's `exp` is validated against the real system clock, which no injected `Clock` reaches.**
> `FixedClock.DEFAULT` is a fixed date now eight months past, so test tokens issued at it were expired
> before they were presented — seven route tests failed at once, all with the same 401 and none of them
> pointing at the cause. `HrTokens` and the sign-in route test issue at `Instant.now()`; everything else
> in the suite stays fixed. This is the one documented boundary of the never-`Instant.now()` rule.

Widening `MigrationTest`'s portability sweep from V1 to the whole directory also turned up a false
positive worth keeping in mind: V2 failed on `MERGE INTO`, in a comment explaining why `MERGE INTO` is
not used. The guard now strips `--` comments before scanning — a guard that cannot be documented around
is one people write around instead.

### Orderings that cause rework if reversed

| Do this | Not that | Why |
|---|---|---|
| ~~**ERT-190 before ERT-410**~~ — **done, 2026-09-16** | Build the hire mapper first, add users later | ERT-410 writes the row↔domain mapper for `employees.created_by`. Narrowing that column afterwards would have redone the mapper, its round-trip test **and** the drift baseline, while ERT-430 wrote a `created_by` naming nobody. The column is already a `PersonId` foreign key, so ERT-410 writes it once. It also closed the gap ERT-340 recorded: `testdata/HrTokens` now mints a token the application accepts, so "requires HR auth" is tested in the positive direction. |
| **ERT-1110 before ERT-630** | Mount the first portal route, redact the logs later | `StatusPages` logs the request URI unredacted, and ERT-630 creates the first route whose path **is** the credential. A token written to a log file cannot be un-logged, and since 2026-09-16 that token is the whole of authentication rather than half of it. The dependency is in ERT-630's `Depends on` row, because a sentence is not checkable. |
| **ERT-160 before ERT-433 and ERT-420** | Hash the link token with bcrypt | bcrypt is salted, so a token hashed at issue cannot be recomputed at lookup. Ship it and **every live link becomes unresolvable** — recovery means re-issuing every credential and re-inviting every hire through a bulk send path §8.2 deliberately makes hard. |
| **ERT-170 before ERT-740** | Write the first portal DTO, guard it later | The realistic failure is not a deliberate preview — it is `mimeType` "for the icon" and `originalFilename` "for the confirmation toast", both of which read as reasonable in review. Removing them later means re-testing every portal endpoint (§8.6, SEC-02). **Load-bearing since 2026-09-16:** with the link alone opening the portal, write-mostly is what keeps a leaked link a fraudulent-upload problem rather than a disclosure one. |
| ERT-600 before ERT-700 | Upload first, session boundary later | Retrofitting a session model onto a live public upload endpoint is a rewrite (§6.6). The trail gap it leaves is worse: an append-only history cannot be backfilled. |
| ERT-900 inside Phase 1 | Defer attestation to Phase 2 | Attestation changes the data model and every packet status depends on it (§7.2, SEC-07) |
| ERT-310 before ERT-433 | Hardcode durations, read policy later | If `expiresAt` comes from `LinkPolicy`'s Kotlin defaults rather than `app_setting`, §8.10 is violated from the first row and **nothing detects it** — the numbers are identical. The quietest of these risks. |
| ERT-610 before ERT-630 | Add the trail once endpoints exist | Invariant 7 admits no gaps, and adding logging to five handlers afterwards means auditing each for early returns — which is exactly where a denied attempt goes. |

---

## Phase 1 exit checklist

The roadmap has never had one, which is exactly how E5 could be a "named exit risk" that nothing
checked. Phase 1 is not done until every row is closed or consciously waived in writing.

| Item | Owner | Ticket |
|---|---|---|
| Malware scanning delivered — the `isClean` gate stops being a stub | Engineering / Security | **ERT-1150** (Q22) |
| `APP_ENV` fails closed, and the deployment's instance assumption is recorded | Engineering | **ERT-1120** |
| CI runs the build and the suite on every push | Engineering | **ERT-1160** |
| Sending domain chosen, with SPF/DKIM/DMARC aligned | IT | Q12's second half |
| Consent notice wording signed off, so the attestation text stops being a placeholder | Legal / compliance | Q5 → ERT-912 |
| Object-storage project and bucket provisioned, private and encrypted at rest | Engineering | Q20 → ERT-710 |
| Exception report has a named owner and a stated cadence | HR | Q19 — an unread report is not a control |
| The §7.1 upload limits and §6.4 defaults confirmed against real use | HR | Q9, Q17 |
| The real requirement checklist replaces Appendix A's illustrative seed | HR stakeholder | Q2 |

---

## Decision register

PRD [§14](employee-requirements-tracker-prd_1.md) holds the questions; this table holds **who owes an
answer, by when, and what proceeds meanwhile**. Every number Q1–Q22 appears here exactly once, which
is the check that would have caught Q7, Q10, Q11, Q13, Q14, Q15 and Q19 going missing from it.

### Answered

| # | Question | Answer | Landed in |
|---|---|---|---|
| Q4 | Who are the HR users, how do they authenticate, is there SSO? | A handful of HR staff; **local accounts, two roles, bcrypt, no SSO.** No separation-of-duties enforcement in v1 | PRD §2, §8.13, §12; architecture §14; **ERT-190** |
| Q12 | Email delivery mechanism and sending domain | **SMTP relay on internal mail.** The sending domain stays with IT, on the exit checklist | **ERT-440**, **ERT-1010** |
| Q20 | Object storage target *(opened 2026-09-16; it had no number before)* | **GCP Cloud Storage**, V4 signed URLs, private and encrypted at rest. Filesystem adapter for dev and tests | PRD §12; architecture §14; **ERT-710** |
| Q21 | The upload MIME allowlist *(opened 2026-09-16)* | The §8.4 preview set — JPEG, PNG, HEIC, HEIF, PDF — **judged on sniffed content**, held in configuration | PRD §12; **ERT-732** |

### Open

| # | Question | Owner | Due | Blocks | Proceeding meanwhile |
|---|---|---|---|---|---|
| Q22 | Is ClamAV acceptable, and who runs it? *(opened 2026-09-16)* | Engineering / Security | Phase 1 exit | **ERT-1150** | `isClean` stubbed open with a startup warning; ERT-810 refuses to serve anything that fails it. A **named exit risk, not a delivered control** |
| Q5 | Data-protection regime and portal consent notice | Legal / compliance | Phase 1 exit | ERT-912's attestation **text** | Versioned placeholder text. The versioning mechanism is the expensive half and is built |
| Q2 | The actual requirement checklist, and whether it differs by employment type | HR stakeholder | Phase 1 exit | ERT-130's seed *content*, not its mechanism | Appendix A seeded and clearly marked illustrative. Templates are data, so replacing them is a seed change |
| Q6 | How does `COMPLETE` reach account provisioning? | IT | Phase 1 exit | The handoff seam | `COMPLETE` is recorded; nothing consumes it yet |
| Q9 | Confirm the §7.1 upload limits and §6.4 defaults | HR / IT | Phase 1 exit | Nothing | Defaults are in the PRD. **Note: the §6.4/§6.6 values are in `app_setting`; the §7.1 upload limits are not** — they are constants on `Submission`, so changing one is a release (C13) |
| Q17 | Confirm the §6.6 defaults — PIN length, session duration, lockout and suspend thresholds | HR / IT | Phase 1 exit | Nothing | Session and lockout values are settings; **PIN length is a compiled constant** and stays one (C12) |
| Q11 | Multiple files per requirement in v1? | Design / HR | Phase 1 exit | Nothing | v1 ships one file per requirement; the version chain already models the rest |
| Q16 | Is a phone number available for out-of-band verification? | HR | Phase 2 | `ChangeHireEmailUseCase` — **and now ERT-650's recovery-PIN hand-off**, which needs the same channel | `VerificationMethod` already offers two non-phone fallbacks (recruiter, in person), so the *model* is not blocked; the **policy** is. Without a channel, SEC-03 is unremediated in practice regardless of what §7.4 says |
| Q7 | Long-term retention here, or archive into the HRIS? | HR / IT | Phase 2 | The retention sweep | Nothing deletes anything yet |
| Q10 | Reopen a `COMPLETE` record, and does it need a second approver? | HR | Phase 2 | `ReopenRecordUseCase` | Phase 2 is not expanded into tickets |
| Q18 | Evidence preservation against data minimisation — which wins? | Legal | Phase 2 | The retention policy's shape | E3 settles *which flags* freeze; this settles *how long* the freeze may last |
| Q19 | Who owns the exception report, and on what cadence? | HR | Phase 3 | §8.13's value, not its build | The report can be built unowned; an unread report is not a control |
| Q8 | Expected volume: hires per month, and peak? | HR | Phase 3 | Whether CSV import matters | Import is Phase 3 regardless |
| Q13 | Reminder cadence, and automatic or HR-approved? | HR | Phase 3 | Reminders | P1, Phase 3 |
| Q14 | Is a Filipino-language portal needed? | HR | Phase 3 | Nothing | P2 |
| Q1 | How do tenured employees enter the system? | HR / IT | Before Phase 4 is scheduled | All of Phase 4 | Phase 4 is not scheduled. The §9.3 seams are already open |
| Q3 | Which documents have validity periods, and how long? | HR stakeholder | Before Phase 4 | Phase 4 | Columns exist and are nullable |

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

Now a real epic with real tickets: [ERT-1100](backlog/ERT-1100-operability-hardening.md).

| Epic | Covers | Gate |
|---|---|---|
| HR authentication | Replace the placeholder JWT scheme with the real model; local `users`, two roles, sign-in | **Done — ERT-190, 2026-09-16** |
| Security hardening | Malware scanning before a file becomes previewable; pepper rotation; retention sweep with the freeze honoured | **ERT-1150** (Q22), **ERT-1130**; retention needs Q7 and Q18 |
| Operability | `APP_ENV` fails closed, startup summary, deployment configuration, CI | **ERT-1120**, **ERT-1160** |
| Documentation hygiene | The root README is still stock Ktor generator boilerplate and advertises deleted features; the unused R2DBC dependencies | **ERT-1140** |

---

## Contradictions and gaps to escalate

Found while writing the backlog, and extended by a full cross-document sweep on 2026-09-16. **Every
row is kept, resolved or not** — the history is the point, and a deleted row is one a future reader
re-files. E1–E8 came from the first pass; C1–C22 from the sweep, of which the ones still worth
tracking are listed.

| # | Issue | Resolution |
|---|---|---|
| E1 | §7.2 asked for thumbnails on the review screen; §8.6 forbids the portal returning a preview | **Closed.** §8.6 wins; PRD v0.5 amended §7.2. The API contract keeps a note so a reader of an older §7.2 does not re-file it |
| E2 | The upload MIME allowlist was never stated | **Closed as Q21.** JPEG, PNG, HEIC, HEIF, PDF — sniffed, configurable. PRD §12, ERT-732 |
| E3 | A duplicate-email override silently froze retention, because `retentionFrozen` derived from `anomalyFlags.isNotEmpty()` | **Closed.** Freezes on the four evidentiary flags only; `SHARED_EMAIL` and `SEPARATION_OF_DUTIES` do not. PRD §7.1, ERT-734, invariant 8. **PRD owner to ratify** |
| E4 | §8.1 required an invite-delivery-failure indicator; §11 modelled no column | **Closed.** Derived from the latest outbox row, with the audit log as history — better than the audit-log-only guess, which predated ERT-440's outbox being settled |
| E5 | Malware scanning was a P0 control with no library, no owner and no question number | **Converted to Q22 + ERT-1150.** ClamAV via `clamd`, Engineering / Security, Phase 1 exit. Still a named exit risk, but now one with a gate |
| E6 | No object-storage target chosen, and the PRD asked no question about it | **Closed as Q20.** GCP Cloud Storage; filesystem for dev. The port's shape now matches presign-with-a-TTL |
| E7 | The audit said 14 findings; the dispositions listed SEC-01…SEC-15 | **Closed.** Five Mediums, not four; 15 findings. Corrected in the audit with a dated note, and in PRD §0 and architecture §1 |
| E8 | Unknown department or employment type — 404 or 422? | **Closed: 422**, two codes. Stated once in the API contract; ERT-300's wording corrected; **ERT-431 implements it** |
| C1 | `ReasonRequired` maps to 422 in code and in the API contract's error table, but three other places said **409** for the duplicate-email case — and the API contract contradicted itself | **Closed: 422.** `Conflict` would drop the `details` entry naming `duplicateReason`, which is why `ReasonRequired` exists as a separate case. **This was never on the escalation list and would have surfaced as a failing test in ERT-450** |
| C2 | `DuplicateEmailRequiresReason` was cited as an `AppError` case in CLAUDE.md, architecture §5 and ERT-431 — **it does not exist** | **Closed.** Three documents were prescribing an unapproved specification change. The rule is carried by `ReasonRequired(code, action)` |
| C4 | Attestation-version failure: the API contract collapsed "missing or unknown" into 422; ERT-912 split missing (422) from stale (409) | **Closed.** Missing → 422, stale → 409 carrying the current version |
| C5 | The audit's proposed §14 questions 15–19 were silently renumbered when folded into the PRD, and one was dropped | **Documented, not renumbered.** PRD §14 carries a note: cross-reference the audit by wording, not by number. Renumbering now would break 26 citations |
| C9 | Port count said 12 in the roadmap, 11 in architecture; there are 11 — and `ReferenceDataRepository` has **no fake** | **Closed.** Count corrected; the missing fake is now in ERT-431's file list, where nothing previously recorded it as anyone's job |
| C12 | PIN length is a compiled constant, but §8.10 and Q17 treated it as configurable | **Closed.** §8.10 now names which §6.6 values are settings and says plainly that PIN length is not |
| C13 | The register claimed the §7.1 upload limits live in `app_setting` — **they do not**; only the nine §6.4/§6.6 keys are seeded | **Closed.** Q9's row now says so, and ERT-732 names `UPLOAD_MIME_ALLOWLIST` as its actual configuration surface |
| C14 | Session management was Phase 2 in the API contract, Phase 3 in three places, P1 in two | **Closed: Phase 3.** Priority and phase are different axes; the storage it reads is still built in Phase 1 |
| C15 | ERT-620's criterion asserted `findActiveForLink` "returns every live session" — the port takes no clock, so it could not | **Closed.** The port grows a `now` parameter. Filtering in the caller would put the same expiry rule in every call site |
| C17 | ERT-644 and ERT-1040 carried identical acceptance criteria, so whichever ran second would duplicate or drop the work | **Closed.** ERT-644 owns the *gate*, ERT-1040 owns the *copy* |
| C18 | Phase 1 promised a "request a new link" affordance whose endpoint is Phase 2 | **Closed.** Phase 1 explains, and names no action. PRD §6.4's "expiry is recoverable" is true from Phase 2; until then recovery is `resend-link` or ERT-650's PIN |
| C20 | Architecture §9 asserted and retracted the same claim in one paragraph | **Closed.** §9 states the corrected claim outright |
| C21 | §12 said "sliding expiry from last activity"; §6.4 says two clocks with the earlier winning. A reader implementing §12 literally would build only the idle clock | **Closed.** §12 now names both clocks |
| C22 | `StatusPages` logs the request URI unredacted at two call sites — a known invariant-4 violation with no ticket | **Closed as ERT-1110**, gating ERT-630 through a `Depends on` row |

**Still open, and deliberately so:** the PRD has **no owner**. E3's ratification, and any future
contradiction between two P0 sections, route to a role nobody holds. Escalated 2026-09-16, due
2026-09-30, HR sponsor.
