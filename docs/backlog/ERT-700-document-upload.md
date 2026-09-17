# ERT-700 · Epic: Document upload

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-170, ERT-600 |
| **PRD** | §8.6, §8.7, §7.1, §12 |
| **Architecture** | §5, §8, §12 invariants 1, 5, 8 |

**Description**

The reason the system exists. PRD §15: "If Phase 1 must be trimmed, cut the validation workflow
before cutting the upload portal — the portal is the reason the system exists."

Three invariants land at once here. **Write-mostly** (1): the portal reports document status and
never content, a signed URL, or an original filename. **Server-side lock enforcement** (5): a locked
requirement refuses an upload in the use case, not by hiding a button. **Retention freeze** (8): no
version is purged while an anomaly flag is open, because an attacker with portal access could
otherwise erase a forgery by uploading five innocuous replacements.

Mobile-first is a functional requirement, not a preference: camera capture and gallery upload must
work in a phone browser, and status must update without a page reload.

**Goal**

An employee on a phone sees their checklist as statuses only, uploads and replaces freely while
editable, and is refused server-side when locked.

**Stories**
- As a New Hire, I want to upload from my phone camera and see the status change without reloading so
  that I can finish in one sitting.
- As a New Hire, I want the portal never to show me my own uploaded documents so that a leaked link
  costs me nothing.

**Out of scope**
- HR preview and version history. ERT-800.
- Multiple files per requirement. P1, and Q11 is unanswered — **one file per requirement in v1**.
- Malware scanning. The `isClean` gate is wired and stubbed; see the escalation list in the roadmap.

---

## ERT-710 — `DocumentStorage` dev adapter: local filesystem

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | — |
| **PRD** | §11, §12, §8.6 |
| **Architecture** | §4, §12 invariant 1 |

**Description**

**The object-storage target is now Q20, answered 2026-09-16: GCP Cloud Storage.** This ticket still
ships the filesystem adapter under `STORAGE_ROOT`, outside the repository — nothing in Phase 1 depends
on the provider, because `DocumentStorage` hides it, and dev and tests want a local one regardless.

Naming the target changes the adapter's **shape**, which is why it matters now rather than later. The
port is written against put / get / delete / **presign-with-a-TTL**, because that is what GCS V4
signed URLs offer. **So the filesystem adapter must mint its own expiring, MAC'd URL** — reuse
`TokenDigest` and the existing pepper so that no new secret appears — rather than returning a path
this application serves. Get that wrong and ERT-810 is rewritten when the provider lands, which is
exactly the migration the port exists to prevent.

The swap to real object storage is one Koin binding **provided the key scheme is opaque from day
one**: `{employeeId}/{requirementId}/v{version}/{random}` with **no filename component**. That
trailing component is a long random suffix and **must not** be shortened to an `EntityId`: its job is
to make the key unguessable, which is a security property rather than an identity one. The id
prefixes are short and enumerable by design; the suffix is what carries the entropy. A filename
inside a key leaks content through logs and URLs exactly as the document does —
`NBI_Clearance_DelaCruz_1998.pdf` says everything. Get the key scheme wrong and the swap becomes a
data migration rather than a binding change.

> **ERT-1200 added a hard constraint on 2026-09-17: this adapter must refuse to run outside dev.**
> The deployment target is Cloud Run, where every write outside the image layers goes to an
> **in-memory tmpfs charged against the container's memory limit**, with no eviction — and it is
> **per-instance**. Two consequences, both fatal and neither visible in a test: a 10 MB upload
> permanently consumes 10 MB of the memory budget until the instance is recycled, so a day of uploads
> OOM-kills it; and a document written by instance A is invisible to instance B, so the download that
> follows an upload misses roughly `(N-1)/N` of the time. The deployment is multi-instance — see the
> answer recorded on ERT-1120 — so `N` is not 1.
>
> `STORAGE_ROOT` being unset outside dev already fails. That is not enough, because the failure this
> prevents is someone *setting* it. The binding itself must refuse: **selected outside dev is a
> startup error**, the same shape and the same justification as `JWT_SECRET` and `TOKEN_PEPPER`. PRD
> §14 Q20 already chose GCS for production, so this only enforces a decision that is already made —
> and it converts "someone will deploy the dev adapter one day" into a boot failure at the first
> deploy, which is when it is cheap.

`isClean` returns `true` with a startup warning naming the gap. Wire the gate anyway — ERT-810 calls
it and renders "pending scan" on `false` — so the seam is live and swapping in a scanner is one
adapter. **The scanner is Q22 and ERT-1150: ClamAV via `clamd` on a local socket**, deliberately not
a hosted scanning API, because shipping government IDs and medical results to a third party is a
disclosure decision rather than a procurement one. Until ERT-1150 lands this is a named Phase 1 exit
risk, not a delivered control.

**Goal**

Document bytes are stored and retrievable behind an opaque key, the swap to object storage is a
binding change, and the malware gate exists even though it does not yet scan.

**Stories**
- As an engineer on the next session, I want storage behind a port so that choosing S3 later costs a
  binding rather than a migration.

**Acceptance criteria**
- [ ] `[derived]` Given a stored document, then its key contains no part of the original filename
- [ ] `[derived]` Given a key, then it encodes employee, requirement and version so a leaked key
      cannot be walked to another employee's documents by guessing
- [ ] `[derived]` Given `put` then `signedUrlFor`, then the document is retrievable through the URL
      and not by a direct path
- [ ] `[derived]` Given `delete`, then the bytes are gone and a subsequent read fails
- [ ] `[derived]` Given `isClean`, then it returns true and logs a warning naming the unimplemented
      control at startup
- [ ] `[derived]` Given `STORAGE_ROOT` is unset outside dev, then startup fails
- [ ] `[derived]` Given the filesystem adapter is the bound `DocumentStorage` outside dev, then
      startup **refuses** — Cloud Run's filesystem is ephemeral and per-instance (ERT-1200)

**Tests**
| Level | Test |
|---|---|
| Use case | `document storage - a file with a revealing name is stored - the generated key contains no part of that name` |
| Use case | `document storage - a stored document - is retrievable through a signed url` |
| Use case | `document storage - a deleted document - is no longer readable` |

**Files**
- create `src/data/storage/FilesystemDocumentStorage.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it

**Out of scope**
- Signed-URL expiry semantics. The dev adapter returns a URL to the HR route; ERT-810 owns that route.

---

## ERT-720 — `SubmissionRepository` adapter and mapper

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-240, ERT-410 |
| **PRD** | §7.1, §11, §9.3 |
| **Architecture** | §4, §13 |

**Description**

Two of the five methods carry more than their signature suggests. `isCurrent` must be maintained
**transactionally** — promoting a new version and demoting the previous one in one transaction, or a
crash between the two leaves a requirement with two current submissions or none. And `totalBytesFor`
backs the 100 MB per-employee cap, so it must be a SQL aggregate; loading every row to sum in memory
turns a cap check into a table scan on every upload.

`validFrom` and `validUntil` are written as null and read back as null throughout Phase 1. They exist
from the start so Phase 4 document-lifecycle tracking costs a migration rather than a redesign
(§9.3) — do not remove them for being unused.

**Goal**

Submissions persist with exactly one current version per requirement, storage totals are computed by
the database, and the Phase 4 validity columns round-trip.

**Stories**
- As an engineer on the next session, I want version bookkeeping handled by the repository so that
  the use case reasons about rules rather than about `isCurrent`.

**Acceptance criteria**
- [ ] `[derived]` Given a new current version, then the previous one is demoted in the same
      transaction
- [ ] `[derived]` Given any requirement with submissions, then exactly one is current
- [ ] `[derived]` Given many submissions for one employee, then `totalBytesFor` is computed by the
      database rather than by loading rows
- [ ] `[derived]` Given `purgeBeyondRetention`, then it removes oldest-first and keeps exactly the
      requested count
- [ ] `[derived]` Given a new submission, then `validFrom` and `validUntil` round-trip as null

**Tests**
| Level | Test |
|---|---|
| Repository | `submission persistence - a new current version - demotes the previous one in the same transaction` |
| Repository | `submission persistence - a requirement with three versions - exactly one is current` |
| Repository | `storage total - many submissions for one employee - is computed by the database` |
| Repository | `version purge - versions beyond the retention limit - are removed oldest first` |

**Files**
- create `src/data/repository/ExposedSubmissionRepository.kt`
- create `src/data/mapper/SubmissionMapper.kt`
- modify [`src/di/DataModule.kt`](../../src/di/DataModule.kt) — bind it

**Out of scope**
- Deciding *when* to purge. That is the use case's rule, in ERT-734.

---

## ERT-730 — `UploadDocumentUseCase`

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket — **split into ERT-731…734; budget two sessions** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-210, ERT-710, ERT-720 |
| **PRD** | §8.7, §7.1 |
| **Architecture** | §5, §12 invariants 5 and 8 |

**Description**

Four sub-tasks. The first two are independent; **733 and 734 interact** — versioning decides what
purge sees, and the freeze changes what purge does — so in practice they are written together. Budget
two sessions rather than pretending it is one.

**Goal**

Every §8.7 and §7.1 rule is proven against fakes.

---

### ERT-731 — Server-side lock check

| | |
|---|---|
| **Parent** | ERT-730 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-210 |
| **PRD** | §8.7, §6.1 |

**Description**

[`RequirementStatus`](../../src/domain/model/RequirementStatus.kt) carries `employeeCanUpload` — the
§6.1 table transcribed onto the type rather than re-derived at each call site. It is the single
authority for this check. Read the decision from it; never re-derive it from a status comparison,
because a re-derivation is what drifts when `EXPIRED` arrives in Phase 4.

**Acceptance criteria**
- [ ] Given a locked requirement, then upload is rejected server-side, not merely hidden in the UI
      (§8.7)
- [ ] Given a requirement in an editable state, then the employee can replace the file without a
      count-based limit (§8.7)
- [ ] `[derived]` Given `UNDER_REVIEW` or `APPROVED`, then upload is refused with `Conflict`
- [ ] `[derived]` Given `PENDING`, `UPLOADED` or `REJECTED`, then upload is accepted
- [ ] `[derived]` Given the check, then it reads `RequirementStatus.employeeCanUpload` rather than
      comparing statuses

**Tests**
| Level | Test |
|---|---|
| Use case | `upload lock - a requirement under review - upload is refused with conflict` |
| Use case | `upload lock - an approved requirement - upload is refused with conflict` |
| Use case | `upload lock - a rejected requirement - upload is accepted` |
| Use case | `upload lock - twenty replacements while editable - all are accepted` |

---

### ERT-732 — Size cap, MIME allowlist, per-employee storage cap

| | |
|---|---|
| **Parent** | ERT-730 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-731 |
| **PRD** | §7.1, §8.6, §12 |

**Description**

The constants already exist on [`Submission`](../../src/domain/model/Submission.kt): 10 MB per file,
100 MB per employee, 5 versions retained. Nothing reads them yet.

**The MIME allowlist is Q21, answered 2026-09-16 and written into PRD §12:** `image/jpeg`,
`image/png`, `image/heic`, `image/heif`, `application/pdf`. HEIF sits beside HEIC because iPhones emit
both.

That is the §8.4 preview set, and starting there buys a property worth having rather than merely
resolving an ambiguity: **every accepted file is one HR can look at in the browser.** A format HR must
download to read is a format that gets reviewed less carefully — and careful review is the control
§8.5 depends on. Office formats are excluded deliberately: a `.docx` is a zip of XML, it is not
previewable, and no §6 requirement asks for one. A certificate of employment often arrives as a Word
file, so HR will eventually want it — which is precisely why the list is **configuration**
(`UPLOAD_MIME_ALLOWLIST`, defaulting to the five) and not a constant. Adding a type should be a
decision taken with its eyes open, not a permissive default nobody chose.

**Acceptance criteria**
- [ ] Given a file over the size limit, then upload is blocked with a message stating the actual
      limit (§8.6)
- [ ] `[derived]` Given an upload that would take the employee over 100 MB, then it is refused
- [ ] `[derived]` Given a MIME type outside the allowlist, then it is refused server-side
- [ ] `[derived]` Given the allowlist, then it is read from `UPLOAD_MIME_ALLOWLIST`, defaulting to
      the five types in §12, and is not compiled in
- [ ] `[derived]` Given a declared MIME type that disagrees with the file's actual content, then the
      content wins — a client-declared type is not a control

**Tests**
| Level | Test |
|---|---|
| Use case | `upload size - a file above the ten megabyte limit - is refused with the actual limit stated` |
| Use case | `upload storage cap - an upload that would exceed the per-employee total - is refused` |
| Use case | `upload type - a file outside the allowlist - is refused server-side` |
| Use case | `upload type - a declared type that disagrees with the content - is judged on content` |

---

### ERT-733 — New version per replacement

| | |
|---|---|
| **Parent** | ERT-730 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-732 |
| **PRD** | §7.1, §9.3 |

**Acceptance criteria**
- [ ] `[derived]` Given a replacement upload, then a new version is created and the previous one is
      retained
- [ ] `[derived]` Given a replacement, then exactly one submission for that requirement is current
- [ ] `[derived]` Given the first upload against a `PENDING` requirement, then the requirement moves
      to `UPLOADED`
- [ ] `[derived]` Given a replacement against a `REJECTED` requirement, then the requirement returns
      to `UPLOADED` and the rejection reason is retained on the superseded version
- [ ] `[derived]` Given a new submission, then it carries null validity dates — the Phase 4 seam
- [ ] `[derived]` Given each upload, then it is recorded in the audit log

**Tests**
| Level | Test |
|---|---|
| Use case | `upload versioning - a replacement upload - creates version two and retains version one` |
| Use case | `upload versioning - after a replacement - exactly one submission is current` |
| Use case | `upload versioning - a replacement against a rejected requirement - returns it to uploaded` |
| Use case | `upload versioning - a new submission - carries null validity dates` |

---

### ERT-734 — Purge beyond retention, unless retention is frozen

| | |
|---|---|
| **Parent** | ERT-730 |
| **Type** | Sub-task |
| **Status** | Not started |
| **Depends on** | ERT-733 |
| **PRD** | §7.1, §8.7 |
| **Architecture** | §12 invariant 8 |

**Description**

SEC-13: the superseded version is the evidence. An attacker with portal access could otherwise erase
a forgery by uploading five innocuous replacements, and automatic purging would do the deleting for
them.

The freeze must be read from
[`Employee.retentionFrozen`](../../src/domain/model/Employee.kt), never from a parameter the
caller chooses — a caller-supplied flag is a caller-supplied bypass.

> **Settled 2026-09-16 (E3), and it changes behaviour from what this ticket first described.**
> `retentionFrozen` derived from `anomalyFlags.isNotEmpty()`, so `SHARED_EMAIL` — set by a
> duplicate-email override in ERT-431 — silently suspended purging for that hire. It now reads
> `anomalyFlags.any { it.freezesRetention }`, with the classification **on the flag** so that a new
> flag must choose rather than inherit.
>
> **Freezes:** `SUSPECTED_FRAUD`, `ACCESS_ANOMALY`, `PIN_FAILURE_SUSPENSION`, `REPEATED_REJECTIONS` —
> each says the uploads themselves may be contested, and §7.1's own threat, erasing a forgery by
> uploading replacements, *is* repeated replacement.
> **Does not freeze:** `SHARED_EMAIL`, `SEPARATION_OF_DUTIES` — administrative facts about how the
> record was handled, not about the documents. `SEPARATION_OF_DUTIES` is what decides it: with Q4
> answered as a handful of staff and no enforced separation, it would fire on nearly every record and
> disable retention entirely.

**Acceptance criteria**
- [ ] Given more than 5 versions exist, then the oldest is purged from storage (§8.7)
- [ ] Given a record with an open **evidentiary** flag, then no version is purged until the flag is
      cleared (§8.7)
- [ ] `[derived]` Given a record whose only flag is `SHARED_EMAIL`, then purging proceeds normally —
      the pair below is what keeps E3 enforced in code rather than settled only in prose
- [ ] `[derived]` Given the flag is later cleared, then purging resumes on the next upload
- [ ] `[derived]` Given a purge, then the bytes are removed from storage and the row from the database
- [ ] `[derived]` Given the freeze check, then it reads the employee's own flags and accepts no
      override parameter

**Tests**
| Level | Test |
|---|---|
| Use case | `version retention - a sixth version is uploaded - the oldest is purged from storage and the database` |
| Use case | `version retention - a sixth version uploaded while a suspected-fraud flag is open - nothing is purged` |
| Use case | `version retention - a sixth version uploaded while only a shared-email flag is open - the oldest is still purged` |
| Use case | `version retention - the flag is later cleared - purging resumes` |
| Use case | `version retention - the freeze - is read from the employee record and cannot be overridden by the caller` |

---

## ERT-740 — `GET /api/portal/{token}/checklist`: status only

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-170, ERT-620, ERT-630 |
| **PRD** | §8.6, §7.3 |
| **Architecture** | §12 invariant 1 |

**Description**

The DTO that ERT-170's guard exists for. **ERT-170 must be merged before this ticket starts.**

Write-mostly, stated as a rule about the response: the employee needs to know whether a document was
accepted, not to re-read their own birth certificate. No preview, no signed URL, no original
filename, no storage key, no MIME type. This single rule removes most of the consequence of a leaked
link and costs nothing.

In `CHANGES_REQUESTED`, only rejected requirements are editable and they are shown first. Phase 1
renders whatever rejection reason is stored — which is null until the Phase 2 reject flow populates
it — so build the field now and let Phase 2 fill it.

**Goal**

A verified session sees their own checklist as names, statuses and dates, and nothing that could
reconstruct a document.

**Stories**
- As a New Hire, I want to see which documents were accepted so that I know what is left, without the
  portal handing my documents back to anyone holding the link.

**Acceptance criteria**
- [ ] Given a verified session, then the employee sees only their own requirements (§8.6)
- [ ] Given a submitted document, then no portal response contains a preview, a signed download URL,
      or the original filename (§8.6)
- [ ] Given a packet in `CHANGES_REQUESTED`, then only rejected requirements are editable and are
      shown first (§8.6)
- [ ] Given a completed packet within the grace window, then a read-only confirmation page lists
      requirement names and outcomes only (§8.6)
- [ ] `[derived]` Given a session for another employee's link, then nothing about this employee is
      returned
- [ ] `[derived]` Given the checklist, then it carries the current attestation version and text, so
      the client knows what to submit against

**Tests**
| Level | Test |
|---|---|
| Use case | `write-mostly portal - a requirement with a submitted file - the checklist carries status and date but no file key, url or filename` |
| Use case | `checklist - a packet in changes requested - rejected requirements are editable and ordered first` |
| Use case | `checklist - a completed packet inside the grace window - returns names and outcomes only` |
| Use case | `checklist - a session for another employee's link - returns nothing about this employee` |
| Route | `checklist - the response - carries the current attestation version` |

**Files**
- create `src/domain/usecase/GetChecklistUseCase.kt`
- create `src/route/dto/portal/ChecklistDto.kt`
- modify `src/route/portal/PortalRoutes.kt`

**Out of scope**
- Thumbnails. §7.2 mentions them for the review screen and §8.6 forbids them; see the roadmap's
  escalation list. Build to §8.6.

---

## ERT-750 — `POST /api/portal/{token}/requirements/{id}/upload`

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket — **split if the streaming refusal is unfamiliar** |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-660, ERT-730, ERT-740 |
| **PRD** | §8.6, §8.7, Appendix B |
| **Architecture** | §8, §12 invariant 1 |

**Description**

Multipart, mobile-first, and the one route where three unfamiliar things meet: Ktor multipart, the
storage adapter's first real use, and the rate limiter's first real key extraction. If the streaming
refusal proves fiddly, split it — accept a single part and persist (a), refuse oversized parts and
disallowed types during streaming (b), and do the delete route separately (ERT-760).

The size cap must be enforced **while streaming the part**, not after `readBytes()`. Buffering a
refused 2 GB upload to discover it is too large is a denial of service with extra steps.

The upload response is the easiest place to leak `originalFilename` by accident — a confirmation
message is the obvious use for it. ERT-170's guard covers the DTO; add a route test as well.

**Goal**

An employee uploads from a phone browser, sees the status change without a reload, and a locked
requirement returns 409.

**Acceptance criteria**
- [ ] Given a successful upload, then status updates immediately without a page reload (§8.6) — the
      response carries the updated requirement status
- [ ] Given a locked requirement, then the request returns 409 (Appendix B)
- [ ] Given a file over the size limit, then upload is blocked with a message stating the actual
      limit (§8.6)
- [ ] `[derived]` Given an oversized part, then it is refused during streaming, not after buffering
- [ ] `[derived]` Given any response from this route, then it carries no original filename, storage
      key or URL
- [ ] `[derived]` Given the upload, then it is recorded in the access trail with action `UPLOAD`
- [ ] `[derived]` Given no verified session, then the request is refused and nothing is stored

**Tests**
| Level | Test |
|---|---|
| Route | `upload route - a multipart upload against a locked requirement - returns 409` |
| Route | `upload route - an oversized part - is refused during streaming rather than after buffering` |
| Route | `upload route - a successful upload - returns the updated requirement status` |
| Route | `upload route - any response - carries no original filename or storage key` |
| Route | `upload route - no verified session - stores nothing and returns the pin prompt` |

**Files**
- create `src/route/portal/PortalUploadRoutes.kt`
- create `src/route/dto/portal/UploadDto.kt`

**Out of scope**
- Multiple parts per requirement. Q11 unanswered; one file per requirement in v1.

---

## ERT-760 — `DELETE /api/portal/{token}/requirements/{id}/file`

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-750 |
| **PRD** | §8.6, Appendix B |

**Description**

Removing a file **before submission**. The same lock rule applies — a locked requirement refuses the
delete exactly as it refuses an upload, or the employee can empty a packet HR is mid-way through
reviewing.

**Goal**

An employee can remove a file while the requirement is editable, returning it to `PENDING`.

**Acceptance criteria**
- [ ] `[derived]` Given an editable requirement with a file, then deleting returns it to `PENDING`
- [ ] `[derived]` Given a locked requirement, then the delete is refused with 409
- [ ] `[derived]` Given a delete, then it is recorded in the audit log and the access trail
- [ ] `[derived]` Given a delete, then superseded versions are retained subject to the retention
      rules — deleting the current file is not a purge

**Tests**
| Level | Test |
|---|---|
| Route | `delete route - removing a file before submission - returns the requirement to pending` |
| Route | `delete route - a locked requirement - returns 409` |
| Use case | `file delete - prior versions exist - they are retained rather than purged` |

**Files**
- modify `src/route/portal/PortalUploadRoutes.kt`

**Out of scope**
- Deleting a whole packet. Not a portal capability.

---

## ERT-770 — Idle-clock touch on portal activity

| | |
|---|---|
| **Parent** | ERT-700 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-750 |
| **PRD** | §6.4 |
| **Architecture** | §12 invariant 7 |

**Description**

§6.4's idle clock dies "this long after the **last employee activity**". Without something moving
`idleExpiresAt` forward, it is frozen at issue and the idle clock silently becomes a second, shorter
absolute ceiling — a link would die 30 days after issue no matter how actively it was used. Nothing
in the epics so far moves it.

Put the touch in the session guard rather than in each route, so it cannot be forgotten when a route
is added.

> **This must not reintroduce `last_accessed_at`.** `idleExpiresAt` is a *policy* value — when this
> link stops working — not an access record. The append-only trail remains the only access history
> (§11, SEC-05, invariant 7).

**Goal**

Portal activity extends the idle window, never past the absolute ceiling, and no access-record column
is added.

**Stories**
- As a New Hire who uploads a document every week, I want my link to stay alive so that the idle clock
  measures inactivity rather than elapsed time.

**Acceptance criteria**
- [ ] `[derived]` Given a successful portal action, then `idleExpiresAt` moves to now plus
      `idleExpiryDays`
- [ ] Given `link.idle_expiry_days` is `0`, then nothing is written and only the absolute ceiling
      applies (§6.4)
- [ ] `[derived]` Given the absolute ceiling is nearer, then the touch never extends access past it
- [ ] `[derived]` Given a failed or denied action, then the idle clock does not move — an attacker
      guessing recovery PINs must not keep the link alive
- [ ] `[derived]` Given the schema, then no `last_accessed_at` column is added

**Tests**
| Level | Test |
|---|---|
| Use case | `idle clock - a successful portal action - pushes the idle expiry forward` |
| Use case | `idle clock - the idle clock is disabled - no idle expiry is written` |
| Use case | `idle clock - a touch near the absolute ceiling - does not extend access past it` |
| Use case | `idle clock - a denied pin attempt - does not move the idle expiry` |

**Files**
- modify `src/route/portal/PortalSessionGuard.kt`
- create `src/domain/usecase/TouchLinkActivityUseCase.kt`

**Out of scope**
- Any change to expiry evaluation, which stays lazy in ERT-644.
