# ERT-1000 · Epic: Notifications and link lifecycle

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-900 |
| **PRD** | §8.9, §6.3, §6.4, §8.10 |
| **Architecture** | §4, §6 |

**Description**

Closes Phase 1. §8.9 names seven notification kinds and Phase 1 needs six of them — invitation,
packet-ready-for-review, link-expiring warning, completion confirmation, suspended-link notice to HR,
and manual resend. The seventh (rejection with reasons) arrives with the Phase 2 reject flow.

One rule governs all of them and is already enforced by the type system: **no email other than the
email ever contains an access PIN, and only the invitation carries a link.** Only `sendInvitation`
accepts an `AccessPin` — a signature kept for the recovery path, which does not send mail — so a
forwarded rejection notice, reminder or expiry warning carries nothing useful. Do not add an
`AccessPin` parameter anywhere else to make a template easier.

The link lifecycle work here is the HR side of §6.3 and §8.10: extend one link without moving the
global setting, revoke one, resend one — each with a reason and an audit entry.

**Goal**

Every Phase 1 notification is delivered by a real transport, an expiring link warns once, and HR can
extend, revoke or resend a single link.

**Stories**
- As a New Hire, I want a warning before my link lapses so that I am not stranded mid-collection.
- As an HR Officer, I want to extend one hire's link without changing the policy for everyone.

**Out of scope**
- Escalating reminders at day 3, 7 and 14. P1, Phase 3.
- `request-new-link`. Phase 2.

---

## ERT-1010 — `Notifier` production adapter: SMTP relay

| | |
|---|---|
| **Parent** | ERT-1000 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-440 |
| **PRD** | §8.9, §12, §14 Q12 |
| **Architecture** | §4, §14 |

**Description**

> ### HAR-14 — `retry` reads and writes in two transactions (2026-09-18, second review)
>
> `OutboxNotifier.retry` calls `find(id)`, which opens its own transaction, checks
> `entry.kind.storesBody`, then opens a **second** transaction to update — so the `check` is made
> against state that may no longer hold when the write lands. Negligible today: `retry` has no caller
> until this ticket and there is one writer.
>
> `markFailed` in the same class reads and increments **inside one transaction**, with a comment
> saying why — so the pattern is understood in that file and simply was not applied here. Fold the
> `find` into the same `factory.transaction { }` the update uses.
>
> **Do not "fix" the neighbouring constraint while you are in there:** a failed invitation cannot be
> re-rendered from this table, because its body is never stored. `retry` refusing a body-less kind is
> ERT-440's decision and reissuing is ERT-1030's `resend-link`.

**Q12 is answered (2026-09-16): an SMTP relay on internal mail.** No transactional-email provider and
no API integration — which also settles a §12 question nobody asked. The invitation names a hire and
carries a live credential; an internal relay keeps both inside the organisation's mail estate.

So this is the adapter swap ERT-440 was built for. Jakarta Mail (Angus Mail) over SMTP with STARTTLS,
declared directly in `libs.versions.toml` and `module.yaml` — **expect no Amper catalog key**, the
same trap architecture §15 records for `ktor-server-routing-openapi`.

**What drains the outbox, and what does not.** The six credential-free kinds queue and are drained by
a poller started from the application lifecycle: claim `PENDING` rows oldest-first with a conditional
update, send, then mark sent or schedule a retry. **The invitation is not in that queue.** ERT-440
decided that an invitation is sent inline and its body never persisted, because a stored body is a
stored credential; its outbox row is a record of the attempt, not a work item. This adapter therefore
has two paths — a synchronous one behind `sendInvitation`, and the poller for everything else. Do not
resolve the asymmetry by queueing the invitation: there is nothing in the row to send from, by design.

**Failure is a value, not an exception.** `DeliveryResult.Failed(reason)` is returned and the caller
decides; `CreateHireUseCase` creates the hire regardless (§8.1, ERT-434). A permanent SMTP 5xx — an
unknown recipient, a rejected sender — fails terminally on the first attempt, because retrying a 550
five times produces five identical failures and a slower answer for HR. A transient 4xx or a
connection failure retries at 1m, 5m, 15m, 1h, 4h, then stops at `OUTBOX_MAX_ATTEMPTS` keeping the
last error.

**The inline path needs a timeout.** With a real relay, `sendInvitation` makes a network call inside
`CreateHireUseCase`, after the hire has committed. A relay that hangs would hold the HTTP request open
for the socket default. Connect and read timeouts are 5 seconds, and a timeout is `Failed` — a
specified path, not an error.

**Mail is off by default.** `MAIL_ENABLED` defaults to false and binds ERT-440's outbox adapter, so a
checkout, a test run, or a staging box pointed at the real relay cannot email a real hire.
`MAIL_REDIRECT_TO`, when set, rewrites every recipient to one address and names the intended one in
the subject. **There is no way to un-send an invitation carrying a live link**, which is why the safe
default is the one that transmits nothing.

**Startup must not depend on mail.** No connection is opened at boot; the poller logs and backs off.
Mail being down must not take the portal down.

**The sending domain is the half of Q12 this ticket does not own.** SPF, DKIM and DMARC alignment for
`MAIL_FROM` belong to whoever runs the relay. It is on the Phase 1 exit checklist with IT as owner: an
invitation that lands in spam is indistinguishable from one never sent, and §13's "≥85% open the link
within 48h" is measured against it. A hire whose invitation is filtered is also exactly the case
ERT-650's recovery PIN exists for, so the two are worth reading together.

**Goal**

Queued notifications are delivered by the internal relay, the invitation is delivered inline and
recorded without its credential, failures are bounded and visible, and no environment mails a real
person by accident.

**Stories**
- As a New Hire, I want the invitation to come from an address my employer actually uses, so that it
  does not read as phishing.
- As an HR Officer, I want a failed send to say why and to be retryable, so that a transient relay
  problem is not a re-created hire.
- As an engineer, I want a staging deployment that cannot email a candidate.

**Acceptance criteria**
- [ ] `[derived]` Given a pending row of a queued kind, then it is delivered and marked sent
- [ ] `[derived]` Given a transient failure, then the row is marked for retry with the reason, the
      attempt count increments, and the next attempt follows the backoff schedule
- [ ] `[derived]` Given a permanent SMTP 5xx, then the row fails terminally on the first attempt
- [ ] `[derived]` Given `OUTBOX_MAX_ATTEMPTS` failures, then the row is terminal with its last error
- [ ] `[derived]` Given an invitation, then it is sent inline and no row, log line, trace line or
      error message contains the plaintext token
- [ ] Given a hire is created, then the invite email is sent within 1 minute (§8.1)
- [ ] `[derived]` Given the relay is unreachable at startup, then the application still starts
- [ ] `[derived]` Given a relay that hangs, then the send times out within 5 seconds as `Failed`
- [ ] `[derived]` Given `MAIL_ENABLED` unset, then the outbox adapter is bound and nothing is
      transmitted
- [ ] `[derived]` Given `MAIL_REDIRECT_TO` is set, then every recipient is rewritten and the subject
      names the intended one
- [ ] `[derived]` Given `MAIL_ENABLED` is true with `SMTP_HOST` or `MAIL_FROM` unset, then startup
      fails
- [ ] `[derived]` Given two pollers claiming one row, then it is sent once

**Tests**
| Level | Test |
|---|---|
| Repository | `notification delivery - a pending row - is delivered and marked sent` |
| Repository | `notification delivery - a transient failure - marks the row for retry and schedules the next attempt` |
| Repository | `notification delivery - a permanent rejection - fails terminally without retrying` |
| Repository | `notification delivery - the attempt ceiling - stops retrying and keeps the last error` |
| Repository | `notification delivery - two pollers claiming one row - deliver it once` |
| Use case | `invitation delivery - a successful send - persists a row carrying no token` |
| Use case | `invitation delivery - a relay that hangs - times out and returns Failed` |
| Use case | `transport selection - mail disabled - binds the outbox adapter and transmits nothing` |
| Use case | `transport selection - a redirect address is set - every recipient is rewritten` |
| Use case | `transport selection - mail enabled with no host - startup fails` |

**Files**
- modify [`libs.versions.toml`](../../libs.versions.toml), [`module.yaml`](../../module.yaml)
- create `src/data/notify/SmtpNotifier.kt` — both paths
- create `src/data/notify/OutboxPoller.kt` — claim, send, schedule
- create `src/data/notify/MailConfig.kt` — the env seam and the startup checks
- modify [`src/Application.kt`](../../src/Application.kt) — start and stop the poller on the lifecycle
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind by `MAIL_ENABLED`
- create `test/data/notify/SmtpNotifierTest.kt`, `test/data/notify/OutboxPollerTest.kt`, `test/data/notify/MailConfigTest.kt`
- modify [`.env.example`](../../.env.example) — the mail block

**Out of scope**
- **Templating beyond plain text with a minimal HTML alternative.** Design is not a Phase 1 concern.
- **Bounce and complaint handling.** An internal relay does not webhook; a hard bounce surfaces as an
  SMTP 5xx on the next attempt, and §8.1's indicator is what HR sees.
- **The sending domain, SPF, DKIM, DMARC.** IT owns it; Phase 1 exit checklist.
- **Escalating reminders.** P1, Phase 3.
- **Leader election for the poller.** The conditional claim makes a double send impossible, but the
  schedule is per instance. GCP runs multiple instances by default (Q20) — if this service is ever run
  more than once, revisit. ERT-1120 carries the constraint.

  > **ERT-1120 answered it on 2026-09-17: MULTI-INSTANCE, and it is running more than once.** This
  > poller is nonetheless **safe as designed** — the conditional claim is the right pattern and must
  > not be "fixed". Two things do change. Cloud Run throttles CPU to near zero between requests, so a
  > 30-second in-process timer does not fire on a service that is idle — which for an invitation
  > system is a defect, not a degradation; production therefore runs with always-on CPU, and UAT does
  > not, so UAT does not faithfully test this poller. And the better shape is Cloud Scheduler calling
  > an authenticated endpoint: one invocation whichever instance answers, and no always-on-CPU
  > dependency at all. Evaluate that before building an in-process timer.

---

## ERT-1020 — Expiry sweep: idle and absolute clocks, one warning if incomplete

| | |
|---|---|
| **Parent** | ERT-1000 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-420, ERT-440 |
| **PRD** | §8.9, §6.4 |
| **Architecture** | §6 |

**Description**

The only Phase 1 notification driven by a clock rather than a request.

A design point that keeps the sweep off the critical path: link **expiry** is evaluated lazily at
access time by ERT-644, comparing now against the stored dates. The sweep exists only to send the
warning email and to keep `LinkStatus` tidy for the HR list — so the portal is never blocked on a
background job having run, and a missed sweep is a missing email rather than a link that outlives its
expiry.

`UploadLinks.warnedAt` exists so the warning is sent once. Without checking it, every sweep inside
the window re-sends, which trains people to ignore the message.

**Goal**

An incomplete packet gets exactly one expiry warning inside the window, a complete one gets none, and
lapsed links are marked `EXPIRED` for the HR list.

**Stories**
- As a New Hire, I want one warning before my link lapses so that I can finish or ask for a new one.
- As an HR Officer, I want lapsed links visible in the list so that a stranded hire is obvious.

**Acceptance criteria**
- [ ] Given a link is within the warning window and the packet is incomplete, then the employee
      receives one expiry warning email (§8.9)
- [ ] Given a packet is already `COMPLETE`, then no expiry warning is sent (§8.9)
- [ ] `[derived]` Given the sweep runs twice inside the window, then a second warning is not sent
- [ ] Given any email at all, then it never contains an access PIN (§8.9, §6.6)
- [ ] `[derived]` Given a link past its absolute or idle expiry, then its status becomes `EXPIRED`
- [ ] `[derived]` Given the sweep has not run, then an expired link is still refused at access time
- [ ] `[derived]` Given `link.completed_grace_days` has elapsed since completion, then the link moves
      from `COMPLETED` to `CLOSED`

**Tests**
| Level | Test |
|---|---|
| Use case | `expiry warning - a link inside the warning window with an incomplete packet - sends one warning` |
| Use case | `expiry warning - the sweep runs twice inside the window - a second warning is not sent` |
| Use case | `expiry warning - a completed packet - sends no warning` |
| Use case | `expiry sweep - a link past its idle expiry - is marked expired` |
| Use case | `expiry sweep - the grace window elapses after completion - the link moves to closed` |

**Files**
- create `src/domain/usecase/WarnOfExpiringLinksUseCase.kt`
- create `src/domain/usecase/SweepExpiredLinksUseCase.kt`
- modify [`src/domain/port/Repositories.kt`](../../src/domain/port/Repositories.kt) — add
  `findDueForWarning` and `findLapsed`
- create `src/data/schedule/ExpirySweep.kt`
- modify [`src/Application.kt`](../../src/Application.kt)

**Out of scope**
- Multi-instance scheduling locks. Phase 1 runs one instance; record the assumption where the
  scheduler is started.

---

## ERT-1030 — `resend-link`, `extend-link`, `revoke-link`

| | |
|---|---|
| **Parent** | ERT-1000 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-450, ERT-520 |
| **PRD** | §8.9, §8.10, §6.6, Appendix B |

**Description**

Three HR controls over one link, each requiring a reason and each audited.

**Resend rotates the token.** A resend that repeated the old link would restate the same credential in
a second email, and would not recover a hire whose link was lost to a mistyped address. Rotation also
means the previous token stops resolving, which is what makes resend the route by which HR unsuspends
a link. Since 2026-09-16 no PIN is involved: the link is the credential, and a hire who never received
either email needs ERT-650's recovery PIN instead, rather
than sending them the same code again.

**Extend moves one link, not the policy.** §8.10 requires HR to be able to give one hire more time
without changing the global setting, and `extendedCount` exists to make repeated extension visible
rather than silent.

**Revoke is immediate and unconditional.** §12 lists it among the P0 controls.

**Goal**

HR can reissue, extend or revoke a single link, each with a recorded reason, and a resend always
carries a fresh link.

**Stories**
- As an HR Officer, I want to extend one hire's link so that a slow employee does not force me to
  loosen the policy for everyone.
- As an HR Officer, I want to revoke a link the moment I suspect a problem.

**Acceptance criteria**
- [ ] Given HR needs more time for one hire, then HR can extend that single link without changing the
      global setting (§8.10)
- [ ] `[derived]` Given a resend, then a new token is issued and the previous one stops resolving
- [ ] `[derived]` Given a revoke, then the link stops opening the portal immediately and any live
      session is ended
- [ ] `[derived]` Given an extend, then `extendedCount` increases and the new expiry is stored
- [ ] `[derived]` Given an extension beyond the configured bounds, then it is refused stating the
      permitted range
- [ ] `[derived]` Given any of the three, then a reason is required and the action is audited with
      actor and timestamp
- [ ] `[derived]` Given a suspended link, then HR can unsuspend it through resend

**Tests**
| Level | Test |
|---|---|
| Use case | `link resend - a link is reissued - the previous pin no longer verifies` |
| Use case | `link extend - a single link - moves its expiry without changing the stored policy` |
| Use case | `link extend - beyond the permitted range - is refused stating the range` |
| Use case | `link revoke - a revoked link - opens no portal and ends live sessions` |
| Use case | `link controls - any of the three - require a reason and are audited` |

**Files**
- create `src/domain/usecase/ResendInvitationUseCase.kt`
- create `src/domain/usecase/ExtendLinkUseCase.kt`
- create `src/domain/usecase/RevokeLinkUseCase.kt`
- modify `src/route/hr/EmployeeRoutes.kt`

**Out of scope**
- Email change, which also rotates credentials but requires out-of-band verification. Phase 2, and
  blocked on Q16.

---

## ERT-1040 — Portal terminal states

| | |
|---|---|
| **Parent** | ERT-1000 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-740, ERT-1020 |
| **PRD** | §6.3, §8.6, §7.3 |
| **Architecture** | §12 invariant 2 |

**Description**

Six link states, and what the holder sees in each. The audit lists terminal-state information leakage
among the areas checked with no findings and warns it is "easy to lose during implementation" — which
is exactly why it gets its own ticket rather than being scattered across the endpoints that produce
each state.

The discipline: a `COMPLETED` link inside the grace window may name requirements and outcomes,
because the employee already knows what they submitted and proof of acceptance prevents a wave of
"did it go through?" messages. Every other terminal state names nothing at all — not the employee,
not a requirement count, not a company-specific detail.

| Status | What the holder sees |
|---|---|
| `EXPIRED` | Explanation only in Phase 1; the "request a new link" action arrives with the endpoint in Phase 2 |
| `SUSPENDED` | Explanation plus "contact HR" |
| `REVOKED` | Explanation plus "contact HR" |
| `COMPLETED` | Read-only confirmation: requirement names and outcomes, plus the completion date |
| `CLOSED` | Generic "this link is no longer available" |

**Goal**

Every terminal state returns the right shape, and only the completed state carries any personal
detail.

**Stories**
- As a New Hire who finished, I want proof my submission was accepted so that I do not have to ask HR
  whether it went through.
- As a stranger who found a closed link, I want it to tell me nothing.

> **This ticket owns the terminal-state copy; ERT-644 owns the link-state *gate*.** Both previously
> carried the same two acceptance criteria word for word, so whichever ran second would either
> duplicate the work or silently drop it (C17, settled 2026-09-16). ERT-644 proves that a suspended,
> revoked or expired link **opens no session**; ERT-1040 proves that what comes back **says the right
> thing and leaks nothing**.

**Acceptance criteria**
- [ ] Given an expired token, then an explanatory response is returned carrying no personal data.
      **Phase 1 names no "request a new link" action**, because `POST /api/portal/request-new-link` is
      Phase 2 — an affordance pointing at a 404 is worse than none (C18). PRD §6.4's claim that
      "expiry is recoverable" is therefore only true from Phase 2; until then recovery is HR-initiated
      through `resend-link`, or ERT-650's recovery PIN
      (§8.6)
- [ ] Given a completed packet within the grace window, then a read-only confirmation page lists
      requirement names and outcomes only (§8.6)
- [ ] Given a closed link, then a generic unavailable message is shown with no personal data (§8.6)
- [ ] `[derived]` Given a suspended or revoked link, then the explanation names no employee and no
      requirement
- [ ] `[derived]` Given the completed confirmation, then it renders no document and links to none
- [ ] `[derived]` Given every terminal state, then the access attempt is recorded in the trail with
      the matching outcome

**Tests**
| Level | Test |
|---|---|
| Route | `terminal state - an expired link - explains and offers a new link` |
| Route | `terminal state - a completed link inside the grace window - lists requirement names and outcomes only` |
| Route | `terminal state - a completed link - renders no document and links to none` |
| Route | `terminal state - a closed link - carries no personal data` |
| Route | `terminal state - a suspended link - names no employee and no requirement` |

**Files**
- modify `src/route/portal/PortalRoutes.kt`
- modify `src/route/dto/portal/PortalPromptDto.kt`

**Out of scope**
- The `request-new-link` endpoint itself. Phase 2; the action here points at it.
