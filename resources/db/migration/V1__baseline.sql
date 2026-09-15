-- V1 baseline: the PRD §11 schema, 12 tables.
--
-- GENERATED from SchemaUtils.createStatements(*allTables) against H2 in PostgreSQL mode, then
-- lowercased outside quoted regions and stripped of IF NOT EXISTS. Do not hand-edit: the drift
-- test in test/data/db/MigrationTest.kt asserts that Exposed has nothing left to actualize, and
-- the six quoted identifiers below are reserved words whose quoting Exposed reproduces verbatim
-- in every query it generates. Regenerate rather than patch.
--
-- Identifiers are unquoted and lowercase on purpose. H2 folds unquoted identifiers to upper case
-- and PostgreSQL to lower, and Exposed emits the same unquoted text at query time, so both fold
-- consistently. Quoting them here -- "DEPARTMENTS" -- would break PostgreSQL permanently.
--
-- IF NOT EXISTS is deliberately absent. Flyway's schema history is the idempotency mechanism; a
-- half-applied or wrong-shaped schema should fail loudly rather than be silently tolerated.
--
-- Two details are load-bearing rather than incidental:
--   * portal_access_logs is append-only, and there is deliberately NO last_accessed_at column on
--     upload_links. One overwritten timestamp cannot answer who, from where, or how often, which
--     is the first question asked when a fraudulent submission surfaces (§11, SEC-05).
--   * upload_links.token_hash is uniquely indexed and is the only lookup path for a link. The
--     plaintext token exists solely in the invitation email.
--
-- portal_sessions.token_hash is NOT in PRD §11. Without it the session cookie would have to carry
-- the primary key, storing a live bearer token in plaintext. Added here and in Tables.kt; the PRD
-- §11 table should be amended to match.
--
-- Identifiers are short alphanumeric strings, not uuid: 8 characters for employees.id and the
-- foreign keys pointing at it, 12 for everything else. Characters are drawn from A-Z, a-z and 0-9.
-- Two details are load-bearing here as well:
--   * varchar, never char. PostgreSQL blank-pads char(n) and returns the padding, so an 8-character
--     employee id stored in the 12-wide audit_logs.entity_id would come back with four trailing
--     spaces and fail the validation it was written under.
--   * There is no database-side default on any id column, so an insert must supply one. The
--     application generates ids through an injected port, which is what makes them deterministic
--     under test; a default here would silently bypass it.

create table departments (id varchar(12) primary key, "name" varchar(128) not null);
alter table departments add constraint departments_name_unique unique ("name");
create table employment_types (id varchar(12) primary key, "name" varchar(128) not null);
alter table employment_types add constraint employment_types_name_unique unique ("name");
create table requirement_templates (id varchar(12) primary key, "name" varchar(256) not null, instructions text not null, is_required boolean not null, expires boolean default false not null, validity_months int null, renewal_lead_days int null, is_active boolean default true not null, sort_order int default 0 not null);
create table template_assignments (employment_type_id varchar(12), requirement_template_id varchar(12), constraint pk_template_assignments primary key (employment_type_id, requirement_template_id), constraint fk_template_assignments_employment_type_id__id foreign key (employment_type_id) references employment_types(id) on delete restrict on update restrict, constraint fk_template_assignments_requirement_template_id__id foreign key (requirement_template_id) references requirement_templates(id) on delete restrict on update restrict);
create table employees (id varchar(8) primary key, first_name varchar(128) not null, middle_initial varchar(8) null, last_name varchar(128) not null, department_id varchar(12) not null, "position" varchar(256) not null, employment_type_id varchar(12) not null, email varchar(320) not null, packet_status varchar(32) not null, submitted_at timestamp null, submitted_by_hr boolean default false not null, attestation_version varchar(32) null, attested_at timestamp null, attested_ip varchar(64) null, originals_sighted_at timestamp null, originals_sighted_by varchar(128) null, anomaly_flags varchar(512) default '' not null, completed_at timestamp null, created_at timestamp not null, created_by varchar(128) not null, constraint fk_employees_department_id__id foreign key (department_id) references departments(id) on delete restrict on update restrict, constraint fk_employees_employment_type_id__id foreign key (employment_type_id) references employment_types(id) on delete restrict on update restrict);
create index employees_email on employees (email);
create table employee_requirements (id varchar(12) primary key, employee_id varchar(8) not null, template_id varchar(12) not null, name_snapshot varchar(256) not null, is_required_snapshot boolean not null, status varchar(32) not null, rejection_count int default 0 not null, constraint fk_employee_requirements_employee_id__id foreign key (employee_id) references employees(id) on delete restrict on update restrict, constraint fk_employee_requirements_template_id__id foreign key (template_id) references requirement_templates(id) on delete restrict on update restrict);
create table submissions (id varchar(12) primary key, employee_requirement_id varchar(12) not null, version int not null, file_key varchar(512) not null, original_filename varchar(512) not null, mime_type varchar(128) not null, size_bytes bigint not null, uploaded_at timestamp not null, status varchar(32) not null, valid_from timestamp null, valid_until timestamp null, reviewed_by varchar(128) null, reviewed_at timestamp null, rejection_reason text null, is_current boolean default true not null, constraint fk_submissions_employee_requirement_id__id foreign key (employee_requirement_id) references employee_requirements(id) on delete restrict on update restrict);
create table upload_links (id varchar(12) primary key, employee_id varchar(8) not null, token_hash varchar(256) not null, pin_hash varchar(256) not null, scope varchar(512) default 'ALL' not null, status varchar(32) not null, issued_at timestamp not null, expires_at timestamp not null, idle_expires_at timestamp null, extended_count int default 0 not null, failed_pin_count int default 0 not null, locked_until timestamp null, warned_at timestamp null, revoked_at timestamp null, revoked_reason text null, constraint fk_upload_links_employee_id__id foreign key (employee_id) references employees(id) on delete restrict on update restrict);
alter table upload_links add constraint upload_links_token_hash_unique unique (token_hash);
create table portal_sessions (id varchar(12) primary key, upload_link_id varchar(12) not null, token_hash varchar(256) not null, started_at timestamp not null, expires_at timestamp not null, ip varchar(64) not null, user_agent varchar(512) not null, ended_at timestamp null, constraint fk_portal_sessions_upload_link_id__id foreign key (upload_link_id) references upload_links(id) on delete restrict on update restrict);
alter table portal_sessions add constraint portal_sessions_token_hash_unique unique (token_hash);
create table portal_access_logs (id varchar(12) primary key, upload_link_id varchar(12) not null, session_id varchar(12) null, "timestamp" timestamp not null, ip varchar(64) not null, user_agent varchar(512) not null, "action" varchar(32) not null, outcome varchar(32) not null, constraint fk_portal_access_logs_upload_link_id__id foreign key (upload_link_id) references upload_links(id) on delete restrict on update restrict, constraint fk_portal_access_logs_session_id__id foreign key (session_id) references portal_sessions(id) on delete restrict on update restrict);
create table app_settings ("key" varchar(128) primary key, "value" varchar(512) not null, value_type varchar(32) not null, min_value varchar(64) null, max_value varchar(64) null, updated_by varchar(128) null, updated_at timestamp null);
create table audit_logs (id varchar(12) primary key, actor varchar(128) not null, "action" varchar(64) not null, entity varchar(64) not null, entity_id varchar(12) not null, "timestamp" timestamp not null, metadata text default '{}' not null);
create index audit_logs_entity_id on audit_logs (entity_id);
