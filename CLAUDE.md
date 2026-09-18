# Employee Requirements Tracker — session guide

A Ktor backend that collects and validates pre-employment documents for new hires. HR creates a
hire, the hire receives a tokenized link plus a 6-digit PIN, uploads documents from a phone, reviews
and attests, and HR validates. See [docs/employee-requirements-tracker-prd_1.md](docs/employee-requirements-tracker-prd_1.md).

## Start here

1. Open [docs/roadmap.md](docs/roadmap.md) — it names the next ticket.
2. Open that ticket's epic file in [docs/backlog/](docs/backlog/). Work only that ticket.
3. Before you finish, set the ticket's **Status** — in its own block **and** on the board — and update
   the "Next ticket" line in the roadmap. Sub-tasks carry a Status row too.

## Build

This is an **Amper** project. There is no Gradle.

```bash
./kotlin build     # compile
./kotlin test      # run tests
./kotlin run       # start on :8080
```

## The dependency rule

Source dependencies point inward, always. `route/` `plugin/` `data/` → `domain/` → `core/`.

| Layer | May depend on | May **not** contain |
|---|---|---|
| `core/` | Kotlin/Java stdlib only | any framework type |
| `domain/` | `core/` | Ktor, Exposed, Koin, Hikari, kotlinx.serialization, `Dispatchers`, JDBC |
| `data/` | `domain/`, `core/`, any library | HTTP concerns |
| `route/` | `domain/`, `core/` | business decisions, direct `data/` access |
| `plugin/` | Ktor | domain rules |
| `di/` | everything | logic |

[test/ArchitectureTest.kt](test/ArchitectureTest.kt) walks `src/domain` and `src/core` and **fails
the build** on a forbidden import. `Dispatchers` is forbidden in the domain because a use case that
picks its own dispatcher cannot be driven on a virtual-time scheduler; dispatcher choice lives in
[DatabaseFactory](src/data/db/DatabaseFactory.kt).

Routes are thin: parse, call one use case, map the sealed result to a status code. A business
decision reachable only through a handler is a bug.

## Invariants — weakening any of these re-opens a closed audit finding

1. The portal returns document **status** — never content, a signed URL, or an original filename.
   `DocumentStorage.signedUrlFor` is HR-side only; no portal use case may depend on that port.
2. A valid link resolves to the holder's own checklist; every invalid, expired, suspended or revoked
   token yields one constant response. The **recovery** page discloses nothing before the PIN is
   verified — not a name, not a requirement count.
3. A wrong recovery PIN and an unrecognised address are **indistinguishable** in response and log
   outcome, as are an unknown token and an expired one.
4. Tokens and PINs are stored hashed. A PIN is **never emailed**: only the one-time HR recovery
   response may carry an `AccessPin`. No stored artefact holds a live PIN or plaintext token — the
   invitation body is rendered at send time and never persisted.
5. Locked-state upload rejection is server-side, via `RequirementStatus.employeeCanUpload`.
6. Requirement sets and `expiresAt` are **snapshotted at creation**, never read live.
7. Every portal access is an append-only row in `portal_access_logs`. There is no `last_accessed_at`.
8. No submission version is purged while `Employee.retentionFrozen` is true — which reads
   `AnomalyFlag.freezesRetention`, so a new flag must choose rather than inherit.
9. `COMPLETE` is not identity assurance. `originalsSightedAt` is separate and must stay separate.
10. An unknown email, a wrong password, a malformed address and a deactivated account are
    indistinguishable at sign-in — **in elapsed time as well as in body**, so every branch of
    `AuthenticateHrUserUseCase` verifies a password against *some* hash. `AppError.AuthenticationFailed`
    is a `data object` for the reason `Denied` is one. It is **HR-side only**; a portal failure keeps
    `Denied`. A `SIGN_IN_FAILED` audit row never names the account, even when one was found.

Full text and rationale: [docs/architecture.md](docs/architecture.md) §12.

## Tests

Test-first. Loop is red → green → refactor → **review**; the review step reads the use case against
its tests hunting for what the happy path hid, and each finding becomes a new failing test.

> **Verified constraint: Amper 0.12.0 does not discover Kotest specs.** A deliberately-failing
> `BehaviorSpec` was silently skipped under 5.9.1 and 6.0.3. Use `kotlin.test` (`@Test`, discovered
> by JUnit 5) with **Kotest assertions** (`shouldBe`). MockK is declared and, as of 2026-09-18, used
> by **no test** — reach for a fake before a mock; ERT-1140 decides whether it stays (HAR-07). Never
> `BehaviorSpec` or any
> other Kotest spec style — it will pass vacuously.

Name tests `<rule> - <scenario> - <outcome>`:

```
hire creation - email duplicates an active hire with no reason given - fails with ReasonRequired
```

A failing test should say which business rule broke without opening the file.

| Level | Where | Covers |
|---|---|---|
| Use case | `test/domain/usecase/` | every business rule, exhaustively — **where business rules are proven**, and where the suite's weight belongs as Phase 1 lands (14% of 689 when measured 2026-09-18; the suite is 845 today) |
| Plugin | `test/plugin/` | a startup rule, as a pure function — see `bootstrapDecision` |
| Repository | `test/data/repository/` | real SQL against H2 in PostgreSQL mode — and against **real PostgreSQL** in CI's second job (ERT-260). Locally, `ERT_TEST_DATABASE_URL` switches engines; H2 is the default and needs no Docker |
| Contract | `test/contract/` | one suite per domain port, run against **both** its fake and its adapter, so the two cannot disagree without the build saying so (ERT-250). A concrete class must be named `*Test` or JUnit's scan silently skips it — `ArchitectureTest` fails the build otherwise |
| Route | `test/route/` | wiring, status codes, serialization — never a decision |
| Architecture | `test/ArchitectureTest.kt` | the dependency rule, as a build failure |

Fakes, `FixedClock` and builders live in `test/testdata/`. **A fake must match its adapter**, and
`test/contract/` is what holds that — adding a port means adding a fake (the build fails otherwise)
and a contract suite beside the others.

## Conventions

- Use cases: one class, one `operator fun invoke`, returning `DomainResult<T>`. Failures are data
  (`AppError`), not exceptions.
- Every use case takes a `UseCaseTracer` and delegates: `invoke` is
  `tracer.trace("XUseCase") { execute(...) }` and the body lives in a private `execute`. The
  architecture test fails the build on a use case that forgets, or that traces under a copied name.
  A use case never logs directly — `org.slf4j` is banned in the inner layers.
- Repository bindings land in [di/DataModule.kt](src/di/DataModule.kt) **with the use case that needs
  them, or with the ticket that establishes the adapter** — never as throwing placeholders. An
  unbound port fails loudly at wiring time; `test/di/DataModuleTest.kt` resolves every bound port and
  keeps one unbound port in a tripwire so "resolves" cannot become "resolves anything". Use cases are
  bound in [di/DomainModule.kt](src/di/DomainModule.kt) as `factory`; adapters and config are `single`.
- A plugin that needs a value **takes it as a parameter** rather than reading the container or the
  environment — `configureRouting(authName)`, `configureSecurity(jwt)`, `configureHrBootstrap(...)`.
  `Application.kt` supplies them. A plugin that reaches for its own inputs cannot be assembled in a
  test, which is what kept "requires HR auth" untestable until ERT-190.
- **HR auth in a test:** `testdata/HrTokens.kt` mints a token the application accepts, through the
  real `JwtIssuer`. Mount `configureSecurity(testJwtConfig())` and use `authenticatedAs(anHrUser())`.
  Tokens must be issued at `Instant.now()`, **not** `FixedClock.DEFAULT` — a JWT's `exp` is checked
  against the real system clock, which no injected `Clock` reaches. This is the one documented
  boundary of the fixed-clock rule.
- The OpenAPI spec is generated from the live route tree. Attach detail with `describe { }` beside
  the handler; never hand-edit a spec file. A route that reads a body declares `requestBody { }` and
  a route that answers with one declares `responses { schema = … }` — the generator infers neither,
  and `ArchitectureTest` fails the build on a handler that receives without publishing.
- When documenting portal routes, present the deliberate behaviours as **intended** — identical
  failures for wrong PIN vs unknown token, constant response from `request-new-link`. Otherwise a
  future reader "fixes" them into an enumeration oracle.
