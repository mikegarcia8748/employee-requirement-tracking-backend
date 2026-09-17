# ERT-600 · Epic: Portal access model

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-160, ERT-170, ERT-400 |
| **PRD** | §6.6, §6.3, §8.6, §8.12, §12 |
| **Architecture** | §5, §8, §12 invariants 2, 3, 7 — **all three reworded 2026-09-16** |

**Description**

**The access model changed on 2026-09-16, and this epic changed with it.** The link alone now opens
the portal; the 6-digit PIN is no longer a per-session gate but an **HR-issued recovery credential**
for a hire whose invitation never arrived (PRD §6.6). SEC-01 is Critical again, accepted in writing in
PRD §12 with a dated risk statement. Read that before taking any ticket here — the tickets below are
written against the new model, and any older copy of §6.6 you meet is superseded.

What did **not** change is why this epic runs before any portal endpoint returns employee data. PRD
§15 is blunt: "retrofitting authentication onto a live public endpoint is a rewrite, not an
addition." The session boundary, the token digest lookup, and the trail are all cheaper to build
first than to retrofit. **This epic must be complete before any portal endpoint returns a byte of
employee data.**

It also carries invariant 7 — every access attempt is an append-only record — and a gap in an
append-only history cannot be backfilled. Writing the trail before the endpoints is far cheaper than
auditing five handlers afterwards for the early-return paths, which is exactly where a denied attempt
goes.

**What the epic now protects.** With no second factor on the normal path, three controls carry the
whole of the accepted risk, and weakening any of them re-opens the §12 decision:

1. **The write-mostly portal** (§8.6, ERT-170's build guard). Load-bearing, not merely cheap.
2. **The access trail** (invariant 7), which is how a leaked link is detected after the fact.
3. **Rate limiting on `GET /api/portal/{token}`** (ERT-660), now the only barrier to guessing a token
   — it moved here from the verify step, which no longer exists on the normal path.

**Goal**

A valid link opens a revocable, time-boxed session and nothing else opens one, except an HR-issued
recovery PIN redeemed on a page that discloses nothing before it verifies; every attempt reaches the
trail.

**Stories**
- As a New Hire, I want to open my checklist from the email I was sent, without a code to find or
  type, so that uploading from my phone takes one tap.
- As a New Hire whose invitation never arrived, I want another way in that does not depend on email,
  so that a mistyped address does not strand me.
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
      is treated as having no session
- [ ] Given a verified session, then it lasts `portal.session_minutes`; when it lapses, re-opening the
      link starts a new one (§6.6)
- [ ] `[derived]` Given HR ends a session, then it stops being active but the row survives for the
      trail
- [ ] `[derived]` Given `findActiveForLink`, then it returns every session **not explicitly ended**,
      and the port grows a `now` parameter so that lapsed sessions can be excluded

> **`findActiveForLink` could not mean what this criterion used to say (C15, settled 2026-09-16).**
> It read "returns every live session", but the port signature hands it no clock — compare
> `findActive(sessionId, now)`, which does take one — so "active" there could only ever mean "not
> explicitly ended", and the Phase 3 listing route would show sessions that had quietly lapsed. The
> roadmap recorded the choice and left it to this ticket. **Taken: the port grows `now`.** The
> alternative, filtering in the caller, puts the same expiry rule in every call site and is exactly
> the kind of business decision that must not live above the port.

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

## ERT-630 — `GET /api/portal/{token}`: resolve the link and open a session

| | |
|---|---|
| **Parent** | ERT-600 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-170, ERT-620, **ERT-1110** |
| **PRD** | §6.6, §8.6, Appendix B |
| **Architecture** | §9, §12 invariant 2 |

**Description**

**This route is the credential check.** Since 2026-09-16 a valid, unexpired token resolves to the
holder's checklist and opens a session; there is no PIN prompt on this path. The whole of
authentication for an ordinary hire happens here, which is why three things ride on it.

**One constant response for every failure.** Unknown, malformed, expired, suspended and revoked all
return the same body. A reader who meets that with no explanation files a bug and "fixes" it into an
enumeration oracle, so the route's `describe { }` must present it as **intended** — that is an
acceptance criterion, not a nicety.

Terminal states are the one deliberate exception, and the reasoning is worth keeping: an expired or
suspended link must explain itself or the real employee has no way to act. The explanation carries no
name, no requirement list and no progress figure, so it tells an attacker only that some link once
existed at that token — which is what they already believe by virtue of holding it.

**The session guard still exists and still matters.** It stops being the thing that gates the *first*
response and becomes the thing that gates every *subsequent* one. Install it as a **route-scoped
plugin on the `/api/portal` subtree** so a handler is gated by default rather than by remembering,
and keep the architecture assertion that no handler under `route/portal/` responds without passing
through it.

**Why this depends on ERT-1110.** `StatusPages.kt` logs `call.request.local.uri` unredacted at two
call sites, bypassing the redaction `Monitoring.kt` applies to `CallLogging`. This ticket creates the
first route whose path *is* a live credential. A token written to a log file cannot be un-logged, so
ERT-1110 lands first — the dependency is in the table above rather than in a sentence, because a
sentence is not checkable.

**Goal**

A valid link opens a session and returns the checklist; every other token yields one constant
response; no token reaches a log.

**Stories**
- As a New Hire, I want my emailed link to take me straight to my checklist so that uploading from a
  phone is one tap rather than a code hunt.
- As an HR Officer, I want a guessed or stale token to reveal nothing, so that the endpoint cannot be
  used to find out who has been hired.

**Acceptance criteria**
- [ ] Given a valid token on an `ACTIVE` link, when the portal is opened, then a session is issued
      for `portal.session_minutes` and the checklist is returned (§6.6, §8.6)
- [ ] `[derived]` Given an unknown, malformed, expired, suspended or revoked token, then all five
      responses are byte-identical
- [ ] `[derived]` Given any of those, then no response carries a name, a requirement count or a
      progress figure
- [ ] `[derived]` Given a terminal link state, then the explanatory response still carries no personal
      data
- [ ] `[derived]` Given every portal handler, then none responds without passing through the session
      guard
- [ ] `[derived]` Given any request to this route, then the token appears in no log line, including
      the `StatusPages` malformed-request and unhandled-exception paths (ERT-1110)
- [ ] `[derived]` Given the generated spec, then this route's description states the constant-failure
      behaviour as intended

**Tests**
| Level | Test |
|---|---|
| Route | `portal entry - a valid token - issues a session and returns the checklist` |
| Route | `portal entry - an unknown token - responds identically to an expired one` |
| Route | `portal entry - a revoked token - responds identically to an unknown one` |
| Route | `portal entry - any refused token - the response carries no name, no requirement count and no progress figure` |
| Route | `portal entry - a malformed body on a portal path - writes no token to any log` |
| Architecture | `portal routes - every handler - is mounted behind the session guard` |
| Route | `api docs - the portal routes - describe the constant-failure behaviour as intended` |

**Files**
- create `src/route/portal/PortalRoutes.kt`
- create `src/domain/usecase/OpenPortalUseCase.kt`
- create `src/route/dto/portal/PortalChecklistDto.kt`
- modify [`src/route/Routing.kt`](../../src/route/Routing.kt)
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt)

**Out of scope**
- Rendering HTML. This is an API; terminal-state pages are the client's job.
- The recovery path. ERT-640 and ERT-650.

---

## ERT-640 — `RedeemRecoveryPinUseCase`

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

**Rescoped 2026-09-16.** This was `VerifyPortalPinUseCase`, gating every portal session. The link now
opens the portal on its own (ERT-630), so this use case governs the **recovery** path only: a hire
whose invitation never arrived, redeeming a PIN HR issued and read to them out of band.

Four sub-tasks, each one red-green-refactor cycle, built against fakes before any HTTP exists. Six
digits rather than four: a million combinations instead of ten thousand, at no usability cost.
Lockout is itself a denial of service against the employee, who then cannot submit anything, which is
why four digits would need aggressive lockout and six does not.

**The PIN is addressed by email, not by token.** The hire who needs this has no link, so the use case
takes an address and a PIN, resolves the employee, and checks the recovery PIN on their current link.
That is the one structural difference from the old design, and it is what makes ERT-641's
indistinguishability rule harder rather than easier: the pair to confuse is now *wrong PIN* and
*unrecognised address*.

**Single-use and expiring.** A redeemed PIN is marked used and cannot open a second session; an
unredeemed one expires. Both failures return the same constant response as a wrong PIN.

**Goal**

Every §6.6 recovery rule is proven against fakes in milliseconds.

**Out of scope**
- HTTP and cookies. ERT-650.

---

### ERT-641 — Identical failure for a wrong PIN and an unrecognised address

| | |
|---|---|
| **Parent** | ERT-640 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-210 |
| **PRD** | §6.6, §8.6 |

**Description**

`AppError.Denied` collapses every case and carries no detail field. The result must be identical, and
the trail must record `DENIED` without recording which.

The pair to confuse is **a wrong PIN and an unrecognised address**, plus two more that are easy to
forget: a PIN already redeemed, and a PIN past its expiry. All four are one response.

There is a second channel that is easy to miss. An unrecognised address short-circuits before any
hash verification, while a wrong PIN pays bcrypt's ~100ms — a difference an attacker can measure, and
a working oracle for whether an address was ever hired. The unrecognised-address path must verify
against a dummy hash so every path costs the same.

**A third channel appears on this route and did not exist on the old one.** The address arrives in the
request body, so a validation error on a malformed address would distinguish it from a well-formed
unknown one. Malformed and unknown addresses must produce the same response as a wrong PIN — the
recovery endpoint is the one place where the §"identifier position" rule in the API contract is
deliberately **not** applied, because here the body field *is* the credential.

**Acceptance criteria**
- [ ] Given a wrong PIN, then the response is indistinguishable from an unrecognised address, and the
      attempt is logged (§8.6)
- [ ] `[derived]` Given a PIN already redeemed, or past its expiry, then the response is identical to
      both of the above
- [ ] `[derived]` Given any of those failures, then the trail records `DENIED` without recording which
- [ ] `[derived]` Given an unrecognised address, then a dummy verification is still performed so the
      timing is not an oracle
- [ ] `[derived]` Given a malformed address, then the response is identical rather than a field-level
      validation error
- [ ] `[derived]` Given any of those failures and `TRACE_USECASES=true`, then the trace lines are
      identical apart from duration — the log is the third channel, alongside the response and the
      trail (ERT-195)

**Tests**
| Level | Test |
|---|---|
| Use case | `recovery pin - a wrong pin and an unrecognised address - return the identical Denied result` |
| Use case | `recovery pin - a pin already redeemed - is refused identically to a wrong pin` |
| Use case | `recovery pin - a pin past its expiry - is refused identically to a wrong pin` |
| Use case | `recovery pin - any failure - is recorded as denied without recording which` |
| Use case | `recovery pin - an unrecognised address - still performs a dummy verification so the timing is not an oracle` |
| Use case | `recovery pin - a malformed address - is refused identically rather than as a validation error` |
| Use case | `recovery pin - a wrong pin and an unrecognised address - emit the identical trace outcome` |

**Note (ERT-195).** The generic form of the last test already exists in
`Slf4jUseCaseTracerTest` — `AppError.Denied` is one `data object` whose code is `not_found`, so both
paths trace identically by construction. Repeat it here against the real use case anyway: the
guarantee that matters is that `RedeemRecoveryPinUseCase` returns the *same* error on every path, and
that is this ticket's to keep, not the tracer's.

---

### ERT-642 — Temporary lockout

| | |
|---|---|
| **Parent** | ERT-640 |
| **Type** | Sub-task |
| **Status** | Not started |
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
| **Status** | Not started |
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
| **Status** | Not started |
| **Depends on** | ERT-643, ERT-620 |
| **PRD** | §6.3, §6.4, §6.6, §8.6 |

**Description**

Expiry is evaluated **lazily**, at access time, by comparing now against `expiresAt` and
`idleExpiresAt`. The ERT-1020 sweep exists to send the warning email and tidy `LinkStatus` for the HR
list — the portal must never be blocked on a background job having run.

Two clocks, and the earlier one wins.

**Acceptance criteria**
- [ ] Given a valid token, or a correct recovery PIN, then a session opens for
      `portal.session_minutes` and the checklist is shown (§8.6)
- [ ] Given an expired token, then an explanatory response is returned carrying no personal data
      (§8.6). **It names no "request a new link" action in Phase 1** — that endpoint is Phase 2, and
      ERT-1040 owns the terminal-state copy. Promising an affordance that 404s is worse than omitting
      it (C17, C18)
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
| Use case | `portal access - a valid token - opens a session for the configured duration` |
| Use case | `portal access - a correct recovery pin - opens a session for the configured duration` |

---

## ERT-650 — `POST /api/portal/recover` and `POST /api/employees/{id}/recovery-pin`

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

**Two endpoints, because the recovery PIN has two ends.** HR mints one on
`POST /api/employees/{id}/recovery-pin`, which is the **only response in the system that carries a
PIN** — returned once, never emailed, never logged. The hire redeems it on `POST /api/portal/recover`,
which takes an address and a PIN and **no token**, because a hire who needs this has no link.

Every attempt is written to the trail **first**, before the result is computed, so an early return
cannot skip it.

Invariant 3 must be asserted at the HTTP level and across headers, not only the body. A `Set-Cookie`
present on one failure path and absent on the other is an oracle just as surely as a different message
would be. **The constant response here is a 200, not a 404**: on a body-addressed endpoint a 404
would still separate "this address is known" from "it is not" the moment anyone diffed two responses.

**The issuing route needs its own audit row**, naming the officer. A credential that can be minted
without a trace is worse than the emailed one it replaces, because nothing records who asked for it.

**Goal**

HR can mint a one-time PIN that is recorded and shown once; the hire can redeem it; and neither
endpoint can be used as a probe for whether a link or an address exists.

**Acceptance criteria**
- [ ] `[derived]` Given a correct PIN, then the session cookie is set and the response reports success
- [ ] Given a wrong PIN, an unrecognised address, a redeemed PIN and an expired PIN, then all four
      responses are byte-identical in status, body **and headers** (§6.6)
- [ ] `[derived]` Given HR requests a recovery PIN, then it is returned exactly once in that response
      and appears in no log line, no trace line and no audit metadata
- [ ] `[derived]` Given HR requests a second PIN while one is live, then the first stops verifying
- [ ] `[derived]` Given a recovery PIN is issued, then an audit row records the issuing officer
- [ ] `[derived]` Given no credentials, then the issuing route is refused
- [ ] `[derived]` Given every attempt, then it reaches the trail before the result is computed
- [ ] `[derived]` Given a burst of attempts, then the request is rate-limited and recorded with
      `RATE_LIMITED`
- [ ] `[derived]` Given the generated spec, then the identical-failure behaviour is described as
      intended

**Tests**
| Level | Test |
|---|---|
| Route | `recovery route - a wrong pin and an unrecognised address - return identical status, body and headers` |
| Route | `recovery route - a redeemed pin and an expired pin - return that same identical response` |
| Route | `recovery route - a correct pin - sets the session cookie` |
| Route | `recovery route - a burst of attempts - is rate limited and recorded as rate limited` |
| Route | `recovery pin issue - an HR request - returns the pin once and writes an audit row naming the officer` |
| Route | `recovery pin issue - a second request while one is live - supersedes the first` |
| Route | `recovery pin issue - an unauthenticated request - is refused` |

**Files**
- modify `src/route/portal/PortalRoutes.kt`
- create `src/route/dto/portal/RecoveryDto.kt`
- create `src/route/hr/RecoveryPinRoutes.kt`, `src/route/dto/RecoveryPinDto.kt`
- create `src/domain/usecase/IssueRecoveryPinUseCase.kt`

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

**Three endpoints now need a limit that is not keyed on a link token**, and two of them did not exist
when this ticket was written:

- `GET /api/portal/{token}` — since 2026-09-16 the link *is* the credential, so this route is the
  only barrier to guessing a token. Keying on the token is useless here, because a guesser supplies a
  different one every time. **Key on source, and keep the limit tight.** This is the most important
  limit in the system and it moved here from the verify step, which no longer exists.
- `POST /api/portal/recover` — an address plus a PIN, no token. Key on source **and** on the address,
  so that neither a single attacker nor a distributed one gets an unbounded guessing budget against
  one hire.
- `POST /api/auth/login` (ERT-190) — the third unauthenticated endpoint in the system, and the
  easiest to forget because it is not under `/api/portal`.

**Single-instance assumption, and it is no longer free.** An in-memory limiter counts per process.
PRD deployment is GCP (Q20), where Cloud Run and GKE are multi-instance by default — so either Phase
1 pins to one instance, or this limiter needs a shared store. **ERT-1120 carries the deployment
constraint; this ticket must state which of the two it assumed.** The lockout and suspend counters do
not have this problem: they are DB-backed through `countRecentFailures`.

> **ERT-1120 answered it on 2026-09-17: MULTI-INSTANCE.** The pin is not available, and not because
> nobody chose it — `--max-instances 1` is a per-revision ceiling rather than a mutex, so during any
> rollout two instances exist. Read the answer on ERT-1120 before writing this limiter.
>
> What follows for this ticket:
>
> - The effective limit is `configured × instance_count`. At `--max-instances 4` a limit of 10
>   behaves like 40 in the worst case. **The multiplier must be documented where the limit is
>   configured**, or an operator sets 10 and gets 40 without being told.
> - **Only non-security-bearing limiting may live in memory.** Coarse request shaping is fine.
>   Anything the security model depends on is not, and the PIN attempt counters already show the
>   correct pattern — DB-backed through `countRecentFailures`. They must stay that way.
> - If a real distributed limit is needed, the store is the database, not a new dependency.

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
- [ ] `[derived]` Given a burst of token guesses from one source, then `GET /api/portal/{token}` is
      limited by source rather than by token
- [ ] `[derived]` Given a burst of recovery attempts, then the limit applies per source and per
      address
- [ ] `[derived]` Given the sign-in route, then it is limited by source
- [ ] `[derived]` Given the limiter, then the code states that the deployment is multi-instance
      (ERT-1120) and documents the `configured × instances` multiplier where the limit is set
- [ ] `[derived]` Given anything the security model depends on, then it is counted in the database
      rather than in memory

**Tests**
| Level | Test |
|---|---|
| Route | `upload rate limit - the eleventh upload against one requirement in an hour - is refused with a retry-later message` |
| Route | `upload rate limit - the thirty-first upload across an employee in an hour - is refused` |
| Route | `rate limiting - two links sharing a source address - one link's traffic does not limit the other` |
| Route | `rate limiting - a limited portal request - is recorded with outcome rate limited` |
| Route | `rate limiting - a burst of token guesses from one source - is limited by source` |
| Route | `rate limiting - a burst of recovery attempts against one address - is limited per address` |
| Route | `rate limiting - a burst of sign-in attempts - is limited by source` |

**Files**
- modify [`libs.versions.toml`](../../libs.versions.toml) and [`module.yaml`](../../module.yaml)
- create `src/plugin/RateLimiting.kt`
- modify [`src/Application.kt`](../../src/Application.kt)

**Out of scope**
- Distributed limiting across instances. The deployment is multi-instance (ERT-1120), so the
  in-memory limiter is coarse shaping only; a real distributed limit is a later ticket.
- HR-side limits. The HR surface is authenticated and low-volume.
