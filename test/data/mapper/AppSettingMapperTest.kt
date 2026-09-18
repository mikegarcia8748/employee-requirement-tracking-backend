package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.testdata.err
import com.pgsystem.employee.requirement.tracker.testdata.errCode
import com.pgsystem.employee.requirement.tracker.testdata.ok
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * The §6.4 validation matrix, away from SQL (ERT-310).
 *
 * Pure, because the rules being checked are about *values* and a reader of the repository test
 * would reasonably assume the database was enforcing them. It is not: `app_settings` stores
 * strings with a declared `value_type` and nullable bounds, so every rule here is one this mapper
 * applies or nobody does.
 */
class AppSettingMapperTest {

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link policy read - the seeded rows - assemble the nine values`() {
        seeded().toLinkPolicy().ok() shouldBe LinkPolicy(
            absoluteExpiryDays = 90,
            idleExpiryDays = 30,
            extendOnRejectionDays = 30,
            warnBeforeExpiryDays = 7,
            completedGraceDays = 14,
            sessionMinutes = 45,
            pinAttemptsBeforeLockout = 5,
            lockoutMinutes = 15,
            pinFailuresBeforeSuspend = 10,
        )
    }

    @Test
    fun `link policy validation - a row declaring no bounds - is refused rather than treated as unbounded`() {
        // The tempting reading of a nullable min_value is "unbounded". That is the same silent
        // fallback this ticket exists to refuse: V3's header says bounds travel with the value
        // precisely so that nobody can forget to enforce them.
        val rows = seeded().replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(min = null, max = null) }

        rows.toLinkPolicy().validationCodes() shouldContainExactly listOf("setting.bounds_missing")
    }

    @Test
    fun `link policy validation - a row declaring a value type other than INT - is refused`() {
        val rows = seeded().replacing(LinkPolicySetting.SESSION_MINUTES) { it.copy(valueType = "STRING") }

        rows.toLinkPolicy().validationCodes() shouldContainExactly listOf("setting.type_unexpected")
    }

    @Test
    fun `link policy validation - a row declaring unreadable bounds - is refused`() {
        val rows = seeded().replacing(LinkPolicySetting.LOCKOUT_MINUTES) { it.copy(min = "seven") }

        rows.toLinkPolicy().validationCodes() shouldContainExactly listOf("setting.bounds_unreadable")
    }

    @Test
    fun `link policy validation - two corrupt rows - reports both rather than the first`() {
        // Fail-fast would make the iteration order of the nine keys load-bearing, and an operator
        // repairing a hand-edited table would learn about one broken key per attempt.
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "400") }
            .minus(LinkPolicySetting.LOCKOUT_MINUTES.key)

        rows.toLinkPolicy().validationCodes() shouldContainExactlyInAnyOrder
            listOf("setting.out_of_range", "setting.missing")
    }

    @Test
    fun `link policy validation - a single corrupt row - still reports through ValidationFailed`() {
        // Deliberately not collapsed to a bare Validation. AppError's own KDoc says both render
        // through one wire shape so a client never branches on how many failed; if the consumer must
        // not branch on the count, the producer must not either.
        val rows = seeded().replacing(LinkPolicySetting.IDLE_EXPIRY_DAYS) { it.copy(value = "thirty") }

        val error = rows.toLinkPolicy().err()

        (error is AppError.ValidationFailed) shouldBe true
        (error as AppError.ValidationFailed).errors.single().code shouldBe "setting.unreadable"
    }

    @Test
    fun `link policy validation - an unknown key in the table - is ignored rather than refused`() {
        // The table is shared. A Phase 2 setting that is not part of the link policy must not make
        // the link policy unreadable.
        val rows = seeded() + ("notifications.digest_hour" to
            StoredSetting("notifications.digest_hour", "8", "INT", "0", "23"))

        rows.toLinkPolicy().ok().absoluteExpiryDays shouldBe 90
    }

    @Test
    fun `link policy validation - a missing key alongside a cross-field violation - reports the missing key alone`() {
        // Cross-field rules are stage two and run only when every value parsed. Complaining that
        // warn is too late, about a save that cannot happen anyway, is noise.
        val rows = seeded()
            .replacing(LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS) { it.copy(value = "30", max = "180") }
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "30") }
            .minus(LinkPolicySetting.LOCKOUT_MINUTES.key)

        rows.toLinkPolicy().validationCodes() shouldContainExactly listOf("setting.missing")
    }

    // ── The cross-field invariants V3 assigns to this adapter ───────────────────────────────────

    @Test
    fun `link policy validation - warn at or after absolute expiry - is refused as a cross-field violation`() {
        // Neither value is out of its own range, which is why per-row min and max cannot catch this.
        val rows = seeded()
            .replacing(LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS) { it.copy(value = "30") }
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "30") }

        val error = rows.toLinkPolicy().err() as AppError.ValidationFailed

        error.errors.single().code shouldBe "setting.warn_not_before_expiry"
        error.errors.single().field shouldBe "link.warn_before_expiry_days"
    }

    @Test
    fun `link policy validation - suspend threshold below the lockout threshold - is refused as a cross-field violation`() {
        val rows = seeded().replacing(LinkPolicySetting.PIN_FAILURES_BEFORE_SUSPEND) { it.copy(value = "5", min = "1") }
            .replacing(LinkPolicySetting.PIN_ATTEMPTS_BEFORE_LOCKOUT) { it.copy(value = "10") }

        rows.toLinkPolicy().validationCodes() shouldContainExactly listOf("setting.suspend_below_lockout")
    }

    @Test
    fun `link policy validation - warn exactly one day before expiry - is accepted`() {
        // The vacuity guard for the cross-field rules: a check written as `<=` would refuse this.
        val rows = seeded()
            .replacing(LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS) { it.copy(value = "29", max = "180") }
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "30") }

        rows.toLinkPolicy().ok().warnBeforeExpiryDays shouldBe 29
    }

    @Test
    fun `link policy validation - suspend equal to the lockout threshold - is accepted`() {
        val rows = seeded()
            .replacing(LinkPolicySetting.PIN_FAILURES_BEFORE_SUSPEND) { it.copy(value = "5", min = "1") }

        rows.toLinkPolicy().ok().pinFailuresBeforeSuspend shouldBe 5
    }

    // ── The third cross-field family: nothing may outrun the absolute ceiling (SEC-39) ─────────

    // V3's comment on `link.extend_on_rejection_days` claimed its 1..90 cap meant "one rejection
    // cannot outrun the absolute ceiling". A static bound cannot express that against a ceiling
    // settable down to 7: absolute 7 with extend 90 pushes a live link to 97 days, 13x the ceiling
    // the same file calls hard. §6.4's stated fear is that a well-meant edit turns a token into a
    // permanent credential, and this was the one lever with no cross-field rule.
    //
    // Each fixture below moves the ceiling to 31 and the dependent to 32 rather than reaching for
    // 7 and 90: at absolute 7 all three of these rules fire at once and `single()` would pass for
    // the wrong reason.

    @Test
    fun `link policy validation - extend on rejection beyond the absolute ceiling - is refused as a cross-field violation`() {
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "31") }
            .replacing(LinkPolicySetting.EXTEND_ON_REJECTION_DAYS) { it.copy(value = "32") }

        val error = rows.toLinkPolicy().err() as AppError.ValidationFailed

        error.errors.single().code shouldBe "setting.extend_beyond_absolute"
        error.errors.single().field shouldBe "link.extend_on_rejection_days"
    }

    @Test
    fun `link policy validation - extend on rejection equal to the absolute ceiling - is accepted`() {
        // The vacuity guard. Written as `<` this refuses a legal policy and nothing else notices.
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "30") }
            .replacing(LinkPolicySetting.EXTEND_ON_REJECTION_DAYS) { it.copy(value = "30") }

        rows.toLinkPolicy().ok().extendOnRejectionDays shouldBe 30
    }

    @Test
    fun `link policy validation - completed grace beyond the absolute ceiling - is refused as a cross-field violation`() {
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "31") }
            .replacing(LinkPolicySetting.COMPLETED_GRACE_DAYS) { it.copy(value = "32") }

        val error = rows.toLinkPolicy().err() as AppError.ValidationFailed

        error.errors.single().code shouldBe "setting.grace_beyond_absolute"
        error.errors.single().field shouldBe "link.completed_grace_days"
    }

    @Test
    fun `link policy validation - completed grace equal to the absolute ceiling - is accepted`() {
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "30") }
            .replacing(LinkPolicySetting.COMPLETED_GRACE_DAYS) { it.copy(value = "30") }

        rows.toLinkPolicy().ok().completedGraceDays shouldBe 30
    }

    @Test
    fun `link policy validation - idle expiry beyond the absolute ceiling - is refused as a cross-field violation`() {
        // V3 already reasons about this bound -- "above which the idle clock could never bind" --
        // but the reasoning assumes the ceiling sits at its own maximum of 180.
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "29") }
            .replacing(LinkPolicySetting.EXTEND_ON_REJECTION_DAYS) { it.copy(value = "29") }
            .replacing(LinkPolicySetting.COMPLETED_GRACE_DAYS) { it.copy(value = "14") }

        val error = rows.toLinkPolicy().err() as AppError.ValidationFailed

        error.errors.single().code shouldBe "setting.idle_beyond_absolute"
        error.errors.single().field shouldBe "link.idle_expiry_days"
    }

    @Test
    fun `link policy validation - idle expiry equal to the absolute ceiling - is accepted`() {
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "30") }

        rows.toLinkPolicy().ok().idleExpiryDays shouldBe 30
    }

    @Test
    fun `link policy validation - an idle clock disabled under the shortest ceiling - is accepted`() {
        // §6.4 requires 0 to disable the idle clock, so the rule must not refuse it at any ceiling.
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "7") }
            .replacing(LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS) { it.copy(value = "1") }
            .replacing(LinkPolicySetting.IDLE_EXPIRY_DAYS) { it.copy(value = "0") }
            .replacing(LinkPolicySetting.EXTEND_ON_REJECTION_DAYS) { it.copy(value = "1") }
            .replacing(LinkPolicySetting.COMPLETED_GRACE_DAYS) { it.copy(value = "1") }

        rows.toLinkPolicy().ok().idleClockEnabled shouldBe false
    }

    @Test
    fun `link policy write - an incoming extend beyond the ceiling - is refused before anything is written`() {
        // The rules run on BOTH paths. `crossFieldErrors()` sits in `validateAgainst` as well as
        // `toLinkPolicy`, so the Phase 2 screen cannot save a combination the reader would refuse.
        val policy = LinkPolicy(absoluteExpiryDays = 31, extendOnRejectionDays = 32)

        policy.validateAgainst(seeded()).validationCodes() shouldContainExactly
            listOf("setting.extend_beyond_absolute")
    }

    // ── The bounds message ──────────────────────────────────────────────────────────────────────

    @Test
    fun `link policy validation - a value above its stored maximum - states the permitted range`() {
        // PRD 8.10: an admin must be able to correct the value rather than guess at the range.
        val rows = seeded().replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "400") }

        val detail = (rows.toLinkPolicy().err() as AppError.ValidationFailed).errors.single().detail

        detail shouldContain "7 to 180"
        detail shouldContain "link.absolute_expiry_days"
    }

    @Test
    fun `link policy validation - a value exactly at its stored maximum - is accepted`() {
        // Bounds are inclusive. Written as `<` instead of `<=` this refuses the legal value 180 and
        // nothing else in the suite notices, because every other fixture sits mid-range.
        val rows = seeded().replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "180") }

        rows.toLinkPolicy().ok().absoluteExpiryDays shouldBe 180
    }

    @Test
    fun `link policy validation - a value exactly at its stored minimum - is accepted`() {
        // Four other values have to move with it. At the shortest legal link life the seeded 7-day
        // warning would fall on the expiry itself, and since SEC-39 the idle clock, the rejection
        // extension and the completed grace all have to fit under the ceiling too — so this is the
        // one place where every §6.4 cross-field rule genuinely constrains the others at once.
        //
        // Each is set to its own stored MINIMUM rather than to 7. Setting them to 7 would make this
        // fixture a second equality-boundary test for the three SEC-39 rules, duplicating their own
        // guards and giving this test two reasons to fail. Its subject is the minimum of
        // `absolute_expiry_days` and nothing else.
        val rows = seeded()
            .replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "7") }
            .replacing(LinkPolicySetting.WARN_BEFORE_EXPIRY_DAYS) { it.copy(value = "1") }
            .replacing(LinkPolicySetting.IDLE_EXPIRY_DAYS) { it.copy(value = "0") }
            .replacing(LinkPolicySetting.EXTEND_ON_REJECTION_DAYS) { it.copy(value = "1") }
            .replacing(LinkPolicySetting.COMPLETED_GRACE_DAYS) { it.copy(value = "1") }

        rows.toLinkPolicy().ok().absoluteExpiryDays shouldBe 7
    }

    // ── The write path ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `link policy write - two incoming values out of bounds - reports both rather than the first`() {
        val policy = LinkPolicy(absoluteExpiryDays = 400, sessionMinutes = 1)

        val error = policy.validateAgainst(seeded()).err() as AppError.ValidationFailed

        error.errors.map { it.field } shouldContainExactlyInAnyOrder
            listOf("link.absolute_expiry_days", "portal.session_minutes")
    }

    @Test
    fun `link policy write - a stored value that is corrupt - does not block a valid incoming policy`() {
        // The stored value is deliberately not validated on the write path: doing so would make a
        // corrupt row unfixable through the application, and repairing one is what the Phase 2
        // settings screen exists for. The stored *metadata* is still checked.
        val rows = seeded().replacing(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS) { it.copy(value = "400") }

        LinkPolicy().validateAgainst(rows) shouldBe DomainResult.Ok(Unit)
    }

    @Test
    fun `link policy write - a row whose declared bounds are unreadable - blocks the write`() {
        // The counterpart to the test above: metadata is not the same as the value. Bounds that
        // cannot be parsed mean the incoming value cannot be judged at all.
        val rows = seeded().replacing(LinkPolicySetting.SESSION_MINUTES) { it.copy(max = "lots") }

        LinkPolicy().validateAgainst(rows).validationCodes() shouldContainExactly
            listOf("setting.bounds_unreadable")
    }

    // ── One list of keys, not two ───────────────────────────────────────────────────────────────

    @Test
    fun `link policy keys - every LinkPolicy field - is named by a settings key`() {
        // A tenth field added to LinkPolicy with no settings key would otherwise take its value from
        // a Kotlin default forever, and because the defaults equal the seeded values, silently.
        val fields = LinkPolicy::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.name }

        fields shouldContainExactlyInAnyOrder LinkPolicySetting.entries.map { it.field }
    }

    @Test
    fun `link policy keys - the field sweep above - inspects nine fields`() {
        LinkPolicySetting.entries.size shouldBe 9
        LinkPolicy::class.java.declaredFields.count { it.type == Int::class.javaPrimitiveType } shouldBe 9
    }

    @Test
    fun `link policy keys - the absolute expiry bounds this mapper enforces - match the range the domain publishes`() {
        // The one range the PRD states verbatim. It lives in two places -- LinkPolicy's companion
        // and V3's min/max -- and nothing else would notice them drifting apart.
        val row = seeded().getValue(LinkPolicySetting.ABSOLUTE_EXPIRY_DAYS.key)

        (row.min!!.toInt()..row.max!!.toInt()) shouldBe LinkPolicy.ABSOLUTE_EXPIRY_DAYS_RANGE
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    /** The nine rows exactly as `V3__app_settings.sql` seeds them. */
    private fun seeded(): Map<String, StoredSetting> = listOf(
        StoredSetting("link.absolute_expiry_days", "90", "INT", "7", "180"),
        StoredSetting("link.idle_expiry_days", "30", "INT", "0", "180"),
        StoredSetting("link.extend_on_rejection_days", "30", "INT", "1", "90"),
        StoredSetting("link.warn_before_expiry_days", "7", "INT", "1", "30"),
        StoredSetting("link.completed_grace_days", "14", "INT", "1", "90"),
        StoredSetting("portal.session_minutes", "45", "INT", "5", "480"),
        StoredSetting("portal.pin_attempts_before_lockout", "5", "INT", "3", "10"),
        StoredSetting("portal.lockout_minutes", "15", "INT", "1", "1440"),
        StoredSetting("portal.pin_failures_before_suspend", "10", "INT", "5", "50"),
    ).associateBy { it.key }

    private fun Map<String, StoredSetting>.replacing(
        setting: LinkPolicySetting,
        change: (StoredSetting) -> StoredSetting,
    ): Map<String, StoredSetting> = this + (setting.key to change(getValue(setting.key)))

    private fun com.pgsystem.employee.requirement.tracker.core.error.DomainResult<*>.validationCodes(): List<String> =
        when (val error = err()) {
            is AppError.ValidationFailed -> error.errors.map { it.code }
            else -> listOf(errCode())
        }
}
