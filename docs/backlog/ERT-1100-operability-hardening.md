# ERT-1100 · Epic: Operability and hardening

| | |
|---|---|
| **Type** | Epic |
| **Phase** | Cross-cutting |
| **Status** | Not started |
| **Depends on** | — |
| **PRD** | §12 |
| **Architecture** | §14 |

> **This number is above ERT-1000 because the epic was opened last, not because it runs last.**
> ERT-1110 must land **before ERT-630**, which is mid-Phase-1. Read the gate column, not the number.

**Description**

Five risks and one gap that were recorded in prose across the roadmap, the architecture doc and three
ticket bodies, and tracked nowhere. That was the defect: a risk without a ticket has no owner, no
gate and no way of being noticed at the moment it stops being theoretical.

They are filed here rather than in ERT-100 for two reasons. ERT-100 is Phase 0 — "the runtime and
test scaffolding Phase 1 assumes" — and three of these are hardening and operations rather than
scaffolding. And ERT-100 is one ticket from closing; re-opening a closed epic for months would make
"ERT-100 is closed" false in the one document people check.

**Goal**

Every risk this project knows about has a number, an owner and a gate, and the two that are
security-relevant have a dependency that a build can check rather than a sentence a reader must
notice.

**Stories**
- As an engineer taking ERT-630, I want the token-in-logs defect fixed **before** I create the first
  portal route, so that I am not the person who ships a live credential into a log file.
- As whoever deploys this, I want a configuration that fails closed, so that a forgotten environment
  variable is a startup error rather than an open `/openapi`, an open `/metrics`, an ephemeral JWT
  key and an ephemeral pepper.

**Out of scope**
- The deployment runbook itself. ERT-1120 produces the configuration behaviour; the runbook is a
  document someone writes once a target environment exists.

---

## ERT-1110 — Redact the request URI in `StatusPages` logging

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **hard gate before ERT-630** |
| **Status** | Not started |
| **Depends on** | ERT-140 |
| **PRD** | §12 |
| **Architecture** | §12 invariant 4 |

**Description**

[`StatusPages.kt`](../../src/plugin/StatusPages.kt) logs `call.request.local.uri` unredacted at
**three** call sites — the malformed-request path, the unsupported-content-type path, and the
unhandled-exception path. All three bypass the portal-token redaction that
[`Monitoring.kt`](../../src/plugin/Monitoring.kt) applies inside `CallLogging`'s `format` block,
three files over.

The third arrived with ERT-146 on 2026-09-17, which is the argument below making itself: it was
added by someone fixing an unrelated 500, it copied the line above it, and nothing objected. The
guard this ticket describes would have caught it, and covers all three at once when it lands — so
this is a re-count and not a change of design.

Today that is harmless, because `route/portal/` is empty and no path contains a credential. **ERT-630
creates the first route whose path *is* the credential.** From that moment the first malformed body
on a portal path writes a live link token to the log file, and since 2026-09-16 that token is the
entire authentication — it is no longer half a credential. A token written to a log cannot be
un-logged.

**The fix is to stop having two copies of the rule.** Extract the redaction out of `Monitoring.kt`'s
`format` block into one function both plugins call. Then add an architecture guard that fails the
build on any file under `plugin/` referencing `local.uri` outside that function — otherwise the next
call site, added in a year by someone debugging, re-opens it silently. That guard is the deliverable;
the two-line fix is not.

**Goal**

No portal token reaches a log line, and a future call site that would change that fails the build.

**Stories**
- As a New Hire, I want my link kept out of the log files, so that read access to logs is not read
  access to my documents.

**Acceptance criteria**
- [ ] `[derived]` Given a malformed request body on a portal path, then the logged line contains
      `[redacted]` and no token
- [ ] `[derived]` Given an unhandled exception on a portal path, then the same holds
- [ ] `[derived]` Given a request on a portal path whose `Content-Type` matches no converter, then
      the same holds — the third call site, added by ERT-146
- [ ] `[derived]` Given the redaction, then `Monitoring.kt` and `StatusPages.kt` call one shared
      function rather than each carrying a copy
- [ ] `[derived]` Given any file under `plugin/` referencing `local.uri` outside that function, then
      the architecture test fails the build
- [ ] `[derived]` Given a non-portal path, then the URI is still logged in full — the redaction is
      scoped, not blanket, or every 404 becomes undiagnosable
- [ ] `[derived]` Given a route mounted under `src/route/portal/` whose path does not sit beneath the
      prefix the redaction matches, then the architecture test fails the build **(SEC-35, added
      2026-09-18)**

> **The redaction is narrower than it reads (SEC-35, [2026-09-18](../2026-09-18-ert-100-200-review.md)).**
> It is a literal `path.startsWith("/api/portal/")`, so a portal route mounted anywhere else is logged
> in full; and it reads `call.request.path()`, which excludes the query string, so a token arriving as
> a parameter is outside its reach. Nothing ties `src/route/portal/` to that prefix — `ArchitectureTest`
> guards what a portal DTO may declare and what a portal route may import, and not where one mounts.
> The shared function this ticket already extracts is the right place for both; the criterion above is
> what stops the prefix and the route tree drifting apart.

**Tests**
| Level | Test |
|---|---|
| Route | `status pages logging - a malformed body on a portal path - writes no token to the log` |
| Route | `status pages logging - an unhandled exception on a portal path - writes no token to the log` |
| Route | `status pages logging - an unreadable content type on a portal path - writes no token to the log` |
| Route | `status pages logging - a non-portal path - logs the uri in full` |
| Architecture | `uri redaction - every plugin referencing local.uri - goes through the shared redaction` |

**Files**
- create `src/plugin/UriRedaction.kt`
- modify [`src/plugin/StatusPages.kt`](../../src/plugin/StatusPages.kt), [`src/plugin/Monitoring.kt`](../../src/plugin/Monitoring.kt)
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt)

**Out of scope**
- Log shipping, retention and access control. ERT-1120.

---

## ERT-1120 — `APP_ENV` fails closed, and one deployment-configuration check

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Done |
| **Depends on** | ERT-195 |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

Architecture §14 already says this "belongs with a deployment-configuration ticket". **This is that
ticket**, and the absence of one is why the observation sat in prose for three epics.

`isDevMode()` treats an unset `APP_ENV` as development, so **four security controls hang off one
variable that fails open**: `/openapi` and `/swagger` are served, `/metrics` is ungated, the JWT
signing key is ephemeral and the token pepper is ephemeral. A deployment that forgets to set it does
not fail — it silently selects the permissive configuration, and nothing in the running system says
so.

**Invert the default: unset means production, and `dev` is opt-in.** That breaks `./kotlin run` on a
fresh checkout, which is why it was not done in passing — so this ticket also ships the replacement
affordance (a committed `.env.dev`, or a `--dev` flag), or it trades one silent failure for a worse
first-run experience.

**Add one startup summary line** naming every control's resolved state — environment, docs exposure,
metrics exposure, JWT key source, pepper source, mail transport. "Which mode is this?" should be
answerable from the log rather than by reading five files.

**GCP is multi-instance, and two tickets assume one instance.** Q20 puts this on GCP, where Cloud Run
and GKE scale horizontally by default. ERT-660's rate limiter is in-memory and ERT-1020's scheduler is
per-process. The PIN counters are safe, being DB-backed, and ERT-1010's poller claims rows
conditionally so a double send is impossible. **This ticket records which assumption the deployment
makes** — pinned to one instance, or not — and ERT-660 and ERT-1020 read it.

> **Answered 2026-09-17, when ERT-1200 created the target environment: MULTI-INSTANCE. Nothing may
> assume one process.**
>
> The pin was rejected rather than merely not chosen. **`--max-instances 1` is a per-revision ceiling,
> not a mutex** — during any rollout the old revision's instance and the new revision's instance both
> exist, and Cloud Run starts a replacement whenever an instance becomes unhealthy. Correctness resting
> on `max-instances 1` rests on something Cloud Run does not promise, and it would be discovered during
> a deploy, which is the worst available moment. Pinning also caps throughput permanently and makes the
> service a single point of failure, in exchange for a property it does not actually deliver.
>
> What the three tickets that read this must now do:
>
> - **ERT-660** — an in-memory limiter is per-instance, so the effective limit is `N × instances`; at
>   `--max-instances 4` a limit of N behaves like 4N in the worst case. That is tolerable for coarse
>   request shaping and **not** tolerable for anything security-bearing. The PIN attempt counters are
>   already DB-backed, which is the correct pattern; only non-security limiting may live in memory, and
>   the multiplier must be documented where the limit is configured.
> - **ERT-1020** — a bare per-process timer runs every job on every instance. Two acceptable designs:
>   Cloud Scheduler calling an authenticated endpoint (one invocation, whichever instance answers —
>   recommended, and it also removes the always-on-CPU dependency), or a DB-backed lease using
>   `SELECT … FOR UPDATE SKIP LOCKED`.
> - **ERT-1010** — already safe. The poller claims rows conditionally, so a double send is impossible.
>   Stated explicitly here so that nobody "fixes" it.
>
> The resolved answer is also printed in the startup summary, so an operator reads it from the log
> rather than from this file.

**The ephemeral-filesystem refusal belongs with the adapter, not here.** ERT-710 will bind a local
filesystem `DocumentStorage` under `STORAGE_ROOT`. On Cloud Run that is an in-memory tmpfs charged
against the container's memory limit, never evicted, and **per-instance** — so a download following an
upload misses roughly `(N-1)/N` of the time, and a day of uploads OOM-kills the instance. The check
that refuses to start when that adapter is selected outside dev cannot be written before the adapter
exists, so it is an acceptance criterion **on ERT-710** rather than a criterion here that would have to
be marked done without being implemented.

**Goal**

A deployment that is misconfigured fails at startup instead of running permissively, and the log says
what mode it came up in.

**Stories**
- As whoever deploys this, I want a forgotten variable to stop the service rather than quietly open
  the docs and the metrics to the internet.

**Acceptance criteria**
- [x] `[derived]` Given `APP_ENV` is unset, then the application resolves to production and refuses to
      start without `JWT_SECRET` and `TOKEN_PEPPER`
- [x] `[derived]` Given `APP_ENV=dev`, then the dev affordances apply exactly as they do today
- [x] `[derived]` Given a fresh checkout, then there is one documented command that runs the
      application in dev without hand-setting variables
- [x] `[derived]` Given startup, then one log line names the resolved state of every environment-gated
      control
- [x] `[derived]` Given the documentation, then the instance assumption is stated, and ERT-660 and
      ERT-1020 cite it

**Tests**
| Level | Test |
|---|---|
| Use case | `environment resolution - APP_ENV unset - resolves to production` |
| Use case | `environment resolution - production with no jwt secret - refuses to start` |
| Use case | `environment resolution - APP_ENV dev - keeps the dev affordances` |
| Route | `startup summary - any boot - names every environment-gated control and no secret value` |

**Files**
- modify `src/plugin/ApiDocs.kt`, `src/plugin/Security.kt`, `src/plugin/Monitoring.kt`
- modify [`src/Application.kt`](../../src/Application.kt) — the summary line
- modify [`.env.example`](../../.env.example); create `.env.dev`
- modify [`docs/architecture.md`](../architecture.md) §14

**Out of scope**
- Secret management (Secret Manager wiring), backups, alerting and log retention. Each needs a target
  environment to exist first; this ticket makes the application behave correctly in any of them.

---

## ERT-1130 — Token pepper rotation and credential re-issue

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | 2 |
| **Status** | Not started |
| **Depends on** | ERT-1030 |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

Rotating `TOKEN_PEPPER` changes every digest, so **every live link and session stops resolving at
once** — and there is no re-issue flow. Recovery means re-inviting every in-flight hire by hand,
through the bulk path §8.2 deliberately makes slow. Until this lands, `.env.example` and architecture
§14 must keep saying the pepper is permanent for the life of an environment.

It depends on ERT-1030 because `resend-link` already mints a fresh token: rotation is that operation
applied in bulk, plus a **two-pepper verification window** — `TOKEN_PEPPER` and
`TOKEN_PEPPER_PREVIOUS` — so live links keep resolving while the roll proceeds.

**Goal**

The pepper can be rotated without stranding every hire mid-onboarding.

**Acceptance criteria**
- [ ] `[derived]` Given both peppers are configured, then a token digested under either resolves
- [ ] `[derived]` Given a link resolved under the previous pepper, then it is re-digested under the
      current one on next access
- [ ] `[derived]` Given the previous pepper is removed, then only current-pepper tokens resolve
- [ ] `[derived]` Given rotation, then an operator can list links still on the previous pepper

**Files**
- modify `src/data/crypto/HmacTokenDigest.kt`
- modify [`.env.example`](../../.env.example), [`docs/architecture.md`](../architecture.md)

**Out of scope**
- Rotating `JWT_SECRET`. Tokens are short-lived by design; rotation costs one sign-in.

---

## ERT-1140 — Documentation hygiene: root README and the unused R2DBC dependencies

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — any time |
| **Status** | Not started |
| **Depends on** | — |
| **PRD** | — |
| **Architecture** | §14, §15 |

**Description**

The root [`README.md`](../../README.md) is **stock Ktor Project Generator boilerplate**. It advertises
features that were deleted — architecture §15 lists them — and says nothing about the Employee
Requirements Tracker. It is the first file a new engineer or an auditor opens, and it currently
describes a different application. [`CLAUDE.md`](../../CLAUDE.md) is the real guide; the README should
be short and point at it, the PRD and the roadmap rather than duplicating any of them.

The same ticket removes `exposed-r2dbc` and `h2database-r2dbc` from `libs.versions.toml` and
`module.yaml`. Nothing imports r2dbc; architecture §14 said they were "left for the owner to remove",
which is a to-do with no owner.

**Goal**

The first file a newcomer opens describes this system, and the dependency list contains nothing the
build does not use.

**Acceptance criteria**
- [ ] `[derived]` Given the README, then it describes this system and links to the PRD, the roadmap
      and CLAUDE.md
- [ ] `[derived]` Given the README, then it advertises no feature that is not present
- [ ] `[derived]` Given `libs.versions.toml` and `module.yaml`, then no r2dbc dependency remains and
      the build still passes
- [ ] `[derived]` Given `module.yaml`, then `ktor.server.sessions` is either installed or removed —
      it is declared and `install(Sessions)` appears nowhere **(HAR-07, added 2026-09-18)**
- [ ] `[derived]` Given MockK, then either a test uses it or it is removed **and** the four documents
      that name it as a house convention are corrected **(HAR-07, added 2026-09-18)**

> **MockK is a convention with no instances (HAR-07,
> [2026-09-18](../2026-09-18-ert-100-200-review.md)).** `CLAUDE.md`, architecture §10, ERT-200's
> Description and ERT-210's Out of scope all say tests use *"`kotlin.test` with Kotest assertions and
> **MockK**"*. `grep -rl io.mockk test/` returns nothing, and ERT-200's own opening paragraph observed
> the same thing before the epic started — only the `kotlinx-coroutines-test` half changed. Decide
> which way, because a reader who follows `CLAUDE.md` reaches for a tool this suite has never used.
> `ktor.server.sessions` is the same shape, and **ERT-620 deliberately keeps it uninstalled** — so if
> it stays, that ticket's reasoning is why, and this ticket should say so rather than remove it.

**Files**
- modify [`README.md`](../../README.md), [`libs.versions.toml`](../../libs.versions.toml), [`module.yaml`](../../module.yaml)

---

## ERT-1150 — Malware scanning behind the `isClean` gate

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Blocked on Q22 |
| **Depends on** | ERT-710, ERT-810 |
| **PRD** | §12, §14 Q22 |
| **Architecture** | §4 |

**Description**

§12 makes malware scanning a P0 control. ERT-710 wires the `isClean` gate and stubs it to `true` with
a startup warning; ERT-810 refuses to serve anything that fails it. The seam being live is what makes
the scanner one adapter and no route change — and it is also what makes the gap easy to forget, which
is why this ticket exists rather than another paragraph.

**Recommended implementation: ClamAV via `clamd` over a local socket** (Q22). It is free, runs beside
the service, is maintained, and — the reason that decides it — **sends nothing off the host**. These
are government IDs, birth certificates and medical results; posting them to a hosted scanning API is a
§12 disclosure decision and a Q5 jurisdiction decision, not a procurement one.

**Blocked on Q22 means awaiting a decision with a date**, not awaiting a stakeholder indefinitely. The
owner is Engineering / Security and the date is the Phase 1 exit checkpoint. Until then the honest
statement is unchanged: a **named Phase 1 exit risk, not a delivered control.**

**Goal**

An uploaded file is scanned before it can be served, and the gate stops being a stub.

**Acceptance criteria**
- [ ] `[derived]` Given an uploaded file, then it is scanned and the verdict stored
- [ ] `[derived]` Given a file that fails the scan, then ERT-810 serves nothing and says why
- [ ] `[derived]` Given the scanner is unreachable, then the file is held as unscanned rather than
      treated as clean — **fail closed**
- [ ] `[derived]` Given startup, then the warning about a stubbed gate no longer appears

**Files**
- create `src/data/storage/ClamAvScanner.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt)

---

## ERT-1160 — CI: build and test on every push

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | — |
| **Architecture** | §10 |

**Description**

**There is no CI of any kind** — no `.github/`, no workflow, nothing that runs `./kotlin build` or
`./kotlin test` except a person who remembers to.

That matters more here than it would in most projects, because this design leans on guards that
**fail the build**: `ArchitectureTest` enforces the dependency rule, the write-mostly portal DTO rule
and the use-case tracing rule; `MigrationTest` catches schema drift; and several tests exist purely as
tripwires that report *vacuous* rather than passing. Every one of those is a control that currently
runs only when someone remembers. ERT-1110 adds another, and ERT-190 adds the first test that can mint
a real token.

**Goal**

Every push runs the build and the full suite, and a failing guard blocks the merge rather than being
discovered later.

**Acceptance criteria**
- [x] `[derived]` Given a push, then `./kotlin build` and `./kotlin test` run
- [x] `[derived]` Given a failing test, then the check fails visibly on the pull request
- [x] `[derived]` Given the workflow, then it pins the toolchain version rather than tracking latest
- [x] `[derived]` Given a run, then the test count is reported, so a suite that silently stops
      discovering tests is visible — the Kotest-discovery trap in architecture §10 is exactly this
      failure
- [x] `[derived]` Given a compiled class whose major version is not 65, then the build fails —
      ERT-1210 pinned `settings.jvm.release`, and a pin without a guard is a comment
- [x] `[derived]` Given a pull request, then the container image builds and boots against in-memory
      H2, so a change that breaks the image is caught before it reaches a deploy workflow
- [x] `[derived]` Given a commit, then a secret scanner runs — `.gitignore` re-includes files named
      `.env.*` (ERT-1120, ERT-1230), and git's last-matching-pattern rule makes that list fragile

**Files**
- create `.github/workflows/build.yml`
- modify [`README.md`](../../README.md) — the status badge, with ERT-1140

**Out of scope**
- Deployment pipelines. ERT-1120 first; there is no target environment yet. **ERT-1200 is that
  pipeline**, and its deploy workflows sit beside this file rather than inside it.
- Pinning third-party actions to commit SHAs. ERT-1165.

---

## ERT-1165 — Pin every third-party GitHub Action to a commit SHA

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Not started |
| **Depends on** | ERT-1160 |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

The three workflows reference actions by tag — `actions/checkout@v4`, `google-github-actions/auth@v2`,
`gitleaks/gitleaks-action@v2`. A tag is mutable. Whoever controls one of those repositories, or
anyone who compromises it, can change what `@v4` points at and run arbitrary code **inside a job that
holds an OIDC token able to impersonate a deployment service account**.

That is not hypothetical for this repository specifically. `deploy-uat.yml` can push to Artifact
Registry and deploy a Cloud Run revision; `deploy-prod.yml` can move production traffic. The blast
radius of a compromised action here is the production service.

**This project already knows the answer** — the `kotlin` wrapper pins both a version *and* a
sha256, and refuses to run on a mismatch. A workflow floating on `@v4` is a weaker link than the
toolchain it is guarding, which is the whole argument.

**Goal**

Every third-party action resolves to bytes that cannot change under us.

**Acceptance criteria**
- [ ] `[derived]` Given any `uses:` line naming a third-party action, then it references a full
      40-character commit SHA with the human-readable tag in a trailing comment
- [ ] `[derived]` Given a pinned action, then a renovation tool or a documented procedure exists for
      moving the pin deliberately — a pin nobody can update is abandoned, not secure
- [ ] `[derived]` Given the deploy workflows, then their `permissions:` blocks grant the narrowest
      set each job needs

**Files**
- modify `.github/workflows/build.yml`, `.github/workflows/deploy-uat.yml`,
  `.github/workflows/deploy-prod.yml`

---

## ERT-1170 — Bound how many sign-in attempts reach bcrypt

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **gate before the service is publicly reachable** |
| **Status** | Not started |
| **Depends on** | ERT-190, ERT-1185 |
| **PRD** | §12 |
| **Architecture** | §12 invariant 10 |

**Description**

`POST /api/auth/login` is public, unthrottled, and **measured at 3.95 requests per second at
concurrency 1** — roughly 340× slower than every other endpoint in the system (SEC-19, PERF-01).
Every request costs one bcrypt cost-12 verification, about 250 ms of CPU, **including every failure**.

That cost is not a defect. Invariant 10 requires that an unknown email, a wrong password, a malformed
address and a deactivated account are indistinguishable **in elapsed time as well as in body**, and
the decoy verify is how that is bought. **The obvious fix is the wrong one**: skipping the
verification when the user is unknown closes the denial of service by reopening the enumeration
oracle, and it would pass a load test while silently breaking a security property.

What is missing is a bound on how many attempts reach bcrypt at all. Two attacks come through this
one endpoint: unauthenticated CPU exhaustion — at `--concurrency=80` on `--cpu=1`, eighty concurrent
sign-ins is about twenty seconds of queued work on one core — and unbounded password guessing, whose
only named compensating control is an audit row nobody reads, which is why ERT-1185 is a dependency
rather than a suggestion.

**The limiter must be database-backed.** The deployment is multi-instance (ERT-1120), so an in-memory
counter gives an effective limit of `configured × instances`. `countRecentFailures` already
establishes the correct pattern for portal PINs.

**Re-measure the cost factor while here.** `BcryptHasher.DEFAULT_COST`'s comment says "~100 ms per
hash on current hardware"; it measured about 250 ms. Raising the cost makes the denial of service
cheaper, so the two decisions have to be made together — and the cost should be readable from the
environment with 12 as a **floor**, not merely a default (SEC-28).

**Goal**

A sustained guessing run costs the attacker more than it costs the service, and a legitimate first
attempt is unchanged.

**Acceptance criteria**
- [ ] `[derived]` Given repeated failed attempts against one address, then further attempts are
      refused **before** any password verification is performed
- [ ] `[derived]` Given a refusal, then it is indistinguishable from a wrong password in body **and**
      in elapsed time
- [ ] `[derived]` Given the limiter, then its counters are held in the database, not in memory
- [ ] `[derived]` Given the bcrypt cost, then it is read from the environment and a value below 12 is
      refused rather than accepted
- [ ] `[derived]` Given the existing timing-uniformity tests, then all of them still pass
- [ ] `[derived]` Given the limiter's key, then it is the attempted **address** — or, if a source
      address is used at all, it comes from a decided trusted-hop count rather than from the peer
      socket **(SEC-33, added 2026-09-18)**

> **The source IP in the audit trail is the load balancer's (SEC-33,
> [2026-09-18](../2026-09-18-ert-100-200-review.md)).** `AuthRoutes.kt:56-59` records
> `call.request.local.remoteAddress` and documents why — `X-Forwarded-For` is caller-controlled, which
> is correct for a server a client reaches directly. On Cloud Run the peer is always the Google front
> end, so the column holds one value for every caller. That matters twice here: a limiter keyed on
> that address limits **every HR user at once and the attacker not at all**, and ERT-1185's alerting
> cannot tell a burst from one source apart from Monday morning. The fix is not "trust XFF" — it is
> `ForwardedHeaders` with a decided trusted-hop count, in **one helper** that this ticket and
> **ERT-610** both call, because the second call site is where a per-route decision diverges. Note
> while there that `ip varchar(64)` must hold one address, not a header chain. **Not measured** —
> confirm with `select ip from audit_logs` against UAT before keying anything on that column.

**Tests**
| Level | Test |
|---|---|
| Use case | `hr sign in - repeated failures against one address - the next attempt is refused without verifying` |
| Use case | `hr sign in - a rate-limited refusal - is indistinguishable from a wrong password` |
| Use case | `hr sign in - a configured bcrypt cost below the floor - is refused at startup` |

**Files**
- modify [`src/domain/usecase/AuthenticateHrUserUseCase.kt`](../../src/domain/usecase/AuthenticateHrUserUseCase.kt),
  [`src/data/crypto/BcryptHasher.kt`](../../src/data/crypto/BcryptHasher.kt),
  [`src/di/CoreModule.kt`](../../src/di/CoreModule.kt)

**Out of scope**
- Volumetric edge limiting. Cloud Armor is the answer for that and it is infrastructure, not code;
  [`docs/deployment.md`](../deployment.md) records it.
- Portal rate limiting. ERT-660, and it must read the multi-instance answer on ERT-1120.

---

## ERT-1175 — Security headers, HSTS, and a request body limit

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **gate before ERT-630** |
| **Status** | Not started |
| **Depends on** | ERT-1120 |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

The complete plugin inventory is `Koin`, `CORS`, `ContentNegotiation`, `StatusPages`, `CallLogging`
and `MicrometerMetrics`. Grepping `src/` for `HSTS`, `DefaultHeaders`, `X-Frame`, `Content-Security`
or `RequestValidation` returns **nothing** (SEC-20). `embeddedServer(Netty)` is configured with a
connector and a shutdown window and nothing else — no body cap.

Two consequences with different timelines. The **body cap is immediate**: an unauthenticated
`POST /api/auth/login` carrying a multi-megabyte body is buffered before `ContentNegotiation` rejects
it, which is a second and cheaper denial of service than ERT-1170's. The **headers matter from
ERT-630**, which puts a phone browser on the portal — that is where a missing `X-Frame-Options` and a
missing `X-Content-Type-Options` stop being theoretical.

`HSTS` must be gated on `APP_ENV != dev`. Sending it from `localhost` poisons a developer's browser
for the whole origin, and the resulting "my other local app stopped working over http" is a long
afternoon.

**Goal**

The transport-level defaults are set once, deliberately, before a browser is pointed at this service.

**Acceptance criteria**
- [ ] `[derived]` Given any response, then it carries `X-Content-Type-Options: nosniff` and a frame
      policy
- [ ] `[derived]` Given `APP_ENV != dev`, then responses carry `Strict-Transport-Security`; given
      dev, then they do not
- [ ] `[derived]` Given a request body above the configured limit, then it is refused with 413
      **without being fully buffered**
- [ ] `[derived]` Given the limit, then it is configurable — ERT-710 needs a different one for
      uploads than for JSON

**Tests**
| Level | Test |
|---|---|
| Route | `security headers - any response - carries nosniff and a frame policy` |
| Route | `security headers - dev - sends no HSTS` |
| Route | `request limits - a body above the cap - is refused with 413` |

**Files**
- modify [`src/plugin/Http.kt`](../../src/plugin/Http.kt), [`src/main.kt`](../../src/main.kt),
  [`src/Application.kt`](../../src/Application.kt)

---

## ERT-1180 — Dependency and image scanning, with an SBOM

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Not started |
| **Depends on** | ERT-1160 |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

CI runs the build, the suite and a secret scan. Nothing scans the 176 runtime dependencies for known
vulnerabilities and no SBOM is produced (SEC-23). `deploy-uat.yml` calls
`gcloud artifacts docker images scan`, but it is `continue-on-error: true` and nobody is required to
read the result.

For a system holding government IDs, birth certificates and medical results, "are we affected by
this CVE?" needs an answer better than reading `libs.versions.toml` by hand.

**The prerequisite is already satisfied**, which is why this is cheap: every version in
`libs.versions.toml` is pinned exactly — no `+`, no `latest.release`, no snapshots — so the
dependency set is deterministic and therefore scannable.

**Make the image scan blocking only after a baseline exists.** A base-image CVE disclosed overnight
blocking an unrelated hotfix is how a gate gets bypassed permanently.

**Goal**

A vulnerable dependency fails a build rather than waiting to be noticed.

**Acceptance criteria**
- [ ] `[derived]` Given a dependency with a known High or Critical advisory, then the build fails
- [ ] `[derived]` Given a finding that has been assessed and accepted, then a documented suppression
      records who accepted it and why
- [ ] `[derived]` Given an image build, then an SBOM is produced and attached to the image
- [ ] `[derived]` Given the image scan, then it blocks on Critical **after** a baseline exists

**Files**
- modify `.github/workflows/build.yml`, `.github/workflows/deploy-uat.yml`

---

## ERT-1185 — Alert on the audit trail that already exists

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **with or before ERT-1170** |
| **Status** | Not started |
| **Depends on** | ERT-1250, ERT-1260 |
| **PRD** | §12, §13 |
| **Architecture** | §14 |

**Description**

Sign-in failures, admin actions and permission changes are recorded in an append-only table. **Nothing
reads them** (SEC-24). That is the whole point of the 2025 rename of the OWASP category — logs nobody
reads are not a control — and this codebase leans on the unread one explicitly:
[`AuthRoutes.kt`](../../src/route/hr/AuthRoutes.kt) justifies the absence of rate limiting with *"until
then the audit row is the detection."*

So a sustained password-guessing run against an HR account produces a perfect record and no
notification. **This ticket is what makes that sentence true**, which is why ERT-1170 depends on it
rather than the other way round.

The pieces are already in place. ERT-1250 made logs structured JSON, so a Cloud Monitoring
**log-based metric** and an alerting policy are configuration rather than code. And
`AuditEntryMapper` already refuses to persist credential-shaped metadata, so an alert can carry the
event without carrying a secret.

**Goal**

Someone finds out.

**Acceptance criteria**
- [ ] `[derived]` Given a burst of failed sign-ins, then an alert fires
- [ ] `[derived]` Given an `HR_ADMIN` action outside working hours, then an alert fires
- [ ] `[derived]` Given an alert payload, then it carries no credential and no PIN
- [ ] `[derived]` Given the policies, then they are recorded in [`docs/deployment.md`](../deployment.md)
      rather than existing only in a console

**Files**
- modify [`docs/deployment.md`](../deployment.md) — the policies, so they are reviewable

**Out of scope**
- Application code. If this needs a code change, the event is not being logged and that is a
  different ticket.

---

## ERT-1190 — Index the baseline schema

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **before ERT-610 and ERT-1020** |
| **Status** | Not started |
| **Depends on** | ERT-120 |
| **PRD** | §6.6, §8.12, §8.13, §11 |
| **Architecture** | §7, §14 |

**Description**

`V1__baseline.sql` declares thirteen tables and creates **two** plain indexes — `employees_email` and
`audit_logs_entity_id` — plus the two `token_hash` uniques and the primary keys. Neither PostgreSQL
nor Exposed creates an index for a foreign key, so every other access path in the schema is a
sequential scan.

**The contrast inside this repository is what makes it an omission rather than a house style.**
`V6__notification_outbox.sql` (ERT-440) indexes its foreign key *and* the column its poller filters
by. A later ticket knew to do it; the baseline did not.

Unindexed, and on a path something already written or already ticketed will take:

| Column(s) | Read by | Grows with |
|---|---|---|
| `employee_requirements.employee_id` | `requirementsOf` — the checklist read | ~14 rows per hire |
| `submissions.employee_requirement_id` | the per-requirement version history | uploads |
| `upload_links.employee_id` | `findActiveForEmployee` | hires |
| `upload_links.status`, `expires_at`, `idle_expires_at` | **ERT-1020's expiry sweep**, which scans for work | hires |
| `portal_sessions.upload_link_id` | `findActiveForLink` | sessions |
| **`portal_access_logs.upload_link_id` + `timestamp`** | **§6.6's `countRecentFailures`** | **every portal request, forever** |
| `audit_logs.timestamp`, `actor_user_id` | §8.13's exception report | every action, forever |
| `employees.department_id`, `employment_type_id`, `packet_status` | ERT-510's HR list | hires |

**The `portal_access_logs` row is the one to read twice.** It is append-only by design, it is the
fastest-growing table in the schema, and `countRecentFailures(linkId, since)` is the query that
decides §6.6's lockout and auto-suspend. That makes it a security control whose cost rises with the
log it reads, on the unauthenticated portal path — the same shape as SEC-19, reached from the schema
instead of from the algorithm.

**Why now.** An index is a pure addition, safe under architecture §14's backward-compatibility rule,
and free against an empty table. `CREATE INDEX` against a large table is a different operation with a
different conversation about locks. **Land it before ERT-610 and ERT-1020**, which are the first
readers.

**Goal**

Every access path the schema already has a reader for is served by an index, and the portal's
anomaly counter does not scan the log it counts.

**Stories**
- As an operator, I want the §6.6 lockout counter to cost the same in month twenty-four as in month
  one, so that the control does not quietly become the slowest thing on the portal path.

**Acceptance criteria**
- [ ] `[derived]` Given the migrated schema, then every foreign-key column named above carries an
      index
- [ ] Given `portal_access_logs`, then `(upload_link_id, timestamp)` is one composite index rather
      than two singles — the table is insert-heavy and the query is always both
- [ ] `[derived]` Given the migration, then `statementsRequiredToActualizeScheme(*allTables)` is still
      empty, so `Tables.kt` declares the same indexes
- [ ] `[derived]` Given `MigrationTest`, then its SQL-file count moves from 7 deliberately rather
      than being discovered as a failure
- [ ] `[derived]` Given the same migration, then it applies cleanly on both H2 in PostgreSQL mode and
      PostgreSQL

**Tests**
| Level | Test |
|---|---|
| Repository | `schema indexes - the migrated schema - every foreign key column carries an index` |
| Repository | `schema indexes - portal_access_logs - carries one composite index over link and timestamp` |
| Repository | `schema drift - the new indexes - Exposed reports no pending statements` |

**Files**
- create `resources/db/migration/V8__indexes.sql`
- modify [`src/data/db/table/Tables.kt`](../../src/data/db/table/Tables.kt) — declare the same indexes
- modify [`test/data/db/MigrationTest.kt`](../../test/data/db/MigrationTest.kt) — the file count, and
  the two new assertions

**Out of scope**
- Indexing every column named in the table above. Pick against the readers that exist; an index on a
  column nothing filters by is write cost with no return.
- Partitioning `portal_access_logs` or `audit_logs`. Both grow without bound and both will want it
  eventually; neither wants it at zero rows.

**Folded in**

TASK-26 from the [2026-09-17 bottleneck audit](../2026-09-17-bottleneck-audit.md) scopes PERF-08's
`lower(email)` functional index to *"the next migration that happens anyway"*. This is that
migration, and the index is additive, so it lands here.

---

## ERT-1195 — Prove the fail-closed controls abort a boot

| | |
|---|---|
| **Parent** | ERT-1100 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Not started |
| **Depends on** | ERT-1120, ERT-1160 |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

Five controls refuse to start outside dev: `JWT_SECRET`, `TOKEN_PEPPER` (which also enforces a
32-character floor), `DATABASE_URL`, `PORTAL_BASE_URL`, and the HR bootstrap when `users` is empty.
Every one of them is tested **only as a pure function**, with the environment handed in as a
parameter.

That parameterisation was right and it is what made the rules testable at all — ERT-150, ERT-160 and
ERT-190 each record the reason: *a JVM test cannot unset an environment variable in its own process*.
What is missing is the other half, and **ERT-160's own scope note says so**:

> *"What is proven is that the refusal fires, not that it aborts the boot — the boot path is covered
> only by the eager resolution in `configureKoin()` being on the same line as `install(Koin)`. Verify
> the real thing by hand with `APP_ENV=prod JWT_SECRET=… ./kotlin run`."*

Five controls rest on a hand-verification, and a hand-verification is a control that runs when
somebody remembers — which is the argument ERT-1160 already made for having CI at all. Worse,
**nothing in this project ever runs in production mode**: `module.yaml` sets `APP_ENV: dev` for the
whole test JVM, and `build.yml`'s container smoke test passes `-e APP_ENV=dev` explicitly.

A refactor that moves the eager `getKoin().get<TokenDigest>()` out of `configureKoin()`, reorders
`rootModule()`, or wraps a `check()` in a `runCatching` would leave every pure-function test green
and ship a permissive deployment. That is SEC-17's shape exactly: one variable, no signal, permissive
by default.

**The fix costs one step, because the infrastructure already exists.** `build.yml`'s `image` job
already runs the container and curls `/health`. A second `docker run` at `APP_ENV=production` with
nothing else set turns five hand-verification notes into a guard.

**Also here, because it is the same function and the same rule.** `DatabaseConfig.fromEnvironment`
reads `password ?: ""` — the one configuration reader in `src/` that does not treat blank as unset,
against ERT-160 Decided-2's house idiom — and `user?.takeUnless(String::isBlank) ?: "sa"`, carrying
H2's default into the PostgreSQL path. SEC-18 fixed `DATABASE_URL` in ERT-1241 and left the
credential pair beside it untouched. It fails closed (PostgreSQL refuses `sa` with no password), so
the cost is a confusing startup error rather than an open database — but it is the exact credential
SEC-18's own impact paragraph named.

**Goal**

Removing any one of the five refusals turns CI red, and every configuration reader treats blank as
unset.

**Stories**
- As an operator, I want a deployment that has lost a secret to fail visibly rather than come up
  looking healthy, and I want CI to be what guarantees that rather than someone's memory.

**Acceptance criteria**
- [ ] `[derived]` Given the container is run with `APP_ENV=production` and nothing else set, then it
      exits non-zero and logs a refusal naming the missing variable
- [ ] `[derived]` Given any one of the five `check(devMode)` calls is removed, then CI fails
- [ ] `[derived]` Given `DATABASE_PASSWORD=""`, then it is treated as unset, matching every other
      reader
- [ ] `[derived]` Given `DATABASE_USER=""` outside dev with a PostgreSQL URL, then startup refuses
      rather than falling back to `sa`
- [ ] `[derived]` Given a URL that is neither H2 nor PostgreSQL, then the driver choice is refused by
      name rather than silently falling back to `org.h2.Driver`

**Tests**
| Level | Test |
|---|---|
| CI | `production boot - no secrets set - the container exits non-zero naming the variable` |
| Use case | `database config - a blank database password - is treated as unset` |
| Use case | `database config - a postgres url with no user outside dev - refuses to start` |
| Use case | `database config - a url naming neither engine - is refused by name` |

**Files**
- modify [`.github/workflows/build.yml`](../../.github/workflows/build.yml) — the second `docker run`
- modify [`src/data/db/DatabaseFactory.kt`](../../src/data/db/DatabaseFactory.kt) — the credential
  pair and the driver choice
- modify [`test/data/db/DatabaseConfigTest.kt`](../../test/data/db/DatabaseConfigTest.kt)
- modify [`docs/deployment.md`](../deployment.md) — name the five controls the smoke test covers

**Out of scope**
- Replacing the pure-function tests. This adds the boot-level half; ERT-160's and ERT-1120's
  unit-level tests stay, because they are what makes each rule's *content* testable.
- `APP_ENV` in the test JVM. It stays `dev` — `module.yaml` already explains that this is a real
  environment variable rather than a test-only backdoor, and the in-process suite could not assert
  these refusals in any case. The second process is the point.
