# Delivery roadmap

**Next ticket: [ERT-140 — Map `AppError` to HTTP status in `StatusPages`](backlog/ERT-100-foundations.md#ert-140--map-apperror-to-http-status-in-statuspages)**

The full board is [docs/backlog/README.md](backlog/README.md). This file holds sequencing, the
decision register, and the pointer above. Each session updates that pointer on the way out.

---

## Where the code is

| | |
|---|---|
| Built | `core/` value objects and error types · 12 domain models with status logic · 10 ports · 12 Exposed tables · bcrypt, clock and secure generators · 6 Ktor plugins · generated OpenAPI · an architecture test that fails the build on a layer violation |
| Empty | `domain/usecase/` · `data/repository/` · `data/mapper/` · `route/hr/` · `route/portal/` · `test/testdata/fake/` |
| Endpoints | `/health`, `/openapi`, `/swagger`. PRD Appendix B specifies ~38. |

The three foundational gaps Phase 0 opened with are closed: `DatabaseFactory.connect()` runs from the
application lifecycle (ERT-110), Flyway applies a baseline guarded by a drift test (ERT-120), and
reference data and policy defaults are seeded (ERT-130). ERT-180 then replaced every UUID identifier
with a validated `PersonId` or `EntityId`.

What remains in Phase 0 is error mapping (ERT-140), metrics (ERT-150), the token digest (ERT-160) and
the write-mostly guards (ERT-170), then the ERT-200 test harness. **No repository, use case or
business route exists yet**, so nothing writes rows outside the tests.

---

## Phases

Phases follow PRD [§15](employee-requirements-tracker-prd_1.md). Phase 0 is additional — it is the
runtime and test scaffolding Phase 1 assumes.

| Phase | Theme | Epics | State |
|---|---|---|---|
| **0** | Foundations | ERT-100, ERT-200 | Specified |
| **1** | Core loop and access model | ERT-300 … ERT-1000 | Specified |
| **2** | Validation loop and accountability | see below | Epic-level |
| **3** | Scale and efficiency | see below | Epic-level |
| **4** | Document lifecycle | see below | Gated on Q1 |

> **Priority and phase are different axes.** §8.10, §8.11, §8.12 and §8.13 are all P0 but land in
> Phases 2–3. P0 means "must exist before launch", not "must be built first".

### Phase 1 scope

PRD §15: §8.1 (create hire), §8.3 (list with progress), §8.4 (detail with preview), §8.6 (upload
portal), §8.7 (upload limits and versioning), §8.9 (notifications), plus a configurable requirement
list.

Three items look deferrable and are not. The PRD is explicit that each is expensive to retrofit
rather than merely inconvenient:

- **The write-mostly portal** (§8.6) — a rule about what the API returns. Deciding it later means
  unbuilding a preview feature and re-testing every portal endpoint.
- **The PIN and session model** (§6.6) — retrofitting authentication onto a live public endpoint is a
  rewrite, not an addition.
- **Review-and-submit with attestation** (§7.2) — it changes the data model, and every packet status
  depends on it.

> If Phase 1 must be trimmed, cut the validation workflow before cutting the upload portal. Do not
> cut the access model or the review-and-submit phase.

---

## Sequencing

```
ERT-100 ──► ERT-200 ──► ERT-300 ──┬─► ERT-400 ──┬─► ERT-500
foundations  test harness  policy  │  hire       │  HR read side
                                   │  creation   │
                                   │             └─► ERT-600 ──► ERT-700 ──┬─► ERT-900 ──► ERT-1000
                                   │                portal      document   │  review &     notifications
                                   │                access      upload     │  submit       & link lifecycle
                                   │                                       │
                                   └───────────────────────────────────────┴─► ERT-800
                                                                              HR document access
```

**Critical path:** ERT-100 → ERT-200 → ERT-300 → ERT-400 → ERT-600 → ERT-700 → ERT-900.

ERT-500 and ERT-800 hang off the path and can be taken whenever their dependencies are met — useful
when you want a shorter session. ERT-1000 closes Phase 1.

### Orderings that cause rework if reversed

| Do this | Not that | Why |
|---|---|---|
| **ERT-160 before ERT-433 and ERT-420** | Hash the link token with bcrypt | bcrypt is salted, so a token hashed at issue cannot be recomputed at lookup. Ship it and **every live link becomes unresolvable** — recovery means re-issuing every credential and re-inviting every hire through a bulk send path §8.2 deliberately makes hard. |
| **ERT-170 before ERT-740** | Write the first portal DTO, guard it later | The realistic failure is not a deliberate preview — it is `mimeType` "for the icon" and `originalFilename` "for the confirmation toast", both of which read as reasonable in review. Removing them later means re-testing every portal endpoint (§8.6, SEC-02). |
| ERT-600 before ERT-700 | Upload first, PIN gate later | Retrofitting a session model onto a live public upload endpoint is a rewrite (§6.6, SEC-01). The trail gap it leaves is worse: an append-only history cannot be backfilled. |
| ERT-900 inside Phase 1 | Defer attestation to Phase 2 | Attestation changes the data model and every packet status depends on it (§7.2, SEC-07) |
| ERT-310 before ERT-433 | Hardcode durations, read policy later | If `expiresAt` comes from `LinkPolicy`'s Kotlin defaults rather than `app_setting`, §8.10 is violated from the first row and **nothing detects it** — the numbers are identical. The quietest of these risks. |
| ERT-610 before ERT-630 | Add the trail once endpoints exist | Invariant 7 admits no gaps, and adding logging to five handlers afterwards means auditing each for early returns — which is exactly where a denied attempt goes. |

---

## Decision register

Nine of the PRD's [§14](employee-requirements-tracker-prd_1.md) open questions are marked blocking
and none has an owner date. Phase 1 does not wait on them: each runs behind a port that already
exists, so answering the question later costs an adapter swap rather than a redesign.

| # | Question | Owner | Blocks | Proceeding meanwhile |
|---|---|---|---|---|
| Q4 | Who are the HR users, how do they authenticate, does SSO exist? | Stakeholder / IT | The HR auth epic, the real protection of every `authenticate(HR_AUTH)` route, and [ERT-190](backlog/ERT-100-foundations.md#ert-190--hr-user-accounts-and-the-persona-model) | The marked-placeholder JWT verifier in [Security.kt](../src/plugin/Security.kt). HR routes are written behind it now. Architecture §14 says it should be **replaced, not extended**. A local `users` table is deliberately **not** built ahead of the answer: if identity lives in an IdP it would be a mirror, not a source of truth. Actors stay free-text `varchar(128)` meanwhile. |
| Q12 | Email delivery mechanism and sending domain | Engineering / IT | ERT-1010 | ERT-440 writes to an outbox table. Rows become real sends when the adapter lands; no use case changes. |
| Q2 | The actual requirement checklist, and whether it differs by employment type | HR stakeholder | ERT-130 seed *content* (not its mechanism) | Appendix A seeded and clearly marked illustrative. Templates are data, so replacing them is a seed change. |
| Q3 | Which documents have validity periods, and how long | HR stakeholder | Phase 4 | Columns already exist and are nullable. |
| Q5 | Data-protection regime and portal consent notice | Legal / compliance | ERT-912 attestation **text** | Versioned placeholder text. The versioning mechanism is the part that is expensive to add later. |
| Q16 | Is a phone number available for out-of-band verification of email changes? | HR | Phase 2 `ChangeHireEmailUseCase` | **Genuinely blocked.** Without a channel, SEC-03 is unremediated in practice regardless of what §7.4 says. Flagged, not worked around. |
| — | Object storage target — no question number in the PRD | Engineering | ERT-710, ERT-810 | Local filesystem adapter behind `DocumentStorage`. Malware scanning (`isClean`) returns a documented stub until a scanner is chosen. |
| Q1 | How do tenured employees enter the system? | HR / IT | All of Phase 4 | Phase 4 is not scheduled. The §9.3 seams are already open. |
| Q6 | How does `COMPLETE` reach account provisioning? | IT | The handoff seam | `COMPLETE` is recorded; nothing consumes it yet. |
| Q9, Q17 | Confirm the §7.1 upload limits and §6.4 / §6.6 defaults | HR / IT | Nothing | Defaults are in the PRD and stored in `app_setting`, changeable without a deployment. |

---

## Phase 2 — Validation loop and accountability

Not yet expanded into tickets. Expand when Phase 1 closes.

| Epic | Covers | PRD |
|---|---|---|
| Approve and reject with identity binding | `ApproveSubmissionUseCase`, `RejectSubmissionUseCase`, name-match and photo-match confirmation, `CHANGES_REQUESTED` handling, link extension on rejection, the 3-rejection flag, originals-sighted | §8.5, §7.3, §7.1 |
| Email change | `ChangeHireEmailUseCase`, out-of-band verification method as a required field, second approver where approved documents exist, token and PIN rotation, notice to the old address | §7.4, §8.8 — **needs Q16** |
| Reopen and completed states | `ReopenRecordUseCase` issuing fresh credentials, the `COMPLETED` read-only confirmation page, grace window into `CLOSED`, `RequestNewLinkUseCase` | §7.3, SEC-08, SEC-09 |
| Admin catalogue and settings | Requirement template CRUD, employment-type mapping, the §6.4 settings surface with bounds and audit | §8.10, §8.11 |
| Accountability surfaced | Access trail on the hire record, anomaly flags visible in the list, PIN-failure burst notification, audit log exposed | §8.12 |

## Phase 3 — Scale and efficiency

| Epic | Covers | PRD |
|---|---|---|
| CSV bulk import | validate → preview → confirm, with invitation sending as a **separate explicit action** | §8.2, SEC-12 |
| Reminders and dashboard | escalating nudges at day 3/7/14 with opt-out; counts by status, awaiting review, overdue, links nearing expiry | P1 |
| Exception report | separation-of-duties detection, shared-email records, anomaly records; names an owner and a cadence | §8.13, SEC-10 |
| HR convenience | bulk download as ZIP, activity timeline, internal notes, target completion dates, force-submit, session management | P1 |

## Phase 4 — Document lifecycle

**Gated on Q1.** Roughly doubles the domain and introduces a new persona. Validity windows, an
expiry-monitoring dashboard with configurable lead time, scoped renewal links, per-employee document
history, and an existing-employee roster (§9). The v1 seams are already open — submissions are
versioned with nullable `validFrom`/`validUntil`, the catalogue is independent of the onboarding
flow, `LinkScope.Only` exists, and nothing assumes one packet per employee — so this costs a
migration rather than a redesign.

## Cross-cutting, outside the phases

| Epic | Covers | Gate |
|---|---|---|
| HR authentication | Replace the placeholder JWT scheme with the real model; add a user store or SSO integration | Q4 |
| Security hardening | Malware scanning before a file becomes previewable; retention policy with scheduled deletion, suspended while an anomaly flag is open | Q7, Q18 |
| Documentation hygiene | The root README is still stock Ktor generator boilerplate and advertises deleted features | — |

---

## Contradictions and gaps to escalate

Found while writing the backlog. Each is a specification question, not an implementation choice, and
none should be resolved silently in code. Owners are suggestions.

| # | Issue | Where | Suggested owner |
|---|---|---|---|
| E1 | **§7.2 asks for thumbnails on the review screen; §8.6 forbids the portal returning a preview.** A direct contradiction between two P0 sections. §8.6 carries the audit disposition for SEC-02 and §15 names the write-mostly portal as non-negotiable, so §8.6 should win and §7.2 should be amended. The backlog builds to §8.6. | PRD §7.2 line 230 vs §8.6 line 355 | PRD owner |
| E2 | **The upload MIME allowlist is never stated.** §12 requires "file type allowlist … enforced server-side"; §8.4 names JPG, PNG, HEIC and PDF for *preview* and says other formats "offer download only", implying they are accepted. There is no question number for this. ERT-732 starts from the §8.4 preview set as configuration. | PRD §12 line 548, §8.4 | HR / Engineering — open a question next to Q11 |
| E3 | **A duplicate-email override silently freezes retention.** `Employee.retentionFrozen` derives from `anomalyFlags.isNotEmpty()`, so the `SHARED_EMAIL` flag set by an §8.1 override suspends version purging for that hire. §7.1 scopes the freeze to "a fraud or anomaly flag" — whether a shared address counts is undefined. ERT-734 implements the freeze as written and flags it. | `Employee.kt:35`, PRD §7.1, §8.1 | PRD owner |
| E4 | **§8.1 requires an invite-delivery failure indicator; §11 models no column for it.** Recommendation is to derive it from the audit log rather than add a column, but that is an inference, not the PRD's instruction. | PRD §8.1 vs §11 | PRD owner |
| E5 | **Malware scanning is a P0 control in §12 with no library, no owner and no question number.** ERT-710 wires the `isClean` gate and stubs it to `true`; ERT-810 refuses to serve anything that fails it. That makes it a **named Phase 1 exit risk, not a delivered control.** | PRD §12 line 550 | Engineering / Security |
| E6 | **No object-storage target has been chosen and the PRD asks no question about it.** ERT-710 uses a filesystem adapter with an opaque key scheme so the swap stays a binding change. | PRD §11, §12 | Engineering |
| E7 | **The audit doc says 14 findings; the dispositions table lists SEC-01 through SEC-15.** Cosmetic, but the count is quoted in the PRD's own changelog. | `2026-09-09-security-audit.md` | Audit author |
