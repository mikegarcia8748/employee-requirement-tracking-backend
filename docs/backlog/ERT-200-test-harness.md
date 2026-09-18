# ERT-200 · Epic: Test harness

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 0 |
| **Status** | In progress |
| **Depends on** | ERT-120 |
| **PRD** | — |
| **Architecture** | §10 |

> **Reopened 2026-09-18**, on ERT-146's precedent. ERT-210–240 are Done and stay Done; the
> [ERT-100/ERT-200 review](../2026-09-18-ert-100-200-review.md) found that nothing keeps a fake and
> its adapter in step (six live divergences, HAR-01), and that the Postgres CI job ERT-240 deferred
> was deferred to nobody. **ERT-250** and **ERT-260** are those two.

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
| **Status** | Done |
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
- [x] `[derived]` Given each of the 10 ports, then a fake exists implementing it fully
      — **there are now 13** (`HrUserRepository`, `ReferenceDataRepository` and `AccessTokenIssuer`
      arrived after this was written) and 13 fakes exist. The count is corrected here rather than
      rewritten, because C9 closed this same drift once as a documentation fix and it drifted again:
      a count in prose has nothing holding it. **ERT-250 adds the guard** *(2026-09-18)*
- [x] `[derived]` Given `FakeNotifier`, then every call is recorded with its arguments, and a test can
      make any single send return `DeliveryResult.Failed`
- [x] `[derived]` Given `FakePortalAccessTrail`, then it exposes no update or delete path, and
      `distinctIpsFor` and `countRecentFailures` behave as the real trail will
- [x] `[derived]` Given `FakeUploadLinkRepository`, then lookup is by token **hash** only — there is
      no by-plaintext path, matching the port
- [x] `[derived]` Given `FakeSubmissionRepository`, then `purgeBeyondRetention` and `totalBytesFor`
      behave per §7.1 so the storage-cap rule can be tested before real storage exists
- [x] `[derived]` Given any fake, then it is safe to construct without a database, a container, or a
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
| **Status** | Done |
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
- [x] `[derived]` Given `FixedClock`, then it returns a set instant and can be advanced by a
      `Duration`
- [x] `[derived]` Given the deterministic generators, then ids, tokens and PINs are predictable and
      repeatable across runs
- [x] `[derived]` Given the id generators, then there is one fake per port — a `PersonId` is 8
      characters and an `EntityId` is 12, and a fake returning the wrong width must not compile
      into the wrong slot
- [x] `[derived]` Given `FixedPinGenerator`, then the PIN it returns satisfies `AccessPin`'s
      six-digit validation, so tests exercise the real value object
- [x] `[derived]` Given a test needs collision behaviour, then a generator can be made to return the
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
| **Status** | Done |
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
- [x] `[derived]` Given a builder for each domain model, then every parameter has a default and any
      one can be overridden by name
- [x] `[derived]` Given `aRequirementSet(required = 5, approved = 2)`, then the progress arithmetic in
      [`RequirementSet`](../../src/domain/model/EmployeeRequirement.kt) reports the stated figures
- [x] `[derived]` Given the named state shortcuts, then each produces an object consistent with the
      §6.1–6.3 tables — an expired link is not also `ACTIVE`
- [x] `[derived]` Given builders are used with `FixedClock`, then timestamps derive from it rather
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
| **Status** | Done |
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
- [x] `[derived]` Given a repository test extends the base, then it runs against a migrated schema
      with ERT-130 seed data present
- [x] `[derived]` Given two tests in the same class, then neither sees the other's rows
- [x] `[derived]` Given the suite runs, then no test requires Docker or an external service
- [x] `[derived]` Given the base, then it exposes the same `DatabaseFactory.transaction` entry point
      production uses, so tests exercise the real transaction path
- [x] `[derived]` Given the base class documentation, then the H2-is-not-Postgres limitation is
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
  **It is now ERT-260** *(2026-09-18)*. This line sat here for five epics with no number, which is
  the roadmap's own E5/E6/C22 lesson — a gap without a number is invisible — applied to everything in
  this project except its test harness.

---

## ERT-250 — Contract tests binding each port's fake to its adapter

| | |
|---|---|
| **Parent** | ERT-200 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-210 |
| **PRD** | — |
| **Architecture** | §10, §11 |

**Description**

The correctness argument for the entire use-case suite is that a fake and its adapter agree.
`ExposedUploadLinkRepository` states it outright: a use case that passes against fakes and behaves
differently against SQL is the failure this harness exists to prevent.

**Nothing enforces it.** There is no shared contract test, no guard, and no mechanism at all beyond
whoever last touched an adapter remembering to touch its fake. Six divergences are live today, each
verified in both files by the [2026-09-18 review](../2026-09-18-ert-100-200-review.md) (HAR-01):

| | Fake | Adapter |
|---|---|---|
| a | `FakeEmployeeRepository.findActiveByEmail` — no ordering | `orderBy(createdAt ASC, id ASC)`, with a test added **in review** because SEC-11's duplicate list must not reshuffle between two reads |
| b | `FakeHrUserRepository.save` — a plain map put | `users_email_unique` + `users_email_is_lowercase`, with three tests asserting they bite |
| c | `FakeHrUserRepository.findByEmail` — `firstOrNull` | `singleOrNull()`, which returns **null** when more than one row matches |
| d | `FakeAuditLog.findFor` — no ordering | `orderBy(timestamp ASC, id ASC)`, with a test |
| e | `FakeUploadLinkRepository.given` — a plain map put | the unique index — and the fake's own constructor **and** `save` both refuse a duplicate `token_hash` and explain why. `given` is the unguarded third door |
| f | `FakeUploadLinkRepository.findActiveForEmployee` — `maxByOrNull` | `orderBy(issuedAt DESC, id DESC).limit(1)`, with the tiebreak named in the KDoc |

**(b) and (c) compose into the sharpest one.** The fake permits two accounts sharing an address and
then signs the first of them in; the adapter finds two rows and returns **null**. A fake-backed
sign-in test can be green in a state where production refuses every sign-in for that address.
`CreateHrUserUseCase` guards duplicates and says it is belt-and-braces with the index — but that
guard is the thing under test, and removing it would leave `HrUserAdministrationTest` green.

**(f) is the one most likely to fire next, for a reason particular to this harness.** Every builder
defaults `issuedAt` to `FixedClock.DEFAULT`, so two links issued in the same instant is not an edge
case here — it is what you get unless a test goes out of its way. `FakesTest`'s ordering test sets
them a day apart and so never reaches the tie the adapter's KDoc exists to describe.

**Two more findings belong in the same change**, because they have the same cause:

- **`FakeNotifier` models a failure mode `OutboxNotifier` structurally cannot produce** (HAR-02).
  `OutboxNotifier.queue` wraps the whole write in `runCatching`, so every failure becomes
  `DeliveryResult.Failed` and none becomes a throw — deliberately, because §8.1 requires the hire to
  survive a delivery failure. `FakeNotifierTest` asserts an `IllegalStateException` path anyway, and
  `FakeFailure`'s own KDoc warns against exactly this mistake in the mirror direction.
- **ERT-210's criterion counts 10 ports, there are 13, and `FakesTest` constructs 11** (HAR-03).
  `FakeHrUserRepository` and `FakeAccessTokenIssuer` are absent and both have zero direct tests —
  and `FakeHrUserRepository` is where two of the three worst divergences live. **C9 closed this exact
  drift once already**, as a documentation correction; it drifted again, because a count in prose has
  nothing holding it.

**Patching the six is not the deliverable.** It leaves the seventh to whoever writes the next
adapter, which is precisely how these six arrived. ERT-146 and ERT-1245 both settled the same
argument the same way: the guard is the deliverable, not the fix.

**Goal**

A fake and its adapter cannot disagree without the build saying so, and a port cannot be added
without a fake.

**Stories**
- As an engineer on the next session, I want a green use-case suite to mean the same thing against
  SQL that it means against fakes, so that "it passed" is worth something.
- As an engineer adding the eighth adapter, I want a contract suite already pointed at my port, so
  that matching the fake is a red test rather than a habit.

**Acceptance criteria**
- [ ] `[derived]` Given each port, then one suite of contract tests runs against both its fake and
      its adapter, asserting ordering, uniqueness and not-found semantics
- [ ] `[derived]` Given the six divergences above, then each is closed by a contract test that failed
      first
- [ ] Given `FakeHrUserRepository`, then a second account on the same address is refused, and
      `findByEmail` answers the way `singleOrNull` does
- [ ] Given `FakeUploadLinkRepository.given`, then it refuses a duplicate `token_hash` the way the
      constructor and `save` already do
- [ ] `[derived]` Given `FakeNotifier`, then its failure mode is `DeliveryResult.Failed` only, with
      the reason recorded in its KDoc
- [ ] `[derived]` Given an `interface` under `src/domain/port/` with no `Fake<Name>.kt` beside the
      others, then the build fails — with the anti-vacuity assertion the file's other guards use
- [ ] `[derived]` Given the four ports with no adapter yet, then their suite runs against the fake
      alone, so ERT-610, ERT-620 and ERT-720 bind into a harness that already exists

**Tests**
| Level | Test |
|---|---|
| Repository | `employee contract - two active hires on one address - both implementations order by creation then id` |
| Repository | `hr user contract - a second account on a stored address - both implementations refuse it` |
| Repository | `hr user contract - a lookup on an address held twice - both implementations answer the same` |
| Repository | `upload link contract - two links issued in the same instant - both implementations break the tie by id` |
| Repository | `audit contract - several entries for one entity - both implementations order chronologically` |
| Use case | `fake notifier - a send that fails - returns Failed and never throws` |
| Architecture | `port coverage - every domain port - has a fake` |
| Architecture | `port coverage - a port with no fake - the build fails` |
| Architecture | `guard integrity - the port directory is populated - the guard reports checked` |

**Files**
- create `test/contract/` — one suite per port, parameterised over `(fake, adapter)`
- modify `test/testdata/fake/FakeEmployeeRepository.kt`, `FakeHrUserRepository.kt`,
  `FakeAuditLog.kt`, `FakeUploadLinkRepository.kt`, `FakeNotifier.kt`
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt) — the port-coverage guard
- modify [`test/testdata/fake/FakesTest.kt`](../../test/testdata/fake/FakesTest.kt) — 11 → 13

**Out of scope**
- **Making every fake mirror its adapter.** Three divergences are deliberate and argued in their
  KDocs, and the contract suite must not "fix" them: `FakeAppSettingsRepository` does **not** enforce
  bounds, because a fake that filtered them would accept a bounds bug in the adapter without
  complaint; `FakeSubmissionRepository` purges whenever asked, because the retention freeze belongs
  to the use case; and `FakeDocumentStorage.signedUrlFor` throws on an unstored key, which is
  stricter than object storage on purpose.
- The `PortalSession` model/port gap. That is a design decision, and it belongs to **ERT-620**.

> **HAR-02's second half is not this ticket's.** `FakeNotifier` records a failed attempt and exposes
> it as `failed`; `OutboxNotifier` returns `Failed` and writes **nothing**, because the insert is what
> failed — so §8.1's delivery-failure indicator, which `NotificationOutbox`'s KDoc says is derived
> from the latest row, cannot see a queue-insert failure at all. **ERT-434 owns that question**, and
> its block now carries it.

---

## ERT-260 — A PostgreSQL CI job, and a migration applied to a database with data

| | |
|---|---|
| **Parent** | ERT-200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Not started |
| **Depends on** | ERT-240, ERT-1160 |
| **PRD** | §11 |
| **Architecture** | §10, §13 |

**Description**

ERT-240 chose H2 in PostgreSQL mode, argued it honestly, and deferred the real thing:

> *"A Postgres CI job. Worth adding before launch; not a Phase 0 blocker."*

**With no ticket number.** That is the exact pattern the roadmap names as the lesson of E5, E6 and
C22 — *a gap without a number is invisible* — applied to everything in this project except its own
test harness. This ticket is the number.

CI today runs `./kotlin build`, `./kotlin test`, a Java-21 bytecode check, a secret scan and a
container smoke test, **all on H2**. PostgreSQL appears nowhere. Three instances are already latent
rather than hypothetical:

- **Collation.** Every fake orders text with Kotlin's `String.compareTo`; every adapter orders with
  the database's collation. Identical under H2, divergent under a PostgreSQL locale that weights case
  and punctuation differently. `ExposedRequirementTemplateRepository` states this caveat and argues
  it cannot fire, because `sort_order` runs 1..14 with no ties. The same caveat is **not** stated for
  `EmployeeRequirements.nameSnapshot`, where `sort_order_snapshot` carries `default(0)` — so ties
  there are ordinary, and the tiebreak that decides a hire's checklist order is the one nobody
  argued about.
- **Expression indexes.** H2 rejects them, which is why `V4__hr_users.sql` uses a `CHECK` plus a
  plain `UNIQUE`. `MigrationTest`'s own comment says the portability sweep would not have caught it:
  *"it is on neither list… this is a proxy and the H2 run is what actually found that one."*
- **Identifier widths.** ERT-180 records that the drift test cannot see them at all, because H2
  reports every `VARCHAR(n)` as equivalent to every `VARCHAR(m)`.

**The second half of this ticket is a different blindness in the same file.** Every one of
`MigrationTest`'s thirteen tests runs against a **fresh** database. The idempotence test proves the
*runner* is idempotent; the drift test proves the *destination* matches `Tables.kt`. Nothing migrates
a database that already holds rows — so `V4__hr_users.sql`, which drops and re-adds four columns, one
of them `not null` with no default, shipped green. Against a populated table that is either a hard
failure or a silent discard of four actor columns. **Every acceptance criterion ERT-120 wrote is
about a fresh database**, so this class of defect ships green by construction, and ERT-1220/1230 made
"a database with rows in it" a real place.

**Goal**

The repository suite runs against real PostgreSQL in CI, and a migration that would destroy data
fails the build instead of shipping.

**Stories**
- As an engineer, I want "H2 in PostgreSQL mode is not PostgreSQL" to be a job rather than a sentence
  in a KDoc, so that the gap it names is checked rather than remembered.
- As an operator, I want a migration applied to a populated UAT database to have been applied to a
  populated database at least once before.

**Acceptance criteria**
- [ ] `[derived]` Given a push, then the repository tests run a second time against a real PostgreSQL
      service container
- [ ] `[derived]` Given a local run with no Docker, then H2 stays the default and the suite is
      unchanged — this is an additional job, not a replacement
- [ ] Given migrations to V3 and then a seeded `employees` and `app_settings` row, when the remaining
      migrations run, then the rows survive with their values
- [ ] `[derived]` Given `V4`, then it either preserves the four actor columns or carries a documented
      exemption saying the destruction was deliberate against an empty database
- [ ] `[derived]` Given the PostgreSQL job, then a collation-sensitive ordering test exists for
      `employee_requirements.name_snapshot`, where `sort_order_snapshot` defaults to 0 and ties are
      ordinary

**Tests**
| Level | Test |
|---|---|
| Repository | `schema migration - a database holding rows - the rows survive the remaining migrations` |
| Repository | `requirement ordering - a sort order tie - resolves the same on both engines` |
| CI | the repository suite, green against PostgreSQL |

**Files**
- modify [`.github/workflows/build.yml`](../../.github/workflows/build.yml) — a service container and
  a second job
- modify [`test/data/RepositoryTestBase.kt`](../../test/data/RepositoryTestBase.kt) — a
  `DATABASE_URL` path, with H2 as the default
- modify [`test/data/db/MigrationTest.kt`](../../test/data/db/MigrationTest.kt) — the
  populated-database test

**Out of scope**
- Testcontainers. A GitHub Actions service container needs no library and no Docker on a developer's
  machine, which is what ERT-240's trade was actually protecting.
- Moving the default. H2 stays the local and default engine; the point is a second opinion, not a
  replacement.
