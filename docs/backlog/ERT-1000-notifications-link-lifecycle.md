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
invitation ever contains the access PIN.** Only `sendInvitation` accepts an `AccessPin`, so a
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

## ERT-1010 — `Notifier` production adapter

| | |
|---|---|
| **Parent** | ERT-1000 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | **Blocked on Q12** |
| **Depends on** | ERT-440 |
| **PRD** | §8.9, §12 |
| **Architecture** | §4 |

**Description**

**Q12 — the email delivery mechanism and the sending domain — is unanswered**, and it is owned by
Engineering/IT rather than by this backlog. ERT-440's outbox keeps every other ticket moving; this
one drains it through a real transport.

Because the outbox already persists what would be sent, this is genuinely an adapter swap: no use
case changes, and §8.1's retry action already has something durable to retry.

Two rules carry over from the outbox. The invitation body holds a live credential, so a delivered
invitation must not leave a usable PIN in the table. And a delivery failure is a specified path, not
an exception — `DeliveryResult.Failed` is returned and the caller decides.

**Goal**

Outbox rows are delivered by a real transport, failures are visible and retryable, and no delivered
row retains a credential.

**Acceptance criteria**
- [ ] `[derived]` Given a pending outbox row, then it is delivered and marked sent
- [ ] `[derived]` Given delivery fails, then the row is marked failed with the reason and remains
      retryable
- [ ] `[derived]` Given a delivered invitation, then the stored row retains no usable PIN
- [ ] Given a hire is created, then the invite email is sent within 1 minute (§8.1)
- [ ] `[derived]` Given the transport is unreachable at startup, then the application still starts —
      mail being down must not take the portal down

**Tests**
| Level | Test |
|---|---|
| Repository | `notification delivery - a pending row - is delivered and marked sent` |
| Repository | `notification delivery - a transport failure - marks the row failed and retryable` |
| Repository | `notification delivery - a delivered invitation - retains no usable pin` |

**Files**
- modify [`libs.versions.toml`](../../libs.versions.toml) and [`module.yaml`](../../module.yaml) —
  add a mail library once Q12 is answered
- create `src/data/notify/SmtpNotifier.kt` or the chosen transport
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — swap the binding

**Out of scope**
- Templating beyond plain text and a minimal HTML alternative. Design is not a Phase 1 concern.

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
- [ ] Given any email other than the invitation, then it never contains the access PIN (§8.9)
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

**Resend rotates the PIN.** §6.6 says the PIN rotates on HR revocation and on employee request, and
a resend that repeated the old PIN would restate a credential in a second email — exactly what §8.9
forbids. Rotation also means a resend genuinely recovers a link whose PIN the employee lost, rather
than sending them the same code again.

**Extend moves one link, not the policy.** §8.10 requires HR to be able to give one hire more time
without changing the global setting, and `extendedCount` exists to make repeated extension visible
rather than silent.

**Revoke is immediate and unconditional.** §12 lists it among the P0 controls.

**Goal**

HR can reissue, extend or revoke a single link, each with a recorded reason, and a resend always
carries a fresh PIN.

**Stories**
- As an HR Officer, I want to extend one hire's link so that a slow employee does not force me to
  loosen the policy for everyone.
- As an HR Officer, I want to revoke a link the moment I suspect a problem.

**Acceptance criteria**
- [ ] Given HR needs more time for one hire, then HR can extend that single link without changing the
      global setting (§8.10)
- [ ] `[derived]` Given a resend, then a new PIN is issued and the previous one stops verifying
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
| `EXPIRED` | Explanation plus a "request a new link" action |
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

**Acceptance criteria**
- [ ] Given an expired token, then an explanatory page with a "request a new link" action is shown
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
