# ERT-800 · Epic: HR document access

| | |
|---|---|
| **Type** | Epic |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-520, ERT-710, ERT-720 |
| **PRD** | §8.4, §12 |
| **Architecture** | §4, §12 invariant 1 |

**Description**

§8.4 requires HR to preview a submission inline — images and PDF rendered without leaving the page —
with submission timestamp, filename, size, version number and, where applicable, reviewer and review
timestamp.

This epic contains the **only** Phase 1 caller of `DocumentStorage.signedUrlFor`, which makes it the
one place invariant 1 could be broken by accident. The port is documented HR-side only and no portal
use case may depend on it; an architecture test asserting this route is the sole caller is cheap and
makes the rule enforceable rather than remembered.

The asymmetry with the portal is deliberate and worth stating so it does not read as inconsistency:
an HR DTO **may** carry `originalFilename`, `sizeBytes` and `mimeType` because §8.4 requires them,
while a portal DTO may not because §8.6 forbids them. ERT-170's guard is scoped to
`route/dto/portal/` for exactly this reason.

**Goal**

HR can open a document inline and walk its version history; every view is audited; nothing unscanned
is served.

**Stories**
- As an HR Officer, I want to see the document without downloading it so that validating a packet is
  one screen rather than a folder of files.
- As an HR Officer, I want to see what an employee replaced so that a suspicious substitution is
  visible.

**Out of scope**
- Approve and reject (§8.5). Phase 2.
- Bulk download as ZIP. Phase 3.

---

## ERT-810 — `GET /api/submissions/{id}/file`: short-lived signed URL

| | |
|---|---|
| **Parent** | ERT-800 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-520, ERT-710 |
| **PRD** | §8.4, §12, Appendix B |
| **Architecture** | §4, §12 invariant 1 |

**Description**

Object storage is never publicly readable and access goes through short-lived signed URLs (§12). The
route returns a URL the HR client embeds; it does not stream bytes through the application, and it
does not hand out a durable link.

Two controls ride on this route. Every document view is audited — §12 lists "every view … download"
explicitly, and the audit log is what makes a later question about who looked at a birth certificate
answerable. And the malware gate is consulted: a file that has not passed `isClean` is not served.
The gate returns `true` in Phase 1 because no scanner is chosen, which makes this a **named Phase 1
exit risk rather than a delivered control** — but the seam is live, so adding a scanner is one
adapter and no route change.

**Goal**

HR gets a short-lived URL for a document, the view is audited, and an unscanned document is withheld.

**Stories**
- As an HR Officer, I want the document to render inline so that I can check the name on it against
  the hire record without leaving the screen.

**Acceptance criteria**
- [ ] Given a submission exists, when HR clicks it, then the file renders inline without leaving the
      page (§8.4) — the response is a short-lived URL the client embeds
- [ ] `[derived]` Given the URL, then it expires within minutes and is not reusable afterwards
- [ ] `[derived]` Given a document view, then it is recorded in the audit log with actor and timestamp
- [ ] `[derived]` Given a document that has not passed the malware gate, then it is not served and the
      response says why
- [ ] `[derived]` Given no credentials, then the request is refused
- [ ] `[derived]` Given the whole application, then this route is the only caller of
      `DocumentStorage.signedUrlFor`

**Tests**
| Level | Test |
|---|---|
| Route | `document access - an HR request - returns a short-lived url and records the view in the audit log` |
| Route | `document access - an unauthenticated request - is refused` |
| Route | `document access - a document that has not passed the scan gate - is not served` |
| Architecture | `document storage - signedUrlFor - is reached from no route outside the HR document route` |

**Files**
- create `src/route/hr/DocumentRoutes.kt`
- create `src/domain/usecase/GetDocumentUrlUseCase.kt`
- modify [`test/ArchitectureTest.kt`](../../test/ArchitectureTest.kt)

**Out of scope**
- Choosing a malware scanner. Escalated on the roadmap.

---

## ERT-820 — `GET /api/employee-requirements/{id}/versions`

| | |
|---|---|
| **Parent** | ERT-800 |
| **Type** | Ticket |
| **Phase** | 1 |
| **Status** | Not started |
| **Depends on** | ERT-810 |
| **PRD** | §8.4, §7.1, Appendix B |

**Description**

Version history is what makes a substitution visible. §7.1 retains the current version plus the last
four so HR can see what changed, and the retention freeze in ERT-734 exists because that history is
evidence.

**Goal**

HR can list every retained version of a requirement with its metadata, newest first.

**Stories**
- As an HR Officer, I want to see prior versions so that a document swapped after I looked at it is
  visible rather than silent.

**Acceptance criteria**
- [ ] Given prior versions exist, then HR can view the version history for that requirement (§8.4)
- [ ] `[derived]` Given the history, then each entry carries submission timestamp, filename, size and
      version number (§8.4)
- [ ] `[derived]` Given versions, then they are returned newest first with the current one marked
- [ ] `[derived]` Given a requirement with no submissions, then an empty list is returned, not a 404
- [ ] `[derived]` Given no credentials, then the request is refused

**Tests**
| Level | Test |
|---|---|
| Route | `version history - a requirement with three versions - returns all three newest first with their metadata` |
| Route | `version history - a requirement with no submissions - returns an empty list` |
| Route | `version history - an unauthenticated request - is refused` |

**Files**
- modify `src/route/hr/DocumentRoutes.kt`
- create `src/route/dto/VersionHistoryDto.kt`

**Out of scope**
- Restoring a prior version. Not a requirement; replacement is the mechanism.
