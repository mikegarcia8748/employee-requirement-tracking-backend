# The board

Every unit of work, one row each. Open [docs/roadmap.md](../roadmap.md) for sequencing, the decision
register, the escalation list, and the pointer to what is next.

**12 epics · 72 tickets · 17 sub-tasks.**
Phase 0 and Phase 1 are specified to ticket depth. Phases 2–4 are epic-level entries in the roadmap,
expanded when their predecessor closes. ERT-1100 and ERT-1200 are cross-cutting and their tickets
carry gates rather than a phase.

Status values: `Not started` · `In progress` · `Done` · `Blocked`. A session updates the status of the
ticket it takes **in two places** — here and in the ticket's own block. Sub-tasks carry a Status row
too, because a session working one opens the epic file and never opens this page.

## [ERT-100](ERT-100-foundations.md) — Runtime foundations

Phase 0 · depends on — · **Done**

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-110](ERT-100-foundations.md#ert-110--wire-databasefactory-into-the-application-lifecycle) | Wire `DatabaseFactory` into the application lifecycle | Ticket | — | Done |
| [ERT-120](ERT-100-foundations.md#ert-120--flyway-baseline-migration-for-the-12-tables-plus-a-schema-drift-test) | Flyway baseline migration for the 12 tables, plus a schema-drift test | Ticket | ERT-110 | Done |
| [ERT-130](ERT-100-foundations.md#ert-130--seed-reference-data-and-app_setting-defaults-with-bounds) | Seed reference data and `app_setting` defaults with bounds | Ticket | ERT-120 | Done |
| [ERT-140](ERT-100-foundations.md#ert-140--map-apperror-to-http-status-in-statuspages) | Map `AppError` to HTTP status in `StatusPages` | Ticket | — | Done |
| [ERT-145](ERT-100-foundations.md#ert-145--a-uniform-response-envelope-for-api) | A uniform response envelope for `/api` | Ticket | ERT-140 | Done |
| [ERT-150](ERT-100-foundations.md#ert-150--expose-the-micrometer-registry-on-a-scrape-route) | Expose the Micrometer registry on a scrape route | Ticket | — | Done |
| [ERT-160](ERT-100-foundations.md#ert-160--deterministic-token-digest-separate-from-pin-hashing) | Deterministic token digest, separate from PIN hashing | Ticket | — | Done |
| [ERT-170](ERT-100-foundations.md#ert-170--architecture-guards-for-the-write-mostly-rule) | Architecture guards for the write-mostly rule | Ticket | — | Done |
| [ERT-180](ERT-100-foundations.md#ert-180--short-alphanumeric-identifiers-replace-uuids) | Short alphanumeric identifiers replace UUIDs | Ticket | ERT-120, ERT-130 | Done |
| [ERT-190](ERT-100-foundations.md#ert-190--hr-user-accounts-roles-and-sign-in) | HR user accounts, roles and sign-in | Ticket | ERT-180, ERT-240, ERT-330 | Done |
| [ERT-195](ERT-100-foundations.md#ert-195--dev-only-tracing-of-use-case-execution) | Dev-only tracing of use case execution | Ticket | ERT-150 | Done |

## [ERT-200](ERT-200-test-harness.md) — Test harness

Phase 0 · depends on ERT-120 · **Done**

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-210](ERT-200-test-harness.md#ert-210--in-memory-fakes-for-the-10-domain-ports) | In-memory fakes for the 10 domain ports | Ticket | — | Done |
| [ERT-220](ERT-200-test-harness.md#ert-220--deterministic-fixedclock-and-id-token-and-pin-generators) | Deterministic `FixedClock` and id, token and PIN generators | Ticket | — | Done |
| [ERT-230](ERT-200-test-harness.md#ert-230--domain-test-builders) | Domain test builders | Ticket | ERT-220 | Done |
| [ERT-240](ERT-200-test-harness.md#ert-240--repository-integration-test-base-against-h2-in-postgresql-mode) | Repository integration-test base against H2 in PostgreSQL mode | Ticket | ERT-120, ERT-130 | Done |

## [ERT-300](ERT-300-catalogue-policy.md) — Requirement catalogue and link policy

Phase 1 · depends on ERT-130, ERT-240 · **Done**

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-310](ERT-300-catalogue-policy.md#ert-310--appsettingsrepository-adapter-with-64-bounds-enforcement) | `AppSettingsRepository` adapter with §6.4 bounds enforcement | Ticket | ERT-130, ERT-240 | Done |
| [ERT-320](ERT-300-catalogue-policy.md#ert-320--requirementtemplaterepository-adapter) | `RequirementTemplateRepository` adapter | Ticket | ERT-130, ERT-240 | Done |
| [ERT-330](ERT-300-catalogue-policy.md#ert-330--auditlog-adapter) | `AuditLog` adapter | Ticket | ERT-240 | Done |
| [ERT-340](ERT-300-catalogue-policy.md#ert-340--get-apirequirement-templates) | `GET /api/requirement-templates` | Ticket | ERT-140, ERT-320 | Done |
| [ERT-350](ERT-300-catalogue-policy.md#ert-350--referencedatarepository-for-departments-and-employment-types) | `ReferenceDataRepository` for departments and employment types | Ticket | ERT-130, ERT-140, ERT-240 | Done |

## [ERT-400](ERT-400-hire-creation.md) — Hire creation

Phase 1 · depends on ERT-300

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-410](ERT-400-hire-creation.md#ert-410--employeerepository-adapter-and-rowdomain-mapper) | `EmployeeRepository` adapter and row↔domain mapper | Ticket | ERT-190, ERT-240 | Done |
| [ERT-420](ERT-400-hire-creation.md#ert-420--uploadlinkrepository-adapter-resolved-by-token-hash) | `UploadLinkRepository` adapter, resolved by token hash | Ticket | ERT-160, ERT-240 | Done |
| [ERT-430](ERT-400-hire-creation.md#ert-430--createhireusecase) | `CreateHireUseCase` | Ticket | ERT-190, ERT-210, ERT-230, ERT-310, ERT-320, ERT-350, ERT-410, ERT-420, ERT-440 | Not started |
| [ERT-431](ERT-400-hire-creation.md#ert-431--email-validation-and-duplicate-on-active-with-typed-reason) | ↳ Email validation and duplicate-on-active with typed reason | Sub-task | ERT-210, ERT-230 | Not started |
| [ERT-432](ERT-400-hire-creation.md#ert-432--requirement-set-snapshot-from-the-template-catalogue) | ↳ Requirement-set snapshot from the template catalogue | Sub-task | ERT-431 | Not started |
| [ERT-433](ERT-400-hire-creation.md#ert-433--token-issue-digested-with-expiresat-computed-from-policy) | ↳ Token issue, digested, with `expiresAt` computed from policy | Sub-task | ERT-432 | Not started |
| [ERT-434](ERT-400-hire-creation.md#ert-434--invitation-dispatch-and-surviving-delivery-failure) | ↳ Invitation dispatch and surviving delivery failure | Sub-task | ERT-433, ERT-440 | Not started |
| [ERT-440](ERT-400-hire-creation.md#ert-440--notifier-dev-adapter-outbox-table-no-smtp) | `Notifier` dev adapter: outbox table, no SMTP | Ticket | ERT-120 | Done |
| [ERT-450](ERT-400-hire-creation.md#ert-450--post-apiemployees-and-its-dtos) | `POST /api/employees` and its DTOs | Ticket | ERT-140, ERT-190, ERT-430 | Not started |

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
| [ERT-630](ERT-600-portal-access.md#ert-630--get-apiportaltoken-resolve-the-link-and-open-a-session) | `GET /api/portal/{token}`: resolve the link and open a session | Ticket | ERT-170, ERT-620, **ERT-1110** | Not started |
| [ERT-640](ERT-600-portal-access.md#ert-640--redeemrecoverypinusecase) | `RedeemRecoveryPinUseCase` | Ticket | ERT-210, ERT-310, ERT-610 | Not started |
| [ERT-641](ERT-600-portal-access.md#ert-641--identical-failure-for-a-wrong-pin-and-an-unrecognised-address) | ↳ Identical failure for a wrong PIN and an unrecognised address | Sub-task | ERT-210 | Not started |
| [ERT-642](ERT-600-portal-access.md#ert-642--temporary-lockout) | ↳ Temporary lockout | Sub-task | ERT-641, ERT-220 | Not started |
| [ERT-643](ERT-600-portal-access.md#ert-643--auto-suspend-with-hr-notified) | ↳ Auto-suspend with HR notified | Sub-task | ERT-642 | Not started |
| [ERT-644](ERT-600-portal-access.md#ert-644--session-issue-and-link-state-gates) | ↳ Session issue and link-state gates | Sub-task | ERT-643, ERT-620 | Not started |
| [ERT-650](ERT-600-portal-access.md#ert-650--post-apiportalrecover-and-post-apiemployeesidrecovery-pin) | `POST /api/portal/recover` and `POST /api/employees/{id}/recovery-pin` | Ticket | ERT-630, ERT-640, ERT-660 | Not started |
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
| [ERT-1010](ERT-1000-notifications-link-lifecycle.md#ert-1010--notifier-production-adapter-smtp-relay) | `Notifier` production adapter: SMTP relay | Ticket | ERT-440 | Not started |
| [ERT-1020](ERT-1000-notifications-link-lifecycle.md#ert-1020--expiry-sweep-idle-and-absolute-clocks-one-warning-if-incomplete) | Expiry sweep: idle and absolute clocks, one warning if incomplete | Ticket | ERT-420, ERT-440 | Not started |
| [ERT-1030](ERT-1000-notifications-link-lifecycle.md#ert-1030--resend-link-extend-link-revoke-link) | `resend-link`, `extend-link`, `revoke-link` | Ticket | ERT-450, ERT-520 | Not started |
| [ERT-1040](ERT-1000-notifications-link-lifecycle.md#ert-1040--portal-terminal-states) | Portal terminal states | Ticket | ERT-740, ERT-1020 | Not started |

## [ERT-1100](ERT-1100-operability-hardening.md) — Operability and hardening

Cross-cutting · depends on —

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-1110](ERT-1100-operability-hardening.md#ert-1110--redact-the-request-uri-in-statuspages-logging) | Redact the request URI in `StatusPages` logging | Ticket | ERT-140 | Not started |
| [ERT-1120](ERT-1100-operability-hardening.md#ert-1120--app_env-fails-closed-and-one-deployment-configuration-check) | `APP_ENV` fails closed, and one deployment-configuration check | Ticket | ERT-195 | Done |
| [ERT-1130](ERT-1100-operability-hardening.md#ert-1130--token-pepper-rotation-and-credential-re-issue) | Token pepper rotation and credential re-issue | Ticket | ERT-1030 | Not started |
| [ERT-1140](ERT-1100-operability-hardening.md#ert-1140--documentation-hygiene-root-readme-and-the-unused-r2dbc-dependencies) | Documentation hygiene: root README and the unused R2DBC dependencies | Ticket | — | Not started |
| [ERT-1150](ERT-1100-operability-hardening.md#ert-1150--malware-scanning-behind-the-isclean-gate) | Malware scanning behind the `isClean` gate | Ticket | ERT-710, ERT-810 | **Blocked on Q22** |
| [ERT-1160](ERT-1100-operability-hardening.md#ert-1160--ci-build-and-test-on-every-push) | CI: build and test on every push | Ticket | — | Done |
| [ERT-1165](ERT-1100-operability-hardening.md#ert-1165--pin-every-third-party-github-action-to-a-commit-sha) | Pin every third-party GitHub Action to a commit SHA | Ticket | ERT-1160 | Not started |
| [ERT-1170](ERT-1100-operability-hardening.md#ert-1170--bound-how-many-sign-in-attempts-reach-bcrypt) | Bound how many sign-in attempts reach bcrypt | Ticket | ERT-190, ERT-1185 | Not started |
| [ERT-1175](ERT-1100-operability-hardening.md#ert-1175--security-headers-hsts-and-a-request-body-limit) | Security headers, HSTS, and a request body limit | Ticket | ERT-1120 | Not started |
| [ERT-1180](ERT-1100-operability-hardening.md#ert-1180--dependency-and-image-scanning-with-an-sbom) | Dependency and image scanning, with an SBOM | Ticket | ERT-1160 | Not started |
| [ERT-1185](ERT-1100-operability-hardening.md#ert-1185--alert-on-the-audit-trail-that-already-exists) | Alert on the audit trail that already exists | Ticket | ERT-1250, ERT-1260 | Not started |


## [ERT-1200](ERT-1200-deployment.md) — Environments, containerisation and deployment

Cross-cutting · depends on ERT-1120, ERT-1160

| ID | Title | Type | Depends on | Status |
|---|---|---|---|---|
| [ERT-1210](ERT-1200-deployment.md#ert-1210--pin-the-jvm-target-and-the-main-class) | Pin the JVM target and the main class | Ticket | — | Done |
| [ERT-1220](ERT-1200-deployment.md#ert-1220--the-production-container-image) | The production container image | Ticket | ERT-1210, ERT-1240 | Done |
| [ERT-1230](ERT-1200-deployment.md#ert-1230--local-development-compose-and-the-three-ways-to-run) | Local development: compose, and the three ways to run | Ticket | ERT-1220, ERT-1120 | Done |
| [ERT-1240](ERT-1200-deployment.md#ert-1240--runtime-hardening-for-a-container-pool-shutdown-boot-time-codegen) | Runtime hardening for a container: pool, shutdown, boot-time codegen | Ticket | — | Done |
| [ERT-1241](ERT-1200-deployment.md#ert-1241--database_url-fails-closed-outside-dev) | `DATABASE_URL` fails closed outside dev | Ticket | ERT-1120 | Done |
| [ERT-1245](ERT-1200-deployment.md#ert-1245--the-password-change-gate-three-hr-routes-skip) | The password-change gate three HR routes skip | Ticket | ERT-190 | Done |
| [ERT-1250](ERT-1200-deployment.md#ert-1250--json-logging-for-cloud-logging) | JSON logging for Cloud Logging | Ticket | — | Done |
| [ERT-1260](ERT-1200-deployment.md#ert-1260--gcp-foundation-identity-federation-registry-network-database-secrets) | GCP foundation: identity federation, registry, network, database, secrets | Ticket | — | Not started |
| [ERT-1270](ERT-1200-deployment.md#ert-1270--deploy-uatyml-every-merge-reaches-uat) | `deploy-uat.yml`: every merge reaches UAT | Ticket | ERT-1220, ERT-1240, ERT-1260, ERT-1160 | Not started |
| [ERT-1280](ERT-1200-deployment.md#ert-1280--deploy-prodyml-promote-the-digest-uat-ran) | `deploy-prod.yml`: promote the digest UAT ran | Ticket | ERT-1270 | Not started |
| [ERT-1285](ERT-1200-deployment.md#ert-1285--move-migrations-out-of-startup-before-the-first-data-rewriting-migration) | Move migrations out of startup before the first data-rewriting migration | Ticket | ERT-1280 | Not started |
| [ERT-1290](ERT-1200-deployment.md#ert-1290--the-deployment-security-and-bottleneck-audits) | The deployment security and bottleneck audits | Ticket | ERT-1270 | Done |
