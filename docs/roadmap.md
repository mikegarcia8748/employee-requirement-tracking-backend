# Delivery roadmap

**Next ticket: [ERT-434 — Invitation dispatch and surviving delivery failure](backlog/ERT-400-hire-creation.md#ert-434--invitation-dispatch-and-surviving-delivery-failure)**
— the deployment track's remaining ticket,
[ERT-1260](backlog/ERT-1200-deployment.md#ert-1260--gcp-foundation-identity-federation-registry-network-database-secrets),
is the one piece of work in this project that needs something outside the repository: a GCP project
and an Owner.

> **Read HAR-02's second half before writing ERT-434.** `OutboxNotifier` returns `Failed` and writes
> **nothing** when the insert is what failed, so §8.1's delivery-failure indicator — which
> `NotificationOutbox`'s KDoc says is derived from the latest row for a hire — cannot see a
> queue-insert failure at all. ERT-250 closed the fake's half of that finding and its block states
> the open question plainly; ERT-434 owns the answer.

> **2026-09-18 — ERT-100 and ERT-200 were reviewed against their own implementation, and ERT-200 is
> reopened.** [The findings](2026-09-18-ert-100-200-review.md) continue the house numbering from
> SEC-30, PERF-09 and C28, and introduce **HAR-** for a third category: the test harness disagreeing
> with the code it stands in for. **Neither 2026-09-17 audit looked at ERT-200 at all**, and both
> excluded index design — which is where most of this lives.
>
> Nothing here changes the **Next ticket** pointer. Three findings gate work already in the queue and
> should be read before the ticket they gate:
>
> | Finding | Gates | Ticket |
> |---|---|---|
> | **HAR-04** — `PortalSession` has no `tokenHash`, but the column is `NOT NULL UNIQUE`, so `save` has no source for it | **ERT-620**, as a compile error inside its adapter | criteria added to ERT-620 |
> | **PERF-10** — the baseline schema indexes almost none of its foreign keys, including `portal_access_logs (upload_link_id, timestamp)`, which §6.6's lockout counter reads on the unauthenticated portal path | **ERT-610, ERT-1020** — after them it is the same work against tables with rows | **ERT-1190** |
> | **HAR-02** — `OutboxNotifier` returns `Failed` and writes **nothing** when the insert is what failed, so §8.1's indicator, *"derived from the latest row"*, cannot see it | **ERT-434 — the next ticket** | criteria added to ERT-434 |
>
> **ERT-250 and ERT-260 closed it again the same day** — see the section below. ERT-200 is `Done`.
>
> **A [second pass](2026-09-18-ert-100-200-review-2.md) then re-read both epics with those tickets
> landed**, and found eleven more. Three of the first pass's *not measured* claims are now measured:
> PERF-12's collation divergence is **real and wider than assumed** (HAR-09), and HAR-05's V4 defect
> is a **hard failure** rather than the silent discard offered as the likelier of two. Two findings
> exist only because ERT-250 built the instruments that found them. **No new ticket numbers were
> created** — every finding folds into a ticket that already owns the area, which is the remedy for
> the first pass leaving two of its own findings unowned (C35).
>
> The largest finding is **HAR-01**: nothing keeps a port, its fake and its adapter in step, and six
> divergences are live. Two compose into one that matters — `FakeHrUserRepository` permits two
> accounts on one address and signs the first in, while the adapter's `singleOrNull` returns **null**,
> so a fake-backed sign-in test can be green in a state where production refuses every sign-in for
> that address. **ERT-250** is a contract suite per port rather than six patches, for the reason
> ERT-146 and ERT-1245 both give: the guard is the deliverable. **ERT-260** finally gives ERT-240's
> unowned *"a Postgres CI job, worth adding before launch"* a number — the project's own E5/E6/C22
> lesson, applied everywhere except to its test harness. **ERT-1195** turns five hand-verified
> fail-closed controls into a CI step, since nothing in this project has ever booted in production
> mode: `module.yaml` sets `APP_ENV: dev` for the whole test JVM and the container smoke test passes
> `-e APP_ENV=dev`.
>
> **Two candidate findings were investigated and dropped**, and the review says so rather than
> omitting them: password length policy (`PasswordPolicy`, 12 characters to 72 bytes, tested) and the
> cross-field `app_setting` bounds the V3 seed deferred (`crossFieldErrors()` implements both). The
> suite was **689 tests, 13.75 s, green** before and after.

> **2026-09-18 — ERT-300 was reviewed against its own implementation, and ERT-310 is reopened.**
> [The findings](2026-09-18-ert-300-review.md) continue the house numbering from SEC-37, PERF-13,
> HAR-14 and C37. **Neither ERT-100/ERT-200 pass looked at this epic**: both were scoped to Phase 0,
> and ERT-300 is the first Phase 1 epic and the first four adapters — the pattern ERT-410 onward copy.
>
> Twelve findings, two High. **Nothing here changes the Next ticket pointer.** Two findings gate work
> already in the queue and should be read before the ticket they gate:
>
> | Finding | Gates | Ticket |
> |---|---|---|
> | **SEC-41** — `Notifier.sendRecoveryPin` takes an `AccessPin` and `NotificationMessages.recoveryPin` renders it into an email body, while PRD §12, PRD §5, architecture §12 invariant 4 and `CLAUDE.md` invariant 4 all say a PIN is **never emailed**. Nothing calls it yet | **ERT-650**, which would be the first caller | criteria added to **ERT-650** |
> | **HAR-15** — PRD §12's append-only guarantee for `audit_logs` is a text sweep of **one file**, and `audit_logs` has no row in architecture §12 at all | **ERT-734**, whose entire job is deleting rows | criteria added to **ERT-734** |
>
> **The largest in-scope finding is SEC-38: two of the nine §6.4 settings can never be saved.**
> `updateLinkPolicy` builds its audit metadata keys from the setting key, and the credential guard
> refuses any key containing `pin` as a substring — which `portal.pin_attempts_before_lockout` and
> `portal.pin_failures_before_suspend` both do, throwing `IllegalArgumentException` out of a
> `DomainResult` method. Fifteen `updateLinkPolicy` exercises across the adapter test and the contract
> suite touch neither field. It is **C25's guard from the other side**: C25 is the value-side false
> positive and stays with ERT-450; the fix here exempts the closed set of keys the application itself
> generates, leaving the denylist as strict for every other caller.
>
> **SEC-39** adds the third cross-field rule `V3__app_settings.sql` claims a static bound enforces —
> `extend_on_rejection_days` can outrun the absolute ceiling by 13×. The first pass cleared this
> category correctly: it read the header's two named deferrals, and the third is asserted forty lines
> down as a property of a bound rather than as a deferral.
>
> **No new ticket numbers.** ERT-310 is reopened with SEC-38, SEC-39 and HAR-19; everything else folds
> into ERT-250, ERT-520, ERT-620, ERT-650, ERT-734, ERT-1020 and ERT-1190. Four documentation
> contradictions (C40, C41, C42) were fixed in the review's own branch.

> **[ERT-146](backlog/ERT-100-foundations.md#ert-146--a-request-with-no-content-type-is-415-and-every-body-taking-route-publishes-its-schema)
> jumped the queue on 2026-09-17 and is Done**, which is why ERT-433 is still the next ticket rather
> than the one after it. `POST /api/auth/login` answered a user-reachable **500** to a request with
> no `Content-Type` — which is the only kind Swagger UI could send it, because no POST route in the
> project published a request schema. A 673-test suite was green throughout: every route test sets
> `contentType(...)`, so none of them ever sent the request that breaks. Read C28, and the ticket.

> **ERT-433 owes two decisions in writing before it writes code.** **C23** is its to settle:
> `Notifier.sendInvitation` still *requires* an `AccessPin`, and since 2026-09-16 the invitation must
> carry none — the parameter is what makes "only the invitation may carry a credential" a
> compile-time property, so removing it weakens a real guard, and three options are on the table with
> none free. And **`AppSettingsRepository.linkPolicy()` returns a `DomainResult`, which ERT-433 must
> propagate rather than recover from**: `LinkPolicy`'s Kotlin defaults are *identical* to the seeded
> rows, so a silent fallback returns exactly what a correct read returns and no behavioural test can
> tell them apart. Refusing to issue a link is the right answer to a policy nobody can read.

> **Two sessions landed on 2026-09-17 and this file is their merge.** ERT-431 took the product path
> forward; ERT-1200 gave the system somewhere to run. They touched disjoint code and the same three
> paragraphs of this file.
>
> **The suite is 653 tests, measured after the merge rather than added up.** Each branch reported its
> own total against the same 592-test base — ERT-431 said 622, ERT-1200 said 623 — and neither number
> survives a merge. The two sets turned out to be disjoint; that was worth checking rather than
> assuming.
>
> ### ERT-431 — the first business use case
>
> `CreateHireUseCase` validates the email, refuses an unknown department or employment type by name,
> and makes a duplicate on an active hire carry a typed reason. `domain/usecase/` now holds seven
> classes, and the eleventh port finally has a fake (C9) — closing an item C9 recorded as nobody's
> job.
>
> **Read the `HireCreated` note in ERT-431's block before writing ERT-432.** The result type is a
> wrapper, decided once so the remaining three sub-tasks extend it rather than re-argue it: ERT-432
> adds the `RequirementSet`, ERT-433 the `UploadLink`, ERT-434 the delivery indicator.
>
> **`SHARED_EMAIL` now has a producer, which makes E3 live rather than theoretical.** ERT-431 is the
> first code that ever sets the flag, and `Employee.retentionFrozen` freezes on it — which the API
> contract says it must not. Deferred to ERT-734 on ERT-410's precedent, and the test deliberately
> asserts **nothing** about `retentionFrozen` in either direction so neither the defect nor its fix is
> entrenched.
>
> **C23 is untouched and still ERT-433's to decide in writing.** ERT-431 issues no link and sends no
> invitation, so it never had to call `Notifier.sendInvitation`.
>
> **Three new escalation rows: C24, C25 and C26.** C25 is the one to read — it is a user-reachable
> 500 that ERT-431 made reachable and did not cause.
>
> **The mutation harness was itself vacuous on its first run**, which is the lesson worth carrying
> past this ticket. See the ERT-431 block.
>
> ### ERT-1200 — the system has somewhere to run
>
> A container image, a local Postgres, CI, and a CI/CD path to two Cloud Run services. Everything
> except ERT-1260 is committed and verified. [`docs/deployment.md`](deployment.md) is the step-by-step
> runbook ERT-1100 deferred until a target environment existed.
>
> **Read this before writing any configuration reader: `APP_ENV` unset now means PRODUCTION**
> (ERT-1120). Five controls used to hang off a variable that failed open. `./kotlin run` on a fresh
> checkout therefore needs `set -a; . ./.env.dev; set +a` first, and the suite gets `APP_ENV=dev`
> from `settings.jvm.test.extraEnvironment` — a real environment variable, not a test-only backdoor
> into a security control. `DATABASE_URL` now fails closed the same way (ERT-1241); it was the only
> config path that did not, and its failure mode was a *green* deploy writing to a database that
> evaporates.
>
> **The multi-instance question is answered: MULTI-INSTANCE, and the pin was rejected rather than
> not chosen.** `--max-instances 1` is a per-revision ceiling, not a mutex. **ERT-660 and ERT-1020
> must read the answer recorded on ERT-1120 before they are written** — an in-memory limiter is
> coarse shaping with an effective limit of `configured × instances`, and a per-process timer runs
> every job on every instance. ERT-1010's poller is already safe; that is stated so nobody "fixes"
> it.
>
> **ERT-1245 found three HR routes that skipped the password-change gate**, contradicting a stated
> invariant. Fixed, and `ArchitectureTest` now fails the build on any handler under `route/hr/` that
> does not open with one. The comment that claimed the gate was "applied once, around every HR
> route" is how it spread from one file to the next.
>
> **Two audits shipped**, the first against code and infrastructure rather than the specification:
> [security](2026-09-17-security-audit.md) and [bottleneck](2026-09-17-bottleneck-audit.md). The one
> finding that is both a denial of service and a guessing oracle is **SEC-19 / PERF-01** — sign-in
> measured at 3.95 req/s at concurrency 1, because invariant 10 requires a bcrypt verification on
> every losing path. That is correct and must not be weakened; ERT-1170 bounds how many attempts
> reach it, and depends on ERT-1185 because the code's own justification for having no rate limit is
> an audit row nobody reads.

The full board is [docs/backlog/README.md](backlog/README.md). This file holds sequencing, the
decision register, and the pointer above. Each session updates that pointer on the way out.

---

## Where the code is

| | |
|---|---|
| Built | `core/` value objects and error types · 13 domain models with status logic · 13 ports · 13 Exposed tables · bcrypt for PINs, an HMAC token digest, clock and secure generators · a use case tracer behind `TRACE_USECASES`, with per-request correlation · 6 Ktor plugins · generated OpenAPI · an architecture test that fails the build on a layer violation, **on a portal DTO leaking document content**, or **on an untraced use case** · a test harness of **13** in-memory fakes, an advanceable `FixedClock`, deterministic generators and a builder per domain model · **a contract suite per port, run against the fake and the adapter both, with a guard failing the build on a port that has no fake** · a `RepositoryTestBase` giving one migrated, seeded, isolated database per test — H2 by default, **a PostgreSQL schema when `ERT_TEST_DATABASE_URL` is set, which CI's second job does** · **seven Exposed adapters, an outbox notifier and a JWT issuer, bound and resolved by a wiring test** — the §6.4 link policy, the append-only audit trail, the requirement catalogue, the reference data, HR accounts, **hires with their requirement sets**, **upload links resolved by token digest** and **a durable notification outbox** · **seven use cases** (sign-in, change password, create/activate/reset a user, bootstrap the first admin, and **hire creation with its snapshotted requirement set**) · **the real HR auth scheme**: local `users`, two roles, bcrypt, tokens signed against a row, a bootstrap admin that refuses to start a non-dev deployment with no way in, and `testdata/HrTokens` minting a token any route test can present |
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
a client cannot generate from. **ERT-146 later found the other half of that sentence missing** — the
generator infers nothing from `call.receive` either, and five POST routes had shipped with no
request schema at all, which made `POST /api/auth/login` answer 415 to its own documentation and 500
to the caller. Both halves are now in `api-contract.md` and both are enforced by `ArchitectureTest`
rather than by convention.

**ERT-100 is now closed** — reopened once, on 2026-09-17, for ERT-146, and closed again in the same session. ERT-150 exposed the
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
  **Landed 2026-09-17**: `sort_order_snapshot` exists and `requirementsOf` orders by it.
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

**ERT-420 then made a link resolvable by its digest, and it is the first ticket where the 2026-09-16
access-model reversal reached code rather than prose.** The suite went from 549 tests to 568, and
ERT-1020 unblocks.

Three things were decided rather than assumed, and the first two bind later tickets:

- **`upload_links.pin_hash` is nullable from V5, and ERT-440's outbox migration moves to V6.** V1
  wrote it `NOT NULL` under the model where the URL opened nothing without the PIN and both were
  issued with the hire; the link alone now opens the portal and the PIN is minted on demand by
  ERT-650, so **ERT-433 issues a link that has none.** The adapter would have round-tripped a
  non-null field perfectly well — **this ticket was not blocked, the next one was** — which is
  exactly why it is recorded. The alternative is worse than it sounds: a bcrypt hash of a six-digit
  value nobody was told is a credential-shaped digest for a credential that does not exist, and the
  column cannot tell it from a live one, so "has this hire been given a recovery PIN?" stops being
  answerable from the data. The migration is `drop not null` and nothing else, because unlike V4's
  `ALTER COLUMN ... TYPE` that spelling is identical in H2 and PostgreSQL — verified against H2
  2.4.240 in PostgreSQL mode before the file was written. `pin_expires_at` and `pin_used_at` stay
  with ERT-650, on ERT-432's principle that a column belongs with the rows it describes.
- **`LinkScope` had no serializer, and the ticket read as though it did.** The column has carried a
  literal `'ALL'` default since V1 with no writer and no reader, so "round-trips with its template
  ids intact" was undesigned work. It is now `ALL`, or `ONLY:` and the ids, sorted, with `All`
  encoding to the column's **own default** so a row written by a migration or a psql prompt decodes
  as the scope it obviously means. Capacity is **39 ids** in `varchar(512)`, recorded in the mapper
  rather than fixed by widening; Appendix A has 14. **ERT-650 and the Phase 4 renewal links are what
  would otherwise hit that ceiling silently.**
- **`findActiveForEmployee` takes no clock, and that is C15's question answered rather than C15
  unfixed.** A session records only `started_at`, `expires_at` and `ended_at`, so that port had to
  grow a `now`; a link carries a stored `LinkStatus`. ERT-1020 states the rule this rests on —
  expiry is evaluated lazily at access time by ERT-644, and the sweep only sends the warning and
  keeps the status tidy — so the stored status is the whole answer, and a clock here would put a
  second definition of expiry in the layer that must hold no business rules. It returns `ACTIVE`
  only, **not** every status with `opensPortal = true`: `COMPLETED` is reachable but is not a link
  `resend-link` should reuse, and `FakeUploadLinkRepository` had already drawn that line.

**And the vacuity lesson got its fourth instance, which is the one that should change how the next
ticket works rather than merely what it knows.** ERT-320 found it in `sort_order`, ERT-350 in a
single seeded department, ERT-410 inside a test written to prevent it. ERT-420's test class opened
with a KDoc section citing all three by name — and **two of its own tests were vacuous anyway.** The
ordering test gave the newest link the *lowest* id, so dropping the `ORDER BY` entirely still passed:
H2 with no ordering returns the primary-key scan, and "lowest id" and "newest" were the same row. The
scope test used **two** ids given as `[2, 1]`, so replacing `sorted()` with `reversed()` produced the
sorted order anyway — ERT-410's exact coincidence, reproduced one epic later by a test whose comment
claimed to have avoided it.

Eight deliberate breaks were applied and the suite re-run each time — the `ACTIVE` filter, widening
it to every `opensPortal` status, the employee predicate, the `ORDER BY` reversed, the `ORDER BY`
dropped, the scope decoder's blank filter, the scope encoder's sort, and `save` always inserting. Six
failed a named test on the first pass; **two did not**, and both tests were rearranged until they
did. So: **intending to write the anti-coincidence test is not the same as writing it, and writing it
is not the same as checking that it works.** The mutation pass is the only step that tells the three
apart, and it is cheap — the whole suite runs in thirteen seconds.

**ERT-440 closed the epic's adapter work: every notification now has a durable row, and nothing is
transmitted.** ERT-1010 lands the SMTP relay Q12 named and drains the table; no use case changes when
it does. The suite went from 568 tests to 592, and **ERT-430 is fully unblocked** — every port it
names has an adapter.

Three things were decided rather than assumed:

- **`storesBody` is a constructor parameter on `NotificationKind`, not a `kind != INVITATION` check
  in the adapter.** Only the invitation carries a credential, so only its rendered body is dropped —
  and that rule has to survive an eighth kind being added by someone who has not read this. A
  comparison buried in the write path would let a new kind inherit `true` in silence; a constructor
  parameter means it **will not compile** until someone chooses. It is the device
  `AnomalyFlag.freezesRetention` and `LinkStatus.opensPortal` already use, and the one invariant 8
  asks for by name.
- **A failed invitation cannot be retried, and `retry` refuses it loudly rather than re-queueing.**
  That is the cost of dropping the body, stated from the other end: the token is persisted nowhere,
  so there is nothing to rebuild the message from, and reissuing is ERT-1030's `resend-link`. Silence
  would hand ERT-1010's drain a row with nothing to send and a loop that never terminates. **ERT-1010
  must not "fix" this by storing the body.**
- **`recipient` is nullable and `employee_id` is not**, which looks backwards until you read the
  port. Two of the seven methods take no address because they go to HR, whose mailbox is ERT-1010's
  configuration — and a sentinel string would be a lie in a column other code reads. Every method
  takes an `Employee`, so the hire is always known, and that is what lets §8.1's delivery-failure
  indicator be **derived** from the latest row (E4) rather than needing a column on `employees` that
  something has to remember to update.

Two findings came out of the guards rather than the plan, and both are worth keeping.

**`MigrationTest`'s column-width sweep failed on the new table**, and it was right to. An
`EntityIdTable` whose foreign key points at `employees` carries an 8-wide column, so the structural
rule reads it as wrong — the sweep's `personColumns` list is where that decision is recorded, and a
new person-keyed column has to be added to it deliberately. The guard cost a minute and would have
caught a genuinely wrong width just as loudly.

**And the vacuity lesson got a fifth instance, of a shape the first four did not cover.** Sixteen
deliberate breaks were applied across both adapters; fifteen failed a named test. The one that did
not was "store the exception message verbatim in the failure reason" — and the test meant to catch it
asserted *the reason does not contain the token*. It passed against the broken adapter, because the
only write failure a test can construct is a foreign-key violation whose message happens not to quote
the body. **It was testing H2's error text, not the adapter.** It now asserts a whitelist — the reason
matches `^[A-Za-z]+$`, a bare exception type — which is a rule about what may appear rather than a
list of what may not, and so holds for the failure the test cannot reach.

The generalisation is worth more than the fix: **a "does not contain" assertion is only as strong as
the input you can arrange.** Where the property is "nothing sensitive escapes", the test has to
constrain the shape of what *does* escape, because the dangerous case is by definition the one nobody
thought to construct. ERT-810's signed URLs and ERT-1110's log redaction are the next two places this
applies.

**ERT-431 opened `domain/usecase/` to the business, and it is the first use case that is not about an
HR account.** Hire creation now validates its email, refuses an unknown department or employment type
by name, and makes proceeding past a duplicate carry a typed reason. The suite went from 592 tests to
622. It bound no adapter: ERT-440 left the epic with every port this use case names already wired,
which is what "fully unblocked" meant.

Four things were decided rather than assumed, and the first two bind the next three sub-tasks:

- **The result is `HireCreated`, not `Employee`.** ERT-430 says 431 establishes the type the other
  three extend, and `Employee` cannot be it: ERT-434's criterion is that *the result* reports a
  delivery failure, which against a bare hire leaves only a delivery column on `employees` — the
  second copy **E4 explicitly forbids** — or a signature change at ERT-434, which is the rework the
  sub-task split exists to avoid. ERT-450 also needs the requirement set in its 201 body, and that is
  reached through `requirementsOf`, so a bare `Employee` would push the route into a second
  repository call. It lives in `domain/model/` on the `HrSession` precedent: commands sit beside
  their use case, results sit in the model.
- **The reference ids are checked *before* the duplicate, and the order is a rule.** The duplicate
  branch asks a human to type a justification that becomes a permanent audit artefact; asking for one
  on a request that is then going to fail on a bad department is the worst available ordering, and it
  confirms an address is in use on a request that was never going to succeed. Both orderings have a
  named test, so a later reordering is a visible deliberate break rather than a silent one.
- **The two reference checks are spelled out twice rather than extracted.** A shared
  `requireReference(raw, field, code, exists = ...)` reads better and would let a caller pass
  `reference::departmentExists` for both ids — the exact defect `ReferenceDataRepository` split into
  two methods to make unrepresentable. The duplication is the control. A malformed id folds into the
  same `*_unknown` failure rather than earning a second code, because from HR's side it has the same
  single remedy; the consequence is that the "does not exist" tests must use **well-formed but
  absent** ids or they never reach the check they are named for.
- **Required fields were nobody's, and the review step took them.** `first_name`, `last_name` and
  `position` are `not null` with no check constraint, so `""` stored cleanly and a hire could render
  as a blank row. ERT-432/433/434 own the snapshot, the token and the invitation; ERT-450 makes no
  decisions; the next candidate owner was Phase 2. `CreateHrUserUseCase` already runs this rule on
  this kind of field, so omitting it in the sibling use case was an inconsistency rather than a
  boundary.

**And the vacuity lesson got a sixth instance, one layer up from all five before it.** ERT-320 found
it in a seed, ERT-350 in a single seeded row, ERT-410 inside a test written to prevent it, ERT-420 in
two such tests in a class whose comment cited the first three, ERT-440 in a "does not contain"
assertion. ERT-431 found it **in the instrument**. Eighteen deliberate breaks were applied and one
reported `SURVIVED` — and it had not survived anything: it was a compile error, and the harness
decided "did it build?" by grepping for `error:`, which Amper never prints, because it writes `ERROR:`
inside a box-drawn frame. An empty list of failing tests was read as "nothing caught this" when it
actually meant "nothing ran".

The harness now requires the string `tests successful` before it will call an empty failure list a
survival. The generalisation: **a mutation pass proves nothing unless the harness can tell a green
suite from a suite that never started** — and every check written here to catch a vacuous *test* had
no equivalent watching the tool. All eighteen breaks fail a named test under the corrected harness,
and the three sharpest — both check orderings and using the argument's id instead of the one `create`
stored — are each caught by exactly the one test written for them.


**ERT-432 made the requirement set a copy, and closed the snapshot-column gap ERT-410 opened.** A
hire now arrives with its checklist — one row per active template, each carrying the template's name,
required flag and **sort order** — and `HireCreated` grew the field ERT-431 reserved for it. The
suite went from 653 tests to 673. ERT-433 is next and owes C23 a written answer.

Five things were decided rather than assumed, and the last two bind later tickets:

- **The sort order is copied, not re-derived, and exactly one test tells the two apart.** Numbering
  the rows `0, 1, 2` by their position in the catalogue-ordered list produces the **identical order**
  and different data; every ordering assertion in the suite passes against it. Only the test that
  asserts the stored integers are `1, 2, 3` fails. A snapshot copies — and copying is also what makes
  the stored order reproduce `findActiveForEmploymentType`'s `sort_order, name` exactly rather than
  an approximation of it.
- **The migration's `default 0` is a compromise with a cost, and the guard against that cost is on
  the Kotlin side.** `add column ... not null` with no default fails on a table holding rows; a
  migration that is only correct against an empty table breaks at deploy time the first time that
  assumption is wrong. But a default is precisely what lets a later writer inherit `0` in silence,
  putting every row at one value and collapsing the order back to name — the defect the column
  removes. So `EmployeeRequirement.sortOrderSnapshot` has **no** Kotlin default: a construction site
  that forgets it does not compile. That is `NotificationKind.storesBody` for the third time.
- **An employment type with no active templates is a 422 naming `employmentTypeId`, refused before
  the duplicate is examined.** A hire with an empty checklist is worse than a refused one: zero of
  zero required documents is *complete*, so the record passes straight through the §8.5 validation
  loop with nothing uploaded. `Validation` and not `Conflict`, on C1's reasoning reached from a third
  direction — `Conflict` renders no `details` entry, so a form could not name the picker to fix. Its
  own code rather than reusing `employment_type_unknown`, on E8's — HR's mistake and an admin's
  configuration gap have different remedies. **No `AppError` case was added** (C2).
- **`findActiveForEmploymentType`'s ordering is now part of the port's contract.** Both
  implementations already sorted by `sortOrder` then `name`; the port promised nothing, and
  `CreateHireUseCase` copies the order it is handed straight into the snapshot. An implementation
  returning storage order would hand a new hire a shuffled checklist and satisfy every other clause
  in the KDoc. A rule that lives only in two files that happen to agree is not a rule.
- **C27 is new, and it is filed rather than fixed.** The empty-catalogue guard's stated harm is "zero
  of zero required is complete" — and a catalogue that is entirely *optional* has exactly that
  property while passing the guard, because the guard asks `isEmpty()`. Not tightened: the defect is
  in the catalogue, the remedy is the §8.11 admin screen refusing to publish an all-optional
  assignment, and refusing at creation would block HR for something only an admin can fix.
  Unreachable today, reachable the moment Q2's real checklist replaces the seed. Pinned with a named
  test on ERT-431's C25 precedent.

**And the vacuity lesson did not get a seventh instance, which is the first time that is true.**
Twenty-four deliberate breaks were applied and **every one failed a named test on the first pass** —
six against the adapter and mapper, eighteen against the use case, the fake and the guards. Five were
caught by exactly the one test written for them: both guard orderings, the stored `PersonId`, the
derived sort order, and the retired template.

That is not luck, and it is worth saying what changed: ERT-320's coincidence was **assumed rather
than rediscovered**. Every ordering test here arranges three rows where sort order, name order, id
order and insertion order each name a *different* sequence, and the builder that cannot do that says
so in its own KDoc — `aRequirementSet` produces names, ids, insertion order and sort order that are
all identical, so an ordering test built on it proves nothing, and it now warns the next reader
instead of trapping them. The six instances before this one were each found by the mutation pass
after the fact; this is the first ticket where the arrangement was designed for it up front and the
pass merely confirmed it.

One smaller thing, recorded because it is a limit rather than an oversight: **`FakeFailure` cannot
target `saveRequirements` alone.** It fails the next call or every call, and three of this use case's
calls go to the same fake, so `failEveryCall` stops at `findActiveByEmail`. The catalogue read *can*
be targeted — it is the only call into its own fake — so the test that exists asserts the stronger
property anyway: a failing catalogue read leaves no hire at all, because everything that can refuse
happens before the first write.

---

**ERT-250 and ERT-260 closed ERT-200 again, and between them they turned two prose rules into
build failures.** The suite went from **689 tests to 845**, green on H2 in **14.4 s** and on
**PostgreSQL 17 in 48.9 s**. `test/contract/` is new and holds 13 suites — one per port, each run
twice.

**Eight divergences were closed, not six, and where each came from is the useful part.** HAR-01
listed six and every one was reproduced red before it was fixed. The **seventh** was found by the
contract suite itself: `FakeAccessTokenIssuer` computed `issuedAt.plus(ttl)` while `JwtIssuer`
truncates to whole seconds first, because `exp` is a NumericDate — so the two disagree for every
instant not already on a second boundary, which is every `Instant.now()` and therefore every real
sign-in. It was invisible because `FixedClock.DEFAULT` sits on a whole second, so no test had ever
handed either implementation an instant that could tell them apart. The **eighth** was found by the
mutation pass: `FakeHrUserRepository`'s *constructor* was a fourth door into the duplicate-address
state that `save` and `given` now refuse. **Writing a contract per port, rather than only for the
ports whose divergences were already known, is what produced the seventh; running the mutation pass
rather than trusting the green suite is what produced the eighth.**

Four things were decided rather than assumed, and three bind later tickets:

- **A concrete contract class must be named `*Test`, and that is now a guard.** Amper runs the suite
  with `--scan-class-path` and no `--include-classname`, so JUnit's own default filter applies:
  `^(Test.*|.+[.$]Test.*|.*Tests?)$`. A class named `EmployeeRepositoryContractSuite` is **silently
  not discovered** — zero tests, no error, and neither `--fail-if-no-tests` nor CI's `require_tests`
  notices because both are whole-run floors. That is this project's vacuity failure with a new door,
  and it is the specific risk of sharing tests through a base class: the abstract base is correctly
  skipped, and a misnamed subclass is skipped identically. Discovery was **proved** before anything
  was built on it — a deliberately failing test on a contract base reported exactly two failures,
  one per side.
- **`ERT_TEST_DATABASE_URL`, deliberately not `DATABASE_URL`** — and ERT-260's own Files list asked
  for the wrong one. Amper's test JVM inherits the ambient environment, and `DATABASE_URL` is what
  `DatabaseConfig.fromEnvironment()` reads, so setting it repoints all **eleven** `testApplication`
  files at the CI container. It would not fail, either: `ServerTest`'s *"no `DATABASE_URL` set -
  connects to the in-memory default"* asserts a 200 and a `select 1`, both true against PostgreSQL.
  **The test name would become a lie and the test would stop testing the dev fallback.**
- **V4 is a hard failure against a populated database, not the silent discard the review
  predicted.** `alter table employees add column created_by varchar(8) not null` cannot apply to a
  table holding rows at all, so a deployment to a database with one hire in it stops mid-chain. It
  is exempt — no database with V4 unapplied holds rows — and the exemption is **load-bearing**: one
  test asserts the rows survive V5 onward, another asserts V4 still fails, so a rewrite of V4 fails
  the build rather than leaving a stale licence for the next migration. Verified by adding a
  deliberately destructive V8 and watching the sweep catch it.
- **The collation divergence is real, larger than PERF-12 assumed, and now measured.** The review
  ranked it *"not measured"* because it *"cannot be exhibited on H2 by definition"*. With a
  PostgreSQL job it can be: `Apple, Zebra, _Underscore, apple` ascending is
  `Apple, Zebra, _Underscore, apple` in Kotlin **and** in H2, and
  `apple, Apple, _Underscore, Zebra` in PostgreSQL 17. **Every fake agrees with H2 and disagrees
  with production.** `ExposedRequirementTemplateRepository` argues its own name tiebreak cannot fire
  because `sort_order` runs 1..14 with no ties; the same argument was never made for
  `employee_requirements.name_snapshot`, where `sort_order_snapshot` carries `default(0)` so ties are
  ordinary — exactly the path ERT-432's migration header warns about. `CollationTest` **pins** it on
  the C25/C27 precedent; the remedy is a collation decision in a migration and belongs with
  **ERT-1190**.

**One thing is recorded as untestable rather than counted as covered.** Swapping `singleOrNull` for
`firstOrNull` in `findByEmail` **survives on both implementations**: with uniqueness enforced on
every write door here and by `users_email_unique` plus the lowercase CHECK there, two rows on one
address are unreachable through the port. It is an equivalent mutant, not a gap — and `singleOrNull`
stays on both, because a migration or a psql prompt can still write the pair and the right answer
then is "I cannot tell you who this is", not "here is the first one I found".

**The mutation pass is what this ticket rests on.** Fourteen deliberate breaks — seven against the
fakes, six against the adapters in the mirror direction, one against the new constructor guard —
and **twelve failed a named contract test**. The two survivors are the equivalent mutant above. Both
new `ArchitectureTest` guards were verified by making the build fail rather than by reading them.

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
| `APP_ENV` fails closed, and the deployment's instance assumption is recorded | Engineering | **ERT-1120 — Done 2026-09-17; multi-instance** |
| CI runs the build and the suite on every push | Engineering | **ERT-1160 — Done 2026-09-17** |
| A deployable container image exists and boots with no external service | Engineering | **ERT-1220 — Done 2026-09-17** |
| The GCP project, identity federation, registry, network, database and secrets exist | Engineering | **ERT-1260** — the one remaining item that needs something outside this repository |
| Third-party GitHub Actions pinned to commit SHAs | Engineering | **ERT-1165** — a tag is mutable, and these jobs hold a token that can deploy production |
| The deployment security and bottleneck audits are dispositioned | Engineering / Security | **ERT-1290 — Done 2026-09-17** |
| Sign-in is rate-limited, and something reads the audit trail | Engineering | **ERT-1170** + **ERT-1185** — SEC-19/PERF-01 is the one finding that is both a DoS and a guessing oracle |
| Security headers, HSTS and a request body limit | Engineering | **ERT-1175** — gate before ERT-630 puts a phone browser on the portal |
| Dependencies and images are scanned, and an SBOM exists | Engineering | **ERT-1180** |
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
| Operability | `APP_ENV` fails closed, startup summary, deployment configuration, CI | **ERT-1120 — Done**, **ERT-1160 — Done** |
| Environments and deployment | Container image, local compose, CI/CD to two Cloud Run services, the runbook, the audits | [**ERT-1200**](backlog/ERT-1200-deployment.md) — ERT-1260 is the only ticket left, and it needs a GCP project |
| Audit follow-up | Sign-in rate limiting, alerting on the audit trail, security headers, supply-chain scanning, SHA-pinned actions | **ERT-1170**, **ERT-1185**, **ERT-1175**, **ERT-1180**, **ERT-1165** — from [the 2026-09-17 audits](2026-09-17-security-audit.md) |
| Documentation hygiene | The root README is still stock Ktor generator boilerplate and advertises deleted features; the unused R2DBC dependencies | **ERT-1140** |

---

## Contradictions and gaps to escalate

Found while writing the backlog, and extended by a full cross-document sweep on 2026-09-16. **Every
row is kept, resolved or not** — the history is the point, and a deleted row is one a future reader
re-files. E1–E8 came from the first pass; C1–C22 from the sweep, of which the ones still worth
tracking are listed; C23–C28 were opened by the tickets that hit them; **C29–C33 by the 2026-09-18
ERT-100/ERT-200 review**.

| # | Issue | Resolution |
|---|---|---|
| E1 | §7.2 asked for thumbnails on the review screen; §8.6 forbids the portal returning a preview | **Closed.** §8.6 wins; PRD v0.5 amended §7.2. The API contract keeps a note so a reader of an older §7.2 does not re-file it |
| E2 | The upload MIME allowlist was never stated | **Closed as Q21.** JPEG, PNG, HEIC, HEIF, PDF — sniffed, configurable. PRD §12, ERT-732 |
| E3 | A duplicate-email override silently froze retention, because `retentionFrozen` derived from `anomalyFlags.isNotEmpty()` | **Closed in prose, still unlanded in code.** Freezes on the four evidentiary flags only; `SHARED_EMAIL` and `SEPARATION_OF_DUTIES` do not. PRD §7.1, ERT-734, invariant 8. **PRD owner to ratify.** **ERT-431 gave `SHARED_EMAIL` its first producer (2026-09-17)**, so ERT-734 now has a real record to exercise — and a hire created past a duplicate has its retention frozen today, contrary to the contract |
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
| C23 | **`Notifier.sendInvitation` requires an `AccessPin`, and since 2026-09-16 the invitation must carry none.** ERT-400's own epic text asserts both in consecutive paragraphs: "it carries no PIN", and "only `sendInvitation` accepts an `AccessPin` … do not add an `AccessPin` parameter to any other method". The parameter is what makes "only the invitation may carry a credential" a compile-time property, so removing it weakens a real guard — but ERT-433 cannot call the method without minting a PIN the new model says must not exist at creation *(opened 2026-09-16 by ERT-440)* | **Closed.** Split the port so `Notifier.sendInvitation` takes no PIN and a separate `sendRecoveryPin` does. This keeps the compile-time guard for the recovery PIN on its own dedicated method, which is also the exact method ERT-650 will need anyway. |

| C24 | **`ReasonRequired` renders `field = "reason"`, but two places say `duplicateReason`.** `AppErrorMapper` hardcodes `ApiErrorDetail(code, field = "reason")` and `ErrorMappingTest` pins it; the API contract's `POST /api/employees` row and ERT-450's acceptance criterion both require the `details` entry to name `duplicateReason`. `ApiResponse`'s KDoc adds a third spelling, `duplicate_email_requires_reason`, which is not a code anything emits *(opened 2026-09-17 by ERT-431)* | **Open, owned by ERT-450.** ERT-431 emits the error and cannot see the wire; ERT-450 is the ticket that renders it and will meet this as a failing test. Fixing it is a one-line mapper change plus its pinned test — but which spelling wins is a contract decision, not a mapper decision |
| C25 | **A typed duplicate reason with no spaces is a user-reachable 500.** ERT-330's audit-metadata guard refuses a value that is 32+ characters of mixed-case base64url, on the stated premise that "a reason is prose" — and prose has spaces, so one space is what saves it. A reason like `ReplacingRecord2026ForJoseDelaCruz` satisfies every clause, and the `require` unwinds out through `ExposedAuditLog.record`. Before ERT-431 no free-text HR value reached that map, so the trap was unreachable *(opened 2026-09-17 by ERT-431)* | **Open, owned by ERT-450.** Deliberately **not** fixed in ERT-431: the guard is ERT-330's security control and weakening a tripwire is a specification change — the C2 failure this very ticket documents. `AuditEntryMapperTest` now **pins today's behaviour** with a named test so the trap is visible rather than discovered in production, and asserts the prose form is still accepted so the pin cannot be mistaken for endorsement |
| C26 | **Nothing enforces the column widths, so over-long input is a 500 rather than a 422.** `first_name` and `last_name` are `varchar(128)`, `position` `varchar(256)`, `email` `varchar(320)`, and `EmailAddress`'s regex is unbounded — so a 400-character address passes validation and dies at the insert *(opened 2026-09-17 by ERT-431)* | **Open, owned by ERT-450.** Unreachable until a route accepts a body. Not fixed in ERT-431 because a length rule belongs to every string-taking use case, and inventing it in one file leaves five later ones to re-invent it; the email cap belongs on `EmailAddress` itself |

| C27 | **A catalogue that is entirely optional produces the record the empty-catalogue guard exists to prevent.** ERT-432 refuses an employment type with no active templates, because a hire at zero of zero required documents is *complete* and passes straight through the §8.5 validation loop with nothing uploaded. The guard asks `isEmpty()` — and an employment type whose templates are all `isRequired = false` has exactly that property while passing it *(opened 2026-09-17 by ERT-432's review step)* | **Open, owned by the Phase 2 admin-catalogue epic (§8.10, §8.11).** Deliberately not tightened at hire creation: the defect is in the **catalogue**, not in the hire, and the remedy is the admin screen refusing to publish an all-optional assignment — refusing at creation would block HR for something only an admin can fix, one hire at a time, late. Unreachable today: the V2 seed cross-joins all fourteen templates and ten are required. It becomes reachable the moment **Q2**'s real checklist replaces the seed, or the Phase 2 screen ships. `CreateHireUseCaseTest` **pins today's behaviour** with a named test on ERT-431's C25 precedent, so the trap is visible rather than discovered in production |

| C28 | **The API contract required every route to declare its *response* schema and said nothing about its *request* schema — and the generator infers neither.** All five POST routes carried `describe { }` blocks with `responses { }` and no `requestBody { }`, so Swagger UI rendered no body editor and its "Try it out" sent `POST /api/auth/login` with no body and no `Content-Type`. ContentNegotiation then skipped every converter, `receive` threw a `ContentTransformationException` — an `IOException`, not a `BadRequestException` — and `StatusPages` answered **500**. The suite was green throughout, because every route test sets `contentType(...)` and so never sent a request without one *(opened 2026-09-17 by manual Swagger testing)* | **Closed as ERT-146.** Both halves are one defect and neither alone fixes it. 415 rather than 422, matching Ktor's own `defaultExceptionStatusCode`, so a client can tell "fix your header" from "fix your body". The handler registers the parent `ContentTransformationException`, because `UnsupportedMediaTypeException` is the identical mistake against the `receiveMultipart()` handler ERT-710 writes. The five `describe { }` blocks are not the deliverable: an `ArchitectureTest` guard now fails the build on a handler that reads a body without publishing its schema, and was checked against the pre-fix tree rather than only against a synthetic offender |
| C29 | **C15 settled that `findActiveForLink` grows a `now` parameter; the fake still tells the reader it is open.** ERT-620 carries the criterion, correctly and unchecked. `FakePortalSessionRepository`'s KDoc says *"ERT-620 should decide whether the port grows a `now`"* — and the fake is the file a use-case author reads *(opened 2026-09-18 by the ERT-100/ERT-200 review)* | **Open, owned by ERT-620.** Deliberately **not** the E3 shape: this is documentation that predates a decision, not a decision nobody landed. One KDoc edit closes it, and ERT-620's criteria now name it |
| C30 | **Port count, for the second time.** ERT-200, ERT-210 and the board say **10** domain ports; the *Where the code is* table below says "13 ports" and "a test harness of **10** in-memory fakes" in one sentence; there are **13** of each, and `FakesTest`'s "every fake is constructible" acceptance test constructs **11**. **C9 closed this exact drift once**, as a documentation fix *(opened 2026-09-18)* | **Corrected here and in the four documents; guarded by ERT-250.** A count in prose has nothing holding it, which is why one correction did not hold. The deliverable is the `ArchitectureTest` port-coverage guard, not a third recount |
| C31 | ERT-120's title and its board row say "the **12** tables"; `MigrationTest` asserts `allTables.size shouldBe 14` *(opened 2026-09-18)* | **Closed.** Corrected in ERT-120 and on the board with a dated note. The assertion tracked reality throughout; only the prose did not |
| C32 | `MigrationTest`'s guard is named `identifier generation - the guard above - is pointed at the **ten** keyed tables` and asserts **12** *(opened 2026-09-18)* | **Closed by rename.** The assertion is right; the name is what a reader skimming test output trusts, and a test whose name disagrees with its body is worse than one with no name at all |
| C43 | **Two test-file KDocs describe an arrangement their own bodies contradict.** `RequirementTemplateRoutesTest.kt:213` carries two stacked KDoc blocks, the first saying *"Mounts the handler with no security plugin"* while the body calls `configureSecurity(testJwtConfig())` — ERT-190 made it false and it was left above the second rather than replaced. And the local `FakeReferenceData` KDoc says the shared fake "earns its keep" at ERT-430; it arrived with ERT-431 *(opened 2026-09-18 by the ERT-300 review)* | **Open, owned by the tickets that own those files.** The second is closed by **HAR-20**'s fix on ERT-250. A stale comment on a test harness is the HAR category's own subject: it tells the next reader the suite proves something it does not |
| C42 | **Architecture §3 and §4 are stale in four places.** §3 annotates `domain/usecase/` as *"(empty — see §5)"* (there are seven) and `HealthRoutes.kt` as *"the only endpoint today"* (thirteen handlers); §4 asserts *"only `sendInvitation` accepts an `AccessPin`"*, which **C23** closed, and *"the seven notification kinds"*, which **C37** corrected to eight in three code comments — the architecture document was not in that sweep *(opened 2026-09-18 by the ERT-300 review)* | **Closed.** Four corrections with a dated note. **And it is how SEC-41 was found:** establishing which method *does* accept an `AccessPin` showed that it takes an `EmailAddress` beside it and renders the PIN into a body. A stale sentence about a control was standing in front of a missing one |
| C41 | **The API contract miscites §8.10 for `GET /api/requirement-templates`** (§8.11 is templates; §8.10 is the link policy), **and says the reference routes are absent from Appendix B when Appendix B lists both** — contradicting the same document's *"Routes outside Appendix B"* opener, with the claim repeated in `ReferenceRoutes.kt`'s KDoc *(opened 2026-09-18 by the ERT-300 review)* | **Closed.** Both corrected in `api-contract.md` and the route KDoc. The second is the sharper one: it is a claim about a gap that was closed, so a reader chasing it finds the endpoint listed and cannot tell which document is stale |
| C40 | **`DataModule`'s KDoc describes two bindings; there are twelve.** Written when ERT-310 and ERT-330 landed together, not revisited as ERT-320, ERT-350, ERT-410, ERT-420 and ERT-440 each added one *(opened 2026-09-18 by the ERT-300 review)* | **Closed.** The load-bearing sentence is *"Both adapters share one `DatabaseFactory`, because it is a `single`"* — the argument that makes `ExposedAppSettingsRepository`'s single-transaction audit write correct. A reader taking "both" at face value may not realise it is required of all seven |
| C39 | **ERT-330 says `AuditEntry` defines 17 actions; it defines 25.** ERT-190 added eight, and `ExposedAuditLogTest` carries `covers all twenty-five actions` *(opened 2026-09-18 by the ERT-300 review)* | **Closed as documentation**, with the parenthetical the board already uses for *"the 12 tables (14 today)"* and *"the domain ports (10 when written; 13 today)"* |
| C38 | **ERT-350's second criterion still reads unchecked and carried-forward, and ERT-431 implemented it.** The note says `CreateHireUseCase` "does not exist" (seven use cases do), that no shared `FakeReferenceDataRepository` exists (it does, with a contract suite), and the criterion's own text still says `NotFound` where **E8** settled 422 *(opened 2026-09-18 by the ERT-300 review)* | **Closed by ticking it**, citing `CreateHireUseCase.kt:195-212` and ERT-431, with the carry-forward note discharged rather than deleted. It is **C34's mirror image** — a false positive where C34 was a false negative — from the same mechanism the board's warning describes: *"nothing checks it"* |
| C37 | **Three comments say the invitation is the only body-less notification, and that two of *seven* `Notifier` methods take no address.** `NotificationKind` has **eight** entries and **two** with `storesBody = false` — `RECOVERY_PIN` joined `INVITATION` when C23 split the port. The code is right in all three places and the prose is not *(opened 2026-09-18 by the second review)* | **Closed.** Corrected with a dated note in each. It is the C33 shape one epic later, and it matters more than it looks: `storesBody` exists so an eighth kind **must choose**, and the comment beside it told a reader the rule was "invitation only" |
| C36 | **`libs.versions.toml:12` declares `kotlin = "2.4.0"` and nothing reads it.** `module.yaml`'s `settings.kotlin` names no version, so the entry binds nothing; the compiler actually in use is **2.4.10**, from the toolchain `./kotlin` pins by sha256 *(opened 2026-09-18 by the second review)* | **Open, owned by ERT-1140.** Same family as the unused R2DBC dependencies that ticket already holds: a value that lives only in prose binds nothing, and a reader would edit that line expecting the compiler to move |
| C35 | **The first ERT-100/ERT-200 review dispositioned two of its own findings into no ticket.** TASK-37 routed SEC-35, SEC-33 and HAR-07 to tickets by number and gave **SEC-34** and **HAR-06** a sentence each; `grep -rn "SEC-34\|HAR-06" docs/backlog/` returned nothing *(opened 2026-09-18 by the second review)* | **Closed by filing both.** SEC-34 into **ERT-1175**, HAR-06 into **ERT-1140**, each with a dated note. Kept rather than quietly fixed because of what it is: the E5/E6/C22 lesson — *a gap without a number is invisible* — which that same document names, applied to its own output eight hours later |
| C34 | **ERT-433's status disagreed with itself in the two places the board's own rule names.** The board row read `Not started`, the ticket block read **`Completed`** — which is not one of the four values the legend defines — and the work had been merged to `main`. The roadmap's pointer still said *"Take ERT-433 unless that exists"* beneath a line naming ERT-434 as next *(opened 2026-09-18 while taking ERT-250)* | **Closed.** All three corrected. Worth keeping rather than silently fixing: the "update it in two places" rule is the only thing holding these in step, nothing checks it, and this is the second time a value carried in prose has drifted — C9 and C30 were the first two, and ERT-250's port-coverage guard is what finally stopped that one |
| C33 | `ExposedRequirementTemplateRepository.kt:44-49` — the KDoc reading *"Does **not** filter by `is_active` — only `findActiveForEmploymentType` does"* sits above `findActiveForEmploymentType`, the method it contrasts *with*, rather than above `findById`, the method it describes *(opened 2026-09-18)* | **Closed.** Moved |

**Still open, and deliberately so:** the PRD has **no owner**. E3's ratification, and any future
contradiction between two P0 sections, route to a role nobody holds. Escalated 2026-09-16, due
2026-09-30, HR sponsor.
