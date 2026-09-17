# ERT-1200 · Epic: Environments, containerisation and deployment

| | |
|---|---|
| **Type** | Epic |
| **Phase** | Cross-cutting |
| **Status** | In progress |
| **Depends on** | ERT-1120, ERT-1160 |
| **PRD** | §12, §14 Q20 |
| **Architecture** | §14 |

> **This epic exists because ERT-1100 refused to write it.** ERT-1100's **Out of scope** reads: *"The
> deployment runbook itself. ERT-1120 produces the configuration behaviour; the runbook is a document
> someone writes once a target environment exists."* There was no target environment. This epic
> creates one, which is why it is a new epic rather than four more tickets under ERT-1100.

**Description**

The application has run on one engineer's laptop since the first commit. There is no `Dockerfile`, no
`.dockerignore`, no compose file, no CI and no deployment target. `./kotlin run` works on a fresh
checkout **only because `DATABASE_URL` is unset and silently falls back to in-memory H2** — the same
silent fallback that, in a production container, is a green deploy writing to a database that
evaporates on restart (ERT-1241).

Three facts decided the shape of this epic, and each was verified rather than assumed:

- **Amper can produce a runnable artefact.** `./kotlin package --format=executable-jar` emits a 58 MB
  Spring-Boot-loader jar (`Start-Class`, `BOOT-INF/lib`) that boots and serves `/health` in four
  seconds. The default `./kotlin build` jar is thin — `Main-Class` with no `Class-Path` — so
  `java -jar` on it fails. Without the `package` task this epic would have had to reconstruct a
  classpath from the Amper dependency cache.
- **The runtime is already Cloud-Run-shaped.** `src/main.kt` honours `PORT` and binds `0.0.0.0`;
  logs go to stdout; `GET /health` is public and cheap. What is missing is everything around it.
- **PRD §14 Q20 already chose GCP.** This epic does not pick a cloud. It builds what that answer
  implies, and answers the multi-instance question architecture §14 left open.

**Goal**

A developer runs the system locally in one command, every merge to `main` reaches a UAT service
automatically, production runs the exact image UAT tested, and a misconfigured deployment fails at
startup rather than serving permissively.

**Stories**
- As a developer joining this project, I want one documented command that gives me a running system
  with a real database, so that my first day is not spent reconstructing an environment.
- As whoever operates this, I want production to run the bytes that passed UAT, so that "it worked in
  UAT" is a statement about the artefact rather than about the source it was built from twice.
- As a security reviewer, I want a forgotten environment variable to stop the service, so that the
  difference between a hardened deployment and an open one is not a variable somebody remembered.

**Out of scope**
- The GCS `DocumentStorage` adapter. ERT-710 has not shipped a filesystem adapter yet, so there is
  nothing to port. ERT-1120 gains the refusal that stops the dev adapter reaching Cloud Run; the GCS
  implementation belongs with ERT-710's epic.
- Terraform. The runbook is the source of truth until there is a reason for it not to be.
- Log retention, alerting policies and on-call. Each needs the environment to exist first.

---

## ERT-1210 — Pin the JVM target and the main class

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **gate before ERT-1220** |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | — |
| **Architecture** | §14 |

**Description**

`./kotlin show settings` reports `release: 25  # default, from ${jdk.version}` and
`mainClass: null  # default`. Both are **inferred from whichever JDK the Amper wrapper happens to
provision**, which is 25 today and will not be 25 forever — `eclipse-temurin:26-jre` already exists.

That is fine while the only thing that runs this code is a laptop. It stops being fine the moment a
runtime base image has to be chosen, because the image encodes a decision the build never made. An
unpinned target drifts silently: the build succeeds, the container starts failing with
`UnsupportedClassVersionError`, and the change that caused it was a toolchain upgrade nobody
reviewed.

Pin `release` explicitly and leave `jdk.version` at its default, so the **toolchain stays free to
upgrade while the artefact contract does not move**. Pin `mainClass` in the same change: the
executable jar's `Start-Class` is otherwise inferred from whatever `main()` the compiler finds.

The pin is worthless without a guard, so CI reads the class-file major version and fails on anything
but 65 (ERT-1160 carries the step).

**Goal**

The bytecode target is a reviewed decision in `module.yaml`, not a side effect of the toolchain.

**Acceptance criteria**
- [x] `[derived]` Given `module.yaml`, then `settings.jvm.release` and `settings.jvm.mainClass` are
      set explicitly
- [x] `[derived]` Given a build, then compiled classes carry major version 65 (Java 21)
- [x] `[derived]` Given `settings.jvm.jdk.version`, then it is **not** pinned — the toolchain may
      still upgrade its own JDK
- [x] `[derived]` Given the full suite, then all 592 tests still pass on the lowered target

**Files**
- modify [`module.yaml`](../../module.yaml)

**Out of scope**
- The CI step that asserts the version. ERT-1160 owns the workflow file.

---

## ERT-1220 — The production container image

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting |
| **Status** | Done |
| **Depends on** | ERT-1210, ERT-1240 |
| **PRD** | — |
| **Architecture** | §14 |

**Description**

A two-stage `Dockerfile`. The build stage runs the Amper wrapper, which downloads the Kotlin CLI, a
JDK and ~176 dependency jars — so **layer ordering and a BuildKit cache mount are the difference
between a 30-second rebuild and a five-minute one**. `HOME` is `/root` in a builder, so the wrapper's
Linux default shared cache root is `/root/.cache/JetBrains/Kotlin` and one mount covers both the CLI
and the dependency cache; no `--shared-cache-dir` flag is needed.

**The builder must not be Alpine.** The Temurin JDK the wrapper downloads is glibc-linked and fails
on musl with an opaque "not found" on a binary that plainly exists.

**The jar path is discovered, not hardcoded**, and asserted. `./kotlin package --format=executable-jar`
writes `build/tasks/_<module>_executableJarJvm/<module>-jvm-executable.jar`, but that path is an
Amper implementation detail — and it **differs between the host and the container**: the module name
comes from the directory, so a host build writes `_PG-EmployeeRequirementsTracker_executableJarJvm/`
and the container, whose `WORKDIR` is `/src`, writes `_src_executableJarJvm/src-jvm-executable.jar`.
A path copied from a host build would have failed on the first container build. Two assertions turn a silent wrong artefact into a build failure: the
manifest's **`Start-Class`** names `MainKt` — note `Start-Class`, **not** `Main-Class`, which names
Spring Boot's `JarLauncher` — and the jar exceeds 5 MB, i.e. it is not the 745 KB thin jar.

`.dockerignore` matters more than it looks: `build/` is **326 MB**, of which `build/logs` alone is
290 MB. Without that one line every image build ships a third of a gigabyte of build logs as context.

**Goal**

One command produces an image that boots with no external service, as a non-root user.

**Acceptance criteria**
- [x] `[derived]` Given `docker build`, then an image is produced and both jar assertions pass
- [x] `[derived]` Given the image run with `APP_ENV=dev` and no `DATABASE_URL`, then it migrates H2
      and answers `GET /health` — proving jar, JVM flags, non-root user, writable cwd, Flyway and
      the Netty bind in one step
- [x] `[derived]` Given the running container, then the process runs as a non-root uid
- [x] `[derived]` Given `SIGTERM`, then the pool-closing log line appears before exit — i.e. `java`
      is PID 1 and the shutdown hook ran
- [x] `[derived]` Given a source-only change, then the dependency layers are reused
- [x] `[derived]` Given `.dockerignore`, then `build/` is excluded

**Files**
- create `Dockerfile`, `.dockerignore`

**Out of scope**
- Pushing or scanning the image. ERT-1270.

---

## ERT-1230 — Local development: compose, and the three ways to run

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting |
| **Status** | Done |
| **Depends on** | ERT-1220, ERT-1120 |
| **PRD** | — |
| **Architecture** | §14 |

**Description**

Postgres is the production database and H2 is what every developer actually runs, which means
**nobody exercises the production database until UAT does**. `MigrationTest` checks portability, but
H2-in-PostgreSQL-mode is not a perfect oracle and was never meant to be one.

A compose file with `postgres:17` fixes that without taking away the zero-config path. The app sits
behind a `profiles: ["app"]` gate so the **default `docker compose up` starts only Postgres** — a
developer then runs `./kotlin run` against it and keeps the fast edit-compile loop. Running the
container too is opt-in, for reproducing exactly what CI builds.

Two details that are not incidental. Postgres publishes on `127.0.0.1:55432`, because 5432 collides
with whatever is already installed on the machine. And its healthcheck names the role and database
(`pg_isready -U ert -d ert`) rather than being a bare `pg_isready`, because the Postgres entrypoint
runs a **temporary server during `initdb`** that a bare check passes — the app would then be released
to connect to a database that is about to be torn down and rebuilt.

**`.gitignore` currently defeats this ticket and ERT-1120.** It ignores `.env.*` and re-includes only
`!.env.example`, so ERT-1120's committed `.env.dev` would be silently untracked and its third
acceptance criterion unmeetable. The re-include list has to grow — and because that list is fragile,
the secret scanner in ERT-1160 is what makes committing `.env.*` files safe rather than merely
convenient.

**Goal**

A developer runs the system against a real Postgres in one command, and the zero-config path still
works.

**Acceptance criteria**
- [x] `[derived]` Given `docker compose up -d`, then Postgres is healthy and reachable on 55432
- [x] `[derived]` Given `docker compose --profile app up --build`, then the app starts only after
      Postgres is healthy and answers `/health`
- [x] `[derived]` Given the app container, then it fails to start if Postgres is unreachable — there
      is no degraded mode and the compose file must not hide that
- [x] `[derived]` Given a fresh checkout, then `.env.dev` is tracked by git
- [x] `[derived]` Given the documentation, then all three ways to run are stated with their trade-offs

**Files**
- create `docker-compose.yml`, `.env.docker`, `../../.env`
- modify [`.gitignore`](../../.gitignore)

---

## ERT-1240 — Runtime hardening for a container: pool, shutdown, boot-time codegen

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **gate before ERT-1270** |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §12 |
| **Architecture** | §14 |

**Description**

Three defects that are invisible on a laptop and load-bearing on Cloud Run.

**The connection pool has no timeouts.** [`DatabaseFactory`](../../src/data/db/DatabaseFactory.kt)
sets `maximumPoolSize`, `isAutoCommit` and `transactionIsolation` and nothing else. Two consequences.
`connectionTimeout` defaults to 30 seconds, and `Dispatchers.IO` has 64 threads against a pool of
five — so a contended instance parks 64 coroutines for half a minute while Cloud Run's request
timeout has not noticed anything is wrong. Failing fast with a 503 is the correct behaviour, because
it is also the signal that makes Cloud Run scale out. Separately, `maxLifetime` and `keepaliveTime`
are what stop a connection the network already dropped from being handed out as healthy — the
symptom is `connection reset by peer` on the first request after a quiet period, which is precisely
what a `min-instances=1` service does overnight.

**The shutdown window is one second.** Ktor already registers the shutdown hook — verified — so
`ApplicationStopping` fires and `DatabaseFactory.close()` runs on SIGTERM. But `shutdownGracePeriod`
defaults to 1000 ms against Cloud Run's 10-second SIGTERM→SIGKILL window. Every rolling deploy
therefore abandons in-flight requests it had time to finish, and leaves Postgres backends to age out
server-side. Raising it needs the `embeddedServer` overload that takes `configure`; the current
four-argument overload has none.

**swagger-codegen runs at every boot.** [`ApiDocs.kt`](../../src/plugin/ApiDocs.kt) generates HTML
into the **relative** path `build/openapi-docs` on every start — confirmed in a container boot log. In
production that is a filesystem write from a non-root user, on the cold-start path, into a tmpfs
charged against the memory limit, producing output nobody reads. Gate the **generator**, not the
routes: `/openapi` and `/swagger` keep their existing `isDevMode()` behaviour.

**Goal**

The runtime behaves correctly under a container's constraints: bounded waits, a real shutdown window,
and no filesystem writes on the production path.

**Acceptance criteria**
- [x] `[derived]` Given the pool, then `connectionTimeout`, `maxLifetime`, `keepaliveTime`,
      `validationTimeout` and `minimumIdle` are set, and each is overridable by environment variable
- [x] `[derived]` Given a pool with no free connection, then acquisition fails in bounded time rather
      than the Hikari default of 30 seconds
- [x] `[derived]` Given `SIGTERM`, then in-flight requests get the configured grace period before the
      hard stop
- [x] `[derived]` Given `APP_ENV` is not `dev`, then swagger-codegen does not run and nothing is
      written to `build/openapi-docs`
- [x] `[derived]` Given `APP_ENV=dev`, then the generated docs behave exactly as they do today

**Tests**
| Level | Test |
|---|---|
| Use case | `database config - no pool environment variables - applies the documented defaults` |
| Use case | `database config - a pool timeout override - is read from the environment` |
| Route | `api docs - outside dev - does not run the html generator` |
| Route | `api docs - in dev - generates the html as before` |

**Files**
- modify [`src/data/db/DatabaseFactory.kt`](../../src/data/db/DatabaseFactory.kt),
  [`src/main.kt`](../../src/main.kt), [`src/plugin/ApiDocs.kt`](../../src/plugin/ApiDocs.kt)
- modify [`.env.example`](../../.env.example)

---

## ERT-1241 — `DATABASE_URL` fails closed outside dev

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Done |
| **Depends on** | ERT-1120 |
| **PRD** | §12 |
| **Architecture** | §12 invariant 10, §14 |

**Description**

`JWT_SECRET`, `TOKEN_PEPPER` and `PORTAL_BASE_URL` all `check(devMode)` and refuse to start when
unset outside dev. **`DATABASE_URL` does not.** `DatabaseConfig.fromEnvironment` takes no `devMode`
parameter and falls back to `jdbc:h2:mem:ert`, user `sa`, empty password.

So a production deploy that loses `DATABASE_URL` — a typo in an env file, a secret that failed to
mount — does not crash. It starts on an **in-memory database**, migrates a fresh schema into it, and
(because `HR_BOOTSTRAP_*` are set) creates a working admin account. `/health` returns 200. HR signs
in, creates hires, and every one of them disappears at the next instance recycle. It is an integrity
failure that presents as a green deploy, and it is the **only** configuration path in the codebase
that does not follow the project's own documented fail-loudly contract.

This is filed separately from ERT-1120 because ERT-1120 is about `APP_ENV` itself. This is one
config reader that was written before that contract existed and never revisited.

**Goal**

Every secret-or-destination configuration value fails the same way: loudly, at startup, outside dev.

**Acceptance criteria**
- [x] `[derived]` Given `APP_ENV` is not `dev` and `DATABASE_URL` is unset, then startup refuses
- [x] `[derived]` Given `APP_ENV=dev` and `DATABASE_URL` unset, then the H2 fallback applies as today
- [x] `[derived]` Given a blank `DATABASE_URL`, then it is treated as unset — matching every other
      reader, which use `takeUnless(String::isBlank)` precisely because sourcing an env file exports
      empty strings
- [x] `[derived]` Given the refusal message, then it names the variable and no value

**Tests**
| Level | Test |
|---|---|
| Use case | `database config - production with no database url - refuses to start` |
| Use case | `database config - dev with no database url - falls back to in-memory h2` |
| Use case | `database config - a blank database url - is treated as unset` |

**Files**
- modify [`src/data/db/DatabaseFactory.kt`](../../src/data/db/DatabaseFactory.kt),
  [`src/di/DataModule.kt`](../../src/di/DataModule.kt)
- modify [`.env.example`](../../.env.example)

---

## ERT-1245 — The password-change gate three HR routes skip

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — any time |
| **Status** | Done |
| **Depends on** | ERT-190 |
| **PRD** | §8.13 |
| **Architecture** | §12 |

**Description**

[`AuthGates.kt`](../../src/route/auth/AuthGates.kt) states the rule plainly: *"Every HR route calls
this. Change-password calls `hrPrincipalOrRefuse` instead, and is the only thing in the codebase that
may."* [`AuthRoutes.kt`](../../src/route/hr/AuthRoutes.kt) restates it.

Three routes do not.
[`RequirementTemplateRoutes.kt`](../../src/route/hr/RequirementTemplateRoutes.kt) and both handlers in
[`ReferenceRoutes.kt`](../../src/route/hr/ReferenceRoutes.kt) call their repository straight through
with no gate. An account carrying `pwd_change=true` — the bootstrap admin before first sign-in, or
anyone whose password an admin has just reset — can read the requirement catalogue, the department
list and the employment types.

The data is low-sensitivity and that is not the point. **The stated invariant and the actual control
disagree**, and `ReferenceRoutes.kt` documents the wrong mental model while doing it: *"the gate is
applied once, around every HR route"* — true of `authenticate`, false of the password gate. A reader
who trusts that comment writes the next route the same way.

Found during the ERT-1200 deployment audit, and fixed here rather than filed, because deployment is
what puts these routes on the internet.

**The three-line fix is not the deliverable.** A comment is what spread this from one file to the
next, and a comment cannot stop the fourth route — so the change also adds an architecture guard that
fails the build on any handler under `route/hr/` whose body does not open with one of the three
gates, with `/api/auth/login` on an explicit allow-list because sign-in is how a caller obtains the
credential every other route requires. Adding to that list costs an edit and a reviewer's attention,
which is what an inferred rule would have given away.

**Goal**

The gate the code documents is the gate the code applies.

**Acceptance criteria**
- [x] `[derived]` Given a token with `pwd_change=true`, then `GET /api/requirement-templates`,
      `GET /api/departments` and `GET /api/employment-types` all refuse
- [x] `[derived]` Given a token with `pwd_change=false`, then all three respond as before
- [x] `[derived]` Given `ReferenceRoutes.kt`, then its comment no longer claims the gate is applied
      route-wide
- [x] `[derived]` Given any handler under `route/hr/` that does not open with a gate, then the
      architecture test fails the build

**Tests**
| Level | Test |
|---|---|
| Route | `requirement templates - a caller who must change their password - is refused` |
| Route | `departments - a caller who must change their password - is refused` |
| Route | `employment types - a caller who must change their password - is refused` |
| Route | `reference data - a caller in good standing - is served` |
| Architecture | `hr routes - every handler under route hr - opens with an authorisation gate` |

**Files**
- modify [`src/route/hr/RequirementTemplateRoutes.kt`](../../src/route/hr/RequirementTemplateRoutes.kt),
  [`src/route/hr/ReferenceRoutes.kt`](../../src/route/hr/ReferenceRoutes.kt)
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt) — the guard

**Out of scope**
- Making the gate structural rather than per-handler — an interceptor, or a route builder that cannot
  mount an ungated handler. Worth doing and worth its own ticket. The guard above detects the
  omission; it does not make it unrepresentable.

---

## ERT-1250 — JSON logging for Cloud Logging

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting |
| **Status** | Done |
| **Depends on** | — |
| **PRD** | §13 |
| **Architecture** | §14 |

**Description**

[`logback.xml`](../../resources/logback.xml) is a plain-text `ConsoleAppender`, which is correct for
a terminal and wrong for Cloud Logging. Cloud Logging ingests **one entry per stdout line**, so a
stack trace becomes thirty separate entries whose order is not guaranteed, all at default severity.
Three concrete losses: you cannot alert on errors, because nothing is classified `ERROR`; you cannot
read a trace, because it is fragmented; and `%X{requestId}` — the ERT-195 correlation id, the single
most useful field in the system — is embedded in a formatted string rather than being a field you can
filter on.

The fix is a **second** configuration file selected by `-Dlogback.configurationFile`, not a change to
the existing one. `logback.xml` stays exactly as it is, so `./kotlin run` and the test suite keep the
human-readable pattern and nothing about local development changes.

Use Logback's built-in `JsonEncoder`. It adds **no dependency**, which matters because ERT-1140 is
specifically about removing dependencies the build does not use. The known cost: its field is `level`,
not `severity`, so Cloud Logging will not classify entries natively — recovered with a log-based
metric on `jsonPayload.level="ERROR"`, which is where alerting configuration belongs anyway.

**Goal**

One log event is one Cloud Logging entry, and the correlation id is a field rather than a substring.

**Acceptance criteria**
- [x] `[derived]` Given the container, then log output is one JSON object per event
- [x] `[derived]` Given an exception, then its stack trace is one entry, not one entry per frame
- [x] `[derived]` Given a request, then `requestId` is a structured field
- [x] `[derived]` Given `./kotlin run` and `./kotlin test`, then output is unchanged from today
- [x] `[derived]` Given the JSON configuration, then it carries the same logger levels as the
      text one — a second file is a second thing to keep in step, and that must be stated

**Files**
- create [`resources/logback-gcp.xml`](../../resources/logback-gcp.xml)

**Note for ERT-1220.** The file is under `resources/`, so it is packaged **inside** the jar at
`BOOT-INF/classes/logback-gcp.xml`. The container selects it with
`-Dlogback.configurationFile=logback-gcp.xml` — a classpath-relative name, verified working — and the
image does **not** need to copy the file in separately.

**Out of scope**
- `X-Cloud-Trace-Context` correlation, which would nest a request's log lines under its request
  entry. A genuine operational win and a change to the request-correlation plugin; its own ticket.

---

## ERT-1260 — GCP foundation: identity federation, registry, network, database, secrets

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **gate before ERT-1270** |
| **Status** | Not started |
| **Depends on** | — |
| **PRD** | §14 Q20 |
| **Architecture** | §14 |

**Description**

A runbook a human executes once, in Cloud Shell. It is a ticket rather than a page of documentation
because it has acceptance criteria that can be checked, and because the decisions inside it are
architectural.

**Authentication is Workload Identity Federation — no service-account key, ever.** The
`--attribute-condition` on the OIDC provider is mandatory, not optional hardening: without it, any
GitHub repository on earth can mint tokens against the pool.

**Four service accounts, not one.** Two deployers and two runtimes, so that "UAT cannot touch prod"
and "prod cannot build an image" are IAM facts rather than conventions. The prod deployer is bound to
the exact OIDC subject `repo:OWNER/REPO:environment:production`, which is what makes the GitHub
Environment's required reviewers a real control — **editing the workflow file cannot bypass it**.

**Cloud SQL connectivity is Direct VPC egress to a private IP.** Of the three options it is the only
one needing **zero code and zero dependency change**, because `DatabaseConfig` already selects the
driver on `url.startsWith("jdbc:postgresql")`. It costs nothing — a Serverless VPC Access *connector*
would be a standing monthly charge; Direct VPC egress has no VM — and it lets the instance drop its
public IP entirely. The argument that decides it: Flyway runs synchronously **before Netty binds** and
the service has no degraded mode, so the socket-factory alternative would put the Cloud SQL Admin API
on the boot path and convert an API hiccup into a total outage.

**Goal**

Every credential, identity and network path a deployment needs exists, is least-privileged, and is
written down.

**Acceptance criteria**
- [ ] `[derived]` Given the provider, then it carries an attribute condition restricting it to this
      repository's owner
- [ ] `[derived]` Given the prod deployer, then only a job running in the `production` GitHub
      Environment can impersonate it
- [ ] `[derived]` Given the prod deployer, then it has Artifact Registry **reader** and cannot push
- [ ] `[derived]` Given the registry, then tags are immutable
- [ ] `[derived]` Given the Cloud SQL instance, then it has no public IP
- [ ] `[derived]` Given each secret, then the accessor role is granted per secret to the runtime
      service account, never project-wide
- [ ] `[derived]` Given no service-account JSON key exists anywhere, then the runbook never creates one

**Files**
- create `deploy/uat.env.yaml`, `deploy/prod.env.yaml`
- create [`docs/deployment.md`](../deployment.md)

**Out of scope**
- Terraform. The runbook is the source of truth for now.

---

## ERT-1270 — `deploy-uat.yml`: every merge reaches UAT

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting |
| **Status** | In progress |
| **Depends on** | ERT-1220, ERT-1240, ERT-1260, ERT-1160 |
| **PRD** | — |
| **Architecture** | §14 |

> **Status note, 2026-09-17: the workflow file is written and committed; nothing has run it.** It
> cannot run until ERT-1260 creates the pool, the service accounts, the registry and the secrets, and
> nothing in this repository can create those — they need a GCP project and an Owner. So this ticket
> is *In progress* rather than *Done*: the deliverable exists and is reviewable, and it is unproven.
> The first merge to `main` after ERT-1260 is what closes it.

**Description**

On push to `main`: build the image, push it tagged with the commit SHA, deploy to the `ert-uat`
Cloud Run service **by digest**, and verify `/health` on the new revision.

UAT is `--no-allow-unauthenticated`. Testers reach it with `gcloud run services proxy`, which gives an
authenticated tunnel on localhost with no load balancer, no IAP and no cost. For a handful of HR staff
that is the right amount of infrastructure, and it means UAT is not a second public attack surface.

The workflow needs `permissions: id-token: write` — without it Workload Identity Federation cannot
work at all — and `concurrency: cancel-in-progress: false`, because a deploy in flight must never be
cancelled by the next merge.

**UAT does not faithfully test background work**, and that must be recorded rather than discovered.
It runs with CPU throttling and `min-instances=0`, so when ERT-1010's outbox poller or ERT-1020's
scheduler is under test, UAT has to be flipped to `--no-cpu-throttling --min-instances=1` for the
duration.

**Goal**

Merging to `main` produces a running UAT service without anyone opening a terminal.

**Acceptance criteria**
- [ ] `[derived]` Given a push to `main`, then an image is built, pushed and deployed to `ert-uat`
- [ ] `[derived]` Given the deploy, then the service is addressed by **digest**, not by tag
- [ ] `[derived]` Given the new revision, then the workflow verifies `/health` before finishing
- [ ] `[derived]` Given the service, then unauthenticated requests are refused
- [ ] `[derived]` Given a second push while a deploy runs, then the first is not cancelled
- [ ] `[derived]` Given the documentation, then UAT's unfaithfulness to background work is stated

**Files**
- create `.github/workflows/deploy-uat.yml`

---

## ERT-1280 — `deploy-prod.yml`: promote the digest UAT ran

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting |
| **Status** | In progress |
| **Depends on** | ERT-1270 |
| **PRD** | — |
| **Architecture** | §14 |

> **Status note, 2026-09-17: the workflow file is written and committed; nothing has run it.** It
> cannot run until ERT-1260 creates the pool, the service accounts, the registry and the secrets, and
> nothing in this repository can create those — they need a GCP project and an Owner. So this ticket
> is *In progress* rather than *Done*: the deliverable exists and is reviewable, and it is unproven.
> The first merge to `main` after ERT-1260 is what closes it.

**Description**

Manual `workflow_dispatch`, gated by the `production` GitHub Environment, promoting **the image
digest UAT is currently serving** — read off `gcloud run services describe ert-uat` rather than
rebuilt from a tag.

**Rebuilding is the thing this ticket exists to prevent.** A rebuild from the same commit is a
different image: a different base-image digest, differently resolved transitive dependencies,
different timestamps. The entire value of a UAT gate is that production runs the *bytes* UAT ran, and
a tag-triggered rebuild quietly throws that away. The workflow refuses if the resolved image is not
digest-pinned.

Rollout is a canary: deploy `--no-traffic --tag=candidate`, smoke the tag URL, then
`update-traffic --to-latest`. Two things that buys. **Flyway runs on exactly one instance**, under a
URL no user has, before any traffic moves — which is the mitigation for concurrent migration at
scale-out. And a boot failure from a bad secret or an unreachable database is caught while 100 % of
traffic is still on the old revision.

**The rollback caveat is the important half of this ticket.** Traffic shifts back in seconds;
**the migration does not**. Flyway has no undo. So every migration must be backward-compatible with
the revision before it — add columns nullable, never rename or drop in the same release that starts
using the new shape, expand then contract. That is a standing architectural rule, and it belongs in
[`docs/architecture.md`](../architecture.md) §14 rather than in a comment in a workflow file.

**Goal**

Production runs a reviewed, human-approved promotion of an artefact that has already run in UAT.

**Acceptance criteria**
- [ ] `[derived]` Given a dispatch with no input, then the digest currently served by `ert-uat` is
      promoted
- [ ] `[derived]` Given a resolved image that is not digest-pinned, then the workflow refuses
- [ ] `[derived]` Given the deploy, then the candidate receives no traffic until `/health` answers
- [ ] `[derived]` Given the prod deployer's IAM, then it cannot push an image even if the workflow
      asked it to
- [ ] `[derived]` Given the runbook, then rollback is documented **with** the statement that
      migrations do not roll back
- [ ] `[derived]` Given architecture §14, then the backward-compatible-migration rule is recorded

**Files**
- create `.github/workflows/deploy-prod.yml`
- modify [`docs/architecture.md`](../architecture.md) §14, [`docs/deployment.md`](../deployment.md)

**Out of scope**
- Extracting Flyway into a migrate-only Cloud Run Job. Correct for the first migration that rewrites
  data rather than adding structure; ERT-1285 carries it.

---

## ERT-1285 — Move migrations out of startup before the first data-rewriting migration

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **gate before any migration that rewrites data** |
| **Status** | Not started |
| **Depends on** | ERT-1280 |
| **PRD** | — |
| **Architecture** | §14 |

**Description**

Migrations run in-process, synchronously, before Netty binds. For the six migrations that exist —
all DDL and seed data — that is the right design and ERT-110 argued it well: an unreachable database
aborts startup instead of producing 500s.

It stops being the right design the first time a migration **rewrites data**. Such a migration takes
minutes, not milliseconds, and during it every starting instance blocks on Flyway's advisory lock
holding a connection and not binding its port. The canary rollout in ERT-1280 contains the blast
radius; it does not make a ten-minute migration survivable inside a startup probe.

This ticket is filed now, `Not started`, **so the decision is on the board rather than discovered
during an incident.** The trigger is stated and checkable: the first migration whose SQL is not
purely additive.

**Goal**

The team decides how long migrations may take before a migration forces the question.

**Acceptance criteria**
- [ ] `[derived]` Given a migrate-only entry point, then it runs Flyway and exits with a non-zero
      status on failure
- [ ] `[derived]` Given the service, then migration at startup can be disabled by configuration
- [ ] `[derived]` Given the pipeline, then migration runs as a Cloud Run Job before the deploy
- [ ] `[derived]` Given a failed migration, then the deploy does not proceed

**Files**
- create `src/MigrateMain.kt`
- modify [`src/plugin/Database.kt`](../../src/plugin/Database.kt), `.github/workflows/deploy-prod.yml`

---

## ERT-1290 — The deployment security and bottleneck audits

| | |
|---|---|
| **Parent** | ERT-1200 |
| **Type** | Ticket |
| **Phase** | Cross-cutting — **Phase 1 exit checklist** |
| **Status** | Done |
| **Depends on** | ERT-1270 |
| **PRD** | §12, §13 |
| **Architecture** | §14 |

**Description**

[`docs/2026-09-09-security-audit.md`](../2026-09-09-security-audit.md) was a **specification** audit
of PRD v0.3, and its Scope says so: *"Not covered: infrastructure and hosting choices…"*. There is now
an implementation and a hosting choice, and neither has been audited.

Two audits, two documents, following the house pattern that audit already set — `## Scope`,
`## Summary`, `## Findings`, `## Task plan`, `## Dispositions`. Findings that this epic fixed are
recorded as closed with the ticket that closed them; findings it did not fix become numbered tickets,
because the roadmap's own stated lesson is that **a gap without a number is invisible — prose has no
status field.**

An audit that lists only faults gets read as noise, so both documents must also record what is right.
Two things earn it: the single `suspend` transaction entry point with a correct `Dispatchers.IO` hop
and zero blocking `transaction {}` on a request thread, and the three-algorithm credential split —
bcrypt cost 12 for secrets that are verified, keyed HMAC-SHA-256 for tokens that are looked up,
`SecureRandom` for generation — with the reasoning written down at each site.

**Goal**

Every security and performance risk in the running system has a number, a severity and a disposition.

**Acceptance criteria**
- [x] `[derived]` Given the audits, then both cite source files and lines rather than PRD sections —
      this is a code and infrastructure audit, not a specification one
- [x] `[derived]` Given each finding, then it carries a severity and a disposition
- [x] `[derived]` Given a finding not fixed in this epic, then it has a ticket number
- [x] `[derived]` Given a finding fixed in this epic, then it names the ticket that closed it
- [x] `[derived]` Given both documents, then each records what the design gets right, not only what
      it gets wrong

**Files**
- create `docs/2026-09-17-security-audit.md`, `docs/2026-09-17-bottleneck-audit.md`
- modify [`docs/roadmap.md`](../roadmap.md) — the escalation table
