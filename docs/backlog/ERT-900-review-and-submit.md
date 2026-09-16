# ERT-900 · Epic: Review, attest and submit

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-700 |
| **PRD** | §7.2, §8.6, §8.9, §6.2 |
| **Architecture** | §5, §6, §12 |

**Description**

Uploading a file is not the same act as declaring "I am done." The employee uploads documents one by
one, then explicitly reviews and submits the packet **as a unit**, and that confirmation is a
declaration rather than a button press: the documents are their own and genuine, and falsification
may result in withdrawal of the offer or termination.

PRD §15 puts this in Phase 1 because "it changes the data model, and every status depends on it."
The columns already exist on `Employees` and
[`Attestation`](../../src/domain/model/Employee.kt) already exists on the model, so the cost of
deferring is not schema — it is that `PacketStatus` transitions and the force-submit distinction both
get rebuilt.

SEC-07 supplies the reason the attestation is versioned. The wording will change, and when a forgery
surfaces you need to know which version that employee accepted. Without it there is no record of the
employee having claimed anything, which weakens both the disciplinary position and any resulting
labour case.

HR can *see* uploaded documents as they arrive — that is the whole point of the progress view — but
approve and reject stay disabled until the packet is submitted. Reviewing a document the employee is
about to replace wastes HR's time and creates status churn.

**Goal**

The employee reviews, attests with a recorded text version, and submits; every requirement locks, the
packet moves to `UNDER_REVIEW`, and HR is notified.

**Stories**
- As a New Hire, I want an explicit "I'm done" step so that uploading a file is not mistaken for
  declaring myself finished.
- As an HR Officer, I want the employee's declaration on record so that a forgery has an owner.

**Out of scope**
- Force-submit. P1, Phase 3 — but nothing here may contradict "a force-submitted packet carries no
  attestation and is marked distinctly", and `submittedByHr` already exists for it.
- **Thumbnails on the review screen.** §7.2 asks for them and §8.6 forbids them; see the roadmap's
  escalation list. Build to §8.6 — names and "file present", never the image.

---

## ERT-910 — `SubmitPacketUseCase`

| | |
|---|---|
| **Parent** | ERT-900 |
| **Type** | Ticket — **split into ERT-911…913** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-210, ERT-740 |
| **PRD** | §7.2, §8.6 |
| **Architecture** | §5, §6 |

**Goal**

Every §7.2 rule is proven against fakes before any route exists.

---

### ERT-911 — Blocked until every required requirement has a file

| | |
|---|---|
| **Parent** | ERT-910 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-210 |
| **PRD** | §7.2, §8.6, §6.5 |

**Acceptance criteria**
- [ ] Given every required requirement has a file, then the Review & Submit step becomes available
      (§8.6)
- [ ] `[derived]` Given a required requirement with no file, then submission is refused with
      `Conflict`
- [ ] `[derived]` Given an optional requirement with no file, then submission is allowed — optional
      requirements gate nothing (§6.5)
- [ ] `[derived]` Given a packet already `UNDER_REVIEW`, then a second submission is refused
- [ ] `[derived]` Given a packet in `CHANGES_REQUESTED` with every rejected requirement replaced, then
      submission is allowed

**Tests**
| Level | Test |
|---|---|
| Use case | `packet submission - a required requirement with no file - is refused with conflict` |
| Use case | `packet submission - an optional requirement with no file - is allowed` |
| Use case | `packet submission - a packet already under review - a second submission is refused` |
| Use case | `packet submission - a changes-requested packet with every rejection replaced - is allowed` |

---

### ERT-912 — Attestation persisted with text version, timestamp and IP

| | |
|---|---|
| **Parent** | ERT-910 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-911 |
| **PRD** | §7.2; SEC-07 |

**Description**

**Q5 — the data-protection regime and the consent notice wording — is unanswered**, and §7.2 makes
that non-blocking by demanding *versioned* text. A placeholder version is a legitimate permanent
record, not a stub: the packet keeps whatever version it accepted, and publishing v1 later does not
alter what a v0 packet recorded. The versioning mechanism is the expensive part; the wording is a
constant.

The attesting IP comes from the request, never from the client-supplied body. A self-reported address
in an evidentiary record is worse than no address.

**Acceptance criteria**
- [ ] `[derived]` Given no attestation version is supplied, then submission is refused
- [ ] `[derived]` Given a **stale** attestation version — a newer text is current — then submission is
      refused with `Conflict` → **409**, carrying the current version so the client can re-present it
- [ ] `[derived]` Given a **missing** attestation version, then submission is refused with
      `Validation` → **422** naming the field. The two are different failures and the API contract
      previously collapsed both into 422 (C4, settled 2026-09-16) — the
      employee must see the current wording
- [ ] Given a valid attestation, then the version of the text agreed to, the timestamp, and the IP are
      persisted with the packet (§7.2)
- [ ] `[derived]` Given a new text version is published, then packets attested under the previous
      version keep the version they recorded
- [ ] `[derived]` Given an employee-submitted packet, then it is distinguishable from a
      force-submitted one via `submittedByHr`

**Tests**
| Level | Test |
|---|---|
| Use case | `packet submission - no attestation version supplied - is refused` |
| Use case | `packet submission - a stale attestation version - is refused with conflict` |
| Use case | `packet submission - a valid attestation - persists the version, timestamp and source address` |
| Use case | `attestation text - a new version is published - packets keep the version they recorded` |

**Files**
- create `src/domain/model/AttestationText.kt` — the current version constant and its text

---

### ERT-913 — Lock all requirements and move the packet to `UNDER_REVIEW`

| | |
|---|---|
| **Parent** | ERT-910 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-912 |
| **PRD** | §6.1, §6.2, §7.2, §8.9 |

**Description**

The transition must be atomic across the requirements and the packet. A crash between them leaves a
packet `UNDER_REVIEW` with unlocked requirements — the exact state §7.1 blocks, reached by accident.

**Acceptance criteria**
- [ ] Given the employee confirms submission, then all requirements lock and HR is notified (§8.6)
- [ ] `[derived]` Given submission, then the packet moves to `UNDER_REVIEW` and `submittedAt` is set
- [ ] `[derived]` Given submission, then an upload against any requirement is refused server-side
- [ ] `[derived]` Given the transition, then it is atomic across requirements and packet
- [ ] `[derived]` Given the HR notification fails to send, then the packet is still submitted
- [ ] `[derived]` Given the notification, then it carries no PIN — structurally guaranteed by
      `sendPacketReadyForReview` taking no `AccessPin`

**Tests**
| Level | Test |
|---|---|
| Use case | `packet submission - the packet is submitted - every requirement moves to under review and is locked` |
| Use case | `packet submission - after submission - an upload against any requirement is refused server-side` |
| Use case | `packet submission - a successful submission - notifies HR that a packet is ready for review` |
| Use case | `packet submission - the notification fails - the packet is still submitted` |
| Repository | `packet submission - the transition - is atomic across requirements and packet` |

---

## ERT-920 — `POST /api/portal/{token}/submit`

| | |
|---|---|
| **Parent** | ERT-900 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-750, ERT-910 |
| **PRD** | §8.6, Appendix B |

**Acceptance criteria**
- [ ] `[derived]` Given a complete attested packet, then 200 is returned with the new packet status
- [ ] `[derived]` Given an incomplete packet, then 409 is returned
- [ ] `[derived]` Given a missing attestation version, then 422 is returned
- [ ] `[derived]` Given the attested address, then it is taken from the request and not from the
      client-supplied body
- [ ] `[derived]` Given submission, then it is recorded in the access trail with action
      `SUBMIT_PACKET`
- [ ] `[derived]` Given no verified session, then the request is refused and nothing is submitted

**Tests**
| Level | Test |
|---|---|
| Route | `submit route - a complete attested packet - returns the new packet status` |
| Route | `submit route - an incomplete packet - returns 409` |
| Route | `submit route - the attested address - is taken from the request rather than the body` |
| Route | `submit route - no verified session - submits nothing` |

**Files**
- modify `src/route/portal/PortalRoutes.kt`
- create `src/route/dto/portal/SubmitDto.kt`

---

## ERT-930 — `POST /api/portal/{token}/report-problem`

| | |
|---|---|
| **Parent** | ERT-900 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-650, ERT-660 |
| **PRD** | §8.6, §6.3; SEC-14 |

**Description**

SEC-14: the employee has no way to report a compromised link. Without this action, the only person
positioned to notice a takeover early has no way to say so, and the first signal reaches HR when a
fraudulent document is already in the packet.

The awkward consequence is deliberate. This must be reachable **without a verified session** — the
person reporting may be precisely the one who cannot get in — which makes it an unauthenticated
denial of service against a hire's own link. That is accepted because §8.6 says "immediately" and a
suspended link is recoverable by HR resend, while a takeover is not. Rate-limit it hard, by source
as well as by link, and require HR to unsuspend.

**Goal**

Anyone holding the link can suspend it immediately, and HR is told why.

**Stories**
- As a New Hire who received a link I did not expect, I want to report it so that whoever else has it
  loses access straight away.

**Acceptance criteria**
- [ ] Given the portal, then a "this wasn't me — report a problem" action is available which
      immediately suspends the link and notifies HR (§8.6)
- [ ] `[derived]` Given a report, then any previously valid session for that link stops opening the
      checklist
- [ ] `[derived]` Given a report, then the link stays suspended until HR acts — it does not self-clear
- [ ] `[derived]` Given a report, then it is reachable without a verified session
- [ ] `[derived]` Given repeated reports, then the endpoint is rate-limited by source and by link
- [ ] `[derived]` Given the response, then it leaks no personal data — it confirms the report and
      nothing else

**Tests**
| Level | Test |
|---|---|
| Use case | `problem report - the employee reports a problem - the link is suspended immediately and HR is notified` |
| Route | `problem report - after suspension - a previously valid session no longer opens the checklist` |
| Route | `problem report - no verified session - is still accepted` |
| Route | `problem report - the response - confirms the report and carries no personal data` |

**Files**
- create `src/domain/usecase/ReportPortalProblemUseCase.kt`
- modify `src/route/portal/PortalRoutes.kt`

**Out of scope**
- An HR unsuspend action. Folded into ERT-1030's link controls.
