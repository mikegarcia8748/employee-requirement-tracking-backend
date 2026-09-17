# Security Audit — Employee Requirements Tracker — 2026-09-17

> **This is a code and infrastructure audit.** [2026-09-09](2026-09-09-security-audit.md) was a
> *specification* audit of PRD v0.3, and its Scope says plainly: *"Not covered: infrastructure and
> hosting choices…"*. There is now an implementation and a hosting choice, and neither had been
> audited. Findings below cite source files and lines rather than PRD sections.
>
> **Numbering continues from SEC-15.** Numbers are never reused in this project, so a reader who
> finds SEC-04 cited somewhere reaches the finding that was actually meant.

## Scope

Audited: branch `feature/ert-1200-containerisation-and-deployment`, all of `src/` and `test/`, plus
the deployment surface ERT-1200 introduced — `Dockerfile`, `.dockerignore`, `docker-compose.yml`,
`.github/workflows/`, `deploy/*.env.yaml`, `module.yaml`, `.gitignore`.

Walked against OWASP Top 10:2025, A01 through A10.

**Not covered:** the GCP project itself — IAM bindings, VPC firewall rules and Cloud SQL
configuration do not exist yet (ERT-1260 creates them, and the runbook's design is reviewed here but
its execution is not). Also not covered: the downstream account-provisioning system, the existing
HRIS, and the legal analysis still open in PRD §12.

**Nothing was changed by this audit.** Findings marked *Fixed* were fixed by the tickets named,
during the same branch, before the audit was written.

## Authorization model audited against

Confirmed, not inferred — Q4 was answered 2026-09-16 and ERT-190 implemented it.

| Actor | Authenticates via | May read | May write |
|---|---|---|---|
| HR Officer | Local account, bcrypt password, role `HR_OFFICER` | Every hire record and every submitted document | Create hires, edit details, approve/reject, resend/revoke links |
| HR Admin | As above, role `HR_ADMIN` | As HR Officer | Plus users, requirement templates and link-policy settings |
| Any HR account owing a password change | As above, `pwd_change=true` | **Nothing** | `POST /api/auth/change-password` only |
| New Hire | Possession of a URL, plus a PIN on recovery | Own checklist status | Upload own documents |
| Unauthenticated | — | `/health` | `POST /api/auth/login` |

The portal half of that table is **not yet implemented** — `src/route/portal/` is empty, enforced by
a vacuous-guard test in `ArchitectureTest.kt`. Everything shipped today is HR-side plus
infrastructure, which is why A01 has a small surface and A02 has a large one.

## Summary

| Severity | Open | Fixed in this branch | Accepted |
|---|---|---|---|
| Critical | 0 | 1 | 0 |
| High | 3 | 1 | 0 |
| Medium | 5 | 1 | 2 |
| Low | 2 | 1 | 0 |
| **Total** | **10** | **4** | **2** |

The three that matter most:

1. **`POST /api/auth/login` is unauthenticated, unthrottled, and spends ~100 ms of CPU on every
   request including failures** (SEC-19). On a 1-vCPU Cloud Run instance that is a cheap denial of
   service and an unbounded password-guessing oracle in the same endpoint.
2. **The workflows that can deploy production reference third-party actions by mutable tag**
   (SEC-22). The blast radius of a compromised action here is the production service.
3. **`APP_ENV` failed open, and five security controls hung off it** (SEC-17). Fixed in this branch,
   and recorded because it is the shape of defect this system is most prone to: one variable, no
   signal, permissive by default.

Two things this codebase gets right and should not lose, recorded because an audit that lists only
faults gets read as noise:

- **One `suspend` transaction entry point with a correct `Dispatchers.IO` hop, and zero blocking
  `transaction {}` on a request thread** (`DatabaseFactory.kt:77`). `Dispatchers` is banned from the
  domain and `ArchitectureTest` fails the build on it.
- **Three credential types, three deliberately different algorithms** — bcrypt cost 12 for secrets
  that are *verified*, keyed HMAC-SHA-256 for tokens that are *looked up*, `SecureRandom` for
  generation — with the reasoning written down at each site. Collapsing any two would either make
  link lookup impossible or make PIN cracking cheap.

---

## Findings

### SEC-16 — Three HR routes skipped the password-change gate  [Medium]  [A01:2025]

**Where:** `src/route/hr/RequirementTemplateRoutes.kt:36`, `src/route/hr/ReferenceRoutes.kt:34,57`

**What:** `AuthGates.kt:55` states *"Every HR route calls this."* Three handlers called their
repository straight through. `authenticate(HR_AUTH)` in `Routing.kt` proves *identity*; the
password-change gate is a **separate, per-handler** check, and `ReferenceRoutes.kt` documented the
wrong model while omitting it — *"the gate is applied once, around every HR route"*, true of
`authenticate` and false of `hrUserOrRefuse`.

**Impact:** an account carrying `pwd_change=true` — the bootstrap admin before first sign-in, or
anyone whose password an admin has just reset — could read the requirement catalogue, the department
list and the employment types. Low-sensitivity data; the gap between the stated control and the
applied one is the finding.

**Reachability:** `GET /api/requirement-templates`, `GET /api/departments`, `GET /api/employment-types`,
with any valid token.

**Test coverage:** none existed. The comment was the only thing asserting the rule.

**Disposition: Fixed — ERT-1245.** The three-line fix is not the deliverable: `ArchitectureTest` now
fails the build on any handler under `route/hr/` that does not open with one of the three gates, with
`/api/auth/login` on an explicit allow-list. Mutation-checked.

---

### SEC-17 — `APP_ENV` failed open, and five controls hung off it  [Critical]  [A02:2025]

**Where:** `src/plugin/ApiDocs.kt:96` (as it was)

```kotlin
internal fun isDevMode(): Boolean = System.getenv("APP_ENV").orEmpty().ifEmpty { "dev" } == "dev"
```

**What:** unset meant dev, and dev meant `/openapi` and `/swagger` served unauthenticated,
`/metrics` unauthenticated, an ephemeral JWT signing key, an ephemeral token pepper, and an HR
bootstrap that warned instead of refusing.

**Impact:** a Cloud Run deploy that forgot one environment variable would come up **looking
healthy** and fully permissive — publishing the exact shape of the portal endpoints, their error
contracts and their rate limits to anyone who asked, on a signing key that changes every restart.
Nothing in the running system said so.

**Reachability:** every deploy. This is not an attack path; it is the default.

**Test coverage:** none. A JVM test cannot unset an environment variable in its own process, so the
rule had to become a pure function before it could be tested at all.

**Disposition: Fixed — ERT-1120.** `dev` is now opt-in; unset, blank and anything unrecognised
resolve to production. `AppEnvironmentTest` covers the typo case explicitly, because `devv` selecting
the permissive configuration would reintroduce the defect through the back door.

---

### SEC-18 — `DATABASE_URL` failed silently to an in-memory database  [High]  [A02:2025, A10:2025]

**Where:** `src/data/db/DatabaseFactory.kt` — `DatabaseConfig.fromEnvironment` (as it was)

**What:** `JWT_SECRET`, `TOKEN_PEPPER` and `PORTAL_BASE_URL` all `check(devMode)` and refuse outside
dev. `DATABASE_URL` took no `devMode` at all and fell back to `jdbc:h2:mem:ert`, user `sa`, empty
password. It also used `?:` rather than `takeUnless(isBlank)`, so `DATABASE_URL=""` — exactly what
sourcing a file copied from `.env.example` produces — reached the same fallback.

**Impact:** a production deploy that lost the variable did not crash. It started, migrated a fresh
schema into memory, created a working admin account from `HR_BOOTSTRAP_*`, and answered `/health`
with 200. HR signs in, creates hires, and every one of them disappears at the next instance recycle.
An integrity and availability failure that presents as a green deploy — and, briefly, a database
reachable with user `sa` and no password.

**Reachability:** every deploy with a missing or empty variable.

**Test coverage:** none.

**Disposition: Fixed — ERT-1241.**

---

### SEC-19 — `POST /api/auth/login` is unthrottled and burns ~100 ms of CPU per attempt  [High]  [A06:2025, A07:2025]

**Where:** `src/route/hr/AuthRoutes.kt:49`; `src/domain/usecase/AuthenticateHrUserUseCase.kt:66`

```kotlin
private val decoyHash: String by lazy { hasher.hash(DECOY_PASSWORD) }
```

**What:** there is **no rate limiting anywhere in the application** — no `install(RateLimit)`, no
limiter of any kind. `AuthRoutes.kt:79` says so: *"Not rate-limited yet — ERT-660 owns that, and
until then the audit row is the detection."* Separately, and correctly, every branch of
`AuthenticateHrUserUseCase` verifies a password against *some* hash so that an unknown email, a wrong
password, a malformed address and a deactivated account are indistinguishable **in elapsed time**
(invariant 10). That uniformity is bought with a bcrypt cost-12 verification — roughly 100 ms of CPU
— on **every** request, including every failure.

**Impact:** two distinct attacks through one endpoint.

- *Denial of service.* Production runs `--cpu=1` with `--max-instances=4`. A single attacker issuing
  concurrent login requests consumes the entire CPU budget of the service with unauthenticated
  traffic; Cloud Run responds by scaling to the cap and then queueing. The portal a new hire needs
  goes down, and the bill goes up.
- *Credential guessing.* Nothing bounds attempts against a known HR address. The compensating control
  named in the code is the audit row, which is **detection with nobody watching** — see SEC-24.

**Reachability:** public, mounted outside `authenticate` in `Routing.kt:68`. No credential needed.

**Test coverage:** none — there is nothing to test yet.

**Recommended fix:** two layers, because neither alone is sufficient on a multi-instance deployment.
Cloud Armor (or an equivalent edge limit) is the real answer for volumetric abuse and is the only
one that stops traffic before it costs CPU. In-process, add a **DB-backed** attempt counter keyed on
the address — the pattern `countRecentFailures` already establishes for portal PINs — rather than an
in-memory limiter, which on a multi-instance deployment gives an effective limit of
`configured × instances` (ERT-1120). Do **not** solve this by weakening the timing uniformity; that
closes a DoS by reopening an enumeration oracle.

**Ticket: ERT-1170.**

---

### SEC-20 — No security headers, and no request body size limit  [Medium]  [A02:2025, A10:2025]

**Where:** `src/main.kt`; the absence of `DefaultHeaders` anywhere in `src/plugin/`

**What:** the complete plugin inventory is `Koin`, `CORS`, `ContentNegotiation`, `StatusPages`,
`CallLogging`, `MicrometerMetrics`. Grepping `src/` for `HSTS`, `httpsRedirect`, `DefaultHeaders`,
`X-Frame`, `Content-Security`, `RequestValidation` returns **zero hits**. `embeddedServer(Netty)` is
configured with a connector and the shutdown window and nothing else — no `requestQueueLimit`, no
body cap.

**Impact:** modest today and materially worse in three tickets. Cloud Run terminates TLS, so the
transport itself is fine, but there is no `Strict-Transport-Security`, no `X-Content-Type-Options`,
no frame options and no CSP — and ERT-630 onward puts a **phone browser** on the portal, which is
where clickjacking and MIME-sniffing actually matter. The missing body cap is the more immediate
one: an unauthenticated `POST /api/auth/login` with a multi-megabyte body is buffered before
`ContentNegotiation` rejects it, which is a second, cheaper denial of service than SEC-19. It becomes
severe when ERT-710 adds multipart upload, and ERT-750 already knows the shape of the answer — *"the
size cap must be enforced while streaming the part, not after `readBytes()`"*.

**Reachability:** every request.

**Test coverage:** none.

**Recommended fix:** `install(DefaultHeaders)` with the standard set, `install(HSTS)` gated on
`APP_ENV != dev` (HSTS on `localhost` poisons a developer's browser for the whole origin), and an
explicit body limit. Keep the limit a configuration value, since ERT-710 will need a different one
for uploads than for JSON.

**Ticket: ERT-1175.**

---

### SEC-21 — `StatusPages` logs the raw request URI  [High when ERT-630 lands; Low today]  [A09:2025]

**Where:** `src/plugin/StatusPages.kt:35,50`

```kotlin
call.application.log.info("Malformed request on ${call.request.local.uri}: ${cause.message}")
call.application.log.error("Unhandled exception on ${call.request.local.uri}", cause)
```

**What:** both bypass the portal-token redaction `Monitoring.kt` applies inside `CallLogging`'s
`format` block. The rule exists in two places and only one of them implements it.

**Impact:** harmless today — `src/route/portal/` is empty and no path contains a credential. **ERT-630
creates the first route whose path *is* the credential**, and since 2026-09-16 that token is the
entire authentication factor rather than half of one. From that moment the first malformed body on a
portal path writes a live credential into a log file, and a token written to a log cannot be
un-logged. Read access to logs becomes read access to a hire's documents.

**Reachability:** not reachable today. Reachable on the day ERT-630 merges.

**Test coverage:** none.

**Disposition: already ticketed — ERT-1110**, correctly carrying a **hard gate before ERT-630**. This
audit confirms the gate and does not move it. Note the ticket's own framing is right: the deliverable
is the architecture guard that fails the build on a third call site, not the two-line fix.

---

### SEC-22 — Workflows that can deploy production use mutable action tags  [Medium]  [A03:2025]

**Where:** `.github/workflows/build.yml`, `deploy-uat.yml`, `deploy-prod.yml`

**What:** `actions/checkout@v4`, `google-github-actions/auth@v2`, `docker/build-push-action@v6`,
`gitleaks/gitleaks-action@v2` and others are referenced by tag. A tag is mutable.

**Impact:** whoever controls one of those repositories — or anyone who compromises it — can change
what the tag resolves to and execute arbitrary code **inside a job holding an OIDC token that can
impersonate a deployment service account**. `deploy-uat` can push to Artifact Registry and create a
Cloud Run revision; `deploy-prod` can move production traffic. The blast radius is the production
service and the data behind it.

**Reachability:** every workflow run.

**Test coverage:** n/a.

**Recommended fix:** full 40-character commit SHAs with the tag in a trailing comment, plus a
documented procedure or a renovation tool for moving a pin deliberately — a pin nobody can update is
abandoned rather than secure. The argument that settles it is internal: this repository already pins
its Kotlin toolchain to a version **and** a sha256 and refuses to run on a mismatch. A workflow
floating on `@v4` is a weaker link than the toolchain it guards.

**Ticket: ERT-1165.**

---

### SEC-23 — No dependency vulnerability scanning and no SBOM  [Medium]  [A03:2025]

**Where:** `.github/workflows/build.yml`; `libs.versions.toml`

**What:** CI runs the build, the suite and a secret scan. Nothing scans the 176 runtime dependencies
for known vulnerabilities, and no SBOM is produced. `deploy-uat.yml` calls
`gcloud artifacts docker images scan`, but it is `continue-on-error: true` and nobody is required to
read the result.

**Impact:** a vulnerable transitive dependency reaches production and stays there until somebody
happens to notice. There is no inventory to answer "are we affected?" when the next widely-exploited
library CVE lands — and for a system holding government IDs, birth certificates and medical results,
"we are not sure" is the expensive answer.

**Credit where due:** `libs.versions.toml` pins every version exactly — no `+`, no `latest.release`,
no snapshots — so the dependency set is at least *deterministic*. That is the prerequisite for
scanning, and it is already satisfied.

**Recommended fix:** OSV-Scanner or Dependency-Check in `build.yml`, failing on High and above with a
documented suppression file. Generate an SBOM at image build and attach it to the Artifact Registry
image. Make the existing image scan blocking on Critical once there is a baseline — not before, or a
base-image CVE disclosed overnight blocks an unrelated hotfix and trains people to bypass the gate.

**Ticket: ERT-1180.**

---

### SEC-24 — The audit trail is written but nothing reads it  [Medium]  [A09:2025]

**Where:** `src/data/repository/ExposedAuditLog.kt`; nine `audit.record` call sites across `src/`

**What:** sign-in failures, admin actions and permission changes are recorded in an append-only
table. Nothing alerts on any of them. The 2025 rename of this OWASP category is the point — *logs
nobody reads are not a control* — and the codebase itself leans on that unread control explicitly:
`AuthRoutes.kt:79` justifies the absence of rate limiting with *"until then the audit row is the
detection."*

**Impact:** a sustained password-guessing run against an HR account produces a perfect record and no
notification. Combined with SEC-19, the compensating control named in the code does not currently
compensate for anything.

**Reachability:** n/a — this is an absence.

**Recommended fix:** the pieces are already in place and cheap to connect. ERT-1250 made logs
structured JSON, so a Cloud Monitoring **log-based metric** over `SIGN_IN_FAILED` events with an
alerting policy is configuration rather than code. Two alerts are enough to start: a burst of
sign-in failures, and any `HR_ADMIN` action outside working hours. Note the constraint the audit
guard already enforces — `AuditEntryMapper.kt:85` refuses to persist credential-shaped metadata — so
an alert can safely carry the event without carrying a secret.

**Ticket: ERT-1185.**

---

### SEC-25 — The planned filesystem `DocumentStorage` adapter is unusable on Cloud Run  [High, design-time]  [A02:2025]

**Where:** `docs/backlog/ERT-700-document-upload.md` — ERT-710; `.env.example` `STORAGE_ROOT`

**What:** no adapter exists yet — `src/di/DataModule.kt:104` carries a TODO and the only
implementation is `test/testdata/fake/FakeDocumentStorage.kt`. The *next* storage ticket ships a
local-filesystem adapter under `STORAGE_ROOT`.

**Impact:** on Cloud Run every write outside the image layers goes to an **in-memory tmpfs charged
against the container memory limit**, with no eviction, and it is **per-instance**. A 10 MB upload
permanently consumes 10 MB of a 1 GiB budget until the instance recycles, so a day of uploads
OOM-kills it; and a document written by instance A is invisible to instance B, so the download that
follows an upload misses roughly `(N-1)/N` of the time. For this system that is silent loss of a
hire's birth certificate.

**Reachability:** not reachable — the adapter does not exist. Filed at High because the cost of
finding it after ERT-710 deploys is a data-loss incident, and PRD §14 Q20 already answered the
question (GCS with V4 signed URLs).

**Disposition: enforced on ERT-710**, which gained an acceptance criterion during this branch: the
binding must **refuse to start** when it is selected outside dev, the same shape as `JWT_SECRET` and
`TOKEN_PEPPER`. The check could not be written here because there is nothing yet to check.

---

### SEC-26 — A JWT cannot be revoked  [Medium — accepted, documented]  [A07:2025]

**Where:** `src/data/auth/JwtIssuer.kt:33-50`

**What:** the verifier never resolves `sub` against `users`. Deactivating an account or resetting a
password does not invalidate a live token; both take effect at the next issue, up to
`JWT_TTL_MINUTES` (default 60) later. No refresh endpoint, no denylist, no `jti`.

**Impact:** a 60-minute window in which a deactivated HR account retains full access.

**Disposition: accepted, and already reasoned in the code.** Recorded here so it appears in one
register rather than only in a KDoc. The trade is stated explicitly in `deploy/prod.env.yaml`:
`JWT_TTL_MINUTES` **is** the revocation window, and shortening it shortens the exposure at the cost
of more sign-ins. Revisit if an HR account is ever compromised in practice.

---

### SEC-27 — `TOKEN_PEPPER` cannot be rotated  [Medium]  [A04:2025]

**Where:** `src/data/crypto/HmacTokenDigest.kt:23-29`

**What:** the digest is deterministic by necessity — `findByTokenHash` resolves a link by its digest
against a unique index, so a salted hash cannot work. Rotating the pepper changes every digest, so
every live upload link and portal session stops resolving **at once**, and there is no re-issue flow.

**Impact:** the pepper is effectively permanent for the life of an environment. If it is ever
disclosed, the remedy is re-inviting every in-flight hire by hand, through the bulk path §8.2
deliberately makes slow.

**Disposition: already ticketed — ERT-1130** (two-pepper verification window, depends on ERT-1030).
Until it lands, `.env.example`, `docs/architecture.md` §14 and `docs/deployment.md` must all keep
saying the pepper is permanent. They do.

---

### SEC-28 — The bcrypt cost factor is not configurable  [Low]  [A04:2025]

**Where:** `src/data/crypto/BcryptHasher.kt` — `DEFAULT_COST = 12`, bound with no override at
`src/di/CoreModule.kt:28`

**What:** cost 12 is correct today and the KDoc says to raise it as hardware improves. Raising it
requires a code change, a build and a deploy.

**Impact:** none currently. It is a hardening gap: the parameter most likely to need changing in
three years is the one that cannot be changed without a release. Note the interaction with SEC-19 —
raising the cost makes the DoS *cheaper* for the attacker, so the two must be decided together.

**Recommended fix:** read it from the environment with 12 as the floor, not merely the default. A
configuration that can lower the cost is worse than one that cannot change it.

**Ticket: folded into ERT-1170**, which has to reason about the same number.

---

### SEC-29 — swagger-codegen wrote to a relative filesystem path at every boot  [Low]  [A02:2025]

**Where:** `src/plugin/ApiDocs.kt:75` (as it was)

**What:** `openAPI()` runs the generator and writes HTML to `build/openapi-docs`, a path relative to
the working directory, on every start.

**Impact:** in a container, a filesystem write from a non-root user on the cold-start path into a
tmpfs charged against the memory limit — a startup failure if the directory is not writable, and a
memory charge if it is. What it produced in exchange is HTML Ktor itself warns about on every boot,
since the spec is OpenAPI 3.1 and swagger-codegen supports 3.0.x.

**Disposition: Fixed — ERT-1240.** The generator is gated on dev; `/swagger` and the machine-readable
spec are unchanged and still behind HR authentication outside dev.

---

### SEC-30 — `deploy/*.env.yaml` ship with placeholder hostnames  [Info]  [A02:2025]

**Where:** `deploy/uat.env.yaml`, `deploy/prod.env.yaml`

**What:** `REPLACE_WITH_CLOUD_SQL_PRIVATE_IP`, `https://ert.example.com/`, and example CORS hosts.

**Impact:** none that is *insecure* — every one of them fails closed. A wrong `JWT_ISSUER` makes
every token fail verification; a wrong `CORS_ALLOWED_HOSTS` permits no cross-origin request; an
unreachable `DATABASE_URL` aborts startup. Recorded because `PORTAL_BASE_URL` is the exception worth
knowing about: it is not verified against anything, the invitation body is rendered at send time and
never persisted, so a wrong value cannot be corrected after the fact — the remedy is reissuing the
credential to every hire invited since the deploy.

**Recommended fix:** `grep -rn REPLACE deploy/` is in the runbook's first-deployment step. Keep it
there.

---

## Categories with no findings

- **A01 — Broken Access Control.** Every handler under `route/hr/` now opens with a gate, and
  `ArchitectureTest` fails the build on one that does not (SEC-16). Role separation is enforced in
  `hrAdminOrRefuse` with an audit row on refusal. No IDOR surface: the only path parameter is a user
  id, and every route that takes one is admin-gated. The portal's access-control surface does not
  exist yet — it is the subject of the 2026-09-09 audit and of ERT-600.
- **A05 — Injection.** No raw SQL anywhere. Every query is Exposed DSL with bound parameters;
  grepping `src/` for `.exec(`, string-built `"SELECT`, and `CustomFunction` returns nothing. No
  dynamic `ORDER BY` from a request parameter, no shell or process invocation, no server-rendered
  HTML from user input.
- **A08 — Software and Data Integrity.** Flyway's checksum validation is on (the default) and
  `baselineOnMigrate` is deliberately `false`, so an unexpected pre-existing schema is a hard
  failure rather than a silently adopted baseline. `clean` is never called. Production runs the image
  UAT ran, promoted **by digest**, on a registry with immutable tags — so what runs is verifiably
  what CI built. No polymorphic deserialization of untrusted input.
- **A10 — Mishandling of Exceptional Conditions.** No fail-open branch found. `StatusPages` has a
  catch-all that returns a constant body and leaks no stack trace. The one `runCatching` in `src/`
  (`OutboxNotifier.kt:265`) is documented and deliberate — it stops a failed outbox insert unwinding
  a completed hire creation — and it returns a typed failure rather than a success. `pwd_change`
  missing from a JWT reads as `true`, which fails closed.

---

## Task plan

Ordered by severity. TASK-11 is cheap and unblocks nothing else, but it is the only one whose absence
is actively exploited the day the service is public.

### TASK-11 — Rate-limit sign-in, and decide the bcrypt cost with it
- **Fixes:** SEC-19, SEC-28
- **Branch:** `feature/ert-1170-sign-in-rate-limit`
- **Ticket:** ERT-1170
- **Files:** `src/route/hr/AuthRoutes.kt`, `src/domain/usecase/AuthenticateHrUserUseCase.kt`,
  `src/data/crypto/BcryptHasher.kt`, `src/di/CoreModule.kt`
- **Business rule:** invariant 10 — an unknown email, a wrong password, a malformed address and a
  deactivated account are indistinguishable **in elapsed time as well as in body**. The fix must not
  weaken it.
- **Write this test first:** the eleventh sign-in attempt against one address within a minute is
  refused without a password verification being performed, and the refusal is indistinguishable from
  a wrong password. Currently every attempt verifies and none is refused — that is the bug.
- **Change:** a DB-backed attempt counter keyed on the address, following `countRecentFailures`.
  **Not** an in-memory limiter: the deployment is multi-instance (ERT-1120), so an in-memory limit is
  `configured × instances`. Make the bcrypt cost an environment value with 12 as a floor.
- **Done when:** the new tests pass, `AuthenticateHrUserUseCaseTest`'s timing-uniformity tests still
  pass, and `docs/deployment.md` records the Cloud Armor edge limit as the volumetric answer.

### TASK-12 — Pin every third-party action to a commit SHA
- **Fixes:** SEC-22
- **Branch:** `feature/ert-1165-pin-actions`
- **Ticket:** ERT-1165
- **Files:** `.github/workflows/build.yml`, `deploy-uat.yml`, `deploy-prod.yml`
- **Write this test first:** n/a — a CI change. The reviewable assertion is that no `uses:` line
  names a third-party action without a 40-character SHA.
- **Change:** SHA pins with the tag in a trailing comment; narrow each job's `permissions:` block to
  the minimum; document or automate how a pin gets moved.
- **Done when:** all three workflows still run green and every `uses:` line is pinned.

### TASK-13 — Security headers, HSTS, and a request body limit
- **Fixes:** SEC-20
- **Branch:** `feature/ert-1175-edge-hardening`
- **Ticket:** ERT-1175
- **Files:** `src/plugin/Http.kt`, `src/main.kt`, `src/Application.kt`
- **Write this test first:** a response to `GET /health` carries `X-Content-Type-Options: nosniff`
  and, outside dev, `Strict-Transport-Security`; and a request body above the configured limit is
  refused with 413 **without** being buffered. Both currently fail.
- **Change:** `install(DefaultHeaders)`, `install(HSTS)` gated on `APP_ENV != dev` — HSTS on
  `localhost` poisons a developer's browser for the whole origin — and an explicit body cap as a
  configuration value, since ERT-710 will need a different one for uploads.
- **Done when:** the new tests pass and `configureHttp` still takes its inputs as parameters.

### TASK-14 — Dependency and image scanning, with an SBOM
- **Fixes:** SEC-23
- **Branch:** `feature/ert-1180-supply-chain-scanning`
- **Ticket:** ERT-1180
- **Files:** `.github/workflows/build.yml`, `.github/workflows/deploy-uat.yml`
- **Change:** OSV-Scanner in `build.yml` failing on High and above with a documented suppression
  file; SBOM generated at image build and attached to the Artifact Registry image; make the existing
  image scan blocking on Critical **after** a baseline exists, not before.
- **Done when:** a deliberately vulnerable pinned dependency fails the build, and the suppression
  path is documented.

### TASK-15 — Alert on the audit trail that already exists
- **Fixes:** SEC-24
- **Branch:** `feature/ert-1185-alerting`
- **Ticket:** ERT-1185
- **Files:** `docs/deployment.md`; Cloud Monitoring configuration (no application code)
- **Change:** log-based metrics over the ERT-1250 JSON logs for `SIGN_IN_FAILED` and admin actions,
  with two alerting policies: a burst of sign-in failures, and any `HR_ADMIN` action outside working
  hours. Record the policies in the runbook so they are reviewable.
- **Done when:** a simulated burst of failed sign-ins in UAT raises an alert. **This task is what
  makes `AuthRoutes.kt`'s "the audit row is the detection" true**, and it should land with or before
  TASK-11.

### TASK-16 — Refuse the filesystem storage adapter outside dev
- **Fixes:** SEC-25
- **Branch:** with ERT-710
- **Ticket:** ERT-710 (acceptance criterion added 2026-09-17)
- **Write this test first:** binding the filesystem `DocumentStorage` with `APP_ENV != dev` refuses
  to start, naming the adapter. Cannot be written until the adapter exists — which is why this is a
  criterion on ERT-710 rather than a task that can start now.

---

## Dispositions

**Recorded 2026-09-17.** Four findings were fixed inside the branch that produced this audit, before
it was written — SEC-16 (ERT-1245), SEC-17 (ERT-1120), SEC-18 (ERT-1241) and SEC-29 (ERT-1240). They
appear here rather than being dropped, because a finding that is only visible in a commit message is
a finding nobody can review.

Two are **accepted with reasons already in the code**: SEC-26 (no token revocation; the TTL *is* the
revocation window) and SEC-27 (the pepper cannot be rotated; ERT-1130 owns the re-issue flow). Both
were accepted before this audit and neither is re-opened by it.

Six remain open and are ticketed: SEC-19 → ERT-1170, SEC-20 → ERT-1175, SEC-21 → ERT-1110 (gate
before ERT-630, unchanged), SEC-22 → ERT-1165, SEC-23 → ERT-1180, SEC-24 → ERT-1185. SEC-25 is a
criterion on ERT-710 and SEC-30 is informational.

**The one to act on before the service is public is SEC-19, and it should land with SEC-24.** The
code's own justification for having no rate limit is that the audit row provides detection; that is
only true once something reads the audit row.
