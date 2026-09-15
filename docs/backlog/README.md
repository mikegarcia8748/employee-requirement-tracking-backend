# The board

Every unit of work, one row each. Open [docs/roadmap.md](../roadmap.md) for sequencing, the decision
register, the escalation list, and the pointer to what is next.

**10 epics · 47 tickets · 17 sub-tasks.**
Phase 0 and Phase 1 are specified to ticket depth. Phases 2–4 are epic-level entries in the roadmap,
expanded when their predecessor closes.

Status values: `Not started` · `In progress` · `Done` · `Blocked`. A session updates the status of the
ticket it takes, both here and in the ticket's own block.

## [ERT-100](ERT-100-foundations.md) — Runtime foundations

Phase 0 · depends on —

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-110](ERT-100-foundations.md#ert-110--wire-databasefactory-into-the-application-lifecycle) | Wire `DatabaseFactory` into the application lifecycle | Ticket | — | Done |
| [ERT-120](ERT-100-foundations.md#ert-120--flyway-baseline-migration-for-the-12-tables-plus-a-schema-drift-test) | Flyway baseline migration for the 12 tables, plus a schema-drift test | Ticket | ERT-110 | Done |
| [ERT-130](ERT-100-foundations.md#ert-130--seed-reference-data-and-app_setting-defaults-with-bounds) | Seed reference data and `app_setting` defaults with bounds | Ticket | ERT-120 | Done |
| [ERT-140](ERT-100-foundations.md#ert-140--map-apperror-to-http-status-in-statuspages) | Map `AppError` to HTTP status in `StatusPages` | Ticket | — | Done |
| [ERT-150](ERT-100-foundations.md#ert-150--expose-the-micrometer-registry-on-a-scrape-route) | Expose the Micrometer registry on a scrape route | Ticket | — | Not started |
| [ERT-160](ERT-100-foundations.md#ert-160--deterministic-token-digest-separate-from-pin-hashing) | Deterministic token digest, separate from PIN hashing | Ticket | — | Not started |
| [ERT-170](ERT-100-foundations.md#ert-170--architecture-guards-for-the-write-mostly-rule) | Architecture guards for the write-mostly rule | Ticket | — | Not started |
| [ERT-180](ERT-100-foundations.md#ert-180--short-alphanumeric-identifiers-replace-uuids) | Short alphanumeric identifiers replace UUIDs | Ticket | ERT-120, ERT-130 | Done |
| [ERT-190](ERT-100-foundations.md#ert-190--hr-user-accounts-and-the-persona-model) | HR user accounts and the persona model | Ticket | ERT-180, Q4 | Blocked |

## [ERT-200](ERT-200-test-harness.md) — Test harness

Phase 0 · depends on ERT-120

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-210](ERT-200-test-harness.md#ert-210--in-memory-fakes-for-the-10-domain-ports) | In-memory fakes for the 10 domain ports | Ticket | — | Not started |
| [ERT-220](ERT-200-test-harness.md#ert-220--deterministic-fixedclock-and-id-token-and-pin-generators) | Deterministic `FixedClock` and id, token and PIN generators | Ticket | — | Not started |
| [ERT-230](ERT-200-test-harness.md#ert-230--domain-test-builders) | Domain test builders | Ticket | ERT-220 | Not started |
| [ERT-240](ERT-200-test-harness.md#ert-240--repository-integration-test-base-against-h2-in-postgresql-mode) | Repository integration-test base against H2 in PostgreSQL mode | Ticket | ERT-120, ERT-130 | Not started |

## [ERT-300](ERT-300-catalogue-policy.md) — Requirement catalogue and link policy

Phase 1 · depends on ERT-130, ERT-240

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-310](ERT-300-catalogue-policy.md#ert-310--appsettingsrepository-adapter-with-64-bounds-enforcement) | `AppSettingsRepository` adapter with §6.4 bounds enforcement | Ticket | ERT-130, ERT-240 | Not started |
| [ERT-320](ERT-300-catalogue-policy.md#ert-320--requirementtemplaterepository-adapter) | `RequirementTemplateRepository` adapter | Ticket | ERT-130, ERT-240 | Not started |
| [ERT-330](ERT-300-catalogue-policy.md#ert-330--auditlog-adapter) | `AuditLog` adapter | Ticket | ERT-240 | Not started |
| [ERT-340](ERT-300-catalogue-policy.md#ert-340--get-apirequirement-templates) | `GET /api/requirement-templates` | Ticket | ERT-140, ERT-320 | Not started |
| [ERT-350](ERT-300-catalogue-policy.md#ert-350--referencedatarepository-for-departments-and-employment-types) | `ReferenceDataRepository` for departments and employment types | Ticket | ERT-130, ERT-140, ERT-240 | Not started |

## [ERT-400](ERT-400-hire-creation.md) — Hire creation

Phase 1 · depends on ERT-300

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-410](ERT-400-hire-creation.md#ert-410--employeerepository-adapter-and-rowdomain-mapper) | `EmployeeRepository` adapter and row↔domain mapper | Ticket | ERT-240 | Not started |
| [ERT-420](ERT-400-hire-creation.md#ert-420--uploadlinkrepository-adapter-resolved-by-token-hash) | `UploadLinkRepository` adapter, resolved by token hash | Ticket | ERT-160, ERT-240 | Not started |
| [ERT-430](ERT-400-hire-creation.md#ert-430--createhireusecase) | `CreateHireUseCase` | Ticket | ERT-210, ERT-230, ERT-310, ERT-320, ERT-410, ERT-420, ERT-440 | Not started |
| [ERT-431](ERT-400-hire-creation.md#ert-431--email-validation-and-duplicate-on-active-with-typed-reason) | ↳ Email validation and duplicate-on-active with typed reason | Sub-task | ERT-210, ERT-230 | Not started |
| [ERT-432](ERT-400-hire-creation.md#ert-432--requirement-set-snapshot-from-the-template-catalogue) | ↳ Requirement-set snapshot from the template catalogue | Sub-task | ERT-431 | Not started |
| [ERT-433](ERT-400-hire-creation.md#ert-433--token-and-pin-issue-hashed-with-expiresat-computed-from-policy) | ↳ Token and PIN issue, hashed, with `expiresAt` computed from policy | Sub-task | ERT-432 | Not started |
| [ERT-434](ERT-400-hire-creation.md#ert-434--invitation-dispatch-and-surviving-delivery-failure) | ↳ Invitation dispatch and surviving delivery failure | Sub-task | ERT-433, ERT-440 | Not started |
| [ERT-440](ERT-400-hire-creation.md#ert-440--notifier-dev-adapter-outbox-table-no-smtp) | `Notifier` dev adapter: outbox table, no SMTP | Ticket | ERT-120 | Not started |
| [ERT-450](ERT-400-hire-creation.md#ert-450--post-apiemployees-and-its-dtos) | `POST /api/employees` and its DTOs | Ticket | ERT-140, ERT-430 | Not started |

## [ERT-500](ERT-500-hr-read-side.md) — HR read side

Phase 1 · depends on ERT-450

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-510](ERT-500-hr-read-side.md#ert-510--hire-list-with-progress) | Hire list with progress | Ticket | ERT-410, ERT-450 | Not started |
| [ERT-511](ERT-500-hr-read-side.md#ert-511--65-progress-arithmetic-tested-and-extracted) | ↳ §6.5 progress arithmetic, tested and extracted | Sub-task | ERT-230 | Not started |
| [ERT-512](ERT-500-hr-read-side.md#ert-512--get-apiemployees-default-view-search-and-filters) | ↳ `GET /api/employees`: default view, search and filters | Sub-task | ERT-511 | Not started |
| [ERT-520](ERT-500-hr-read-side.md#ert-520--get-apiemployeesid-detail-with-requirements) | `GET /api/employees/{id}`: detail with requirements | Ticket | ERT-510 | Not started |

## [ERT-600](ERT-600-portal-access.md) — Portal access model

Phase 1 · depends on ERT-160, ERT-170, ERT-400

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-610](ERT-600-portal-access.md#ert-610--portalaccesstrail-adapter-append-only) | `PortalAccessTrail` adapter, append-only | Ticket | ERT-240 | Not started |
| [ERT-620](ERT-600-portal-access.md#ert-620--portalsessionrepository-and-the-server-side-session-cookie) | `PortalSessionRepository` and the server-side session cookie | Ticket | ERT-120, ERT-160, ERT-610 | Not started |
| [ERT-630](ERT-600-portal-access.md#ert-630--get-apiportaltoken-pin-prompt-only-no-packet-data) | `GET /api/portal/{token}`: PIN prompt only, no packet data | Ticket | ERT-170, ERT-620 | Not started |
| [ERT-640](ERT-600-portal-access.md#ert-640--verifyportalpinusecase) | `VerifyPortalPinUseCase` | Ticket | ERT-210, ERT-310, ERT-610 | Not started |
| [ERT-641](ERT-600-portal-access.md#ert-641--identical-failure-for-wrong-pin-and-unknown-token) | ↳ Identical failure for wrong PIN and unknown token | Sub-task | ERT-210 | Not started |
| [ERT-642](ERT-600-portal-access.md#ert-642--temporary-lockout) | ↳ Temporary lockout | Sub-task | ERT-641, ERT-220 | Not started |
| [ERT-643](ERT-600-portal-access.md#ert-643--auto-suspend-with-hr-notified) | ↳ Auto-suspend with HR notified | Sub-task | ERT-642 | Not started |
| [ERT-644](ERT-600-portal-access.md#ert-644--session-issue-and-link-state-gates) | ↳ Session issue and link-state gates | Sub-task | ERT-643, ERT-620 | Not started |
| [ERT-650](ERT-600-portal-access.md#ert-650--post-apiportaltokenverify) | `POST /api/portal/{token}/verify` | Ticket | ERT-630, ERT-640, ERT-660 | Not started |
| [ERT-660](ERT-600-portal-access.md#ert-660--rate-limiting-on-every-public-portal-endpoint) | Rate limiting on every public portal endpoint | Ticket | ERT-140 | Not started |

## [ERT-700](ERT-700-document-upload.md) — Document upload

Phase 1 · depends on ERT-170, ERT-600

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-710](ERT-700-document-upload.md#ert-710--documentstorage-dev-adapter-local-filesystem) | `DocumentStorage` dev adapter: local filesystem | Ticket | — | Not started |
| [ERT-720](ERT-700-document-upload.md#ert-720--submissionrepository-adapter-and-mapper) | `SubmissionRepository` adapter and mapper | Ticket | ERT-240, ERT-410 | Not started |
| [ERT-730](ERT-700-document-upload.md#ert-730--uploaddocumentusecase) | `UploadDocumentUseCase` | Ticket | ERT-210, ERT-710, ERT-720 | Not started |
| [ERT-731](ERT-700-document-upload.md#ert-731--server-side-lock-check) | ↳ Server-side lock check | Sub-task | ERT-210 | Not started |
| [ERT-732](ERT-700-document-upload.md#ert-732--size-cap-mime-allowlist-per-employee-storage-cap) | ↳ Size cap, MIME allowlist, per-employee storage cap | Sub-task | ERT-731 | Not started |
| [ERT-733](ERT-700-document-upload.md#ert-733--new-version-per-replacement) | ↳ New version per replacement | Sub-task | ERT-732 | Not started |
| [ERT-734](ERT-700-document-upload.md#ert-734--purge-beyond-retention-unless-retention-is-frozen) | ↳ Purge beyond retention, unless retention is frozen | Sub-task | ERT-733 | Not started |
| [ERT-740](ERT-700-document-upload.md#ert-740--get-apiportaltokenchecklist-status-only) | `GET /api/portal/{token}/checklist`: status only | Ticket | ERT-170, ERT-620, ERT-630 | Not started |
| [ERT-750](ERT-700-document-upload.md#ert-750--post-apiportaltokenrequirementsidupload) | `POST /api/portal/{token}/requirements/{id}/upload` | Ticket | ERT-660, ERT-730, ERT-740 | Not started |
| [ERT-760](ERT-700-document-upload.md#ert-760--delete-apiportaltokenrequirementsidfile) | `DELETE /api/portal/{token}/requirements/{id}/file` | Ticket | ERT-750 | Not started |
| [ERT-770](ERT-700-document-upload.md#ert-770--idle-clock-touch-on-portal-activity) | Idle-clock touch on portal activity | Ticket | ERT-750 | Not started |

## [ERT-800](ERT-800-hr-document-access.md) — HR document access

Phase 1 · depends on ERT-520, ERT-710, ERT-720

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-810](ERT-800-hr-document-access.md#ert-810--get-apisubmissionsidfile-short-lived-signed-url) | `GET /api/submissions/{id}/file`: short-lived signed URL | Ticket | ERT-520, ERT-710 | Not started |
| [ERT-820](ERT-800-hr-document-access.md#ert-820--get-apiemployee-requirementsidversions) | `GET /api/employee-requirements/{id}/versions` | Ticket | ERT-810 | Not started |

## [ERT-900](ERT-900-review-and-submit.md) — Review, attest and submit

Phase 1 · depends on ERT-700

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-910](ERT-900-review-and-submit.md#ert-910--submitpacketusecase) | `SubmitPacketUseCase` | Ticket | ERT-210, ERT-740 | Not started |
| [ERT-911](ERT-900-review-and-submit.md#ert-911--blocked-until-every-required-requirement-has-a-file) | ↳ Blocked until every required requirement has a file | Sub-task | ERT-210 | Not started |
| [ERT-912](ERT-900-review-and-submit.md#ert-912--attestation-persisted-with-text-version-timestamp-and-ip) | ↳ Attestation persisted with text version, timestamp and IP | Sub-task | ERT-911 | Not started |
| [ERT-913](ERT-900-review-and-submit.md#ert-913--lock-all-requirements-and-move-the-packet-to-under_review) | ↳ Lock all requirements and move the packet to `UNDER_REVIEW` | Sub-task | ERT-912 | Not started |
| [ERT-920](ERT-900-review-and-submit.md#ert-920--post-apiportaltokensubmit) | `POST /api/portal/{token}/submit` | Ticket | ERT-750, ERT-910 | Not started |
| [ERT-930](ERT-900-review-and-submit.md#ert-930--post-apiportaltokenreport-problem) | `POST /api/portal/{token}/report-problem` | Ticket | ERT-650, ERT-660 | Not started |

## [ERT-1000](ERT-1000-notifications-link-lifecycle.md) — Notifications and link lifecycle

Phase 1 · depends on ERT-900

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-1010](ERT-1000-notifications-link-lifecycle.md#ert-1010--notifier-production-adapter) | `Notifier` production adapter | Ticket | ERT-440 | **Blocked on Q12** |
| [ERT-1020](ERT-1000-notifications-link-lifecycle.md#ert-1020--expiry-sweep-idle-and-absolute-clocks-one-warning-if-incomplete) | Expiry sweep: idle and absolute clocks, one warning if incomplete | Ticket | ERT-420, ERT-440 | Not started |
| [ERT-1030](ERT-1000-notifications-link-lifecycle.md#ert-1030--resend-link-extend-link-revoke-link) | `resend-link`, `extend-link`, `revoke-link` | Ticket | ERT-450, ERT-520 | Not started |
| [ERT-1040](ERT-1000-notifications-link-lifecycle.md#ert-1040--portal-terminal-states) | Portal terminal states | Ticket | ERT-740, ERT-1020 | Not started |
