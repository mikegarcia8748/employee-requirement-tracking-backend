-- V4 HR user accounts (ERT-190). PRD 14 Q4, answered 2026-09-16: a handful of HR staff, local
-- accounts held here, two roles, bcrypt, no SSO.
--
-- Written in the style V1 establishes: unquoted lowercase identifiers, no IF NOT EXISTS, no
-- dialect-specific syntax. 'MigrationTest.migration portability' checks this file for the same
-- banned constructs it checks V1 for.
--
-- THE FOUR ACTOR COLUMNS ARE DROPPED AND RE-ADDED RATHER THAN ALTERED IN PLACE, AND THAT DISCARDS
-- ANY DATA THEY HOLD. Two reasons, and the first alone would not be enough:
--
--   1. There is nothing to discard. No code path writes any of them: hire creation is ERT-430,
--      document review is Phase 1, and 'app_settings.updated_by' is only written by
--      AppSettingsRepository.updateLinkPolicy, which no route calls yet. The columns are empty in
--      every environment this migration can reach.
--   2. Even with data, there would be no conversion. They hold free text -- an email, a typed-in
--      name -- and the new columns hold a users(id) that must EXIST. There is no users row for
--      "whatever someone typed", so an in-place ALTER would have to invent one or fail.
--
-- ALTER COLUMN ... TYPE is also not portable: PostgreSQL requires the TYPE keyword and H2 does not
-- accept it in the same position. Drop-and-add is plain SQL in both, which is what this project's
-- H2-in-PostgreSQL-mode test suite can actually verify.
--
-- audit_logs.actor is the FIFTH actor column and deliberately does NOT become a foreign key. The
-- trail must record actors who are not users -- the V2 seed, the ERT-1020 expiry sweep, a future
-- import job -- and an append-only trail that can refuse a write because it cannot name a user is
-- worse than one carrying a string. It keeps its free text and gains a NULLABLE actor_user_id
-- beside it. 8.13's exception report joins on actor_user_id; everything else reads actor.
--
-- There is deliberately NO last_login_at. Same reasoning that kept last_accessed_at off
-- upload_links (11, SEC-05): one overwritten timestamp cannot answer who, from where, or how often.
-- A sign-in is an audit_logs row.

create table users (id varchar(8) primary key, email varchar(320) not null, full_name varchar(256) not null, password_hash varchar(256) not null, "role" varchar(32) not null, is_active boolean default true not null, password_change_required boolean default false not null, created_at timestamp not null);

-- Case-insensitive uniqueness, in TWO constraints rather than one expression index.
--
-- The obvious spelling is 'create unique index ... on users (lower(email))'. H2 does not support
-- expression indexes and rejects it outright, so the whole test suite would run against a schema
-- production could not have. A CHECK constraint carrying the same expression IS standard SQL and
-- both engines accept it, so the pair below is what this project can actually verify.
--
-- Together they are strictly stronger than the expression index would have been. The check forces
-- every stored address into canonical lower case, so 'A@x.com' cannot be written at all; the unique
-- index then makes a second row with the same canonical text impossible. Two casings of one address
-- can therefore never coexist, and every stored value is already in the form findByEmail compares
-- against -- which an expression index alone would not have guaranteed.
--
-- EmailAddress.of lower-cases on construction, so this application cannot violate either. These are
-- for the writers it does not control: a migration, an import, or a person at a psql prompt.
alter table users add constraint users_email_is_lowercase check (email = lower(email));
alter table users add constraint users_email_unique unique (email);

-- ── The four actor columns become foreign keys ───────────────────────────────────────────────────
-- on delete restrict throughout: a user who acted cannot be deleted out from under the record. That
-- is also why accounts are DEACTIVATED rather than deleted -- is_active above, not a delete path.

alter table employees drop column created_by;
alter table employees add column created_by varchar(8) not null;
alter table employees add constraint fk_employees_created_by__id foreign key (created_by) references users(id) on delete restrict on update restrict;

alter table employees drop column originals_sighted_by;
alter table employees add column originals_sighted_by varchar(8) null;
alter table employees add constraint fk_employees_originals_sighted_by__id foreign key (originals_sighted_by) references users(id) on delete restrict on update restrict;

alter table submissions drop column reviewed_by;
alter table submissions add column reviewed_by varchar(8) null;
alter table submissions add constraint fk_submissions_reviewed_by__id foreign key (reviewed_by) references users(id) on delete restrict on update restrict;

alter table app_settings drop column updated_by;
alter table app_settings add column updated_by varchar(8) null;
alter table app_settings add constraint fk_app_settings_updated_by__id foreign key (updated_by) references users(id) on delete restrict on update restrict;

-- ── The fifth stays free text, and gains a nullable reference beside it ──────────────────────────

alter table audit_logs add column actor_user_id varchar(8) null;
alter table audit_logs add constraint fk_audit_logs_actor_user_id__id foreign key (actor_user_id) references users(id) on delete restrict on update restrict;
