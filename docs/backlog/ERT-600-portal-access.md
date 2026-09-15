# ERT-600 · Epic: Portal access model

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-160, ERT-170, ERT-400 |
| **PRD** | §6.6, §6.3, §8.6, §8.12, §12 |
| **Architecture** | §5, §8, §12 invariants 2, 3, 7 |

**Description**

SEC-01 found that the upload link was an unauthenticated bearer credential with a 90-day life, and
PRD §15 is blunt about the consequence of deferring the fix: "retrofitting authentication onto a live
public endpoint is a rewrite, not an addition." **This epic must be complete before any portal
endpoint returns a byte of employee data.**

It also carries invariant 7 — every access attempt is an append-only record — and a gap in an
append-only history cannot be backfilled. Writing the trail before the endpoints is far cheaper than
auditing five handlers afterwards for the early-return paths, which is exactly where a denied attempt
goes.

The access model is deliberately single-channel and the PRD says so plainly: link and PIN both travel
in the same email, so a compromised mailbox yields portal access. That risk is formally accepted in
§12 **in exchange for** the write-mostly portal, out-of-band email-change verification, the access
trail, and HR's identity binding. Weakening any of those re-opens the decision.

**Goal**

A bare link resolves to a PIN prompt and nothing else; a correct PIN opens a revocable, listable,
time-boxed session; every attempt reaches the trail.

**Stories**
- As a New Hire, I want my link to be useless to whoever finds it in a shared browser's history so
  that my birth certificate is not one URL away.
- As an HR Officer, I want every access to a packet recorded so that "was this really the employee?"
  is an answerable question.

**Out of scope**
- The access-trail HR screen and anomaly flags (§8.12 display) — Phase 2.
- `request-new-link` — Phase 2, and SEC-09 keeps it at Medium severity.
- HR session listing and termination routes — Phase 3. The **data** must support them now.

---

## ERT-610 — `PortalAccessTrail` adapter, append-only

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-240 |
| **PRD** | §8.12, §11 |
| **Architecture** | §7, §12 invariant 7 |

**Description**

PRD v0.3 had a `last_accessed_at` column on `upload_link`. It is deliberately gone: a single
overwritten timestamp cannot answer who, from where, or how often, which is the first question asked
when a fraudulent submission surfaces (SEC-05). `portal_access_logs` replaces it and is append-only
— nothing updates or deletes a row there, ever.

The adapter must offer no mutation path. `distinctIpsFor` and `countRecentFailures` feed the anomaly
flags and the HR notification, so they need to be correct here rather than approximated.

`PortalOutcome.DENIED` covers both a wrong PIN and an unknown token and **must not record which**.
The trail is read by people, and a trail that distinguishes the two re-creates the oracle the
response was carefully built to avoid.

**Goal**

Every portal attempt can be recorded and read back, with no path that mutates history and no column
that distinguishes a wrong PIN from an unknown token.

**Stories**
- As an HR Officer, I want the full access history rather than a last-seen timestamp so that a
  takeover is provable rather than suspected.

**Acceptance criteria**
- [ ] Given any portal access attempt, then a record is written with timestamp, IP, user agent,
      action and outcome (§8.12)
- [ ] Given three accesses from different IPs, when HR opens the hire record, then all three appear
      in an access trail (§8.12)
- [ ] `[derived]` Given the adapter, then it exposes no update or delete path
- [ ] `[derived]` Given a wrong PIN and an unknown token, then both record `DENIED` and the rows are
      indistinguishable
- [ ] `[derived]` Given `countRecentFailures` with a cutoff, then only failures after that instant are
      counted
- [ ] `[derived]` Given the schema, then `upload_links` still has no `last_accessed_at` column

**Tests**
| Level | Test |
|---|---|
| Repository | `access trail - a recorded attempt - is never updated or deleted by any repository method` |
| Repository | `access trail - accesses from three addresses - distinctIpsFor returns all three` |
| Repository | `access trail - a wrong pin and an unknown token - produce indistinguishable rows` |
| Repository | `access trail - failures before the cutoff - are not counted` |

**Files**
- create `src/data/repository/ExposedPortalAccessTrail.kt`
- create `src/data/mapper/PortalAccessLogMapper.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it
- create `test/data/repository/ExposedPortalAccessTrailTest.kt`

**Out of scope**
- Anomaly detection over the trail (§8.12 flags). Phase 2.

---

## ERT-620 — `PortalSessionRepository` and the server-side session cookie

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-120, ERT-160, ERT-610 |
| **PRD** | §6.6, §8.6, §12 |
| **Architecture** | §12 invariant 2 |

**Description**

Access is carried by the session, not by the URL. The mechanism is an opaque token generated by the
existing `TokenGenerator`, stored as an ERT-160 digest in `portal_sessions.token_hash`, and delivered
as a cookie:

```
Set-Cookie: pg_portal=<token>; HttpOnly; Secure; SameSite=Strict; Path=/api/portal; Max-Age=<sessionMinutes*60>
```

Three alternatives were considered and rejected. A **Ktor `Sessions` signed cookie** carries
client-side state and cannot be revoked, which kills §8.6's requirement that HR can terminate active
sessions outright. A **bearer token in a header** needs JavaScript on every request — and the portal
is mobile-browser-first, where a plain multipart form POST cannot carry a custom header and JS-held
state is what mobile Safari discards on tab eviction mid-upload. **Ktor `Sessions` with a custom
`SessionStorage`** is revocable but is a key-value store with no `expiresAt`, `ip` or "list active
for this link", so it would mean two APIs over one table when `PortalSessionRepository` already
models it correctly. `$ktor.server.sessions` stays declared and uninstalled.

The digest, not bcrypt, hashes the session token — for the same reason as the link token in ERT-160.
Paying 100ms of bcrypt on every portal request to defend a 256-bit secret with no offline guessing
attack is a latency cost with no security return. Write that down in the file, or it reads as a
mistake.

Two consequences follow from the cookie being path-scoped rather than token-scoped. A cookie on
`Path=/api/portal` is sent for **every** `{token}` under that path, so the guard must check that the
session's `uploadLinkId` matches the link being addressed and, on mismatch, behave exactly as if
there were no session at all. And `Secure` must be config-driven off `isDevMode()` so local HTTP
still works.

For CSRF, `SameSite=Strict` plus the existing CORS default — [Http.kt](../../src/plugin/Http.kt)
permits no cross-origin host when `CORS_ALLOWED_HOSTS` is unset — is sufficient. If a cross-origin
portal host is ever configured, a double-submit token on state-changing portal routes is the answer.
Note it; do not build it.

**Goal**

A verified session is carried by an opaque, revocable, link-bound cookie, and the raw token is never
stored.

**Stories**
- As a New Hire on a phone, I want my session to survive a page reload mid-upload so that I do not
  lose progress.
- As an HR Officer, I want to be able to terminate a session so that a suspected takeover can be cut
  off.

**Acceptance criteria**
- [ ] `[derived]` Given a verified session, then the cookie is `HttpOnly`, `SameSite=Strict` and
      scoped to `/api/portal`, and `Secure` outside dev
- [ ] `[derived]` Given a stored session, then only the token digest is persisted, never the raw token
- [ ] `[derived]` Given a session cookie presented against a different link's token, then the request
      is treated as having no session and the PIN prompt is returned
- [ ] Given a verified session, then it lasts `portal.session_minutes`; when it lapses, the PIN is
      required again (§6.6)
- [ ] `[derived]` Given HR ends a session, then it stops being active but the row survives for the
      trail
- [ ] `[derived]` Given `findActiveForLink`, then it returns every live session, so the Phase 3
      listing route needs no schema change

**Tests**
| Level | Test |
|---|---|
| Repository | `portal session - a session token - resolves by digest and expires at its stored instant` |
| Repository | `portal session - HR ends a session - it is no longer active but the row survives` |
| Route | `portal session cookie - a verified session - is issued HttpOnly SameSite strict and scoped to the portal path` |
| Route | `portal session - a session issued for another link - is refused and the pin prompt is returned` |
| Route | `portal session - the session window has lapsed - the pin is required again` |

**Files**
- modify [`src/domain/port/Repositories.kt`](../../src/domain/port/Repositories.kt) — `findActive`
  takes a session id today; it must resolve by token digest
- create `src/data/repository/ExposedPortalSessionRepository.kt`
- create `src/route/portal/PortalSessionCookie.kt`
- create `src/route/portal/PortalSessionGuard.kt` — a route-scoped plugin, see ERT-630

**Out of scope**
- HR routes to list and terminate sessions. Phase 3; the data supports them already.

---

## ERT-630 — `GET /api/portal/{token}`: PIN prompt only, no packet data

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-170, ERT-620 |
| **PRD** | §8.6, Appendix B |
| **Architecture** | §9, §12 invariant 2 |

**Description**

Appendix B states it without room for interpretation: a bare link returns **nothing about the
employee** — not a name, not a requirement count, not a progress figure. The PIN prompt is all a bare
link resolves to.

The session guard is the single enforcement point for that, and the realistic failure is a new
handler that simply forgets to call it. Install it as a **route-scoped plugin on the `/api/portal`
subtree** so a handler is gated by default rather than by remembering, and add an architecture
assertion that no handler under `route/portal/` responds without passing through it.

When this route is documented, its description must present the behaviour as **intended**. A reader
who meets "identical failure for wrong PIN and unknown token" with no explanation files a bug and
"fixes" it into an enumeration oracle.

**Goal**

A bare link returns a PIN prompt and nothing else, and an unknown token is indistinguishable from a
known one.

**Stories**
- As a New Hire, I want a link found in a shared browser's history to reveal nothing about me so that
  the URL alone is inert.

**Acceptance criteria**
- [ ] Given a valid link and no verified session, when the portal is opened, then only the PIN prompt
      is returned and no requirement data of any kind (§8.6)
- [ ] `[derived]` Given an unknown token, then the response is identical to a known token with no
      session — no name, no requirement count, no progress figure
- [ ] `[derived]` Given every portal handler, then none responds without passing through the session
      guard
- [ ] `[derived]` Given the generated spec, then this route's description states the
      identical-failure behaviour as intended

**Tests**
| Level | Test |
|---|---|
| Route | `bare link - no verified session - the response carries no name, no requirement count and no progress figure` |
| Route | `bare link - an unknown token - responds identically to a known one` |
| Architecture | `portal routes - every handler - is mounted behind the session guard` |
| Route | `api docs - the portal routes - describe the identical-failure behaviour as intended` |

**Files**
- create `src/route/portal/PortalRoutes.kt`
- create `src/route/dto/portal/PortalPromptDto.kt`
- modify [`src/route/Routing.kt`](../../src/route/Routing.kt)
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt)

**Out of scope**
- Rendering HTML. This is an API; terminal-state pages are the client's job.

---

## ERT-640 — `VerifyPortalPinUseCase`

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket — **split into ERT-641…644** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-210, ERT-310, ERT-610 |
| **PRD** | §6.6, §8.6 |
| **Architecture** | §5, §8, §12 invariants 2 and 3 |

**Description**

Four sub-tasks, each one red-green-refactor cycle, built against fakes before any HTTP exists.
Six digits rather than four: a million combinations instead of ten thousand, at no usability cost.
Lockout is itself a denial of service against the employee, who then cannot submit anything, which
is why four digits would need aggressive lockout and six does not.

**Goal**

Every §6.6 rule is proven against fakes in milliseconds.

**Out of scope**
- HTTP and cookies. ERT-650.

---

### ERT-641 — Identical failure for wrong PIN and unknown token

| | |
|---|---|
| **Parent** | ERT-640 |
| **Type** | Sub-task |
| **Depends on** | ERT-210 |
| **PRD** | §6.6, §8.6 |

**Description**

`AppError.Denied` collapses both cases and carries no detail field. The result must be identical, and
the trail must record `DENIED` without recording which.

There is a second channel that is easy to miss. An unknown token short-circuits before any hash
verification, while a wrong PIN pays bcrypt's ~100ms — a difference an attacker can measure, and a
working oracle for whether a link exists. The unknown-token path must verify against a dummy hash so
both paths cost the same.

**Acceptance criteria**
- [ ] Given a wrong PIN, then the response is indistinguishable from an unknown link, and the attempt
      is logged (§8.6)
- [ ] `[derived]` Given either failure, then the trail records `DENIED` without recording which
- [ ] `[derived]` Given an unknown token, then a dummy verification is still performed so the timing
      is not an oracle
- [ ] `[derived]` Given a malformed token that cannot be a valid digest, then the response is still
      identical
- [ ] `[derived]` Given either failure and `TRACE_USECASES=true`, then the two trace lines are
      identical apart from duration — the log is the third channel, alongside the response and the
      trail (ERT-195)

**Tests**
| Level | Test |
|---|---|
| Use case | `pin verification - a wrong pin and an unknown token - return the identical Denied result` |
| Use case | `pin verification - either failure - is recorded as denied without recording which` |
| Use case | `pin verification - an unknown token - still performs a dummy verification so the timing is not an oracle` |
| Use case | `pin verification - a wrong pin and an unknown token - emit the identical trace outcome` |

**Note (ERT-195).** The generic form of the last test already exists in
`Slf4jUseCaseTracerTest` — `AppError.Denied` is one `data object` whose code is `not_found`, so both
paths trace identically by construction. Repeat it here against the real use case anyway: the
guarantee that matters is that `VerifyPortalPinUseCase` returns the *same* error on both paths, and
that is this ticket's to keep, not the tracer's.

---

### ERT-642 — Temporary lockout

| | |
|---|---|
| **Parent** | ERT-640 |
| **Type** | Sub-task |
| **Depends on** | ERT-641, ERT-220 |
| **PRD** | §6.6 |

**Acceptance criteria**
- [ ] `[derived]` Given `portal.pin_attempts_before_lockout` consecutive failures, then the link locks
      for `portal.lockout_minutes`
- [ ] `[derived]` Given a lockout is in force, then even the correct PIN is refused
- [ ] `[derived]` Given the lockout window elapses, then verification is accepted again
- [ ] `[derived]` Given a successful verification, then the consecutive-failure count resets
- [ ] `[derived]` Given a lockout, then the attempt is recorded with outcome `LOCKED_OUT`

**Tests**
| Level | Test |
|---|---|
| Use case | `pin lockout - the fifth consecutive failure - locks the link for the configured window` |
| Use case | `pin lockout - the correct pin during a lockout - is still refused` |
| Use case | `pin lockout - the window elapses - verification is accepted again` |
| Use case | `pin lockout - a successful verification - resets the consecutive failure count` |

---

### ERT-643 — Auto-suspend with HR notified

| | |
|---|---|
| **Parent** | ERT-640 |
| **Type** | Sub-task |
| **Depends on** | ERT-642 |
| **PRD** | §6.6, §8.9, §8.12 |

**Description**

A burst of failed attempts is one of the few signals of an attack while it is still happening. It
must reach a person, not only a log file.

**Acceptance criteria**
- [ ] Given the lockout or suspend thresholds in §6.6 are reached, then access is blocked and HR is
      notified (§8.6)
- [ ] Given a suspended link, then HR is notified with the reason (§8.9)
- [ ] Given a PIN failure burst, then HR is notified rather than the event only reaching a log file
      (§8.12)
- [ ] `[derived]` Given a suspension, then `PIN_FAILURE_SUSPENSION` is recorded on the hire record
- [ ] `[derived]` Given a suspended link, then it stays suspended until HR acts — it does not
      self-clear on a timer

**Tests**
| Level | Test |
|---|---|
| Use case | `pin suspension - the tenth cumulative failure - suspends the link and notifies HR` |
| Use case | `pin suspension - a suspended link - flags the hire record for HR attention` |
| Use case | `pin suspension - time passes - the link does not self-clear` |

---

### ERT-644 — Session issue and link-state gates

| | |
|---|---|
| **Parent** | ERT-640 |
| **Type** | Sub-task |
| **Depends on** | ERT-643, ERT-620 |
| **PRD** | §6.3, §6.4, §6.6, §8.6 |

**Description**

Expiry is evaluated **lazily**, at access time, by comparing now against `expiresAt` and
`idleExpiresAt`. The ERT-1020 sweep exists to send the warning email and tidy `LinkStatus` for the HR
list — the portal must never be blocked on a background job having run.

Two clocks, and the earlier one wins.

**Acceptance criteria**
- [ ] Given a correct PIN, then a session opens for `portal.session_minutes` and the checklist is
      shown (§8.6)
- [ ] Given an expired token, then an explanatory page with a "request a new link" action is shown
      (§8.6)
- [ ] Given a closed link, then a generic unavailable message is shown with no personal data (§8.6)
- [ ] `[derived]` Given the absolute expiry has passed, then the link is treated as expired even if no
      sweep has run
- [ ] `[derived]` Given the idle expiry falls before the absolute, then the earlier clock wins
- [ ] `[derived]` Given a suspended or revoked link, then no session opens
- [ ] `[derived]` Given a successful verification, then the attempt is recorded with `SUCCESS` and the
      new session id

**Tests**
| Level | Test |
|---|---|
| Use case | `portal access - the absolute expiry has passed - the link is treated as expired without a sweep having run` |
| Use case | `portal access - the idle expiry falls before the absolute - the earlier clock wins` |
| Use case | `portal access - a suspended or revoked link - opens no session` |
| Use case | `portal access - a closed link - returns a generic message carrying no name or requirement list` |
| Use case | `portal access - a correct pin - opens a session for the configured duration` |

---

## ERT-650 — `POST /api/portal/{token}/verify`

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-630, ERT-640, ERT-660 |
| **PRD** | §6.6, §8.6, Appendix B |
| **Architecture** | §8, §12 invariant 3 |

**Description**

Every attempt is written to the trail **first**, before the result is computed, so an early return
cannot skip it.

Invariant 3 must be asserted at the HTTP level and across headers, not only the body. A `Set-Cookie`
present on one failure path and absent on the other is an oracle just as surely as a different
message would be.

**Goal**

The endpoint verifies a PIN, issues a session cookie on success, and is unusable as a probe for
whether a link exists.

**Acceptance criteria**
- [ ] `[derived]` Given a correct PIN, then the session cookie is set and the response reports success
- [ ] Given a wrong PIN and an unknown token, then the responses are byte-identical in status, body
      **and headers** (§6.6)
- [ ] `[derived]` Given every attempt, then it reaches the trail before the result is computed
- [ ] `[derived]` Given a burst of attempts, then the request is rate-limited and recorded with
      `RATE_LIMITED`
- [ ] `[derived]` Given the generated spec, then the identical-failure behaviour is described as
      intended

**Tests**
| Level | Test |
|---|---|
| Route | `pin verification route - a wrong pin and an unknown token - return identical status, body and headers` |
| Route | `pin verification route - a correct pin - sets the session cookie` |
| Route | `pin verification route - a burst of attempts - is rate limited and recorded as rate limited` |

**Files**
- modify `src/route/portal/PortalRoutes.kt`
- create `src/route/dto/portal/VerifyDto.kt`

**Out of scope**
- `request-new-link`. Phase 2.

---

## ERT-660 — Rate limiting on every public portal endpoint

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-140 |
| **PRD** | §7.1, §8.7, §12 |
| **Architecture** | §14 |

**Description**

§12 requires rate limiting on all public endpoints and §8.7 has an acceptance criterion for it, but
no rate-limit plugin is installed and `ktor-server-rate-limit` is not declared in `module.yaml`.
Expect the same Amper catalog problem the architecture doc records for
`ktor-server-routing-openapi` — declare it directly in `libs.versions.toml` if there is no catalog
key.

The keying decision is the one that matters. §7.1's limits are **per requirement** and **per
employee**, not per caller: 10 per requirement per hour, 30 per employee per hour. Keying on source
IP would be wrong twice over — a phone on carrier NAT shares an address with strangers, so one
person's uploads would lock out another's, and an attacker on a different address would get a fresh
budget. Key on the link token in the path.

One exception runs the other way. The unauthenticated report-problem endpoint (ERT-930) must be
limited by source as well, because it suspends a link without a session.

**Goal**

Public portal endpoints are limited per §7.1, keyed on the link rather than the caller, and a limited
request reaches the trail.

**Stories**
- As a New Hire on mobile data, I want the limit counted against my own link so that a stranger
  sharing my carrier's address cannot lock me out.

**Acceptance criteria**
- [ ] Given the rate limit is exceeded, then a clear retry-later message is shown (§8.7)
- [ ] `[derived]` Given 10 uploads against one requirement within an hour, then the eleventh is
      refused
- [ ] `[derived]` Given 30 uploads across an employee's requirements within an hour, then the
      thirty-first is refused
- [ ] `[derived]` Given two links sharing a source address, then one link's traffic does not limit the
      other
- [ ] `[derived]` Given a rate-limited portal request, then the attempt is recorded with outcome
      `RATE_LIMITED`
- [ ] `[derived]` Given a limited response, then it states when to retry and leaks no personal data

**Tests**
| Level | Test |
|---|---|
| Route | `upload rate limit - the eleventh upload against one requirement in an hour - is refused with a retry-later message` |
| Route | `upload rate limit - the thirty-first upload across an employee in an hour - is refused` |
| Route | `rate limiting - two links sharing a source address - one link's traffic does not limit the other` |
| Route | `rate limiting - a limited portal request - is recorded with outcome rate limited` |

**Files**
- modify [`libs.versions.toml`](../../libs.versions.toml) and [`module.yaml`](../../module.yaml)
- create `src/plugin/RateLimiting.kt`
- modify [`src/Application.kt`](../../src/Application.kt)

**Out of scope**
- Distributed limiting across instances. Phase 1 runs one instance; record the assumption.
- HR-side limits. The HR surface is authenticated and low-volume.
