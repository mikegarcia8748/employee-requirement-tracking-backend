# ERT-200 · Epic: Test harness

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 0 |
| **Status** | Not started |
| **Depends on** | ERT-120 |
| **PRD** | — |
| **Architecture** | §10 |

**Description**

Architecture §10 says use-case tests are "the bulk of the suite" and that fakes, `FixedClock` and
builders live in `test/testdata/`. That directory is empty. `test/testdata/fake/` exists and contains
nothing, and MockK and `kotlinx-coroutines-test` are declared in
[module.yaml](../../module.yaml) and imported by no test.

Every Phase 1 use-case ticket is written test-first against in-memory ports. Without the harness,
each of those tickets would grow a bespoke fake, the fakes would disagree with each other, and the
first real repository would silently break tests that were passing against a fake with different
semantics. Building it once, first, is what makes the rest of Phase 1 a sequence of short sessions.

One constraint governs everything here and is easy to get wrong:

> **Amper 0.12.0 does not discover Kotest specs.** Both 5.9.1 and 6.0.3 were tried; a
> deliberately-failing `BehaviorSpec` was silently skipped under the default runner and found zero
> tests even when selected explicitly by class. An undiscovered spec is indistinguishable from a
> passing one. Use `kotlin.test` `@Test` with Kotest **assertions** and MockK, which work normally
> as libraries.

**Goal**

A use case can be constructed in a test from plain fakes with no Koin container, driven on a fixed
clock with predictable tokens, and asserted against — and a repository can be tested against real
SQL.

**Stories**
- As an engineer on the next session, I want ready fakes and builders so that a new use-case test
  costs three lines of setup rather than a file of scaffolding.
- As an engineer, I want fakes whose semantics match the real adapters so that a green use-case suite
  means something once the repositories land.

**Out of scope**
- Any production code. This epic touches `test/` only.
- Testcontainers. H2 in PostgreSQL mode is the target, matching `DatabaseConfig`'s own default.

---

## ERT-210 — In-memory fakes for the 10 domain ports

| | |
|---|---|
| **Parent** | ERT-200 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Not started |
| **Depends on** | — |
| **PRD** | §8.9, §8.12 |
| **Architecture** | §4, §10, §12 |

**Description**

One fake per port: the six in [`Repositories.kt`](../../src/domain/port/Repositories.kt), plus
`AuditLog`, `PortalAccessTrail`, `Notifier` and `DocumentStorage`. Backed by maps, recording what
they were asked to do so a test can assert on effects as well as returns.

Two fakes carry more weight than the rest and should be built to match the rule, not merely the
signature. `FakeNotifier` records each send so a test can assert that an invitation went out, that a
delivery failure did not lose the hire, and — structurally — that nothing but `sendInvitation` ever
saw a PIN. `FakePortalAccessTrail` is append-only with no mutation path at all, so a use case that
tries to overwrite an entry cannot compile against it.

Each fake also needs a failure mode. Delivery failure is a specified path in §8.1, not an edge case:
the hire is created regardless and HR gets a retry action.

**Goal**

Every domain port has an in-memory implementation with inspectable state and an injectable failure
mode.

**Stories**
- As an engineer on the next session, I want to assert "an invitation was sent to this address" in
  one line so that notification rules are cheap to test.

**Acceptance criteria**
- [ ] `[derived]` Given each of the 10 ports, then a fake exists implementing it fully
- [ ] `[derived]` Given `FakeNotifier`, then every call is recorded with its arguments, and a test can
      make any single send return `DeliveryResult.Failed`
- [ ] `[derived]` Given `FakePortalAccessTrail`, then it exposes no update or delete path, and
      `distinctIpsFor` and `countRecentFailures` behave as the real trail will
- [ ] `[derived]` Given `FakeUploadLinkRepository`, then lookup is by token **hash** only — there is
      no by-plaintext path, matching the port
- [ ] `[derived]` Given `FakeSubmissionRepository`, then `purgeBeyondRetention` and `totalBytesFor`
      behave per §7.1 so the storage-cap rule can be tested before real storage exists
- [ ] `[derived]` Given any fake, then it is safe to construct without a database, a container, or a
      dispatcher

**Tests**
| Level | Test |
|---|---|
| Use case | `fake notifier - a send is configured to fail - returns Failed and records the attempt` |
| Use case | `fake access trail - three attempts from two addresses - distinctIpsFor returns two` |
| Use case | `fake submission repository - six versions with retention of five - purges the oldest` |

**Files**
- create `test/testdata/fake/FakeEmployeeRepository.kt` and one file per remaining port

**Out of scope**
- Fakes for anything not a domain port. MockK covers one-off collaborators.

---

## ERT-220 — Deterministic `FixedClock` and id, token and PIN generators

| | |
|---|---|
| **Parent** | ERT-200 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Not started |
| **Depends on** | — |
| **PRD** | §6.4, §6.6 |
| **Architecture** | §2, §10 |

**Description**

Almost every Phase 1 rule is a time or randomness question: does this link expire, is the lockout
over, is the idle clock earlier than the ceiling, is this PIN right. None of that is testable against
`Instant.now()` and a `SecureRandom`.

`Clock`, `EntityIdGenerator`, `PersonIdGenerator`, `TokenGenerator` and `PinGenerator` are already
`fun interface`s in `core/`
and already bound in [CoreModule.kt](../../src/di/CoreModule.kt), so this is substitution, not
redesign. `FixedClock` needs to advance on demand — testing "lockout expires after 15 minutes"
requires moving time forward, not just pinning it.

This is also why `Dispatchers` is forbidden in the domain: a use case that picks its own dispatcher
cannot be driven on a virtual-time scheduler.

**Goal**

Time and randomness are inputs a test controls, so every expiry, lockout and credential rule is
deterministic.

**Stories**
- As an engineer on the next session, I want to advance the clock 91 days in one call so that the
  absolute-expiry rule is a two-line test.

**Acceptance criteria**
- [ ] `[derived]` Given `FixedClock`, then it returns a set instant and can be advanced by a
      `Duration`
- [ ] `[derived]` Given the deterministic generators, then ids, tokens and PINs are predictable and
      repeatable across runs
- [ ] `[derived]` Given the id generators, then there is one fake per port — a `PersonId` is 8
      characters and an `EntityId` is 12, and a fake returning the wrong width must not compile
      into the wrong slot
- [ ] `[derived]` Given `FixedPinGenerator`, then the PIN it returns satisfies `AccessPin`'s
      six-digit validation, so tests exercise the real value object
- [ ] `[derived]` Given a test needs collision behaviour, then a generator can be made to return the
      same value twice

> The collision criterion above is no longer hypothetical. A `PersonId` draws from 62^8, so a
> duplicate is a real if unlikely event at scale and the hire-creation insert must retry (ERT-400).
> That retry needs a generator that repeats on demand, which is what this ticket supplies.

**Tests**
| Level | Test |
|---|---|
| Use case | `fixed clock - advanced by fifteen minutes - reports the later instant` |
| Use case | `fixed pin generator - generates - satisfies AccessPin validation` |

**Files**
- create `test/testdata/FixedClock.kt`
- create `test/testdata/DeterministicGenerators.kt`

**Out of scope**
- Faking `Hasher`. Real bcrypt is used in use-case tests — slow but correct, and hashing is exactly
  what must not be stubbed in credential rules. If the suite becomes slow, lower the cost factor in
  tests rather than replacing the algorithm.

---

## ERT-230 — Domain test builders

| | |
|---|---|
| **Parent** | ERT-200 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Not started |
| **Depends on** | ERT-220 |
| **PRD** | §5, §6.1, §6.2, §6.3 |
| **Architecture** | §10 |

**Description**

[`Employee`](../../src/domain/model/Employee.kt) has 18 fields and
[`UploadLink`](../../src/domain/model/UploadLink.kt) has 15. Constructing either inline makes a test
about one rule read as a wall of irrelevant values, and the one field that matters disappears into
the noise.

Builders give every field a sensible default and let a test override only what it is about — so
`anEmployee(packetStatus = UNDER_REVIEW)` reads as the rule it is testing. Named shortcuts for the
common states (`anActiveLink()`, `anExpiredLink()`, `aLockedOutLink()`, `aSubmittedPacket()`) are
worth having because those states recur across the whole suite.

**Goal**

A test states only the field under test, and the resulting objects are valid.

**Stories**
- As an engineer on the next session, I want `anEmployee(packetStatus = COMPLETE)` so that the reader
  sees the rule instead of seventeen irrelevant defaults.

**Acceptance criteria**
- [ ] `[derived]` Given a builder for each domain model, then every parameter has a default and any
      one can be overridden by name
- [ ] `[derived]` Given `aRequirementSet(required = 5, approved = 2)`, then the progress arithmetic in
      [`RequirementSet`](../../src/domain/model/EmployeeRequirement.kt) reports the stated figures
- [ ] `[derived]` Given the named state shortcuts, then each produces an object consistent with the
      §6.1–6.3 tables — an expired link is not also `ACTIVE`
- [ ] `[derived]` Given builders are used with `FixedClock`, then timestamps derive from it rather
      than from `Instant.now()`

**Tests**
| Level | Test |
|---|---|
| Use case | `requirement set builder - five required with two approved - reports approval progress of two of five` |
| Use case | `requirement set builder - an optional requirement - is excluded from the denominator` |
| Use case | `link builder - an expired link - reports a status that does not open the portal` |

**Files**
- create `test/testdata/Builders.kt`

**Out of scope**
- Persisting built objects. Builders return domain models only.

---

## ERT-240 — Repository integration-test base against H2 in PostgreSQL mode

| | |
|---|---|
| **Parent** | ERT-200 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Not started |
| **Depends on** | ERT-120, ERT-130 |
| **PRD** | §11 |
| **Architecture** | §10 |

**Description**

Fakes prove a use case obeys its rules; they prove nothing about SQL. Repository tickets from ERT-310
onward each need a migrated database, a clean slate between tests, and the real seed data present.

H2 in PostgreSQL mode is the target rather than Testcontainers: it is already
[`DatabaseConfig`](../../src/data/db/DatabaseFactory.kt)'s default, needs no Docker, and keeps the
suite fast. The trade is that H2 in PostgreSQL mode is not Postgres — the drift test from ERT-120
catches schema divergence, but genuinely Postgres-specific behaviour will not surface here. Record
that limitation where a reader of a green suite will see it.

**Goal**

A repository test declares one base class and gets a migrated, seeded, isolated database.

**Stories**
- As an engineer on the next session, I want repository tests that run without Docker so that the
  suite stays fast enough to run on every change.

**Acceptance criteria**
- [ ] `[derived]` Given a repository test extends the base, then it runs against a migrated schema
      with ERT-130 seed data present
- [ ] `[derived]` Given two tests in the same class, then neither sees the other's rows
- [ ] `[derived]` Given the suite runs, then no test requires Docker or an external service
- [ ] `[derived]` Given the base, then it exposes the same `DatabaseFactory.transaction` entry point
      production uses, so tests exercise the real transaction path
- [ ] `[derived]` Given the base class documentation, then the H2-is-not-Postgres limitation is
      stated

**Tests**
| Level | Test |
|---|---|
| Repository | `integration base - a test writes a row - the next test does not see it` |
| Repository | `integration base - a fresh database - seed data from the migrations is present` |

**Files**
- create `test/data/RepositoryTestBase.kt`

**Out of scope**
- A Postgres CI job. Worth adding before launch; not a Phase 0 blocker.
