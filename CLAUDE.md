# Employee Requirements Tracker — session guide

A Ktor backend that collects and validates pre-employment documents for new hires. HR creates a
hire, the hire receives a tokenized link plus a 6-digit PIN, uploads documents from a phone, reviews
and attests, and HR validates. See [docs/employee-requirements-tracker-prd_1.md](docs/employee-requirements-tracker-prd_1.md).

## Start here

1. Open [docs/roadmap.md](docs/roadmap.md) — it names the next ticket.
2. Open that ticket's epic file in [docs/backlog/](docs/backlog/). Work only that ticket.
3. Before you finish, set the ticket's **Status** and update the "Next ticket" line in the roadmap.

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
2. A bare link resolves to a PIN prompt and nothing else — not a name, not a requirement count.
3. A wrong PIN and an unknown token are **indistinguishable** in both response and log outcome.
4. Tokens and PINs are stored hashed. Only `Notifier.sendInvitation` may carry an `AccessPin`.
5. Locked-state upload rejection is server-side, via `RequirementStatus.employeeCanUpload`.
6. Requirement sets and `expiresAt` are **snapshotted at creation**, never read live.
7. Every portal access is an append-only row in `portal_access_logs`. There is no `last_accessed_at`.
8. No submission version is purged while `Employee.retentionFrozen` is true.
9. `COMPLETE` is not identity assurance. `originalsSightedAt` is separate and must stay separate.

Full text and rationale: [docs/architecture.md](docs/architecture.md) §12.

## Tests

Test-first. Loop is red → green → refactor → **review**; the review step reads the use case against
its tests hunting for what the happy path hid, and each finding becomes a new failing test.

> **Verified constraint: Amper 0.12.0 does not discover Kotest specs.** A deliberately-failing
> `BehaviorSpec` was silently skipped under 5.9.1 and 6.0.3. Use `kotlin.test` (`@Test`, discovered
> by JUnit 5) with **Kotest assertions** (`shouldBe`) and **MockK**. Never `BehaviorSpec` or any
> other Kotest spec style — it will pass vacuously.

Name tests `<rule> - <scenario> - <outcome>`:

```
hire creation - email duplicates an active hire with no reason given - fails with DuplicateEmailRequiresReason
```

A failing test should say which business rule broke without opening the file.

| Level | Where | Covers |
|---|---|---|
| Use case | `test/domain/usecase/` | every business rule, exhaustively — the bulk of the suite |
| Repository | `test/data/repository/` | real SQL against H2 in PostgreSQL mode |
| Route | `test/route/` | wiring, status codes, serialization — never a decision |
| Architecture | `test/ArchitectureTest.kt` | the dependency rule, as a build failure |

Fakes, `FixedClock` and builders live in `test/testdata/`.

## Conventions

- Use cases: one class, one `operator fun invoke`, returning `DomainResult<T>`. Failures are data
  (`AppError`), not exceptions.
- Repository bindings land in [di/DataModule.kt](src/di/DataModule.kt) **with the use case that needs
  them** — never as throwing placeholders. An unbound port fails loudly at wiring time.
- The OpenAPI spec is generated from the live route tree. Attach detail with `describe { }` beside
  the handler; never hand-edit a spec file.
- When documenting portal routes, present the deliberate behaviours as **intended** — identical
  failures for wrong PIN vs unknown token, constant response from `request-new-link`. Otherwise a
  future reader "fixes" them into an enumeration oracle.
