# Security & Business Rules Audit — Employee Requirements Tracker — 2026-09-09

> **Disposition recorded 2026-09-09.** All findings accepted. SEC-01 accepted with a modified remedy — a single-channel PIN in place of SMS — and SEC-04 accepted as a business-process control. See **Dispositions** at the end of this document. Remedies are folded into PRD v0.4.

## Scope

Audited: **PRD v0.3** (approved revision), all sections. This is a **specification audit**, not a code audit — there is no implementation yet. Findings cite PRD sections rather than source files, and "reachability" means a documented workflow that reaches the weakness, not a call path.

Trigger for this audit: the upload link is a bearer credential, and the PRD does not say what happens when it reaches someone other than the hire.

**Not covered:** infrastructure and hosting choices, the downstream account-provisioning system, the existing HRIS, and the legal analysis flagged in PRD §12 (still open).

---

## Authorization model audited against

Inferred from the PRD, because no role model has been confirmed yet (PRD §14, Blocking Q4). **Confirm this before acting on the audit.**

| Actor | Authenticates via | May read | May write |
|---|---|---|---|
| HR Officer | Unspecified — open question | Every hire record and every submitted document | Create hires, edit details incl. email, approve/reject, resend/revoke links, reopen completed records |
| HR Admin | Unspecified | As HR Officer | Plus requirement templates and link policy settings |
| New Hire | **Possession of a URL. Nothing else.** | Own checklist, own submitted documents, rejection reasons | Upload and replace own documents, submit packet |
| Anyone holding the URL | — | **Identical to New Hire** | **Identical to New Hire** |

That last row is the entire problem. The PRD never distinguishes "the hire" from "whoever has the link," so every rule written for the first applies to the second.

---

## Summary

| Severity | Count |
|---|---|
| Critical | 4 |
| High | 3 |
| Medium | 4 |
| Low | 3 |
| **Total** | **14** |

The three that matter most:

1. **The portal hands back everything it has been given** (SEC-02) — a leaked link is not merely a fraudulent-upload risk, it is bulk disclosure of birth certificates, government IDs and medical results. This is also the cheapest finding to fix.
2. **The documented email-change workflow is an account-takeover procedure** (SEC-03) — an attacker does not need to intercept a link; they can ask HR to send them one, and the PRD instructs HR to comply.
3. **Nothing anywhere checks that the documents belong to the person hired** (SEC-04) — the system can report `COMPLETE` on a genuine, valid, correctly-formatted set of documents belonging to a different human being.

---

## Findings

### SEC-01 — The upload link is an unauthenticated bearer credential with a 90-day life [Critical]

**Where:** §6.3 (link statuses), §6.4 (90-day ceiling), §8.6 ("Given a valid token, then the employee sees only their own requirements").

**What:** Possession of the URL is the entire authentication scheme. There is no second factor, no proof of identity, no session concept, and no limit on how many people or devices use the same token concurrently. §8.6's acceptance criterion is satisfied by an attacker — the token *does* scope to one employee's requirements; it just doesn't check who is holding it.

**Impact:** Whoever holds the URL becomes the hire for up to 90 days. They can read every document collected so far (see SEC-02), replace unapproved documents with forgeries, and finalise the packet — which locks every requirement (§6.1) and shuts the real employee out of their own onboarding.

**Reachability:** No interception required. All of these are ordinary events:
- The hire forwards the link to a relative or a fixer to "help with the uploads"
- Their personal email is compromised, or has a forwarding rule on it
- The address is a shared household or agency inbox
- HR mistypes the address at §8.1 — the duplicate check catches duplicates, not typos, so the link goes to a stranger who receives a named individual's onboarding portal
- The link sits in browser history on a shared or public computer
- The hire pastes it into a group chat asking for help

**Coverage gap:** No acceptance criterion in §8.6 tests the negative path — a holder who is not the hire. It cannot be tested, because the spec has no way to tell the difference.

**Recommended fix:** Split the channel. Capture a **mobile number at §8.1** and require a one-time code sent by SMS to open a portal session. The link then becomes a *pointer*, not a credential, and email compromise alone stops being sufficient. If SMS is rejected on cost or deliverability, a knowledge factor already on the hire record (date of birth) raises the bar meaningfully at zero cost, but is guessable by a family member and should be treated as a stopgap. Sessions should be short-lived (30–60 minutes) rather than the link granting standing access.

---

### SEC-02 — The portal displays previously submitted documents back to the holder [Critical]

**Where:** §7.3 ("approved documents stay locked so the employee cannot accidentally replace something already cleared" — locked, but still shown), §6.3 (`COMPLETED` shows "read-only confirmation of what was accepted"), §8.6 ("previously uploaded documents and their statuses are visible").

**What:** The portal is specified as read-write. It shows the holder what has already been submitted.

**Impact:** This converts SEC-01 from a *tampering* risk into a *mass personal-data disclosure*. A link leaked on day 60 exposes everything gathered in those 60 days: birth certificate, government IDs, tax and social security numbers, and pre-employment medical results — for one named individual, neatly indexed. Under any data-protection regime this is a reportable breach, and §12 already flags breach-notification duties as unresolved.

**Reachability:** Every path in SEC-01.

**Recommended fix:** Make the portal **write-mostly**. The employee needs to know *whether* a document was accepted, not to re-read the document. Show requirement name, status, submission date, and rejection reason. Do not render a preview, do not issue a signed download URL, and do not show the original filename (filenames leak content: `NBI_Clearance_DelaCruz_1998.pdf`). The `COMPLETED` confirmation page should list requirement names and outcomes only.

**Note:** This is the single cheapest high-value change in the audit. It removes most of the impact of SEC-01, SEC-03 and SEC-08 without adding a field, a message, or a piece of infrastructure — and it should ship regardless of what is decided about second factors.

---

### SEC-03 — The email-change workflow is a documented account-takeover path [Critical]

**Where:** §7.4, §8.8.

**What:** §7.4 is well-designed for the *mechanics* — old token revoked, new link issued, change audit-logged, confirmation dialog shown. But it is silent on the **trigger**. The stated scenario is "the employee gives notice that the email provided is no longer active," over an unspecified channel. The controls all fire *after* HR has decided to make the change; nothing governs whether the request was legitimate.

**Impact:** An attacker emails HR: *"Hi, this is Juan from the new batch — I can't access my old email, can you resend my requirements link to juan.delacruz.new@gmail.com?"* HR follows the documented process exactly as written and issues a valid credential to the attacker. If the packet already contains approved documents, the attacker receives a completed PII set (see SEC-02). No interception, no technical skill, no defect — the front door, working as specified.

**Reachability:** §7.4 is the sanctioned procedure. Following it correctly is the vulnerability.

**Coverage gap:** §8.8's acceptance criteria test that the token rotates. None test that the requester was the employee.

**Recommended fix:** An email change must be **verified out-of-band and must never be actionable from an inbound email alone**. Call the mobile number captured at §8.1 (which SEC-01 already requires) and confirm against details on the hire record. Add an acceptance criterion requiring HR to record the verification method before the change commits. Where the packet already contains approved documents, require a reason and consider second-person approval. This is a procedural control, not a technical one — write it into the PRD as a business rule so it survives implementation.

---

### SEC-04 — Nothing binds the documents to the person hired [Critical]

**Where:** §8.5 (validation actions), §7.3 (validation loop), §1 (framing).

**What:** HR validates that a document is *present, legible and valid*. No rule anywhere requires checking that the name on the document matches the hire record, or that the person in the ID photo is the person who was interviewed and hired. The word "valid" is doing enormous unexamined work in this spec.

**Impact:** This is the fraud case that survives every fix above. A third party — or the hire themselves, colluding — submits **genuine, authentic, verifiable documents belonging to someone else**. A clean police clearance for a different individual. Someone else's medical result. The system marks the packet `COMPLETE`, the handoff to account provisioning fires (§3, Goal 4), and the organisation employs a person whose background was never actually checked. Every control in this audit passes. The document set is real; it just isn't theirs.

**Reachability:** Reachable through a leaked link (SEC-01/03), and reachable with no compromise at all by a hire who chooses to submit substituted documents.

**Recommended fix:** Two changes, both business rules rather than features.

1. **Add name-match and photo-match to the validation checklist in §8.5.** HR confirms the name on each document matches the hire record, and that photo ID matches the individual who attended the interview. Make it an explicit acceptance criterion so it appears in the reviewer's UI, not just in someone's habits.
2. **Add a physical verification checkpoint.** Originals are sighted on day 1 and HR marks "originals sighted" on the record. Until that happens the record is not truly complete.

And amend §1 to state what the system is authoritative for: **it is authoritative for "a document was received and appears valid." It is not authoritative for "this person is who they say they are."** Digital collection is *pre-validation* that removes the paper chase; it does not replace identity verification. If that framing is not written down, someone will eventually treat `COMPLETE` as an identity assurance, because that is what a green progress bar looks like.

---

### SEC-05 — Portal access is not logged, so a takeover is undetectable and unprovable [High]

**Where:** §12 (audit log covers "every view, approve, reject, download, email change, and reopen **by HR**"), §11 (`upload_link.last_accessed_at` — a single overwritten timestamp).

**What:** Employee-side access is not part of the audit trail. One timestamp, overwritten on every visit, records neither who, nor from where, nor how often.

**Impact:** When a fraudulent submission is discovered — and given SEC-01 through SEC-04, at some point one will be — nobody can answer the first question asked: *was this the employee, or someone else?* There is no IP, no user agent, no access history, no way to see that a single packet was worked on from three countries in one afternoon. It is also impossible to detect a takeover **while it is happening**; the only signal is the employee eventually complaining, and per SEC-14 they have no way to do that.

**Recommended fix:** Log every portal access — timestamp, IP, user agent, action, requirement touched — as first-class audit records, not a mutable column. Surface an access trail on the hire record for HR. Flag records accessed from more than N distinct IPs or from more than one country, and surface the flag alongside the existing rejection-count flag from §8.7. Retain the trail per the §12 retention policy.

---

### SEC-06 — No session boundary; the credential is standing, concurrent and unlimited [High]

**Where:** §6.4, §8.6.

**What:** The link grants immediate access on every visit, from any device, with no re-authentication and no cap on concurrent sessions, for up to 90 days.

**Impact:** Access granted once is access granted for the life of the link. A URL in the history of a shared computer, or a screenshot in a chat thread, remains live for months. There is no logout, and no way for the employee to end other sessions.

**Recommended fix:** Follows from SEC-01. The link initiates a short-lived session behind the second factor; the session, not the link, carries access. Record device and IP per session (SEC-05), and let HR terminate active sessions from the hire record.

---

### SEC-07 — The employee makes no declaration when submitting [High]

**Where:** §7.2 (Review & Submit — the employee confirms, but affirms nothing).

**What:** The review step captures an action, not a statement. There is no attestation that the documents are the submitter's own and genuine, and no acknowledgement of the consequences of falsification.

**Impact:** Two distinct costs. First, **evidentiary**: when forgery is found, the organisation has no record of the employee having claimed anything, which weakens the basis for withdrawing an offer or for disciplinary action, and weakens its position in any resulting labour dispute. Second, **compliance**: §12 flags consent language as an open question, and the submit step is the natural place to capture it. Right now that moment passes unused.

**Recommended fix:** Add a required attestation at the §7.2 confirm step — the documents are mine, they are genuine, I understand falsification may result in withdrawal of the offer or termination — plus the data-privacy consent notice once §14 Q5 is answered. Persist the attestation text version, timestamp, and IP with the packet. Version the text, because it will change and you will need to know which version was agreed to.

---

### SEC-08 — Reopening a completed record re-arms a dormant credential [Medium]

**Where:** §7.3 ("This reverts the packet to `CHANGES_REQUESTED`, **reactivates the link**, and re-notifies the employee").

**What:** Reopen resurrects the original token, potentially months after completion, at an address the employee may no longer control — former employer domain, deactivated account, recycled address.

**Impact:** A live credential is issued to an address whose ownership was verified long ago and may since have changed hands. Combined with SEC-02, the recipient receives the full document set.

**Recommended fix:** Reopen should **issue a fresh link and re-run the second factor**, never revive the old token. Confirm the address is still current before sending. Log reopen with a mandatory reason (§14 Q10 already asks whether reopen should require a second approver — this finding argues yes when approved documents are involved).

---

### SEC-09 — `request-new-link` makes email compromise permanently self-healing [Medium]

**Where:** Appendix B (`POST /api/portal/request-new-link`), §8.6.

**What:** The endpoint is correctly designed against enumeration — a constant response whether or not the address exists. But it re-delivers to the address on file, and email is the only channel.

**Impact:** An attacker who controls the inbox does not need to keep the original link. They can mint new ones indefinitely, surviving every expiry and every HR-initiated revocation, for the life of the record.

**Recommended fix:** Gate re-issuance behind the same out-of-band second factor as SEC-01, and rate-limit per employee per day. **If SEC-01 is implemented, this drops to Low** — the emailed link stops being sufficient on its own. Log every re-issuance to the access trail (SEC-05); repeated requests are a strong takeover signal.

---

### SEC-10 — One HR role can create, alter, approve and complete a record unaided [Medium]

**Where:** §2 (personas), §14 Blocking Q4 (role model unresolved), §8.1/§8.5/§8.8.

**What:** As specified, a single HR officer can create a hire, set or change the email, approve every document, and drive the record to `COMPLETE` — which triggers downstream account provisioning. No separation of duties, no second pair of eyes on any step.

**Impact:** An insider fraud path with no technical compromise at all: create a ghost hire, or wave a friend's substituted documents through §8.5 without genuine validation. The audit log records it faithfully, but nothing prevents it and nothing reviews the log. Per the severity rubric, internal-only is not grounds for downgrading this — insider misuse is precisely the threat model for an internal system holding this data.

**Recommended fix:** For v1, keep the single role but make the log actionable: an exception report showing records where the same officer created the hire, changed the email, and approved every document. For P1, require that the officer who changed an email is not the sole approver of that packet. Fold this into the answer to §14 Q4 rather than leaving the role model undefined.

---

### SEC-11 — Duplicate email address is a warn-and-proceed [Medium]

**Where:** §8.1 ("Given a duplicate email on an active hire, when HR saves, then a warning is shown and HR must confirm before proceeding").

**What:** Two active hires may share one email address after a confirmation click, with no reason recorded.

**Impact:** One inbox controls two individuals' packets. Legitimate in rare cases (a shared family address); indistinguishable from a fraud setup, since it lets one person drive two onboardings. The confirmation click leaves no record of why.

**Recommended fix:** Keep the override — blocking outright will strand genuine cases — but require a typed reason, write it to the audit log, and surface shared-address records in the exception report from SEC-10.

---

### SEC-12 — Bulk import multiplies mis-delivery blast radius [Medium]

**Where:** §8.2.

**What:** The create/send separation is a genuine strength and should be preserved. But nothing validates that an address in the email column actually belongs to the person named in the same row, and there is no staged send or volume cap.

**Impact:** A shifted column or a copy-paste error sends dozens of named onboarding portals to the wrong recipients in one action. With SEC-02 unfixed, each is a live PII portal; even with SEC-02 fixed, each is a fraudulent-upload vector.

**Recommended fix:** Show name and email side by side in the §8.2 preview so mismatches are visible to a human before sending. Cap a single send batch and require re-confirmation beyond it. After sending, disallow bulk resend without re-running validation.

---

### SEC-13 — Automatic version purging can destroy fraud evidence [Low]

**Where:** §7.1 ("Version retention: current + last 4 versions… older versions purged automatically").

**What:** Retention is a fixed storage-control rule with no exception for records under suspicion.

**Impact:** When fraud is discovered, the superseded versions are the evidence — the forged document that was quietly replaced is exactly the artifact that gets purged first. An attacker with portal access can also deliberately purge history by uploading five innocuous replacements.

**Recommended fix:** Freeze retention on any record with an open fraud or anomaly flag, and preserve all versions until the flag is cleared. Note that this interacts with the §12 retention policy — evidence preservation and data-minimisation pull in opposite directions, and legal should decide the balance (add to §14).

---

### SEC-14 — The employee has no way to report a compromised link [Low]

**Where:** §6.3, §8.6, §8.9 — no employee-initiated revocation path exists.

**What:** The employee is the person most likely to notice something wrong ("I never uploaded that"), and has no channel to act on it. Revocation is HR-initiated only.

**Impact:** The detection window is however long it takes the employee to find someone at HR by other means — during which the attacker retains access.

**Recommended fix:** Add a "this wasn't me / report a problem" action to the portal that immediately suspends the link and notifies HR. Cheap, and it is the only detection control that costs nothing to operate.

---

### SEC-15 — Force-submit conflicts with attestation and attribution [Low]

**Where:** §8.9 P1 / §7.2 ("HR may force-submit a packet on behalf of an unresponsive employee").

**What:** HR can perform the act that SEC-07 wants to attach a personal declaration to.

**Impact:** A force-submitted packet looks identical to an employee-submitted one in status terms, but carries no attestation. If that distinction is not recorded, the evidentiary value of every attestation is weakened, because none can be relied on without checking.

**Recommended fix:** Record force-submitted packets distinctly, never attach the employee attestation to them, and display the distinction on the hire record. Consider requiring the originals-sighted checkpoint (SEC-04) before a force-submitted record can reach `COMPLETE`.

---

## Areas checked with no findings

These were examined and are sound as specified. They should not be weakened during implementation.

- **Token generation and storage** (§12) — long, random, single-purpose, stored hashed. Correct.
- **Object storage exposure** (§12) — never publicly readable, short-lived signed URLs. Correct. (SEC-02 concerns *who receives* a signed URL, not how it is issued.)
- **User enumeration** (Appendix B) — `request-new-link` returns a constant response. Correctly reasoned and explicitly justified in the PRD.
- **Terminal-state information leakage** (§12) — closed and revoked pages leak no name or requirement list. Correct, and easy to lose during implementation.
- **Server-side enforcement of locked states** (§8.7) — explicitly required rather than hidden in the UI. Correct.
- **Import create/send separation** (§8.2) — the reasoning is sound and the control is right.
- **Rate limiting and malware scanning** (§7.1, §12) — present and appropriately placed.
- **Requirement-set and expiry snapshotting** (§5, §6.4) — not a security control as such, but it prevents retroactive state mutation, which is what makes the audit log meaningful.

---

## Task plan

Ordered by severity, with cheap unblocking work first.

### TASK-01 — Make the upload portal write-mostly
- **Fixes:** SEC-02; materially reduces SEC-01, SEC-03, SEC-08
- **PRD sections:** §6.3, §7.3, §8.6
- **Business rule:** the portal reports document *status*; it never returns document *content*.
- **Write this criterion first:** given a submitted document, when the portal is loaded, then no preview, download URL, or original filename is returned in the response body. Currently §8.6 requires the opposite — that is the change.
- **Change:** portal responses carry requirement name, status, submission date, rejection reason. Nothing else.
- **Done when:** no portal endpoint can return document bytes or a signed URL.
- **Note:** no new fields, no new infrastructure, no extra message to the employee. Ship this first regardless of what is decided on TASK-02.

### TASK-02 — Add an out-of-band second factor and a session boundary
- **Fixes:** SEC-01, SEC-06; downgrades SEC-09
- **PRD sections:** §8.1 (new field), §8.6, §6.3, §6.4
- **Business rule:** the link identifies a packet; the second factor identifies the person.
- **Write this criterion first:** given a valid link and no verified session, when the portal is opened, then only the verification prompt is returned and no requirement data.
- **Change:** capture mobile number at hire creation; SMS one-time code opens a 30–60 minute session; access is carried by the session, not the link.
- **Done when:** possession of the URL alone yields no employee data, and HR can terminate active sessions.
- **Depends on:** a decision on SMS cost and deliverability. If rejected, fall back to date of birth and record the decision as an accepted risk.

### TASK-03 — Harden the email-change workflow
- **Fixes:** SEC-03, SEC-11
- **PRD sections:** §7.4, §8.8, §8.1
- **Business rule:** an email change is never actionable from an inbound email alone.
- **Write this criterion first:** given an email-change request, when HR saves, then the form requires a recorded out-of-band verification method before the change commits.
- **Change:** verification against the mobile number on file; typed reason required; both written to the audit log; second-person approval where approved documents already exist.
- **Done when:** the audit log shows how each email change was verified, not merely that it happened.

### TASK-04 — Bind documents to the person, and say what the system is authoritative for
- **Fixes:** SEC-04
- **PRD sections:** §1, §8.5, §7.3
- **Business rule:** `COMPLETE` means documents were received and appear valid. It does not mean identity was verified.
- **Write this criterion first:** given a document whose name does not match the hire record, when HR reviews it, then the validation UI requires an explicit name-match confirmation before approval is possible.
- **Change:** add name-match and photo-match to the §8.5 validation checklist; add an "originals sighted" checkpoint before a record can be treated as fully cleared; amend §1 with the authoritativeness statement.
- **Done when:** no record reaches downstream provisioning without an explicit identity confirmation step, and the PRD says plainly that this system does not verify identity on its own.
- **Note:** mostly a process change. Cheap to specify, and the only finding that survives every technical fix in this plan.

### TASK-05 — Log portal access and surface anomalies
- **Fixes:** SEC-05, supports SEC-14
- **PRD sections:** §11 (`upload_link`, `audit_log`), §12, §8.4
- **Business rule:** every access to a packet is attributable and reviewable.
- **Write this criterion first:** given three portal accesses from different IPs, when HR opens the hire record, then all three appear in an access trail with timestamp, IP, and action.
- **Change:** replace `last_accessed_at` with append-only access records; flag multi-IP and multi-country access next to the existing rejection-count flag.
- **Done when:** "was this really the employee?" can be answered from the record.

### TASK-06 — Capture an attestation at submission
- **Fixes:** SEC-07, SEC-15
- **PRD sections:** §7.2, §8.6, §8.9
- **Business rule:** submitting the packet is a declaration, not just a button press.
- **Write this criterion first:** given the review screen, when the employee confirms without ticking the attestation, then submission is blocked.
- **Change:** required attestation plus privacy consent at confirm; persist text version, timestamp, IP; force-submitted packets are marked distinctly and carry no attestation.
- **Depends on:** §14 Q5 (legal — consent wording).

### TASK-07 — Reopen issues a fresh credential
- **Fixes:** SEC-08
- **PRD sections:** §7.3, §6.3
- **Write this criterion first:** given a reopened record, when the employee opens the previously issued link, then it is rejected as revoked.
- **Change:** reopen revokes the old token, issues a new one, re-runs the second factor, and requires address reconfirmation and a reason.

### TASK-08 — Close the separation-of-duties gap
- **Fixes:** SEC-10, supports SEC-11
- **PRD sections:** §2, §14 Q4
- **Change:** define the role model; add an exception report covering records where one officer created, altered the email, and approved throughout, and records sharing an email address.
- **Done when:** §14 Q4 is answered and the exception report has a named owner who reviews it on a stated cadence. An unread report is not a control.

### TASK-09 — Freeze retention on flagged records
- **Fixes:** SEC-13
- **PRD sections:** §7.1, §12
- **Change:** suspend automatic version purging for records with an open fraud or anomaly flag; preserve all versions until cleared.
- **Depends on:** legal input on the conflict between evidence preservation and data minimisation — add to §14.

### TASK-10 — Reduce import mis-delivery risk
- **Fixes:** SEC-12
- **PRD sections:** §8.2
- **Change:** show name and email adjacently in the validation preview; cap send-batch size with re-confirmation beyond it; require re-validation before any bulk resend.

---

## Proposed PRD amendments

If these findings are accepted, v0.4 needs:

| Change | Section |
|---|---|
| Mobile number added to the hire record and to CSV import | §8.1, §8.2, §11 |
| Statement of what the system is and is not authoritative for | §1 |
| Second factor and session model | §6.3, §6.4, §8.6 |
| Portal returns status only, never document content | §7.3, §8.6 |
| Out-of-band verification required for email changes | §7.4, §8.8 |
| Name-match, photo-match, and originals-sighted checkpoint | §8.5, §7.3 |
| Attestation and consent at submission | §7.2, §8.6 |
| Portal access trail; `last_accessed_at` replaced | §11, §12 |
| Reopen issues a fresh credential | §7.3 |
| Retention freeze on flagged records | §7.1, §12 |
| Exception report and role model | §2, §8.10 |

**New open questions for §14:**

| # | Question | Owner | Blocking |
|---|---|---|---|
| 15 | Is SMS OTP acceptable on cost and deliverability? If not, which fallback factor, and is the residual risk accepted in writing? | HR / IT / stakeholder | Yes |
| 16 | Is a mobile number reliably available at the point of hire creation? | HR | Yes |
| 17 | Who owns the day-1 originals-sighting checkpoint, and what happens if originals do not match? | HR | Yes |
| 18 | Where evidence preservation conflicts with data minimisation, which wins? | Legal | No |
| 19 | Who reviews the exception report, and how often? | HR | No |


---

## Dispositions

Recorded 2026-09-09 following stakeholder review. All 14 findings accepted; two with modified remedies.

| Finding | Disposition | Where it landed in PRD v0.4 |
|---|---|---|
| SEC-01 | **Accepted, remedy modified.** SMS OTP declined. Replaced with a 6-digit PIN issued with the link and required to open every session. | §5, §6.6, §8.1, §8.6, §8.9 |
| SEC-02 | Accepted as recommended | §7.3, §8.6, Appendix B |
| SEC-03 | Accepted as recommended | §7.4, §8.8 |
| SEC-04 | **Accepted, handled as business process.** Identity binding is HR's manual judgement; the system makes the step explicit and attributable. | §1, §8.5 |
| SEC-05 | Accepted as recommended | §8.12, §11 |
| SEC-06 | Accepted as recommended | §6.6, P1 session management |
| SEC-07 | Accepted as recommended | §7.2, §8.6 |
| SEC-08 | Accepted as recommended | §7.3 |
| SEC-09 | Accepted; severity remains Medium — see note below | §6.6, §12 |
| SEC-10 | Accepted as recommended | §8.13 |
| SEC-11 | Accepted as recommended | §8.1 |
| SEC-12 | Accepted as recommended | §8.2 |
| SEC-13 | Accepted as recommended | §7.1, §8.7 |
| SEC-14 | Accepted as recommended | §8.6 |
| SEC-15 | Accepted as recommended | §7.2 |

### Note on the modified SEC-01 remedy

The PIN travels in the same email as the link, so this is a **single-channel** control. It is not equivalent to the out-of-band factor originally recommended, and the audit's severity assessment for the underlying issue does not fully clear.

**What the PIN does fix:** every threat where the URL leaks *separately* from the email — shared-computer browser history, screenshots, links pasted into group chats, referrer leakage. A bare URL becomes inert. That is a real and substantial reduction, and it covers the most common accidental-leakage paths.

**What it does not fix:** mailbox compromise, mail-forwarding rules, shared inboxes, and mis-delivery to a mistyped address. In each of those the holder receives both halves of the credential.

**Consequences that are now load-bearing rather than optional:**

1. **SEC-02's fix carries the residual risk.** Because mailbox compromise is not prevented, it must be made survivable. A write-mostly portal turns that event from bulk PII disclosure into a fraudulent upload HR should catch at validation. If document preview is ever added back to the portal, this accepted risk becomes unacceptable and must be re-decided.
2. **SEC-09 does not downgrade.** The audit noted it would drop to Low if SEC-01 gained an out-of-band factor. It has not, so `request-new-link` remains a Medium finding: an attacker holding the mailbox can mint fresh links and PINs indefinitely. Rate limiting and access-trail logging are the mitigation; they are detective, not preventive.
3. **SEC-03's remedy needs a channel.** Out-of-band verification of email changes was predicated on a mobile number. Since none is captured here, verification must use the recruitment record's phone number or the recruiter who met the candidate — hence blocking question 16 in PRD §14. Without an answer, SEC-03 is unremediated in practice regardless of what §7.4 says.

**Recorded as accepted risk in PRD §12**, with named triggers for revisiting: observed mailbox-compromise incidents, volume growth that makes per-message SMS cost trivial, or expansion of the requirement catalog to more sensitive documents.

### Note on the modified SEC-04 remedy

Handling identity binding as manual HR validation is the right call — no technical control can determine that a genuine document belongs to the person hired. Two things were nonetheless written into the PRD so the decision holds up in practice:

1. **The step is unskippable and attributable.** Approve stays disabled until the officer confirms the name matches, and that confirmation is logged with the approval. A rule that lives only in someone's habits disappears the first busy week.
2. **`COMPLETE` is defined as not meaning identity assurance** (§1), and an *originals sighted* flag records the physical checkpoint separately. Without this, a green progress bar will eventually be read downstream as verification, because that is what a green progress bar looks like.
