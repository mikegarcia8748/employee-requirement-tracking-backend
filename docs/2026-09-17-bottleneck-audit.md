# Runtime Bottleneck Audit — Employee Requirements Tracker — 2026-09-17

## Scope

Audited: branch `feature/ert-1200-containerisation-and-deployment`, the request path end to end —
`src/main.kt`, `src/plugin/`, `src/route/`, `src/domain/usecase/`, `src/data/repository/`,
`src/data/db/DatabaseFactory.kt` — against the deployment ERT-1200 introduces.

**Target deployment:** Cloud Run, `--cpu=1`, `--memory=1Gi`, `--concurrency=80`,
`--max-instances=4`, `--min-instances=1`, Cloud SQL PostgreSQL 17 over Direct VPC egress,
`DATABASE_MAX_POOL_SIZE=5`.

**Not covered:** SQL plans, index design and vacuum behaviour — no production data exists to plan
against. The portal half of the system, which is not implemented.

## Method

**Measured, not guessed, where measurement was possible.** The executable jar was run locally
(Apple Silicon, `-XX:+UseSerialGC -XX:MaxRAMPercentage=60`, `APP_ENV=dev`, `DATABASE_MAX_POOL_SIZE=5`)
against in-memory H2, and driven with `ab` at concurrency 1, 10, 20 and 80.

**Read the caveat before trusting any number below.** In-memory H2 answers in microseconds, so these
runs **flatter every database-bound path** and cannot reveal pool contention at all. What they do
measure honestly is CPU-bound work, because that is hardware-bound rather than storage-bound — and on
a Cloud Run vCPU, which is slower than this laptop's core, the CPU-bound numbers get **worse**, not
better. So: PERF-01 is measured and transfers to production with a safety margin in the wrong
direction. PERF-05 is explicitly a hypothesis that this method *cannot* test, and it is ranked
accordingly.

### Baseline

| Endpoint | Concurrency | rps | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|
| `GET /health` | 1 | 1156 | 1 ms | 2 ms | 5 ms |
| `GET /health` | 80 | 1191 | 58 ms | 142 ms | 228 ms |
| `GET /api/requirement-templates` (2576 B) | 1 | 1349 | 0 ms | 2 ms | 6 ms |
| `GET /api/requirement-templates` | 80 | 1371 | 56 ms | 110 ms | 139 ms |
| **`POST /api/auth/login` — wrong password** | **1** | **3.95** | **240 ms** | 485 ms | 485 ms |
| `POST /api/auth/login` — correct password | 1 | 4.17 | 238 ms | — | 250 ms |
| `POST /api/auth/login` — wrong password | 20 | 37.3 | 363 ms | 812 ms | 1055 ms |

Cold start, process launch to first `200` from `/health`: **3974 ms** (the application reports
`Application started in 3.223 seconds`).

Payload sizes, uncompressed: `/api/departments` 90 B, `/api/employment-types` 217 B,
`/api/requirement-templates` 2576 B, `/api/users` 238 B. No `Content-Encoding` is returned even when
the client sends `Accept-Encoding: gzip`; no `ETag` and no `Cache-Control` on any of them.

## Summary

**The dominant bottleneck is `POST /api/auth/login`, which is measured at 3.95 requests per second
and is roughly 340× slower than every other endpoint in the system.**

Ranked by expected win:

| # | Finding | Expected win | Basis |
|---|---|---|---|
| PERF-01 | Sign-in costs ~250 ms of CPU per attempt, including failures | **High** | measured |
| PERF-02 | Nothing measures the running system; `/metrics` is unreachable by design | **High** (enabling) | static |
| PERF-03 | No response compression | Medium | measured |
| PERF-04 | No `ETag`/`Cache-Control` on effectively-immutable reference data | Medium | measured |
| PERF-05 | `Dispatchers.IO` (64) vs Hikari pool (5) under Cloud Run concurrency 80 | Medium | **hypothesis** |
| PERF-06 | No pagination anywhere; `AuditLog.findFor` grows without bound | Medium (latent) | static |
| PERF-07 | `saveRequirements` issues two statements per row, serially | Low–Medium | static |
| PERF-08 | Duplicate-email check is a documented table scan | Low (latent) | static |
| PERF-09 | ~4 s cold start, and the first requests after one are slower still | Low | measured |

**What is already right, and should not be traded away in any of the tasks below:** there is exactly
one transaction entry point, it is `suspend`, and it hops to `Dispatchers.IO` via `withContext`
(`DatabaseFactory.kt:77`). Grepping `src/` for `transaction {` finds only `factory.transaction {`
call sites; there is no `runBlocking` in the request path, no blocking file I/O, and no blocking HTTP
client. `Dispatchers` is banned from `src/domain/` and `ArchitectureTest` fails the build on it. **The
single most common cause of concurrency collapse in a Ktor/Exposed service is absent here by
construction**, which is why this report starts at step 2 of the usual list rather than step 1.

---

## Findings

### PERF-01 — Sign-in costs ~250 ms of CPU per attempt, on every path including failure  [High expected win]

**Where:** `src/domain/usecase/AuthenticateHrUserUseCase.kt:84`; `src/data/crypto/BcryptHasher.kt`

```kotlin
// Runs on every path, including both branches that have already lost, so the elapsed time
// does not separate them. `verified` is read only when there is a user to read it for.
val verified = hasher.verify(command.password, user?.passwordHash ?: decoyHash)
```

**Mechanism.** Invariant 10 requires that an unknown email, a wrong password, a malformed address and
a deactivated account are indistinguishable **in elapsed time as well as in body**. The design buys
that with a bcrypt cost-12 verification against a decoy hash on every losing path — which is the
right call, and it means every request to this endpoint performs one bcrypt. There is exactly one
`verify` call, confirmed by reading the use case; this is not double hashing.

**Evidence — measured.** 3.95 rps at concurrency 1, p50 240 ms. Every other endpoint measured between
1156 and 1444 rps with a p50 of 0–1 ms, so the cost is not framework overhead. Subtracting a
sub-millisecond DB path leaves **roughly 250 ms attributable to the single bcrypt verification**.

Two things follow that are worth stating plainly:

- **`BcryptHasher.DEFAULT_COST`'s own comment says "~100 ms per hash on current hardware".** Measured
  here it is about 2.5× that. Whatever the original basis, the documented assumption no longer
  matches the code's behaviour on this hardware, and every capacity estimate derived from it is
  wrong by the same factor.
- **A Cloud Run vCPU is slower than this laptop's core.** Because this is a CPU-bound path, the
  production number will be *worse* than 250 ms, not better. This is the one measurement in the
  report whose error bar points in the safe direction.

**Impact at the configured deployment.** `--concurrency=80` on `--cpu=1` means Cloud Run will admit
80 simultaneous requests to a single vCPU. Eighty concurrent sign-ins is 80 × 250 ms ≈ **20 seconds of
queued CPU work on one core**, against a `--timeout=120`. Measured at concurrency 20 the p99 is
already 1055 ms and throughput is 37 rps; the curve's knee is well below the configured concurrency.
With `--max-instances=4` the whole service tops out somewhere near 150 sign-ins per second under
ideal conditions, and unauthenticated traffic can consume all of it.

**This is the same defect as SEC-19 in the security audit**, reached from the other side. It is a
denial-of-service vector *and* an unbounded guessing oracle, and it is the only finding in either
report that is both.

**Expected win.** Not from making bcrypt faster — lowering the cost weakens the credential, and
raising it makes the DoS cheaper. The win comes from **bounding how many attempts reach bcrypt at
all**. A DB-backed attempt counter keyed on the address, refusing before the verify, takes a
sustained attack from 36 rps of full-cost work to near zero, and costs a legitimate user nothing.

**Risk.** The obvious wrong fix is to skip the decoy verify when the user is unknown. That closes the
DoS by reopening the enumeration oracle invariant 10 exists to prevent, and it would pass a load test
while silently breaking a security property. Any change here must keep
`AuthenticateHrUserUseCaseTest`'s timing-uniformity tests green, and the refusal itself must be
indistinguishable from a wrong password.

---

### PERF-02 — Nothing measures the running system  [High expected win — enabling]

**Where:** `src/plugin/Monitoring.kt:63` → `src/route/MetricsRoutes.kt:38`

**Mechanism.** Micrometer is wired and a `PrometheusMeterRegistry` is populated, but `/metrics` is
served only in dev; outside dev it sits behind `authenticate(HR_AUTH)`, so **no scraper can reach
it** — deliberately, and the security audit agrees it should stay that way. The consequence is that
the registry is populated for nobody. Cloud Run's built-in metrics cover request count, latency
percentiles, instance count, CPU and memory, which is genuinely most of what a dashboard needs — but
they cannot see **inside** the process, and the two numbers that decide whether PERF-05 is real are
exactly there: Hikari's active/idle/pending gauges and `Dispatchers.IO` saturation.

**Evidence.** Static. There is also no load test of any kind in the repository, and no recorded
baseline before this document.

**Expected win.** None directly — this finding produces no latency improvement. It is ranked High
because **PERF-05 cannot be confirmed or dismissed without it**, and because a tuning task with no
before-number is unverifiable and therefore never finishes. Do this before PERF-05, not after.

**Risk.** Exposing `/metrics` to a scraper would undo a deliberate security decision. The fix must not
be "open the endpoint": push Hikari's gauges into Cloud Monitoring as custom metrics, or emit them as
structured log lines (ERT-1250 made logs JSON, so a log-based metric is configuration) rather than
adding a scrape target.

---

### PERF-03 — No response compression  [Medium expected win]

**Where:** the absence of `install(Compression)` anywhere in `src/plugin/`

**Mechanism.** `ContentNegotiation` is installed; `Compression` is not. Cloud Run does not compress
on the application's behalf.

**Evidence — measured.** A request with `Accept-Encoding: gzip` returns
`Content-Length: 2576` and **no `Content-Encoding` header**. JSON of this shape typically compresses
by 70–80 %.

**Expected win.** Small in absolute terms today — 2.5 KB is not a problem. It matters for two
specific reasons that are already on the board. The **portal is a phone over mobile data** (PRD §4),
where round-trip and bytes dominate and CPU does not. And **ERT-510's HR list with progress** is the
first endpoint that returns a row per hire with a nested requirement summary; that is where an
uncompressed payload stops being theoretical. Installing it now costs one line and one decision
(exclude already-compressed content types); retrofitting it later means re-testing every endpoint.

**Risk.** Double compression if a proxy is later added in front. Cloud Run does not compress, so
there is nothing to double today — but it is worth a line in the runbook so that a future load
balancer does not silently produce it.

---

### PERF-04 — Effectively-immutable reference data is re-transferred on every request  [Medium expected win]

**Where:** `src/route/hr/ReferenceRoutes.kt:34,57`; `src/route/hr/RequirementTemplateRoutes.kt:36`

**Mechanism.** `/api/departments`, `/api/employment-types` and `/api/requirement-templates` are
seeded reference data. PRD §8.11 makes the catalogue admin-editable, but edits are rare by
construction, and reference data is *"seeded, not managed"* in Phase 1 — `ReferenceRoutes.kt` says so
itself. Every response carries no `ETag`, no `Last-Modified` and no `Cache-Control`.

**Evidence — measured.** Headers on `/api/requirement-templates` are `HTTP/1.1 200 OK` and
`Content-Length: 2576`. Nothing else.

**Expected win.** The add-hire form needs all three, so opening it is three round trips and ~2.9 KB
every time, forever. With an `ETag` the second and subsequent loads become three 304s carrying no
body. The saving is per form-open rather than per second, which is why this is Medium rather than
High — but it is also the cheapest item in this report.

**Risk.** An `ETag` computed from the payload is always correct and costs a hash of a small body. A
hand-maintained `Cache-Control: max-age` is the version that goes wrong: an admin edits the catalogue
under §8.11 and HR keeps seeing the old one until the TTL expires. Prefer the conditional request
over the TTL.

---

### PERF-05 — `Dispatchers.IO` is 64 threads against a pool of 5  [Medium expected win — hypothesis, not measured]

**Where:** `src/data/db/DatabaseFactory.kt:77`

```kotlin
suspend fun <T> transaction(block: ...): T =
    withContext(Dispatchers.IO) { suspendTransaction(db = database) { block() } }
```

**Mechanism.** `Dispatchers.IO` defaults to 64 threads. The configured pool is 5. Under load, up to
64 coroutines can be inside `suspendTransaction` contending for 5 connections, and the excess blocks
on Hikari's `connectionTimeout`. That timeout is now 10 s rather than Hikari's default 30 s
(ERT-1240), which bounds the damage but does not remove the contention: at Cloud Run's
`--concurrency=80` a single instance can admit more concurrent requests than it has threads, which in
turn outnumber its connections by 13×.

**Evidence — explicitly a hypothesis.** The local run showed **no degradation at concurrency 80**:
1371 rps, p99 139 ms, zero failures. That result is not evidence of health — **in-memory H2 answers in
microseconds, so a connection is never held long enough to contend**. This method cannot test this
finding, which is the honest statement. Against Cloud SQL over Direct VPC egress a round trip is
1–3 ms rather than microseconds, and the arithmetic changes by three orders of magnitude.

**Expected win.** Unknown, and deliberately not guessed. What can be said without measuring: the
three numbers — Cloud Run concurrency (80), `Dispatchers.IO` parallelism (64) and pool size (5) —
were each chosen independently and none was chosen against the others. That is worth resolving
whatever the measurement says.

**Risk.** Tuning any of the three in isolation moves the bottleneck rather than removing it. Raising
the pool without raising Cloud SQL's connection ceiling exhausts the database instead — the budget in
`docs/deployment.md` is `Σ(max_instances × pool)` and it is already accounted for. Lowering Cloud Run
concurrency multiplies instances, which multiplies connections. Measure first (PERF-02), then change
one thing.

---

### PERF-06 — No pagination on any list endpoint, and one that grows without bound  [Medium expected win — latent]

**Where:** `src/data/repository/ExposedAuditLog.kt:36` — `findFor(entityId)`;
`src/route/hr/UserRoutes.kt:80`; `ExposedReferenceDataRepository.kt:33,44`

**Mechanism.** Every list route returns a whole table. `ApiMeta(total = all.size)` counts what was
already materialised, so it is a count rather than a cursor.

**Evidence — measured.** Payloads today are 90 B to 2576 B. This is genuinely not a problem *now*,
and saying otherwise would be padding.

**The exception is real, though.** `AuditLog.findFor(entityId)` returns **every audit row for an
entity, unbounded**, and the audit log is append-only by design — it only grows. Every reopened
record, every rejection, every resend adds rows that are never removed. Cardinality for HR users is
bounded by headcount; cardinality for an audit trail is bounded by time.

**Expected win.** Nothing today. The reason to act is that `findFor` has no natural ceiling and no
caller yet — so adding a limit now costs one parameter, and adding it after ERT-520 renders a hire's
history costs a contract change.

**Risk.** Paginating the reference endpoints would complicate the add-hire form for no measurable
benefit. Scope this to `findFor` and leave the rest documented as a deliberate decision.

---

### PERF-07 — `saveRequirements` issues two statements per row, serially  [Low–Medium expected win]

**Where:** `src/data/repository/ExposedEmployeeRepository.kt:134-154`

```kotlin
requirements.forEach { requirement ->
    val exists = EmployeeRequirements.selectAll()
        .where { EmployeeRequirements.id eq requirement.id.value }.empty().not()
    if (exists) EmployeeRequirements.update({ … }) { … } else EmployeeRequirements.insert { … }
}
```

**Mechanism.** A `SELECT` then an `INSERT` or `UPDATE` per requirement, sequentially, inside one
transaction. The seed catalogue has 14 templates (`/api/requirement-templates` measures 2576 bytes
over 14 entries), so creating one hire is roughly **28 round trips**. The same read-then-write shape
appears in `ExposedHrUserRepository.save` and `ExposedUploadLinkRepository.save`, where it is one row
rather than fourteen.

**Evidence.** Static. ERT-430 has not shipped, so no hire-creation path exercises this yet.

**Expected win.** Against H2, nothing measurable. Against Cloud SQL at 1–3 ms per round trip, 28 trips
is 30–80 ms added to hire creation — noticeable on a form submit and multiplied by §8.2's bulk import,
which is the path that turns this from tidiness into a real number. `batchUpsert` collapses it to one
statement.

**Risk.** Low. It is one adapter method with repository tests already covering its behaviour, and the
transaction boundary does not change.

---

### PERF-08 — The duplicate-email check cannot use its index  [Low expected win — latent]

**Where:** `src/data/repository/ExposedEmployeeRepository.kt:78-95`

**Mechanism.** The check is `Employees.email.lowerCase() eq email.value`. The adapter's own KDoc says
it: *"the plain `employees_email` index cannot serve a `lower()` predicate, so this is a scan."*

**Evidence.** Static, and already documented in the source.

**Expected win.** None at current volume. A functional index — `CREATE INDEX ON employees (lower(email))`
— is one migration and makes the cost independent of table size. Worth doing before the table is
large, because it is a pure addition; worth doing *with* a migration that is already happening, since
it is additive and therefore safe under the backward-compatibility rule in architecture §14.

**Risk.** None beyond the index write cost, which is negligible at this cardinality.

---

### PERF-09 — Cold start is ~4 s, and the requests right after one are slower  [Low expected win]

**Where:** `src/main.kt`; `src/plugin/Database.kt`

**Evidence — measured.** 3974 ms from process launch to the first `200`, of which the application
attributes 3.223 s to its own startup. That is on a warm filesystem with no image pull and with H2
rather than a network database; Cloud Run adds an image pull (~200 MB, 1–3 s cold) and a slower vCPU,
and Flyway must reach Cloud SQL over VPC.

**Mechanism.** JVM start and classloading for Ktor, Netty, Exposed, Hikari, Koin, Flyway and
Micrometer, then pool initialisation and a Flyway schema-history read. ERT-1240 already removed
swagger-codegen from this path outside dev, which was the most suspicious item in it.

**Expected win.** Small, and mostly already taken. `--min-instances=1` means production users
essentially never meet a cold start, and `--cpu-boost` roughly halves the JVM portion when one does
happen. The remaining exposure is scale-out under load and UAT, which runs at `min-instances=0`.

**Risk.** `-XX:TieredStopAtLevel=1` is the tempting next step and is the wrong one here: it is a real
cold-start win and a real steady-state throughput loss, and with `min-instances=1` that trades
something needed for something not had. AppCDS is the correct next step if this ever matters, and it
should not be attempted without a measured baseline.

---

## Checked and healthy

- **Blocking on the wrong dispatcher — the usual number-one cause — is absent by construction.** One
  `suspend` transaction entry point, a correct `withContext(Dispatchers.IO)` hop, no `transaction {}`
  on a request thread, no `runBlocking` in the request path, no blocking file I/O, no blocking HTTP
  client, no `Thread.sleep`. `ArchitectureTest` fails the build on `Dispatchers` in the domain.
- **Serialized work that should be concurrent.** Checked and found nothing worth changing. There is
  no `async`/`awaitAll` anywhere, but there is also no pair of independent I/O calls to parallelise —
  `AuthenticateHrUserUseCase` is strictly sequential because each step depends on the previous one.
- **Per-request construction of expensive objects.** None. The `Json` instance is configured once in
  `configureSerialization`; the meter registry is a single; `HmacTokenDigest` constructs a fresh
  `Mac` per call, which is microseconds and documented as a deliberate thread-safety choice.
- **Tracing overhead.** `Slf4jUseCaseTracer.trace` short-circuits on `!log.isDebugEnabled` before
  reading the clock, so tracing is near-free when off — and it is off outside dev.
- **Outbound timeouts and retries.** No outbound HTTP calls exist yet. ERT-1010's outbox already
  specifies capped attempts with backoff.
- **Heap and container sizing.** `MaxRAMPercentage=60` on a 1 GiB container leaves headroom for
  metaspace, code cache, thread stacks and Netty's direct buffers, and `MaxDirectMemorySize` is set
  explicitly — the omission of which is the usual cause of a container OOM kill with a healthy-looking
  heap.

---

## Task plan

Ordered by expected win. TASK-22 is first among equals: it produces no speedup itself, but TASK-25 is
not verifiable without it.

### TASK-21 — Bound how many sign-in attempts reach bcrypt
- **Fixes:** PERF-01 (and SEC-19)
- **Branch:** `feature/ert-1170-sign-in-rate-limit`
- **Ticket:** ERT-1170
- **Change:** a DB-backed attempt counter keyed on the address, refusing **before** the verification —
  following `countRecentFailures`, not an in-memory limiter, because the deployment is multi-instance
  (ERT-1120). Pair with an edge limit (Cloud Armor) for volumetric abuse.
- **Measure before:** 3.95 rps at concurrency 1, p50 240 ms; 37.3 rps at concurrency 20, p99 1055 ms.
- **Done when:** a sustained attack against one address settles below 5 rps of *full-cost* work while
  a legitimate first attempt still completes in one bcrypt, and
  `AuthenticateHrUserUseCaseTest`'s timing-uniformity tests are unchanged and green.
- **Watch for:** the refusal must be indistinguishable from a wrong password, in body **and** in
  elapsed time. Do not remove the decoy verify — that closes the DoS by reopening the enumeration
  oracle. Re-measure the bcrypt cost while here: the code says ~100 ms and it measured ~250 ms.

### TASK-22 — Make the pool and the dispatcher observable
- **Fixes:** PERF-02; prerequisite for TASK-25
- **Branch:** `perf/pool-observability`
- **Change:** bind Hikari's metrics to the existing Micrometer registry and export active/idle/pending
  plus `Dispatchers.IO` saturation to Cloud Monitoring as custom metrics — **or** as structured log
  lines, since ERT-1250 made logs JSON and a log-based metric is then configuration. Do **not** open
  `/metrics` to a scraper; that undoes a deliberate security decision.
- **Measure before:** nothing is measured today; that is the finding.
- **Done when:** pending-connection count and IO-thread saturation are visible for a UAT load run.
- **Watch for:** metric cardinality. Per-route labels on a Cloud Monitoring custom metric get
  expensive quickly.

### TASK-23 — Install compression and conditional requests
- **Fixes:** PERF-03, PERF-04
- **Branch:** `perf/compression-and-etags`
- **Change:** `install(Compression)` excluding already-compressed content types, and an `ETag` on the
  three reference endpoints computed from the payload. Prefer conditional requests to a
  `Cache-Control` TTL — §8.11 makes the catalogue editable, and a TTL means an admin's edit is
  invisible until it expires.
- **Measure before:** `/api/requirement-templates` returns 2576 B with no `Content-Encoding`, no
  `ETag`, no `Cache-Control`; the add-hire form costs three round trips and ~2.9 KB every open.
- **Done when:** the same request returns `Content-Encoding: gzip`, and a second request carrying
  `If-None-Match` returns 304 with no body.
- **Watch for:** double compression if a load balancer is ever put in front. Note it in the runbook.

### TASK-24 — Bound `AuditLog.findFor`
- **Fixes:** PERF-06
- **Branch:** `perf/bound-audit-history`
- **Change:** add a limit and an offset (or a cursor) to `AuditLog.findFor`. Leave the reference
  endpoints unpaginated and record that as a decision rather than an oversight.
- **Measure before:** unbounded by construction; no caller renders it yet.
- **Done when:** the port cannot return an unbounded history, and ERT-520 consumes the bounded form.
- **Watch for:** do this **before** ERT-520 renders a hire's history. Afterwards it is a contract
  change rather than a parameter.

### TASK-25 — Size concurrency, dispatcher and pool against each other
- **Fixes:** PERF-05
- **Branch:** `perf/dispatcher-pool-sizing`
- **Depends on:** TASK-22, and a load test against **real Postgres** — H2 cannot exhibit this.
- **Change:** decide `--concurrency`, `Dispatchers.IO` parallelism and `DATABASE_MAX_POOL_SIZE` as one
  set. Today they are 80, 64 and 5, each chosen independently.
- **Measure before:** unknown — the local run showed no degradation at concurrency 80, and that result
  is an artefact of in-memory H2, not evidence of health.
- **Done when:** a ramp against UAT on Cloud SQL shows a knee above the target load, with pending
  connections near zero at steady state.
- **Watch for:** the connection budget in `docs/deployment.md`. Raising the pool without raising Cloud
  SQL's ceiling moves the exhaustion to the database, and lowering Cloud Run concurrency multiplies
  instances, which multiplies connections.

### TASK-26 — Batch the requirement snapshot, and index `lower(email)`
- **Fixes:** PERF-07, PERF-08
- **Branch:** `perf/batch-requirement-snapshot`
- **Change:** `batchUpsert` in `saveRequirements`; a functional index on `lower(email)` in the next
  migration that happens anyway — it is additive, so it is safe under the backward-compatibility rule
  in architecture §14.
- **Measure before:** ~28 round trips per hire creation, unmeasurable against H2.
- **Done when:** hire creation issues one statement for the snapshot, and the duplicate-email check
  uses the index.
- **Watch for:** land it with or before ERT-430, which is the first code to exercise either path.

---

## Dispositions

**Recorded 2026-09-17.** PERF-01 is the only finding that is both measured and urgent, and it is the
same defect as SEC-19 reached from the performance side. **It should be fixed before the service is
publicly reachable**, together with the alerting task from the security audit — the code's own
justification for having no rate limit is that the audit row provides detection, and that is only
true once something reads the audit row.

PERF-02 through PERF-04 are accepted as worth doing and unblocked; PERF-03 and PERF-04 together are
the cheapest items in either audit.

**PERF-05 is explicitly undecided, and that is the finding's disposition rather than a gap in it.**
Local measurement against in-memory H2 cannot exhibit pool contention, and inventing a number would
have made this report less trustworthy, not more. It is ranked on the strength of the arithmetic —
80 concurrent requests, 64 threads, 5 connections, none of the three chosen against the others — and
it stays a hypothesis until TASK-22 and a load test against real Postgres say otherwise.

PERF-06 through PERF-09 are latent. Each is recorded with the event that makes it stop being latent —
ERT-520 for the audit history, ERT-430 and §8.2's bulk import for the snapshot batching, and growth
for the functional index — so that the trigger is checkable rather than a matter of someone
remembering.
