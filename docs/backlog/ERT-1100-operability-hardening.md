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

[`StatusPages.kt`](../../src/plugin/StatusPages.kt) logs `call.request.local.uri` unredacted at two
call sites — the malformed-request path and the unhandled-exception path. Both bypass the portal-token
redaction that [`Monitoring.kt`](../../src/plugin/Monitoring.kt) applies inside `CallLogging`'s
`format` block, three files over.

Today that is harmless, because `route/portal/` is empty and no path contains a credential. **ERT-630
creates the first route whose path *is* the credential.** From that moment the first malformed body
on a portal path writes a live link token to the log file, and since 2026-09-16 that token is the
entire authentication — it is no longer half a credential. A token written to a log cannot be
un-logged.

**The fix is to stop having two copies of the rule.** Extract the redaction out of `Monitoring.kt`'s
`format` block into one function both plugins call. Then add an architecture guard that fails the
build on any file under `plugin/` referencing `local.uri` outside that function — otherwise the third
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
- [ ] `[derived]` Given the redaction, then `Monitoring.kt` and `StatusPages.kt` call one shared
      function rather than each carrying a copy
- [ ] `[derived]` Given any file under `plugin/` referencing `local.uri` outside that function, then
      the architecture test fails the build
- [ ] `[derived]` Given a non-portal path, then the URI is still logged in full — the redaction is
      scoped, not blanket, or every 404 becomes undiagnosable

**Tests**
| Level | Test |
|---|---|
| Route | `status pages logging - a malformed body on a portal path - writes no token to the log` |
| Route | `status pages logging - an unhandled exception on a portal path - writes no token to the log` |
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
| **Status** | Not started |
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
- [ ] `[derived]` Given a push, then `./kotlin build` and `./kotlin test` run
- [ ] `[derived]` Given a failing test, then the check fails visibly on the pull request
- [ ] `[derived]` Given the workflow, then it pins the toolchain version rather than tracking latest
- [ ] `[derived]` Given a run, then the test count is reported, so a suite that silently stops
      discovering tests is visible — the Kotest-discovery trap in architecture §10 is exactly this
      failure

**Files**
- create `.github/workflows/build.yml`
- modify [`README.md`](../../README.md) — the status badge, with ERT-1140

**Out of scope**
- Deployment pipelines. ERT-1120 first; there is no target environment yet.
