# ERT-100 · Epic: Runtime foundations

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §6.4, §8.10, §11 |
| **Architecture** | §7, §11, §14 |

**Description**

The application boots without a database. [`DatabaseFactory.connect()`](../../src/data/db/DatabaseFactory.kt)
is never called from anywhere, `allTables` at [Tables.kt:185](../../src/data/db/table/Tables.kt)
is referenced by nothing, and there is no migration tooling in `module.yaml` or
`libs.versions.toml`. The Exposed table definitions are therefore decorative: no schema is ever
created, so the first repository written would fail at runtime rather than at wiring time.

Two smaller gaps sit alongside. [`StatusPages.kt`](../../src/plugin/StatusPages.kt) maps only
`Throwable` to a generic 500, so a route has no way to turn an `AppError` into a status code — every
Phase 1 route needs that mapping before it can return a domain failure. And
[`Monitoring.kt`](../../src/plugin/Monitoring.kt) collects a Prometheus registry that no route
exposes, so metrics are gathered and unreachable.

Two further items belong here rather than in Phase 1, because both are cheap now and expensive
after the first slice lands. [`BcryptHasher`](../../src/data/crypto/BcryptHasher.kt) is salted, so
the same token hashes differently every time — yet
[`UploadLinkRepository.findByTokenHash`](../../src/domain/port/Repositories.kt) and
`UploadLinks.tokenHash.uniqueIndex()` both require a **deterministic** digest. As written, no link
could ever be resolved (ERT-160). And the write-mostly rule has no mechanical guard, so the first
portal DTO written without one decides the question by accident (ERT-170).

**Goal**

The application connects to a database on start, migrates its schema, has reference data and policy
defaults to read, can express a domain failure as an HTTP status, can be scraped, resolves a link by
a deterministic token digest, and fails the build if a portal DTO leaks document content.

**Stories**
- As an engineer on the next session, I want the schema to exist when the app starts so that I can
  write a repository without first inventing a bootstrap.
- As an HR Admin, I want the §6.4 durations to come from the database so that changing one does not
  need a deployment.

**Out of scope**
- Any repository implementation, use case, or business endpoint.
- Replacing the placeholder JWT scheme (gated on Q4).

---

## ERT-110 — Wire `DatabaseFactory` into the application lifecycle

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §11 |
| **Architecture** | §3, §11, §14 |

**Description**

`DatabaseFactory` is bound in [DataModule.kt:17](../../src/di/DataModule.kt) as a Koin `single`,
which is lazy. Nothing ever resolves it, so no pool is opened and `lateinit var database` stays
uninitialised. The class itself is correct — Hikari with `isAutoCommit = false`,
`TRANSACTION_READ_COMMITTED`, and the `Dispatchers.IO` hop confined to `transaction` so the domain
never sees a dispatcher. It simply is not called.

The connection must open on application start and close on stop. Ktor's `ApplicationStarted` /
`ApplicationStopping` monitor events are the hook; `rootModule()` gains one `configureDatabase()`
call after `configureKoin()`, since the factory comes from the container.

**Goal**

`./kotlin run` opens a connection pool on boot and closes it cleanly on shutdown; a failure to
connect stops startup loudly rather than surfacing on the first query.

**Stories**
- As an engineer on the next session, I want a live `Database` by the time routes mount so that a
  repository call works without per-call connection handling.
- As an operator, I want a bad `DATABASE_URL` to fail at startup so that the service does not accept
  traffic it cannot serve.

**Acceptance criteria**
- [x] `[derived]` Given the application starts, then `DatabaseFactory.connect()` has run before any
      route handles a request
- [x] `[derived]` Given the application stops, then the Hikari pool is closed
- [x] `[derived]` Given `DATABASE_URL` points at an unreachable server, then startup fails with the
      underlying cause, and the server does not begin listening
- [x] `[derived]` Given no `DATABASE_URL` is set, then the in-memory H2 default is used, so a fresh
      checkout and the test suite need no external service

**Tests**
| Level | Test |
|---|---|
| Route | `application startup - no DATABASE_URL set - connects to the in-memory default and health responds` |
| Route | `application shutdown - server stops - the connection pool is closed` |

**Files**
- create `src/plugin/Database.kt` — `Application.configureDatabase()` using `monitor.subscribe`
- modify [`src/Application.kt`](../../src/Application.kt) — call it after `configureKoin()`
- modify [`test/ServerTest.kt`](../../test/ServerTest.kt) — assert the lifecycle

**Out of scope**
- Schema creation — that is ERT-120.
- Any change to `DatabaseFactory` itself; it is already correct.

---

## ERT-120 — Flyway baseline migration for the 12 tables, plus a schema-drift test

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-110 |
| **PRD** | §11 |
| **Architecture** | §7, §13 |

**Description**

There is no migration tooling of any kind. Flyway with plain versioned SQL is the choice: Postgres is
the production target and the dev and test default is H2 in PostgreSQL mode, so one dialect serves
both. `SchemaUtils.createMissingTablesAndColumns` was rejected — it keeps no version history and
cannot express a data migration, and architecture §13 promises that Phase 4 "costs a migration, not
a redesign", which only holds if migrations exist.

Making SQL the source of truth creates a drift risk against [Tables.kt](../../src/data/db/table/Tables.kt).
Exposed can answer that question directly: after migration,
`statementsRequiredToActualizeScheme(*allTables)` must return empty. That test is what keeps the two
definitions honest, and it gives `allTables` its first real use.

Two details the schema must preserve, both load-bearing:

- `portal_access_logs` is **append-only**. Nothing updates or deletes rows there. There is
  deliberately no `last_accessed_at` column on `upload_links` — a single overwritten timestamp
  cannot answer who, from where, or how often, which is the first question asked when a fraudulent
  submission surfaces (SEC-05).
- `upload_links.token_hash` carries a unique index and is the only lookup path. The plaintext token
  exists solely in the invitation email.

**Goal**

A fresh database reaches the full 12-table schema by running migrations, and CI fails if
`Tables.kt` and the SQL disagree.

**Stories**
- As an engineer on the next session, I want a versioned schema so that I can add a column in Phase 2
  without hand-editing anyone's database.
- As an engineer, I want drift between the Exposed definitions and the SQL to fail the build so that
  the two cannot quietly diverge.

**Acceptance criteria**
- [x] `[derived]` Given an empty database, when the application starts, then all 12 tables in
      `allTables` exist
- [x] `[derived]` Given `portal_sessions`, then it carries a unique `token_hash` column. The table as
      defined today has none, so a session cookie would have to carry the primary key — storing live
      session bearer tokens in plaintext. Adding the column now is free; adding it later is a
      migration plus a forced logout of everyone mid-upload.
- [x] `[derived]` Given migrations have already run, when the application starts again, then no
      migration is re-applied and startup succeeds
- [x] `[derived]` Given migrations have run, then
      `statementsRequiredToActualizeScheme(*allTables)` is empty
- [x] `[derived]` Given the same migration SQL, then it applies cleanly on both H2 in PostgreSQL mode
      and PostgreSQL
- [x] Given the schema, then `upload_links` has no `last_accessed_at` column (§11, SEC-05)
- [x] `[derived]` Given the schema, then `upload_links.token_hash` is uniquely indexed

**Tests**
| Level | Test |
|---|---|
| Repository | `schema migration - a fresh database - creates every table in allTables` |
| Repository | `schema migration - run twice - applies nothing the second time` |
| Repository | `schema drift - migrations have run - Exposed reports no pending statements` |
| Repository | `access trail schema - upload_links - has no last_accessed_at column` |

**Files**
- modify [`libs.versions.toml`](../../libs.versions.toml) — add `flyway-core`, and
  `flyway-database-postgresql`
- modify [`module.yaml`](../../module.yaml) — declare them
- create `resources/db/migration/V1__baseline.sql` — the 12 tables, indexes and foreign keys
- modify `src/plugin/Database.kt` — run Flyway before the pool is handed out
- create `test/data/db/MigrationTest.kt`

**Out of scope**
- Seed data — that is ERT-130.
- Removing the unused `exposed-r2dbc` and `h2database-r2dbc` dependencies. Harmless, and not this
  ticket's business.

---

## ERT-130 — Seed reference data and `app_setting` defaults with bounds

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-120 |
| **PRD** | §6.4, §8.10, §8.11, Appendix A |
| **Architecture** | §4 |

**Description**

`AppSettingsRepository.linkPolicy()` reads the §6.4 policy from `app_setting` at runtime, and
`CreateHireUseCase` computes `expiresAt` from it. With an empty table there is nothing to read, so
the first hire cannot be created. The nine defaults in
[`LinkPolicy`](../../src/domain/model/LinkPolicy.kt) are the seed values, and each row carries its
own `min_value` / `max_value` so the bounds live with the data rather than in a validator someone
forgets to call — §6.4 is explicit that a well-meant edit must not be able to turn a token into a
permanent credential.

Departments, employment types and the requirement catalogue also need starting rows. The catalogue
is **Appendix A, which the PRD marks illustrative pending Q2** — seed it, mark it clearly, and keep
it replaceable as data rather than code.

**Goal**

A migrated database has a readable link policy with enforced bounds, and enough reference data for a
hire to be created and assigned a requirement set.

**Stories**
- As an HR Admin, I want the §6.4 durations stored as data so that changing one does not need a
  deployment.
- As an engineer on the next session, I want a seeded catalogue so that I can create a hire and watch
  a requirement set snapshot without inventing fixtures.

**Acceptance criteria**
- [x] `[derived]` Given a migrated database, then every `LinkPolicy` field has a corresponding
      `app_setting` row carrying its default, type, and min/max
- [x] Given `link.absolute_expiry_days`, then its stored bounds are 7 to 180 (§6.4)
- [x] `[derived]` Given the seed runs twice, then it is idempotent and overwrites nothing an admin
      has since changed
- [x] `[derived]` Given the seed, then the Appendix A catalogue exists with `is_required`, `expires`
      and `sort_order` populated, and is recorded in the migration as illustrative pending Q2
- [x] `[derived]` Given the seed, then at least one department and each employment type exist, with a
      `template_assignment` row set per employment type

**Tests**
| Level | Test |
|---|---|
| Repository | `policy seed - a migrated database - every LinkPolicy field has a settings row` |
| Repository | `policy seed - absolute expiry bounds - are stored as 7 to 180` |
| Repository | `policy seed - applied twice - does not overwrite an admin-changed value` |
| Repository | `catalogue seed - an employment type - resolves to a non-empty template set` |

**Files**
- create `resources/db/migration/V2__reference_data.sql`
- create `resources/db/migration/V3__app_settings.sql`

**Out of scope**
- The `AppSettingsRepository` adapter that reads these rows — that is ERT-310.
- Replacing Appendix A with the real checklist. That is a seed change once Q2 is answered.

---

## ERT-140 — Map `AppError` to HTTP status in `StatusPages`

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §8.6, §8.7, §6.6 |
| **Architecture** | §8, §12 invariant 3 |

**Description**

[`StatusPages.kt`](../../src/plugin/StatusPages.kt) handles `Throwable` only, logging server-side and
returning a generic code — correct, and deliberately so, since the generator's version echoed
`"500: $cause"` to clients. But use cases return `DomainResult.Err(AppError)`, not exceptions, and
there is no shared mapping from an `AppError` to a status. Without it every route invents its own,
and the routes are supposed to make no decisions.

One mapping is a security control rather than a convenience. `AppError.Denied` deliberately collapses
a wrong PIN and an unknown token into one case, and it must render **identically** in status, body
and headers — otherwise the endpoint becomes an oracle for whether a link exists (§6.6, SEC-01).
`Denied` therefore carries a code but no detail field, and the mapper must not add one.

| `AppError` | Status |
|---|---|
| `Validation` | 422 Unprocessable Entity, with the field name |
| `NotFound` | 404 Not Found |
| `Conflict` | 409 Conflict — the locked-upload case of §8.7 |
| `ReasonRequired` | 422, naming the action needing justification |
| `Denied` | 404 Not Found, body identical in every instance |

**Identifier parse failures need a deliberate decision, not a default.** `PersonId.of` and
`EntityId.of` return `AppError.Validation`, which the table above maps to 422. That is right for a
request body, but a malformed id in a *path* is arguably a 404 — the resource named cannot exist. The
decision matters most on portal routes: answering 422 `person_id.invalid_format` for a malformed id
and 404 for a well-formed unknown one turns the endpoint into an enumeration oracle, which is exactly
what §6.6 and the `Denied` row above exist to prevent. Whatever is chosen, a portal route must give
the **same** answer for malformed, unknown and not-yours.

### Decided

**1. Where the id was read decides the status: a path id is 404, a body id is 422.** A path names a
resource, and an id that cannot exist names a resource that does not exist. The rule is uniform
across HR and portal, so no route author decides it again and a portal route cannot become an
enumeration oracle by picking the wrong helper. `PersonId.of` / `EntityId.of` are unchanged — they
still return `Validation`, and [`PathIds.kt`](../../src/route/mapper/PathIds.kt) reinterprets it at
the edge with `orNotFound(entity)` (HR) and `orDenied()` (portal, collapses **every** failure).
`orDenied` has no caller until ERT-630 on purpose: the rule has to exist before the first portal
route, for the same reason ERT-170's guard has to exist before the first portal DTO.

**2. `AppError.Denied` is now a `data object`, not a `data class` carrying a code.** As a data class,
two call sites could construct two different `Denied` values and render two different bodies —
making invariant 3 a convention someone has to remember. As a data object with a fixed
`code = "not_found"`, differing responses are *unrepresentable*. Done now because it had **zero**
call sites (`domain/usecase/` is empty), which is the same "last cheap moment" argument as ERT-180.
It is a change to a core sealed type and is recorded here as one.

**3. Two additions beyond the table above, both deliberate.** A `BadRequestException` (a malformed
JSON body) mapped to **422 `request.malformed`** — it previously fell to `exception<Throwable>` and
told the client the *server* had failed, the same class of trap as the 500 a mistyped path id used to
return. And a `status(NotFound)` handler giving an **unmatched route the identical body a `Denied`
produces**, so a mistyped portal sub-path is not distinguishable from a denied one. `401` and `405`
are deliberately left with Ktor's own handling: a `status(Unauthorized)` handler risks dropping the
`WWW-Authenticate` challenge, and neither status discloses whether a link exists.

> **`status(...)` handlers overwrite a body the route already sent — verified, not assumed.** With
> the `MappedErrorKey` guard removed, every mapped `NotFound` collapses into `{"code":"not_found"}`
> and the HR side loses the code naming the missing entity. Two tests fail when the guard is taken
> out; do not remove it as redundant.

Note also that this closes a live trap: before ids became value objects, an unguarded
`UUID.fromString` on a path segment threw `IllegalArgumentException`, and `StatusPages` has only an
`exception<Throwable>` branch — so a typo in a URL returned **500**.

**Goal**

A route can return an `AppError` and get the right status with no per-route mapping, and `Denied`
is indistinguishable across causes.

**Stories**
- As an engineer on the next session, I want one place that turns a domain failure into a status so
  that handlers stay free of decisions.
- As a New Hire, I want a wrong PIN and a mistyped link to look the same so that nobody can use the
  endpoint to discover whether my link is real.

**Acceptance criteria**
- [x] `[derived]` Given a use case returns `Validation`, then the response is 422 and names the field
- [x] `[derived]` Given `Conflict`, then the response is 409
- [x] Given `Denied` from a wrong PIN and `Denied` from an unknown token, then the two responses are
      byte-identical in status, body and headers (§6.6)
- [x] `[derived]` Given any `AppError`, then the response body carries the stable `code` and no
      internal detail, stack trace or SQL
- [x] `[derived]` Given an unexpected `Throwable`, then the existing behaviour is unchanged — logged
      server-side, generic code to the client
- [x] `[derived]` Given a malformed id in a path, then the response is indistinguishable from a
      well-formed unknown one
- [x] `[derived]` Given an unmatched route, then its body is identical to a `Denied` response

**Tests**
| Level | Test |
|---|---|
| Route | `error mapping - a validation failure - returns 422 naming the field` |
| Route | `error mapping - a conflict - returns 409` |
| Route | `denied response - wrong pin versus unknown token - the two responses are byte-identical` |
| Route | `error mapping - an unexpected throwable - leaks no detail to the client` |

**Files**
- modify [`src/core/error/AppError.kt`](../../src/core/error/AppError.kt) — `Denied` becomes a
  `data object`
- modify [`src/plugin/StatusPages.kt`](../../src/plugin/StatusPages.kt) — envelope is now
  `{ code, detail?, field? }`, plus the `BadRequestException` and `status(NotFound)` handlers
- create [`src/route/mapper/AppErrorMapper.kt`](../../src/route/mapper/AppErrorMapper.kt)
- create [`src/route/mapper/PathIds.kt`](../../src/route/mapper/PathIds.kt)
- create `test/route/ErrorMappingTest.kt`, `test/route/PathIdsTest.kt` — 18 tests, suite 74 → 92

**Out of scope**
- Rate-limit responses (429) — those arrive with ERT-660.

---

## ERT-145 — A uniform response envelope for `/api`

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-140 |
| **PRD** | §12 |
| **Architecture** | §9, §12 invariant 3 |

**Description**

The front-end decodes every endpoint with one generic `BaseResponse<T>`, so every `/api` response —
success and failure — is one envelope: `result`, `data`, `meta`, `error`. Done now rather than later
because `/health` was the only route mounted; after ERT-450 this is a migration across every handler,
DTO and route test.

Four shapes were rejected along the way, and the reasons belong with the ticket:

- **`status` in the body.** It duplicates the status line, and nothing detects a disagreement.
  `MockEngine` sets status and body independently, so a fixture claiming `"status":"200"` beside a
  500 is trivial to write and silently wrong.
- **A free-text `message` on every response.** Clients cannot branch on prose, and on the success
  path nothing renders it. `message` is now error-only and defaults to a lookup on `code`.
- **JSend's payload rules.** JSend puts a `fail`'s reasons in `data`. That makes `data` a DTO on
  success and a field-error map on failure, which breaks `BaseResponse<T>` outright. Only the
  success/fail/error trichotomy was adopted; `data` stays the success payload.
- **`field` and `detail` at the error root.** With `details` also present, a one-field failure was
  expressible two ways and a client had to handle both. The error block is now `code`, `message`,
  `details?` — and a single-field failure is a list of length one.

**Acceptance criteria**

- [x] `[derived]` Every `/api` response carries `result`, derived from the status class, never passed
      by a handler.
- [x] `[derived]` A one-field and a four-field validation failure render the same shape.
- [x] `[derived]` A `result: "error"` body carries no `details`.
- [x] `[derived]` Two `Denied` responses stay byte-identical, and an unmatched route still matches
      both. (Written as "a wrong PIN and an unknown token" before the 2026-09-16 access change; the
      guarantee is unchanged, the example moved to the recovery path.)
- [x] `[derived]` `GET /health` is unchanged and still outside the envelope.

**Implementation notes**

- add `src/route/dto/ApiResponse.kt` — `ApiResponse<T>`, `ApiResult`, `ApiError`, `ApiErrorDetail`,
  `ApiMeta`
- add `src/route/mapper/ApiResponses.kt` — `resultFor`, `errorEnvelope`, `respondResult`, `respondOk`
- add `src/route/mapper/ErrorMessages.kt` — `messageFor`
- add `AppError.ValidationFailed`; rewrite `AppErrorMapper` around `toApiError`
- `MappedErrorKey` moves from `plugin/` to `route/mapper/` so the dependency runs one way:
  `plugin` reads from `route.mapper`, never the reverse
- `respondResult` and `respondOk` are **`inline` + `reified`**. Ktor resolves a serializer from
  `typeInfo<T>()`; in a non-reified helper the type argument of `ApiResponse<T>` erases and
  serialization fails at runtime rather than at compile time
- add `test/route/ApiEnvelopeTest.kt` — 12 tests, suite 92 → 104

**Verified, not assumed: the OpenAPI generator infers nothing from `call.respond`.**

The spike that opened this ticket asked whether `ApiResponse<T>` would erase to `data: object` in
the generated spec. It does not — `data` renders as `$ref: #/components/schemas/HealthResponse`, and
`result` even carries its enum constraint. But the baseline had no response schema *either*: with a
plain `HealthResponse` the operation published no `responses` key at all. Schemas are not derived
from the route tree; they must be declared:

```kotlin
responses { response(200) { schema = jsonSchema<ApiResponse<HireDto>>() } }
```

So architecture §9's "`route/dto/` types are what the schema is generated from" was aspirational,
and §9 now states the corrected claim outright rather than appending a retraction to it (C20).
Both §9 and this ticket say what is actually required, and `/health` carries the first such
block as the pattern to copy. **Every route from ERT-340 onward needs one**, or the published spec
has no body type and the front-end has nothing to generate a client from.

**Out of scope**
- Pagination. `ApiMeta` reserves `page`/`pageSize`; cursor-or-offset is a contract decision for
  ERT-512. `total` works today.
- The client-side `BaseResponse<T>` — a separate repo. The contract for it is in
  [api-contract.md](../api-contract.md).
- A correlation id. It belongs in an `X-Request-Id` **header**, not the body: a per-request body
  field would break the byte-identity test outright.
- `ReasonRequired.action` has no wire slot under the new error shape. **Settled with C1 in the API
  contract**: the duplicate-email case renders as 422 with a `details` entry naming
  `duplicateReason`, so `action` stays off the wire and the field it names is carried by `details`.
  ERT-431 implements it.

---

## ERT-146 — A request with no `Content-Type` is 415, and every body-taking route publishes its schema

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-140, ERT-145, ERT-190 |
| **PRD** | §12 |
| **Architecture** | §9, §12 |

**Description**

Found by hand, through Swagger UI, on 2026-09-17. `POST /api/auth/login` answered **500** with
`Unhandled exception … CannotTransformContentToTypeException: Cannot transform this request's
content to SignInRequest`. Nothing was wrong with the DTO, the use case, or the JSON. Two
independent defects compose into that one symptom, and **neither half alone fixes it** — which is
why they are one ticket.

**1. The spec published no request body, so Swagger sent none.** `describe { }` on the five POST
routes declared `summary`, `description`, `operationId`, `tag` and `responses { }` — and never
`requestBody { }`. `OpenApiDocSource.Routing` reads the route *tree*; it cannot see inside a handler
lambda, so it infers a body from `call.receive<T>()` no more than ERT-145 found it infers one from
`call.respond`. The generated spec carried `SignInResponse` and no `SignInRequest`, and the
generated sample was `curl -X POST -H "Accept: application/json" "/api/auth/login"` — no body, no
`Content-Type`. Swagger UI renders no body editor for an operation with no `requestBody`, so "Try it
out" sent exactly that.

**This is ERT-145's rule with one half missing.** That ticket established that the generator infers
nothing from `call.respond` and wrote the response rule into `api-contract.md`. The identical
sentence is true of `call.receive` and was never written down, so five routes shipped without one
and the contract had nothing to say about it. The omission *is* the defect.

**2. A 415 condition was answered as a 500.** `Serialization.kt` registers `json(...)` with no
`contentType` argument, so `application/json` is the only converter. ContentNegotiation does not
fail on a mismatch: `convertRequestBody` skips every converter whose type does not match — an absent
header parses as `*/*`, which matches nothing — returns the raw bytes, and `receive<T>()` throws on
finding them. That exception is `ContentTransformationException : IOException`, **not** a
`BadRequestException`, so ERT-140's 422 arm never saw it and `exception<Throwable>` told the client
the server had broken. Ktor's own `defaultExceptionStatusCode` maps it to 415; installing
`StatusPages` displaces that default, and this application never restored it.

**Why 415 and not 422.** Nothing was parsed — there may be no body at all — so the payload is not
"well-formed but unprocessable". 422 is already spent on domain `Validation` and on
`request_malformed`, a body that *was* parsed and failed; folding this into it destroys the one
distinction a client can act on: **422 means fix the body, 415 means fix the header.**

**Why the parent exception class.** `ContentTransformationException` has two subclasses and Ktor
treats them identically. The sibling, `UnsupportedMediaTypeException`, is the same client mistake
against a `receiveMultipart()` handler — which **ERT-710 writes**. Registering the narrow class here
would have handed the upload route this same 500.

**Why the suite was green.** Every route test sets `contentType(ContentType.Application.Json)`. The
suite never sent a request without one, so it could not see either half. The malformed-JSON case at
`ErrorMappingTest:177` is the neighbouring branch and has passed since ERT-140 — it is kept
unmodified here as the pin that the two `StatusPages` arms do not shadow each other.

**Acceptance criteria**

- [x] `[derived]` Given a POST with no `Content-Type`, then the response is 415 `unsupported_media_type`
      and names no Kotlin type.
- [x] `[derived]` Given a POST whose `Content-Type` matches no converter, then the answer is
      identical to the one above — the header must match, not merely be present.
- [x] `[derived]` Given a POST with `Content-Type: application/json` and a malformed body, then the
      response is still 422 `request_malformed`.
- [x] `[derived]` Given the generated spec, then each of the five POST operations carries a
      `requestBody` whose schema `$ref`s its DTO — on the **operation**, not only in
      `components.schemas`.
- [x] `[derived]` Given a handler that calls `call.receive<T>()` and publishes no `requestBody`, then
      the architecture test fails the build.

**Tests**

| Level | Test |
|---|---|
| Route | `error mapping - a request body with no content type - is 415 rather than 500` |
| Route | `error mapping - a request body sent as text plain - is 415 rather than 500` |
| Route | `error mapping - a matching content type with the wrong body shape - stays 422 rather than 415` |
| Route | `api docs - a route that reads a request body - publishes it on the operation itself` |
| Route | `api docs - the user routes are mounted - publish their request as well as their response schemas` |
| Architecture | `documented request bodies - every handler that reads a body - publishes its request schema` |
| Architecture | `documented request bodies - a handler that receives without describing one - the build fails` |
| Architecture | `documented request bodies - a handler that describes its request body - passes` |
| Architecture | `guard integrity - the route tree holds handlers that read a body - the guard reports checked` |

**Implementation notes**

- `src/plugin/StatusPages.kt` — an `exception<ContentTransformationException>` arm between the
  existing two. Handler selection is by nearest registered superclass (`findHandlerByValue` filters
  on `instanceOf`, then `selectNearestParentClass`), so registration order is irrelevant and
  `BadRequestException` — not in this hierarchy — is untouched. **Register a class, never an
  interface**: `selectNearestParentClass` measures distance by walking `superclass`.
- `src/route/mapper/ErrorMessages.kt` — `unsupported_media_type`. Worded without naming
  `application/json`, so ERT-710's multipart route can reuse it.
- `src/route/hr/AuthRoutes.kt`, `src/route/hr/UserRoutes.kt` — `requestBody { }` on all five POST
  routes. Inside that block `description` is the **body's**; the inner receiver shadows the
  operation's.
- `test/ArchitectureTest.kt` — the guard, on ERT-1245's precedent. Verified against the pre-fix tree
  rather than only against its synthetic offender: it reports exactly those five routes before the
  fix and none after.
- The sign-in `requestBody` description says nothing about which inputs fail. Naming one would put
  back, in prose, the oracle the four identical 401s exist to close (invariant 10).
- Suite 673 → 682. **Measured before and after on this branch**, not added up — the roadmap's 653
  predates ERT-432 and ERT-1120.

**The five `describe { }` blocks are not the deliverable.** `api-contract.md` had carried the
response half of this rule since ERT-145, and five routes broke the unwritten request half anyway.
A convention that lives only in prose does not stop the sixth route; `ArchitectureTest` does. Same
argument, and the same shape, as ERT-1245's gate guard.

**Out of scope**
- Registering a second converter content type. Accepting `*/*` would make the server guess at an
  absent header and read a `text/plain` body as JSON, and — because `json(...)` registers a response
  converter too — negotiate responses under `*/*`. The client's missing header is the defect; the
  converter is not the place to paper over it.
- `receiveText` and `receiveParameters`. They want a different content type declared and have no
  call site in this project; inventing the rule for them in one file leaves the first real caller to
  re-invent it (C26's reasoning).
- Redacting the URI in the new log line. It is the **third** unredacted `local.uri` call site in
  `StatusPages.kt` and ERT-1110 owns all three; this ticket re-counts it there and adds its
  acceptance criterion. Harmless until ERT-630 mounts a route whose path is a credential.

---

## ERT-150 — Expose the Micrometer registry on a scrape route

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §13 |
| **Architecture** | §3 |

**Description**

[`Monitoring.kt:33`](../../src/plugin/Monitoring.kt) installs `MicrometerMetrics` with a Prometheus
registry and stores it in `attributes`, but no route calls `scrape()`. Metrics are collected and
unreachable. PRD §13 sets leading indicators — PIN entry failure rate, sessions blocked by lockout,
upload error rate — that need a scrape target to be measurable at all.

The endpoint must not be public. It sits behind the same gate as the Swagger surface: open in dev,
HR-authenticated otherwise.

**Goal**

Prometheus can scrape the service, and the endpoint is not world-readable outside dev.

**Stories**
- As an operator, I want a scrape endpoint so that the §13 launch metrics can be measured rather than
  estimated.

### Decided

**1. `plugin/Monitoring.kt` mounts the route, not `configureRouting()`.** The gate needs `HR_AUTH`
and `isDevMode()`, both in `plugin/`, and ERT-145 recorded that the dependency runs `plugin` →
`route` and never the reverse. So `route/MetricsRoutes.kt` holds the route and takes
`registry`, `devMode` and `authName` as parameters — it imports nothing from `plugin/` — and
`configureMonitoring()`, which already owns the registry, supplies them.
[`ApiDocs.kt`](../../src/plugin/ApiDocs.kt) mounts `/openapi` and `/swagger` the same way and for the
same reason: all three are operational surfaces, gated identically, and none belongs to the `/api`
contract `configureRouting()` assembles. **`Routing.kt` is therefore untouched**, which is the one
deviation from the Files list below. `routing { }` is additive, so mounting before
`configureRouting()` runs is not an ordering hazard.

**2. `devMode` is a parameter rather than an `isDevMode()` call inside the route.** A JVM test cannot
unset `APP_ENV` in its own process, so without the parameter the refused-outside-dev criterion is
unprovable. The same seam is what ERT-160 uses for `TOKEN_PEPPER`.

**3. The content type states `version=0.0.4`,** not bare `text/plain`. Scrapers accept the latter, so
dropping the parameter would fail nothing loudly — it is pinned by a test instead.

> **`hide()` inside `authenticate { }` was verified, not assumed.** Outside dev the handler is nested
> one level deeper, and `hide()` attaches to whatever `Route` node `get` returned in *that* tree. Had
> it attached to the wrong node, `/metrics` would be published in exactly the configuration where it
> is protected and where nobody looks. Both spec tests fail when `.hide()` is removed, so neither
> passes vacuously.

**Acceptance criteria**
- [x] `[derived]` Given the app is running in dev, when `/metrics` is requested, then the Prometheus
      exposition format is returned
- [x] `[derived]` Given `APP_ENV` is not dev, when `/metrics` is requested without HR credentials,
      then it is refused
- [x] `[derived]` Given the OpenAPI spec, then `/metrics` is hidden from it — it is an operational
      surface, not an API

**Tests**
| Level | Test |
|---|---|
| Route | `metrics endpoint - dev mode - returns prometheus exposition format` |
| Route | `metrics endpoint - outside dev without credentials - is refused` |
| Route | `metrics endpoint - the generated spec - does not publish it` |
| Route | `metrics endpoint - outside dev behind authentication - is still absent from the generated spec` |
| Route | `metrics endpoint - the response content type - names the prometheus text format version` |

**Files**
- create [`src/route/MetricsRoutes.kt`](../../src/route/MetricsRoutes.kt) — using `hide()` so it
  stays out of the spec
- modify [`src/plugin/Monitoring.kt`](../../src/plugin/Monitoring.kt) — mounts it; see Decided 1
- create `test/route/MetricsRoutesTest.kt` — 5 tests, suite 104 → 109

**Out of scope**
- Defining custom business metrics. Those land with the use cases that emit them.

---

## ERT-160 — Deterministic token digest, separate from PIN hashing

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §6.6, §12 |
| **Architecture** | §4, §12 invariant 4, §14 |

**Description**

There is a contradiction in the current code, and it is load-bearing.
[`BcryptHasher`](../../src/data/crypto/BcryptHasher.kt) uses `BCrypt.withDefaults().hashToString`,
which generates a **random salt per call** — hashing the same token twice yields two different
strings. But [`findByTokenHash`](../../src/domain/port/Repositories.kt) resolves a link *by* its
hash, and `UploadLinks.tokenHash` carries a `uniqueIndex()`. Both only make sense for a
deterministic digest. As the code stands, no presented token could ever be looked up.

The resolution is that the two credentials need different primitives, for different reasons:

| Credential | Primitive | Why |
|---|---|---|
| Link token, session token | HMAC-SHA-256 keyed by a server-side pepper | It is **looked up**, so the digest must be reproducible. 256 bits of entropy has no offline guessing attack worth a work factor. |
| Access PIN | bcrypt, cost 12 | It is **verified** against one known row, never looked up. The keyspace is 10⁶, which is exactly what a work factor defends — architecture §14 makes this argument and it still holds. |

This has to land in Phase 0. `CreateHireUseCase` writes `tokenHash` and `OpenPortalUseCase`
reads by it; discovering the problem after either ships means re-issuing every live credential and
re-inviting every hire — through a bulk send path §8.2 deliberately makes hard.

A consequence worth recording rather than discovering: rotating the pepper invalidates every live
link. There is no re-issue flow, so rotation is not currently possible. Note it as a known gap.

**Goal**

A `TokenDigest` port produces a reproducible keyed digest for link and session tokens, `Hasher`
continues to hash PINs only, and the pepper is required outside dev.

**Stories**
- As an engineer on the next session, I want token lookup and PIN verification to use the right
  primitive each so that neither the lookup silently fails nor every portal request pays 100ms of
  bcrypt.

### Decided

**1. Absence is forgiven in dev; weakness never is.** A dev run with no `TOKEN_PEPPER` gets an
ephemeral pepper, so a fresh checkout works unconfigured — the same trade as the in-memory H2
default. A pepper that is *present but shorter than 32 characters* fails in every mode, dev included:
without a floor the "is it configured" check is satisfied by `TOKEN_PEPPER=x`, which adds no work to
an offline attempt and is configuration theatre.

**2. Blank counts as absent.** `?:` catches `null` but not `""`. Sourcing a `.env` copied from
`.env.example` supplies exactly the empty string, so without this, following the documentation would
walk straight past the check. The same one-line fix was applied to `JWT_SECRET`, which had the same
hole and where the consequence is booting on an empty signing key.

**3. A fresh `Mac` per call.** `javax.crypto.Mac` is stateful and not thread-safe. A shared instance
field would interleave `update`/`doFinal` across concurrent portal requests and return digests
belonging to neither caller — a fault that appears only under load and reads as data corruption.
Construction costs microseconds against the ~100 ms bcrypt call it replaces on the lookup path.

**4. `TokenDigest` is resolved eagerly in `configureKoin()`.** Koin singles are lazy, so a missing
pepper would otherwise surface on the first portal request rather than at boot — in production, long
after the deploy looked successful. Same argument `Database.kt` records for calling `connect()` in
the module body. *(This modifies `di/AppModule.kt`, which the Files list below did not name.)*

**5. It must stay a `single`, never a `factory`.** In dev the pepper is generated per instance, so a
`factory` would digest a token one way at issue and another at lookup — every dev link issued already
broken. Pinned by an identity assertion in `ServerTest`.

**Acceptance criteria**
- [x] `[derived]` Given the same token digested twice, then the two digests are equal, so a link can
      be resolved by hash
- [x] `[derived]` Given two different tokens, then their digests differ
- [x] `[derived]` Given no pepper is configured outside dev, then startup fails — matching the
      existing `JWT_SECRET` behaviour in [Security.kt:32](../../src/plugin/Security.kt)
- [x] `[derived]` Given a leaked database, then no stored value yields a usable token or PIN
- [x] `[derived]` Given an access PIN, then it is still hashed with bcrypt and not with the digest
- [x] `[derived]` Given the documentation, then the pepper-rotation gap is recorded — architecture
      §14 and the `HmacTokenDigest` KDoc

**Tests**
| Level | Test |
|---|---|
| Use case | `token digest - the same token digested twice - produces the same value so a link resolves by hash` |
| Use case | `token digest - two different tokens - produce different digests` |
| Use case | `token digest - two different peppers - produce different digests for the same token` |
| Use case | `token digest - a stored digest - does not contain the token it was made from` |
| Use case | `credential hashing - an access pin - is hashed with a work factor rather than a fast digest` |
| Use case | `token digest - no pepper configured outside dev - startup refuses` |
| Use case | `token digest - a blank pepper outside dev - is treated as absent rather than accepted` |
| Use case | `token digest - a pepper below the minimum length - is refused in dev as well as outside it` |
| Use case | `token digest - no pepper configured in dev - falls back to an ephemeral pepper that still works` |
| Route | `token digest wiring - the running application - resolves one shared digest, not one per call` |

> **Scope note on the refusal tests.** This ticket filed them at Route level, meaning a real server
> boot. A JVM test cannot unset `TOKEN_PEPPER` in its own process, so `fromEnvironment` takes the
> pepper as a defaulted parameter and the tests drive it directly. **What is proven is that the
> refusal fires, not that it aborts the boot** — the boot path is covered only by the eager
> resolution in `configureKoin()` being on the same line as `install(Koin)`. Verify the real thing by
> hand with `APP_ENV=prod JWT_SECRET=… ./kotlin run`.

**Files**
- create [`src/core/crypto/TokenDigest.kt`](../../src/core/crypto/TokenDigest.kt) — the port
- create [`src/data/crypto/HmacTokenDigest.kt`](../../src/data/crypto/HmacTokenDigest.kt)
- modify [`src/core/crypto/Hasher.kt`](../../src/core/crypto/Hasher.kt) — narrowed to the PIN; it
  claimed to cover the link token
- modify [`src/data/crypto/BcryptHasher.kt`](../../src/data/crypto/BcryptHasher.kt) — same
- modify [`src/di/CoreModule.kt`](../../src/di/CoreModule.kt) — bind it
- modify [`src/di/AppModule.kt`](../../src/di/AppModule.kt) — resolve it eagerly; see Decided 4
- modify [`src/plugin/Security.kt`](../../src/plugin/Security.kt) — blank `JWT_SECRET` now counts as
  absent; see Decided 2
- modify `docs/architecture.md` — §14 rewritten (it stated "bcrypt for token and PIN", which this
  ticket supersedes), §12 invariant row, §3 and §4
- create `test/core/crypto/TokenDigestTest.kt`, modify `test/ServerTest.kt` — 10 tests, suite
  109 → 119

**Out of scope**
- A pepper-rotation or credential re-issue flow. Recorded as a gap, not built.

---

## ERT-170 — Architecture guards for the write-mostly rule

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §8.6 |
| **Architecture** | §2, §12 invariants 1 and 2 |

**Description**

[`ArchitectureTest.kt`](../../test/ArchitectureTest.kt) currently guards imports in `domain/` and
`core/` and asserts one docstring in `DocumentStorage.kt`. The write-mostly rule has no mechanical
guard at all.

PRD §15 says deciding that rule late means "unbuilding a preview feature and re-testing every portal
endpoint". The realistic failure is not someone deliberately adding preview — it is `ChecklistDto`
gaining a `mimeType` "for the icon" and an upload response echoing `originalFilename` "for the
confirmation toast". Both read as reasonable in review. A filename leaks content as surely as the
document does: `NBI_Clearance_DelaCruz_1998.pdf` says everything.

**This guard must exist before the first portal DTO is written.** Afterwards it is an audit, not a
guard.

Two details matter. The guard scopes to **portal** DTOs only — §8.4 explicitly requires
`originalFilename`, `sizeBytes` and `mimeType` on the HR side, so a blanket rule would block ERT-820.
And it needs the anti-vacuity assertion the existing suite already uses: while `route/dto/portal/` is
empty, a naive guard passes forever.

**Goal**

A portal DTO carrying document content, or a portal route reaching `DocumentStorage`, fails the
build — and the guard cannot pass vacuously.

**Stories**
- As an engineer on the next session, I want the write-mostly rule enforced mechanically so that I
  cannot reintroduce the finding the audit closed without the build telling me.

### Decided

**1. Each guard is a pure function over `(path, source)` pairs, driven two ways.** Against the real
tree — which is what fails the build — and against **synthetic sources** that assert the rule itself.
The synthetic half is not redundant: `src/route/dto/portal/` and `src/route/portal/` hold no files,
so a real-tree-only guard passes without examining anything, and would keep passing after someone
broke the rule it is named for.

**2. The ban is on document *handles*, not on six spellings.** Exact names (`fileKey`,
`originalFilename`, `url`, `downloadUrl`, `signedUrl`, `mimeType` and their snake_case forms) plus a
suffix rule: any property ending in `Url` or `Filename`. The named list would not have caught
`previewUrl`, which is the shape the next well-meant addition actually takes. `@SerialName` values
are matched too — renaming the field only on the wire is the obvious way around a property check and
the one that ships the field.

**3. The path scope lives inside the rule, not at the call site.** §8.4 requires `originalFilename`,
`sizeBytes` and `mimeType` on the HR side, so a blanket ban would block ERT-820. The first draft
scoped by what the caller passed in; the HR test caught it, which is the review step working as
intended.

**4. `GuardOutcome` makes vacuity a visible state.** `Vacuous` when the walk matched nothing,
`Checked(scanned, violations)` otherwise — so "it examined nothing" cannot be mistaken for "it found
nothing". A second test asserts a populated directory reports `Checked`, without which a guard that
returned `Vacuous` unconditionally would satisfy the tripwire forever.

> **The tripwire is deliberate.** `guard integrity - the portal dto directory is empty` asserts
> `Vacuous` **today**. The day the first portal DTO or portal route lands, it fails — that is the
> signal, not a regression. Flip the expectation to `Checked`; do not delete the test, and do not
> delete the two real-tree assertions it is guarding.

**Verified, not assumed: every guard fails the build on a real violation.** A portal DTO declaring
`originalFilename` and `previewUrl`, a portal route importing `DocumentStorage`, and a route file
importing `org.jetbrains.exposed` and `…tracker.plugin` were each planted in `src/` and the suite
re-run. Five tests failed across the four rules, and the vacuity tripwire fired alongside them. The
sources were then removed.

**Acceptance criteria**
- [x] `[derived]` Given a type under `route/dto/portal/`, then it declares no field named `fileKey`,
      `originalFilename`, `url`, `downloadUrl`, `signedUrl` or `mimeType` (§8.6)
- [x] `[derived]` Given any file under `src/route/portal/`, then it does not import `DocumentStorage`
- [x] `[derived]` Given any file under `src/route/`, then it does not import
      `org.jetbrains.exposed` — routes make no direct `data/` access, which is currently unguarded
- [x] `[derived]` Given `route/dto/portal/` is empty, then the guard reports vacuous rather than
      passing
- [x] `[derived]` Given an HR DTO carrying `originalFilename`, then the guard does **not** fire —
      §8.4 requires it

**Tests**
| Level | Test |
|---|---|
| Architecture | `write-mostly portal - a portal dto declares a file key or original filename - the build fails` |
| Architecture | `write-mostly portal - a portal dto names a preview url the ban list never anticipated - the build fails` |
| Architecture | `write-mostly portal - a portal dto renames the field only on the wire - the build fails` |
| Architecture | `write-mostly portal - an HR dto carrying an original filename - does not trip the guard` |
| Architecture | `write-mostly portal - a portal route imports DocumentStorage - the build fails` |
| Architecture | `write-mostly portal - every portal dto in the tree - declares no document field` |
| Architecture | `write-mostly portal - every portal route in the tree - reaches no document storage` |
| Architecture | `dependency rule - a route imports Exposed directly - the build fails` |
| Architecture | `dependency rule - a route imports plugin - the dependency runs plugin to route not the reverse` |
| Architecture | `guard integrity - the portal dto directory is empty - the guard reports vacuous rather than passing` |
| Architecture | `guard integrity - a populated directory - reports checked so the tripwire above can fire` |

**Files**
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt) — 11 tests, suite 119 → 130

**Added beyond the original scope**

`dependency rule - a route imports plugin - …`. ERT-145 decided the arrow runs `plugin` → `route` and
never the reverse, and recorded it in a ticket and nowhere else — a route author could only learn it
by reading one. It is the rule that decided where ERT-150 mounts `/metrics`. Same file walk, one
extra assertion.

**Out of scope**
- A multi-module split. Architecture §14 defers it deliberately.

---

## ERT-180 — Short alphanumeric identifiers replace UUIDs

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-120, ERT-130 |
| **PRD** | §11 |
| **Architecture** | §2, §12 |

**Description**

Every identifier was a 36-character `java.util.UUID`. They are now two validated value objects:
`PersonId`, 8 characters, for `employees.id` and the foreign keys pointing at it, and `EntityId`,
12 characters, for everything else. Both draw from `A-Z a-z 0-9`.

Done in Phase 0 because it is the last cheap moment. `IdGenerator.newId()` had **zero** call sites,
`domain/usecase/`, `data/repository/`, `data/mapper/` and both route packages were empty, and nothing
had ever persisted. The same change after Phase 1 is a rewrite of every mapper and route against a
live schema.

The widths are deliberately **disjoint**, which is what lets `Identifier.of` resolve a stored id to
its kind by length — needed for `audit_logs.entity_id`, the one column that can hold either.

Three things were deliberately left alone, none of them an entity identifier: the dev-mode JWT
signing key in `Security.kt`, the in-memory database name in `MigrationTest.kt`, and the random
suffix in ERT-700's storage key scheme. All three are secrets or opacity devices, and shortening a
secret weakens it.

**Goal**

An identifier is a validated value object of a known width, a malformed one cannot reach a query, and
every insert names its own id.

**Stories**
- As an engineer on the next session, I want `findById` to take a `PersonId` rather than a `UUID` so
  that I cannot pass an employment-type id to it by mistake.

**Acceptance criteria**
- [x] `[derived]` Given a value of the wrong length, charset or with surrounding whitespace, then
      `PersonId.of` / `EntityId.of` return `AppError.Validation` rather than a value
- [x] `[derived]` Given a stored id, then `Identifier.of` resolves it to the right kind by length,
      and rejects any length that is neither
- [x] `[derived]` Given the generators, then every character is an independent unbiased draw from the
      shared alphabet, proved against a counting random rather than by sampling
- [x] `[derived]` Given the migrated schema, then every id and foreign-key column is `varchar` of the
      width its type declares
- [x] `[derived]` Given any keyed table, then it declares **no** client default, so an insert that
      omits the id fails instead of silently receiving one
- [x] `[derived]` Given `src/domain` or `src/core`, then no file imports `java.util.UUID`
- [x] `[derived]` Given the seed migration, then every seeded id satisfies the `EntityId` rule

**Tests**
| Level | Test |
|---|---|
| Use case | `person id - surrounding whitespace - is rejected rather than being trimmed into shape` |
| Use case | `identifier resolution - a 10 character id - is rejected because no id kind has that length` |
| Use case | `person id generation - a random drawing 0 1 2 and so on - maps each index to the matching character` |
| Repository | `identifier columns - the migrated schema - are the width their id type declares` |
| Repository | `identifier generation - every keyed table - declares no client default` |
| Repository | `catalogue seed - every seeded id - is a well formed entity id` |
| Architecture | `identifier discipline - no domain or core file imports java util UUID - ids are value types` |

**Files**
- create `src/core/value/Identifier.kt`, `PersonId.kt`, `EntityId.kt`
- create `src/core/id/EntityIdGenerator.kt`, `PersonIdGenerator.kt`; delete `IdGenerator.kt`
- create `src/data/db/table/IdTables.kt`
- create `test/testdata/Ids.kt`
- modify `src/data/id/SecureRandomGenerators.kt`, `src/di/CoreModule.kt`, the 8 domain models, the 3
  port files, `src/data/db/table/Tables.kt`
- regenerate `resources/db/migration/V1__baseline.sql`; rewrite the 19 literals in
  `V2__reference_data.sql`

**Out of scope**
- The retry when a generated `PersonId` collides. It belongs with the insert, in ERT-410 — a value
  object cannot know what the database already holds.
- HR user accounts. See ERT-190.

> **The schema-drift test cannot catch a wrong identifier width.** H2 reports every `VARCHAR(n)` as
> equivalent to every `VARCHAR(m)`, and an id column's Exposed type is `EntityIDColumnType` rather
> than `VarCharColumnType`, so the size comparison is skipped entirely. This was verified by setting
> `employees.id` to `varchar(36)` against a `varchar(8)` table definition: drift passed, and only the
> new width test failed. Do not read a green drift test as proof the baseline is correct.

---

## ERT-190 — HR user accounts, roles and sign-in

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-180, ERT-240, ERT-330 |
| **PRD** | §2, §8.13, §12, §14 Q4 |
| **Architecture** | §4, §5, §11, §12, §14 |

**Description**

**Q4 is answered (2026-09-16): a small team, simple accounts.** A handful of HR staff, local accounts
held here, two roles, no SSO, and no separation-of-duties enforcement in v1. Architecture §14 said the
JWT scheme should be **replaced, not extended**, once Q4 landed. It is now replaced: the mechanism
survives, the placeholder framing does not. The scheme stops signing with a per-run random key and
starts issuing tokens against a row.

**Two roles, not four.** `HR_OFFICER` creates hires, validates documents, manages links and issues
recovery PINs. `HR_ADMIN` does all of that plus the §6.4 settings, the requirement catalogue and user
administration. `SYSTEM_ADMIN` and `RECRUITMENT` are dropped — nothing in §8 asks for either, and a
role with no requirement behind it becomes a place to put permissions nobody has thought about.
Operator access is database access, not an application role.

**The roles differ in configuration rights, not validation rights.** §8.13 retains one effective role
for v1 and mitigates it with the exception report, and Q4's answer does not change that: an HR Officer
can still create a hire, change its email and approve every document unaided. **Do not read a second
role as having closed SEC-10.**

HR users reuse `PersonId` rather than introducing a third identifier width, so `Identifier.of`'s
length dispatch stays unambiguous. An 8-character value in `audit_logs.entity_id` then means "an
employee or a user", which is correct — `AuditEntry.entity` already names which. Note the column is
`varchar(12)` and a `PersonId` is 8: store it unpadded and read it back through `Identifier.of`, which
dispatches on length. `V1__baseline.sql` already warns about the trailing-space trap here.

**Four of the five actor columns become foreign keys; `audit_logs.actor` does not.**
`employees.created_by`, `employees.originals_sighted_by`, `submissions.reviewed_by` and
`app_settings.updated_by` each name a person who must exist, so each narrows from `varchar(128)` to a
`PersonId` referencing `users(id)` `on delete restrict` — a user who acted cannot be deleted out from
under the record. `audit_logs.actor` keeps its free text and gains a **nullable** `actor_user_id`
beside it, because the trail must record actors who are not users: the ERT-130 seed, the ERT-1020
expiry sweep, a future import job. An append-only trail that can refuse a write because it cannot name
a user is worse than one carrying a string. §8.13's exception report joins on `actor_user_id`;
everything else reads `actor`. **This corrects the pre-Q4 version of this ticket, which put a foreign
key on all five.**

**No `last_login_at`.** The reasoning that removed `upload_links.last_accessed_at` (§11, SEC-05): one
overwritten timestamp cannot answer who, from where, or how often. Sign-ins are audit rows.

**Token lifetime is the revocation window, and it is a trade.** Resolving the subject against `users`
on every request would make deactivation instant and put a query in front of every HR call. The
verifier validates claims only, so deactivating a user takes effect within `JWT_TTL_MINUTES`, default
60. Stated now rather than discovered later: an account disabled for cause is live for up to an hour,
and the immediate control is revoking what the person could reach, not the token.

**A failed sign-in is uniform.** An unknown email, a wrong password and a deactivated account return
the same 401 with the same body, for the reason §6.6 gives about the portal — otherwise the endpoint
enumerates who works in HR. Every attempt, success or failure, is an audit row.

**Bootstrap, once.** With no SSO and no self-registration the first account must come from somewhere.
On start, if `users` is empty, one `HR_ADMIN` is created from `HR_BOOTSTRAP_EMAIL` and
`HR_BOOTSTRAP_PASSWORD` with `password_change_required` set. Outside dev, startup **refuses** if the
table is empty and those are unset — the same shape as `JWT_SECRET` and `TOKEN_PEPPER`, for the same
reason: a deployment that comes up with no way in, or with a known way in, is worse than one that does
not come up.

**Why this runs before ERT-410.** Three costs, all rework rather than delay:

1. **ERT-410 writes the mapper for `employees.created_by`.** If the column changes from `varchar(128)`
   to a `PersonId` foreign key afterwards, the mapper, its round-trip test and the drift baseline are
   all redone.
2. **ERT-430 writes the first actor value.** A `created_by` written as free text before `users` exists
   is a backfill against rows that have no correct answer.
3. **ERT-450 is the fourth HR route behind a scheme no test can satisfy.** ERT-340 recorded that gap
   in its test class and said it closes with Q4. ERT-450's "given no credentials, then the request is
   refused" can only be tested negatively today; the positive half — a valid token reaches the handler
   — has never been tested at all.

**Goal**

An HR action is attributable to a row rather than to a typed-in name, a test can mint a token this
application accepts, and `authenticate(HR_AUTH)` protects something real.

**Stories**
- As an HR Admin, I want each officer to have their own account so that the audit trail names a person
  rather than whatever they typed.
- As an engineer on the next session, I want to sign in as a test user and call an HR route end to
  end, so that "requires HR auth" is a tested property rather than a mounting convention.
- As an HR Officer, I want my own password rather than a shared one, so that a departure means
  disabling an account instead of telling everyone the new password.

**Verified, not assumed.**

- **The failures really are identical, in four ways.** `AuthRoutesTest` asserts that a malformed
  address, an unknown address, a wrong password and a deactivated account produce one distinct
  `(status, body)` pair between them — `bodies.distinct().size shouldBe 1`, which fails if any branch
  diverges rather than asserting each pair separately and missing a third.
- **Timing is uniform, and it is asserted structurally rather than by measuring.** A `CountingHasher`
  wrapping the real bcrypt proves `verify` is called on the unknown-address and deactivated branches.
  A wall-clock assertion would be flaky on a loaded CI box and would prove nothing on a fast one.
- **The audit trail is not an oracle either.** A failure for a real account and one for an unknown
  address are compared field by field, modulo the row id and the attempted address: both carry a null
  `actorUserId` and both point at `HrUser.NO_SUBJECT`.
- **The foreign keys bite, in both directions.** Deleting a user who created a hire is refused;
  deleting one who created nothing succeeds, so the refusal is the constraint rather than users being
  undeletable in general. Inserting a hire naming a user that does not exist is refused too.
- **Case-insensitive uniqueness bites against raw SQL**, not against `EmailAddress` — which lower-cases
  on construction and so cannot produce the violating row. Two different addresses are inserted in a
  third test, or a broken helper would make both rejections pass.
- **The `pwd_change` gate fails closed.** A hand-built token omitting the claim is refused with 409,
  so forgetting the claim can never become a silent exemption.
- **A token signed with another key is refused**, which is the only test that would fail against a
  verifier that skipped signature checking.

> **The ERT-195 tripwire fired, as designed.** `guard integrity - the use case directory is empty`
> asserted `Vacuous` and said the day the first use case landed it would fail, and that this was the
> signal rather than a regression. ERT-190 landed six, so it was flipped to `Checked` — with a
> non-vacuity assertion added — rather than deleted.

**Found rather than decided**

- **A JWT's `exp` is validated against the real system clock, which no injected `Clock` reaches.**
  Issuing test tokens at `FixedClock.DEFAULT` — a fixed date eight months past — expired them before
  they were presented, failing seven route tests at once with the same 401 and no indication of the
  cause. `HrTokens` and the sign-in route test issue at `Instant.now()`; everything else stays fixed.
  This is the one documented boundary of the never-`Instant.now()` rule.
- **H2 rejects expression indexes**, so `create unique index ... (lower(email))` — valid PostgreSQL —
  could not be used. A `check (email = lower(email))` plus a plain unique index is standard SQL in
  both engines and is strictly stronger: it also guarantees the stored form is canonical.
- **`configureSecurity` had to stop reading the container.** Injecting `JwtConfig` broke
  `MetricsRoutesTest`, which assembles three plugins and no Koin — and the fix was the thing that made
  the whole ticket testable, since a test can now hand the verifier the config `HrTokens` signs with.
- **Widening `MigrationTest`'s portability sweep from V1 to the whole directory flagged V2** for
  `MERGE INTO`, appearing in a comment explaining why `MERGE INTO` is not used. The sweep now strips
  `--` comments: a guard that cannot be documented around is one people write around instead.

**Delivered beyond the file list**

- `POST /api/users`, `GET /api/users`, `/active` and `/reset-password` — the user-administration
  endpoints the **Out of scope** section places here ("the endpoints and the model land here; the
  screen is Phase 2"). Without at least one route an `HR_OFFICER` cannot reach, `HR_ADMIN` would gate
  nothing, and the 403 acceptance criterion would be untestable.
- `AppError.AuthenticationFailed` and `AppError.Forbidden`, both `data object`s. A specification
  change, and deliberate: sign-in must answer 401 rather than `Denied`'s 404, and `data object` is
  what makes "byte-identical" structural rather than a convention.
- `src/di/DomainModule.kt`, which `AppModule` anticipated.

**Acceptance criteria**
- [x] `[derived]` Given a `users` table, then its primary key is a `PersonId` and email is unique,
      case-insensitively
- [x] `[derived]` Given a password, then it is stored through the existing `Hasher` port and never
      appears in a log, a response, a trace line or an audit row
- [x] `[derived]` Given valid credentials, then a token is returned carrying the user id as `sub` and
      the role as a claim, and `authenticate(HR_AUTH)` accepts it
- [x] `[derived]` Given an unknown email, a wrong password and a deactivated account, then all three
      responses are byte-identical
- [x] `[derived]` Given any sign-in attempt, then an audit row records outcome, actor and source IP
- [x] `[derived]` Given `password_change_required`, then every route except change-password refuses
      until the password is changed
- [x] `[derived]` Given an `HR_OFFICER` on an `HR_ADMIN`-only route, then it is refused with 403 and
      the attempt is audited
- [x] `[derived]` Given the four actor columns, then each references `users(id)` with
      `on delete restrict`
- [x] `[derived]` Given an audit row written by no user, then it still writes, with `actor_user_id`
      null
- [x] `[derived]` Given an empty `users` table outside dev and no bootstrap variables, then startup
      fails rather than booting with no way in
- [x] `[derived]` Given the bootstrap account, then it is created only when `users` is empty and
      carries `password_change_required`
- [x] `[derived]` Given `JWT_SECRET` is unset outside dev, then startup still refuses — unchanged
- [x] `[derived]` Given the generated spec, then sign-in and change-password appear with their
      200/401 contract, and every HR route shows the `hr-jwt` requirement

**Tests**
| Level | Test |
|---|---|
| Use case | `hr sign in - a correct password - returns a token naming the user and role` |
| Use case | `hr sign in - an unknown email - fails identically to a wrong password` |
| Use case | `hr sign in - a deactivated user with the correct password - fails identically to an unknown email` |
| Use case | `hr sign in - any attempt - is written to the audit trail with its outcome` |
| Use case | `password change - a new password - is stored hashed and clears the change-required flag` |
| Use case | `password change - the current password is wrong - is refused and audited` |
| Repository | `hr user persistence - a user - round-trips including role and active flag` |
| Repository | `hr user persistence - the same email in another case - is rejected` |
| Repository | `actor reference - deleting a user who created a hire - is refused by the database` |
| Repository | `audit trail - a row written by no user - stores a null actor user id` |
| Route | `hr sign in - valid credentials - returns a token the authenticate block accepts` |
| Route | `hr routes - a token minted by this application - are reachable` |
| Route | `hr routes - a token signed with another key - are refused` |
| Route | `hr routes - an officer on an admin-only route - is refused with 403` |
| Route | `password change required - any other hr route - is refused until the password is changed` |
| Route | `bootstrap - an empty users table outside dev with nothing set - startup fails` |

**Files**
- create `resources/db/migration/V4__hr_users.sql` — `users`, four foreign keys, `audit_logs.actor_user_id`
- create `src/domain/model/HrUser.kt` — `HrUser`, `HrRole`
- modify [`src/domain/port/Repositories.kt`](../../src/domain/port/Repositories.kt) — `HrUserRepository`
- create `src/domain/usecase/AuthenticateHrUserUseCase.kt`, `src/domain/usecase/ChangeHrPasswordUseCase.kt`
- create `src/data/repository/ExposedHrUserRepository.kt`, `src/data/mapper/HrUserMapper.kt`
- create `src/data/auth/JwtIssuer.kt`
- modify [`src/plugin/Security.kt`](../../src/plugin/Security.kt) — real verifier, role claim, change-required gate; the placeholder KDoc goes
- create `src/route/hr/AuthRoutes.kt`, `src/route/dto/AuthDto.kt`, `src/route/mapper/AuthDtoMapper.kt`
- modify [`src/route/Routing.kt`](../../src/route/Routing.kt) — sign-in outside `authenticate`, change-password inside
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt), [`src/di/AppModule.kt`](../../src/di/AppModule.kt)
- modify `src/domain/model/Employee.kt`, `src/domain/model/Submission.kt` — three actor fields become `PersonId`
- modify [`src/data/db/table/Tables.kt`](../../src/data/db/table/Tables.kt)
- create `test/testdata/fake/FakeHrUserRepository.kt`; modify [`test/testdata/Builders.kt`](../../test/testdata/Builders.kt)
- create `test/testdata/HrTokens.kt` — mints a token every route test can use. **This is the gap ERT-340 recorded in its test class.**
- create the four test files named above
- modify [`test/data/db/MigrationTest.kt`](../../test/data/db/MigrationTest.kt) — drift baseline
- modify [`.env.example`](../../.env.example), [`CLAUDE.md`](../../CLAUDE.md), `docs/architecture.md`, `docs/api-contract.md`, `docs/backlog/README.md`

**Out of scope**
- **Self-service password reset.** It needs a mail transport (ERT-1010) and introduces a second bearer
  credential with its own expiry and threat model. An HR Admin resets; the user must change at next
  sign-in. Enough for a handful of people.
- **Department-scoped permissions.** PRD P2.
- **Separation-of-duties enforcement.** Q4's answer is explicit that there is none in v1. The
  exception report stays the control (§8.13).
- **A user-administration screen.** The endpoints and the model land here; the screen is Phase 2 with
  the rest of the admin surface.
- **Rate limiting on sign-in.** ERT-660 owns it and its scope now names `/api/auth/login`. Until then
  the audit row is the detection.

> **Taken as one ticket.** The split seams below were offered and not used, so 191 to 194 stay
> unallocated. Recorded because the reasoning still holds if any of this is revisited: the table,
> migration and repository; the two use cases against fakes; the routes, the real verifier and
> `HrTokens`; the four foreign keys. **The third is what closed ERT-340's "no test can mint a token
> this application accepts"** — and it turned out to depend on the first, because `configureSecurity`
> had to stop reading the container before a test could configure both halves of the scheme.

---

## ERT-195 — Dev-only tracing of use case execution

| | |
|---|---|
| **Parent** | ERT-100 |
| **Type** | Ticket |
| **Phase** | 0 |
| **Status** | Done |
| **Depends on** | ERT-150 |
| **PRD** | §12, §13 |
| **Architecture** | §2, §12 invariants 1–4 |

**Description**

Nothing below the route is observable. `CallLogging` reports `POST /api/employees -> 422` and stops
there, so "which rule rejected it, and what did it cost" can only be answered with a debugger — and
on a portal path even the route line collapses to `/api/portal/[redacted]`, so the action is not
visible either.

`src/domain/usecase/` is empty. That is the reason to do this now rather than later: a seam laid
before the first use case is one every use case is written against, and one an architecture guard can
hold. ERT-170 made the same argument for the portal guards, in the same words — afterwards it is an
audit, not a guard.

The hazard is the obvious one. A trace of business logic sits exactly where the arguments are, and
§12 invariants 1–4 say a PIN, a token, a filename, and anything separating a wrong PIN from an
unknown token must never reach a log. So the design question is not "what would be useful to log" but
"what can a call site be prevented from logging".

**Goal**

With `TRACE_USECASES=true`, every use case invocation emits one line — name, outcome, duration —
correlated to its request. Unset, a no-op is bound and nothing is measured.

**Stories**
- As an engineer debugging a data or business-logic problem, I want to see which use case ran, what
  it decided and how long it took, so that I can locate a fault without attaching a debugger.
- As an engineer on the next session, I want a use case that forgets to trace to fail the build, so
  that coverage does not decay one file at a time.

### Decided

**1. The block returns `DomainResult`, so the tracer reads the outcome itself.** A call site cannot
report something richer because it reports nothing: it hands over a name and a block. This is the
whole of the privacy design — not a rule to remember, an absence of anything to pass.

**2. The outcome word is `AppError.code`, never the error.** `Validation` and `Conflict` carry a
`detail` holding whatever the caller typed. `Denied` is a single `data object` whose code is
`not_found`, so a wrong PIN and an unknown token render identically — invariant 3 holds in the trace
for the same reason it holds on the wire.

**3. Its own environment variable, not `isDevMode()`.** Architecture §10 already records that four
controls hang off `APP_ENV` and that it defaults to dev when unset. A fifth would mean a deployment
that forgot the variable silently started tracing. `TRACE_USECASES` is off unless set to `true`; an
unparseable value warns rather than refusing to boot, because a debug flag should not be able to take
production down, but silently ignoring `TRACE_USECASES=1` would send someone hunting for a tracer
that was never on.

**4. The port is in `core/`, beside `Clock`, not in `domain/port/`.** It is infrastructure a use case
depends on, not a business collaborator — the same category `CoreModule` already names. This also
leaves ERT-210 scoped to ten domain ports, unchanged.

**5. `invoke` delegates to a private `execute`.** Wrapping a use case body directly would turn every
`return` into `return@trace` — a compile error rather than a silent bug, but permanent noise in
guard-clause-shaped code, re-touched by each of ERT-430's four sub-tasks:

```kotlin
suspend operator fun invoke(command: CreateHire): DomainResult<Employee> =
    tracer.trace("CreateHireUseCase") { execute(command) }
```

**6. Elapsed time is `System.nanoTime()`, not the injected `Clock`.** `Clock` is a wall clock, so an
NTP step lands mid-measurement; and ERT-220's `FixedClock` never advances, so every duration in every
test would be `0` and every timing assertion would pass without measuring anything.

**7. Correlation reuses the `CallLogging` MDC hook.** Ktor wraps the Monitoring and Call phases in
`withContext(MDCContext(...))` and routing intercepts `Call`, so the value reaches every suspend
frame the request opens, including work handed to another dispatcher inside a transaction. **The id
must stay opaque and generated.** The portal redaction lives inside `format` and protects that one
line; an id derived from the path would travel through `%X{requestId}` onto every line in the file,
and a portal path carries a live credential.

**Verified, not assumed.**

- **The guard fails the build on a real violation.** An untraced `ProbeUseCase` was planted in
  `src/domain/usecase/`; the real-tree assertion and the vacuity tripwire both failed. Replacing it
  with a correctly traced one left only the tripwire failing — which is the tripwire working. Removed
  afterwards.
- **The tracer's privacy assertion bites.** `outcomeOf` was mutated to return `error.toString()`;
  exactly one test failed, the one asserting the detail stays out. The invariant-3 test correctly did
  **not** fail, since `Denied` renders identically either way.
- **The MDC reaches a handler, and survives a dispatcher hop.** Asserted in `RequestCorrelationTest`
  against a `Dispatchers.IO` probe, because "Ktor propagates the MDC" is a claim about a library that
  would fail silently on a version bump — the id would render blank and traces would quietly stop
  being attributable.
- **End to end, both ways.** With `TRACE_USECASES=true`, `usecase - ProbeUseCase ok in 0ms` and its
  `GET /probe -> 200` shared one id, and two requests got different ids. Unset, zero `usecase` lines
  with request logging intact.

> **The tripwire is deliberate.** `guard integrity - the use case directory is empty` asserts
> `Vacuous` **today**. The day ERT-430 lands the first use case it fails — that is the signal, not a
> regression. Flip the expectation to `Checked`; do not delete the test.

**Acceptance criteria**
- [x] `[derived]` Given `TRACE_USECASES` is unset or blank, then the no-op tracer is bound and no
      line is emitted
- [x] `[derived]` Given a use case that fails, then the line carries `AppError.code` and not
      `AppError.Validation.detail`
- [x] `[derived]` Given a wrong PIN and an unknown token, then the two trace lines are identical
      apart from duration (§12 invariant 3)
- [x] `[derived]` Given a use case that throws, then the exception class is traced, the message is
      not, and the exception is rethrown unchanged
- [x] `[derived]` Given a file in `src/domain/usecase/` named `*UseCase.kt`, then it takes a
      `UseCaseTracer` and traces under its own file name
- [x] `[derived]` Given a use case importing `org.slf4j`, then the build fails
- [x] `[derived]` Given a request, then its handler and any coroutine it opens see one `requestId`
- [x] `[derived]` Given a portal path carrying a link token, then no part of the token appears in
      the request id (§12, invariant 4)
- [x] `[derived]` Given `src/domain/usecase/` is empty, then the guard reports vacuous rather than
      passing

**Tests**
| Level | Test |
|---|---|
| Unit | `trace line - a use case that succeeds - names the use case and reports ok` |
| Unit | `trace line - a use case that fails - reports the error code and not the error detail` |
| Unit | `trace line - a wrong pin and an unknown token - are indistinguishable in the trace` |
| Unit | `trace line - a use case that throws - reports the exception class and rethrows` |
| Unit | `trace line - the elapsed time - is reported in milliseconds` |
| Unit | `trace line - the level is above debug - the use case still runs and nothing is emitted` |
| Unit | `tracer selection - the flag is unset - binds the no-op` |
| Unit | `tracer selection - the flag is blank - binds the no-op rather than treating it as set` |
| Unit | `tracer selection - the flag is true - binds the logging tracer` |
| Unit | `tracer selection - the flag is set to something unparseable - binds the no-op` |
| Unit | `tracer selection - the no-op tracer - returns the result and emits nothing` |
| Route | `request correlation - a route handler - sees a request id in the MDC` |
| Route | `request correlation - work handed to another dispatcher - keeps the same request id` |
| Route | `request correlation - two requests - are given different ids` |
| Route | `request correlation - one request - reports one id for its whole duration` |
| Route | `request correlation - a portal path carrying a link token - the id contains no part of it` |
| Architecture | `dependency rule - a use case logs directly instead of through the port - the build fails` |
| Architecture | `use case tracing - every use case in the tree - is traced` |
| Architecture | `use case tracing - a use case that takes no tracer - the build fails` |
| Architecture | `use case tracing - a use case tracing under a copied name - the build fails` |
| Architecture | `use case tracing - a use case wired to the port under its own name - passes` |
| Architecture | `guard integrity - the use case directory is empty - the guard reports vacuous rather than passing` |
| Architecture | `guard integrity - a file beside a use case that is not one - does not make the guard look enforcing` |

**Files**
- create [`src/core/trace/UseCaseTracer.kt`](../../src/core/trace/UseCaseTracer.kt) — port and no-op
- create [`src/data/trace/Slf4jUseCaseTracer.kt`](../../src/data/trace/Slf4jUseCaseTracer.kt) — adapter and env seam
- create [`test/data/trace/Slf4jUseCaseTracerTest.kt`](../../test/data/trace/Slf4jUseCaseTracerTest.kt)
- create [`test/RequestCorrelationTest.kt`](../../test/RequestCorrelationTest.kt)
- modify [`src/di/CoreModule.kt`](../../src/di/CoreModule.kt) — one binding
- modify [`src/plugin/Monitoring.kt`](../../src/plugin/Monitoring.kt) — `mdc(REQUEST_ID)`
- modify [`resources/logback.xml`](../../resources/logback.xml) — `%X{requestId}`, `usecase` at DEBUG
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt) — 7 tests, `org.slf4j` banned
- modify `.env.example`, [`CLAUDE.md`](../../CLAUDE.md), [`docs/architecture.md`](../architecture.md)

Suite 130 → 153.

**Added beyond the original scope**

`logback.xml` used `%d{YYYY-…}`, the ISO week-year, which disagrees with the calendar year in the
last days of December. One character, in a file this ticket already edits, and the kind of defect
found a year late in a log file. Corrected to `yyyy`.

**Out of scope**
- **A Micrometer `Timer` per use case.** Strictly better for the operational question — p95 with no
  log volume and no PII surface, which is the shape PRD §13's leading indicators want — and the port
  supports a second adapter with no interface change. Blocked today: the registry lives in
  `Application.attributes` under `MeterRegistryKey`, not in Koin, so a Koin-resolved tracer cannot
  reach it. Wants its own ticket alongside the first use case that emits a business metric.
- **`X-Request-Id` on the wire.** ERT-145 deferred it deliberately: a per-request field would break
  the envelope's byte-identity test. The id here is log-side only and does not touch a response.
- **A Koin decorator over a `UseCase<C, R>` supertype**, which would remove the tracer from every
  constructor and upgrade the guard from a text check to a structural one. It needs a common
  supertype that does not exist and that "one class, one `operator fun invoke`" does not imply.
- **`StatusPages` logging the raw URI.** Found while reading `Monitoring.kt`: `StatusPages.kt:35` and
  `:50` log `call.request.local.uri` unredacted, so the first malformed body on a portal path writes
  a link token to the log. Not live while `route/portal/` is empty. Wants its own ticket.
