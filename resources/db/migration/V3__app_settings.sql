-- V3 link and portal policy: the nine PRD 6.4 settings.
--
-- Every duration the portal enforces lives here, never as a constant in code (8.10) -- changing one
-- must not need a deployment. Bounds travel with the value rather than sitting in a validator
-- someone forgets to call, because 6.4 is explicit that a well-meant edit must not be able to turn
-- a token into a permanent credential.
--
-- Only link.absolute_expiry_days has a PRD-stated range (7 to 180). The other eight bounds are
-- chosen here, each with its reason, and are revisable as data rather than as a release.
--
-- Guarded with WHERE NOT EXISTS and containing NO UPDATE statement, so a manual replay adds what
-- is missing and never overwrites a value an admin has since changed. Flyway already guarantees
-- once-only execution; this is the second layer.
--
-- Two invariants here are cross-field and CANNOT be expressed in per-row min_value/max_value:
--   * link.warn_before_expiry_days must be less than link.absolute_expiry_days
--   * portal.pin_failures_before_suspend must be at least portal.pin_attempts_before_lockout
-- Clamping warn's maximum to 7 would make its default equal its maximum, which is silly. Both
-- belong in the AppSettingsRepository validator (ERT-310).
--
-- Formatting rule: no semicolon inside any string literal. SeedDataTest replays this file by
-- splitting it on semicolons.

-- Hard ceiling from issue. PRD 6.4 states the 7..180 range verbatim.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'link.absolute_expiry_days', '90', 'INT', '7', '180'
where not exists (select 1 from app_settings where "key" = 'link.absolute_expiry_days');

-- Minimum is 0, not 1: 6.4 requires 0 to disable the idle clock and rely on the ceiling alone.
-- Maximum matches the absolute ceiling, above which the idle clock could never bind.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'link.idle_expiry_days', '30', 'INT', '0', '180'
where not exists (select 1 from app_settings where "key" = 'link.idle_expiry_days');

-- Must move the window by at least a day to be worth anything; capped so one rejection cannot
-- outrun the absolute ceiling.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'link.extend_on_rejection_days', '30', 'INT', '1', '90'
where not exists (select 1 from app_settings where "key" = 'link.extend_on_rejection_days');

-- Must precede expiry by at least a day. See the cross-field note above.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'link.warn_before_expiry_days', '7', 'INT', '1', '30'
where not exists (select 1 from app_settings where "key" = 'link.warn_before_expiry_days');

-- A read-only confirmation page should not outlive a quarter.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'link.completed_grace_days', '14', 'INT', '1', '90'
where not exists (select 1 from app_settings where "key" = 'link.completed_grace_days');

-- Under 5 minutes the PIN re-prompt makes a phone upload unusable; 480 is one working day.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'portal.session_minutes', '45', 'INT', '5', '480'
where not exists (select 1 from app_settings where "key" = 'portal.session_minutes');

-- Under 3 a single typo locks the hire out; over 10 the lockout stops being a control.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'portal.pin_attempts_before_lockout', '5', 'INT', '3', '10'
where not exists (select 1 from app_settings where "key" = 'portal.pin_attempts_before_lockout');

-- Beyond 24 hours it is a suspension, which is a different setting with a different notification.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'portal.lockout_minutes', '15', 'INT', '1', '1440'
where not exists (select 1 from app_settings where "key" = 'portal.lockout_minutes');

-- Roughly ten lockout cycles at the default attempt threshold.
insert into app_settings ("key", "value", value_type, min_value, max_value)
select 'portal.pin_failures_before_suspend', '10', 'INT', '5', '50'
where not exists (select 1 from app_settings where "key" = 'portal.pin_failures_before_suspend');
