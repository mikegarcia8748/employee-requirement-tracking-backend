-- V2 reference data: departments, employment types, and the requirement catalogue.
--
-- THE CATALOGUE BELOW IS ILLUSTRATIVE ONLY, PENDING OPEN QUESTION 2.
-- It is PRD Appendix A, which the PRD itself marks illustrative and defers to Q2 ("What is the
-- actual requirement checklist, and does it genuinely differ by employment type?"). Replacing it
-- is a seed change, not a code change. Do not treat any row here as a company decision.
--
-- Every insert is guarded by WHERE NOT EXISTS and there is deliberately NO UPDATE statement in
-- this file. Flyway already guarantees it runs once; the guard is so that a manual replay adds
-- what is missing and overwrites nothing an admin has since changed.
--
-- ON CONFLICT (col) DO NOTHING is a syntax error on H2 in PostgreSQL mode, and MERGE INTO ... KEY
-- is not valid PostgreSQL. INSERT ... SELECT ... WHERE NOT EXISTS is the only portable form.
--
-- Ids are hardcoded rather than generated: there is no portable deterministic generator, and
-- per-environment ids would defeat the point of a seed you can reason about.
-- Scheme: d... departments, e... employment types, c... catalogue templates, each padded to the
-- 12 characters an entity id requires. They are deliberately legible rather than random -- a seed
-- row is reference data you look up by hand, not something anyone should have to guess at.
--
-- Formatting rule: no semicolon inside any string literal. SeedDataTest replays this file by
-- splitting it on semicolons.

-- One placeholder department. Department names are company data the PRD never enumerates, and
-- inventing an org chart here would be fiction that later gets treated as fact.
insert into departments (id, "name")
select 'd00000000001', 'Unassigned'
where not exists (select 1 from departments where "name" = 'Unassigned');

insert into employment_types (id, "name")
select 'e00000000001', 'Regular'
where not exists (select 1 from employment_types where "name" = 'Regular');

insert into employment_types (id, "name")
select 'e00000000002', 'Probationary'
where not exists (select 1 from employment_types where "name" = 'Probationary');

insert into employment_types (id, "name")
select 'e00000000003', 'Project-based'
where not exists (select 1 from employment_types where "name" = 'Project-based');

insert into employment_types (id, "name")
select 'e00000000004', 'Part-time'
where not exists (select 1 from employment_types where "name" = 'Part-time');

-- PRD Appendix A, in order. sort_order follows the appendix.
--
-- Appendix A has three categories: Required, Conditional, and Optional. RequirementTemplate.isRequired
-- is a Boolean, so rows 10 and 11 -- both Conditional -- collapse to is_required = false, which
-- under PRD 6.5 also removes them from the progress denominator. That is probably wrong: a
-- conditional requirement that does apply to a given hire ought to be required for that hire.
-- Not fixable without a model change. Flagged against Q2.

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000001', 'Government-issued ID',
       'Upload a clear photo or scan of a valid government-issued ID, front and back.',
       true, true, cast(null as int), 60, true, 1
where not exists (select 1 from requirement_templates where "name" = 'Government-issued ID');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000002', 'Birth certificate',
       'Upload the PSA-issued birth certificate. All corners must be visible and the text legible.',
       true, false, cast(null as int), cast(null as int), true, 2
where not exists (select 1 from requirement_templates where "name" = 'Birth certificate');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000003', 'Tax identification number',
       'Upload the card or any official document showing your tax identification number.',
       true, false, cast(null as int), cast(null as int), true, 3
where not exists (select 1 from requirement_templates where "name" = 'Tax identification number');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000004', 'Social security number',
       'Upload the card or any official document showing your social security number.',
       true, false, cast(null as int), cast(null as int), true, 4
where not exists (select 1 from requirement_templates where "name" = 'Social security number');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000005', 'Health insurance number',
       'Upload the card or any official document showing your health insurance number.',
       true, false, cast(null as int), cast(null as int), true, 5
where not exists (select 1 from requirement_templates where "name" = 'Health insurance number');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000006', 'Housing fund number',
       'Upload the card or any official document showing your housing fund number.',
       true, false, cast(null as int), cast(null as int), true, 6
where not exists (select 1 from requirement_templates where "name" = 'Housing fund number');

-- Appendix A: "typically 1 year".
insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000007', 'Police / background clearance',
       'Upload your police or background clearance. It must be dated within the last year.',
       true, true, 12, 60, true, 7
where not exists (select 1 from requirement_templates where "name" = 'Police / background clearance');

-- Appendix A: "typically 6-12 months". 12 is seeded; narrow it once Q2 is answered.
insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000008', 'Pre-employment medical result',
       'Upload the full result from your pre-employment medical examination.',
       true, true, 12, 30, true, 8
where not exists (select 1 from requirement_templates where "name" = 'Pre-employment medical result');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000009', 'Transcript of records or diploma',
       'Upload your transcript of records or your diploma. Either is accepted.',
       true, false, cast(null as int), cast(null as int), true, 9
where not exists (select 1 from requirement_templates where "name" = 'Transcript of records or diploma');

-- Conditional in Appendix A. See the note above about the Boolean collapse.
insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000010', 'Certificate of employment (previous employer)',
       'Required only if you have previous employment. Upload the certificate from your last employer.',
       false, false, cast(null as int), cast(null as int), true, 10
where not exists (select 1 from requirement_templates where "name" = 'Certificate of employment (previous employer)');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000011', 'Tax form from previous employer',
       'Required only if you were employed within the current tax year.',
       false, false, cast(null as int), cast(null as int), true, 11
where not exists (select 1 from requirement_templates where "name" = 'Tax form from previous employer');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000012', 'ID photos',
       'Upload recent ID photographs against a plain background.',
       true, false, cast(null as int), cast(null as int), true, 12
where not exists (select 1 from requirement_templates where "name" = 'ID photos');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000013', 'Marriage certificate',
       'Optional. Upload only if married and claiming a dependent spouse.',
       false, false, cast(null as int), cast(null as int), true, 13
where not exists (select 1 from requirement_templates where "name" = 'Marriage certificate');

insert into requirement_templates (id, "name", instructions, is_required, expires, validity_months, renewal_lead_days, is_active, sort_order)
select 'c00000000014', 'Dependents'' birth certificates',
       'Optional. Upload only if claiming dependents.',
       false, false, cast(null as int), cast(null as int), true, 14
where not exists (select 1 from requirement_templates where "name" = 'Dependents'' birth certificates');

-- Every active template applies to every employment type. Q2 asks whether the checklist genuinely
-- differs by employment type and has no answer yet, so a uniform set is the honest placeholder.
-- The join table already exists to differentiate once it does.
insert into template_assignments (employment_type_id, requirement_template_id)
select et.id, rt.id
from employment_types et cross join requirement_templates rt
where rt.is_active = true
  and not exists (
    select 1 from template_assignments ta
    where ta.employment_type_id = et.id and ta.requirement_template_id = rt.id
  );
