package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.data.db.table.AppSettings
import com.pgsystem.employee.requirement.tracker.data.mapper.LINK_POLICY_ENTITY
import com.pgsystem.employee.requirement.tracker.data.mapper.LINK_POLICY_ID
import com.pgsystem.employee.requirement.tracker.data.mapper.LinkPolicySetting
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.LinkPolicy
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.codeOf
import com.pgsystem.employee.requirement.tracker.testdata.anAuditEntry
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.err
import com.pgsystem.employee.requirement.tracker.testdata.ok
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The §6.4 policy against real SQL (ERT-310).
 *
 * `AppSettingMapperTest` owns the validation matrix; what is tested here is everything that only
 * SQL can answer — that the seeded table really does assemble a policy, that a refused write leaves
 * no rows behind, and that a settings change and its audit row land in **one** transaction.
 */
class ExposedAppSettingsRepositoryTest : RepositoryTestBase() {

    private val clock = FixedClock()
    private val ids = FixedEntityIdGenerator()
    private val settings by lazy { ExposedAppSettingsRepository(factory, clock, ids) }

    /**
     * The row [ACTOR] points at.
     *
     * JUnit 5 runs a superclass's `@BeforeEach` first, so the database is already migrated by the
     * time this runs. `runBlocking` rather than `runTest`: this is arrange, not a test body, and
     * there is no virtual time to advance.
     */
    @BeforeTest
    fun seedActingAdmin() {
        runBlocking {
            execute(
                """
                insert into users (id, email, full_name, password_hash, "role", is_active,
                                   password_change_required, created_at)
                values ('${ACTOR.value}', 'hr.admin@example.com', 'Marisol Tan', 'x', 'HR_ADMIN',
                        true, false, current_timestamp)
                """.trimIndent()
            )
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link policy read - a seeded database - returns the stored nine values`() = runTest {
        settings.linkPolicy().ok() shouldBe LinkPolicy(
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
    fun `link policy read - absolute expiry stored as 400 - is rejected stating the 7 to 180 range`() = runTest {
        // The case PRD 6.4 names: a well-meant edit that would turn a token into a near-permanent
        // credential. Refused rather than clamped, and the message carries the range so that an
        // admin can correct it rather than guess (8.10).
        setValue("link.absolute_expiry_days", "400")

        val detail = (settings.linkPolicy().err() as AppError.ValidationFailed).errors.single().detail

        detail shouldContain "7 to 180"
        detail shouldContain "link.absolute_expiry_days"
    }

    @Test
    fun `link policy read - a missing key - fails naming the key rather than defaulting`() = runTest {
        // LinkPolicy's Kotlin default for this key is 90, and the seeded value is also 90 -- so a
        // silent fallback would return the correct answer here and the wrong one the day an admin
        // changes it. Nothing but this test distinguishes the two.
        execute("""delete from app_settings where "key" = 'link.absolute_expiry_days'""")

        val error = settings.linkPolicy().err() as AppError.ValidationFailed

        error.errors.single().code shouldBe "setting.missing"
        error.errors.single().field shouldBe "link.absolute_expiry_days"
    }

    @Test
    fun `link policy read - a value that is not a whole number - fails naming the key rather than defaulting`() = runTest {
        setValue("portal.session_minutes", "forty-five")

        (settings.linkPolicy().err() as AppError.ValidationFailed).errors.single().let {
            it.code shouldBe "setting.unreadable"
            it.field shouldBe "portal.session_minutes"
        }
    }

    @Test
    fun `link policy read - idle expiry of zero - reports the idle clock disabled`() = runTest {
        // PRD 6.4 requires 0 to be a legal value meaning "rely on the absolute ceiling alone", which
        // is why that row's min_value is 0 rather than 1.
        setValue("link.idle_expiry_days", "0")

        val policy = settings.linkPolicy().ok()

        policy.idleExpiryDays shouldBe 0
        policy.idleClockEnabled shouldBe false
    }

    @Test
    fun `link policy read - the nine keys the mapper requires - all exist in the seeded table`() = runTest {
        // The mapper's key list and V3's rows are two lists that must agree. SeedDataTest checks the
        // same fact from the other direction, driven off the same enum.
        strings("""select "key" from app_settings""") shouldContainAll LinkPolicySetting.entries.map { it.key }
    }

    @Test
    fun `link policy read - an empty settings table - reports all nine keys rather than the first`() = runTest {
        // The shape of a deployment whose migrations did not run. Reporting one key per attempt
        // would make that a nine-round diagnosis.
        execute("delete from app_settings")

        val error = settings.linkPolicy().err() as AppError.ValidationFailed

        error.errors.map { it.field } shouldContainExactlyInAnyOrder LinkPolicySetting.entries.map { it.key }
    }

    @Test
    fun `link policy read - a successful read - writes no audit row`() = runTest {
        settings.linkPolicy().ok()

        countOf("audit_logs") shouldBe 0
    }

    @Test
    fun `link policy read - the adapter and mapper sources - never construct LinkPolicy with its defaults`() {
        // The only coverage this can have. LinkPolicy's defaults are identical to the seeded values,
        // so an accidental `LinkPolicy()` returns exactly what a correct read returns and no
        // behavioural test anywhere can tell them apart.
        // Matches a constructor call only: `.toLinkPolicy()` is a read and must not trip this.
        val construction = Regex("""(?<![A-Za-z0-9_.])LinkPolicy\(\)""")

        listOf(ADAPTER, MAPPER).filter { construction.containsMatchIn(codeOf(it)) }.shouldBeEmpty()
    }

    @Test
    fun `link policy read - the defaults sweep above - can tell a constructor call from a method call`() {
        // Both halves matter. The sweep must be pointed at files that mention LinkPolicy at all, and
        // it must not be the naive substring search that flagged `storedSettings().toLinkPolicy()`.
        val construction = Regex("""(?<![A-Za-z0-9_.])LinkPolicy\(\)""")

        listOf(ADAPTER, MAPPER).filterNot { "LinkPolicy" in codeOf(it) }.shouldBeEmpty()
        construction.containsMatchIn("val policy = LinkPolicy()") shouldBe true
        construction.containsMatchIn("storedSettings().toLinkPolicy()") shouldBe false
    }

    @Test
    fun `link policy read - the adapter source - spells the audit metadata suffixes nowhere`() {
        // SEC-38's fix turns on `AuditEntryMapper` exempting exactly the keys this adapter
        // generates, which only holds while both read one spelling of `.old` and `.new`. A second,
        // independently written copy here is one rename away from an exemption that matches
        // nothing -- and the symptom would be two settings silently unsaveable again.
        val code = codeOf(ADAPTER)

        listOf("\".old\"", "\".new\"").filter { it in code }.shouldBeEmpty()
    }

    @Test
    fun `link policy read - the suffix sweep above - is looking at an adapter that still derives them`() {
        // The anti-vacuity partner the file's other sweeps carry: the sweep passes just as happily
        // against an adapter that writes no audit metadata at all.
        codeOf(ADAPTER) shouldContain "auditMetadataKeys()"
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link policy update - a valid change - records old and new value in the audit log`() = runTest {
        settings.updateLinkPolicy(policyWith(absoluteExpiryDays = 45), ACTOR).ok()

        val metadata = auditLog().findFor(LINK_POLICY_ID).single().let {
            it.action shouldBe AuditAction.SETTING_CHANGED
            it.actor shouldBe ACTOR.value
            it.timestamp shouldBe FixedClock.DEFAULT
            it.entity shouldBe LINK_POLICY_ENTITY
            it.metadata
        }

        metadata shouldBe mapOf(
            "link.absolute_expiry_days.old" to "90",
            "link.absolute_expiry_days.new" to "45",
        )
    }

    @Test
    fun `link policy update - several keys at once - records old and new for each of them`() = runTest {
        // The realistic Phase 2 case: the settings form saves every field, and two of them moved.
        // A per-key audit row would turn one save into nine entries; one entry carries both changes.
        settings.updateLinkPolicy(policyWith(absoluteExpiryDays = 45, sessionMinutes = 120), ACTOR).ok()

        auditLog().findFor(LINK_POLICY_ID).single().metadata shouldBe mapOf(
            "link.absolute_expiry_days.old" to "90",
            "link.absolute_expiry_days.new" to "45",
            "portal.session_minutes.old" to "45",
            "portal.session_minutes.new" to "120",
        )
    }

    @Test
    fun `link policy update - a change followed by a read - returns the new policy`() = runTest {
        settings.updateLinkPolicy(policyWith(sessionMinutes = 120), ACTOR).ok()

        settings.linkPolicy().ok().sessionMinutes shouldBe 120
    }

    @Test
    fun `link policy update - the changed keys - are stamped with the actor and the clock`() = runTest {
        settings.updateLinkPolicy(policyWith(lockoutMinutes = 30), ACTOR).ok()

        strings("""select updated_by from app_settings where "key" = 'portal.lockout_minutes'""")
            .single() shouldBe ACTOR.value

        // Asserted as an Instant rather than as the raw column text. `updated_at` is `timestamp`
        // without a time zone, so the stored text is the JVM default zone's rendering -- reading it
        // as a string would pin the machine's locale into the test and fail in CI.
        updatedAt("portal.lockout_minutes") shouldBe FixedClock.DEFAULT
    }

    @Test
    fun `link policy update - a key whose value did not change - keeps its null updated_at`() = runTest {
        // Otherwise "when did this value last change" quietly becomes "when did someone last press
        // Save", and the audit metadata would carry eight no-op entries per change.
        settings.updateLinkPolicy(policyWith(lockoutMinutes = 30), ACTOR).ok()

        strings("""select count(*) from app_settings where updated_at is null""").single() shouldBe "8"
    }

    @Test
    fun `link policy update - a policy identical to the stored one - writes nothing and records no audit row`() = runTest {
        settings.updateLinkPolicy(settings.linkPolicy().ok(), ACTOR).ok()

        countOf("audit_logs") shouldBe 0
        strings("""select count(*) from app_settings where updated_at is null""").single() shouldBe "9"
    }

    @Test
    fun `link policy update - a value outside its stored bounds - is refused stating the permitted range and writes nothing`() = runTest {
        val error = settings.updateLinkPolicy(policyWith(absoluteExpiryDays = 400), ACTOR).err()

        (error as AppError.ValidationFailed).errors.single().detail shouldContain "7 to 180"
        storedValue("link.absolute_expiry_days") shouldBe "90"
        countOf("audit_logs") shouldBe 0
    }

    @Test
    fun `link policy update - a cross-field violation - is refused and writes nothing`() = runTest {
        // Neither value is out of its own range: warn 30 is within 1..30 and absolute 30 within
        // 7..180. Only the relationship between them is wrong.
        val error = settings.updateLinkPolicy(
            policyWith(absoluteExpiryDays = 30, warnBeforeExpiryDays = 30),
            ACTOR,
        ).err()

        (error as AppError.ValidationFailed).errors.single().code shouldBe "setting.warn_not_before_expiry"
        storedValue("link.absolute_expiry_days") shouldBe "90"
        countOf("audit_logs") shouldBe 0
    }

    @Test
    fun `link policy update - a key with no row - is refused rather than inserted`() = runTest {
        // An insert here would create a row with null bounds, permanently disarming the 6.4 check
        // for that key. A missing row is a migration problem and gets a migration.
        execute("""delete from app_settings where "key" = 'portal.lockout_minutes'""")

        val error = settings.updateLinkPolicy(policyWith(lockoutMinutes = 30), ACTOR).err()

        (error as AppError.ValidationFailed).errors.single().code shouldBe "setting.missing"
        countOf("app_settings") shouldBe 8
    }

    @Test
    fun `link policy update - a currently corrupt row being corrected - is allowed and records the raw old value`() = runTest {
        // updateLinkPolicy validates the incoming policy, never the stored values. Validating the
        // stored ones would make a corrupt row unfixable through the application, and repairing it
        // is exactly what the Phase 2 settings screen is for.
        setValue("link.absolute_expiry_days", "400")

        settings.updateLinkPolicy(policyWith(absoluteExpiryDays = 90), ACTOR).ok()

        storedValue("link.absolute_expiry_days") shouldBe "90"
        auditLog().findFor(LINK_POLICY_ID).single()
            .metadata["link.absolute_expiry_days.old"] shouldBe "400"
    }

    @Test
    fun `link policy update - the audit row - names the link policy singleton rather than a settings key`() = runTest {
        // audit_logs.entity_id is 12 characters and its kind is recovered from that length, so a
        // 25-character setting key could never go there. The link policy is one entity whose nine
        // fields happen to be stored as nine rows.
        settings.updateLinkPolicy(policyWith(completedGraceDays = 21), ACTOR).ok()

        strings("select entity_id from audit_logs").single() shouldBe LINK_POLICY_ID.value
        strings("select entity from audit_logs").single() shouldBe LINK_POLICY_ENTITY
    }

    @Test
    fun `link policy update - the audit insert fails on every attempt - the nine settings rows are unchanged`() = runTest {
        // The test that makes the one-transaction claim falsifiable: the audit row is written after
        // the settings rows, so if it went through the port in its own transaction the settings
        // change would survive its failure.
        //
        // The generator is scripted to repeat one id because **Exposed retries a failed
        // transaction** -- see the test below. A single collision is not enough; the retry would
        // draw a fresh id and succeed.
        val colliding = ExposedAppSettingsRepository(
            factory,
            clock,
            FixedEntityIdGenerator(*Array(8) { TAKEN_AUDIT_ID }),
        )
        seedAuditRow(TAKEN_AUDIT_ID)

        runCatching { colliding.updateLinkPolicy(policyWith(absoluteExpiryDays = 45), ACTOR) }
            .isFailure shouldBe true

        storedValue("link.absolute_expiry_days") shouldBe "90"
        countOf("audit_logs") shouldBe 1
    }

    @Test
    fun `link policy update - the first audit id collides - Exposed retries the whole block rather than committing half of it`() = runTest {
        // Recorded because it surprised this session and will surprise the next one: Exposed retries
        // a transaction that throws, re-running the entire block. Anything non-transactional inside
        // it runs again -- here the id generator, which is why the retry draws ENT000000002.
        //
        // It is also the sharpest evidence for the rollback. The retry re-reads the stored value and
        // records old="90". Had the first attempt's write survived, the retry would have read 45,
        // found nothing changed, and written no audit row at all.
        seedAuditRow(TAKEN_AUDIT_ID)

        settings.updateLinkPolicy(policyWith(absoluteExpiryDays = 45), ACTOR).ok()

        strings("select id from audit_logs order by id") shouldBe listOf(TAKEN_AUDIT_ID, "ENT000000002")
        auditLog().findFor(LINK_POLICY_ID)
            .last().metadata["link.absolute_expiry_days.old"] shouldBe "90"
        storedValue("link.absolute_expiry_days") shouldBe "45"
    }

    @Test
    fun `transaction nesting - a factory transaction inside another - joins it rather than committing independently`() = runTest {
        // Why this is here rather than in a framework's own test suite: it is the fact that decides
        // whether `updateLinkPolicy` may write its audit row through the AuditLog port instead of on
        // the transaction it already holds. It joins today, so both designs are atomic and no test
        // can tell them apart -- the adapter writes on its own transaction so that atomicity does
        // not *depend* on this, and this test exists so that an Exposed upgrade changing it is
        // visible rather than silent.
        runCatching {
            transaction {
                AppSettings.update({ AppSettings.key eq "portal.lockout_minutes" }) { it[value] = "99" }
                ExposedAuditLog(factory).record(anAuditEntry(id = entityId(TAKEN_AUDIT_ID)))
                error("the outer transaction fails after the inner one returned")
            }
        }.isFailure shouldBe true

        countOf("audit_logs") shouldBe 0
        storedValue("portal.lockout_minutes") shouldBe "15"
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun auditLog() = ExposedAuditLog(factory)

    private suspend fun seedAuditRow(id: String) = execute(
        """
        insert into audit_logs (id, actor, "action", entity, entity_id, "timestamp", metadata)
        values ('$id', 'hr', 'SETTING_CHANGED', '$LINK_POLICY_ENTITY', '${LINK_POLICY_ID.value}',
                timestamp '2026-01-01 00:00:00', '{}')
        """.trimIndent()
    )

    private suspend fun updatedAt(key: String): java.time.Instant? = transaction {
        AppSettings.selectAll().where { AppSettings.key eq key }.single()[AppSettings.updatedAt]
    }

    /** The seeded policy with one field changed, so a test states only what it is about. */
    private fun policyWith(
        absoluteExpiryDays: Int = 90,
        idleExpiryDays: Int = 30,
        extendOnRejectionDays: Int = 30,
        warnBeforeExpiryDays: Int = 7,
        completedGraceDays: Int = 14,
        sessionMinutes: Int = 45,
        pinAttemptsBeforeLockout: Int = 5,
        lockoutMinutes: Int = 15,
        pinFailuresBeforeSuspend: Int = 10,
    ) = LinkPolicy(
        absoluteExpiryDays = absoluteExpiryDays,
        idleExpiryDays = idleExpiryDays,
        extendOnRejectionDays = extendOnRejectionDays,
        warnBeforeExpiryDays = warnBeforeExpiryDays,
        completedGraceDays = completedGraceDays,
        sessionMinutes = sessionMinutes,
        pinAttemptsBeforeLockout = pinAttemptsBeforeLockout,
        lockoutMinutes = lockoutMinutes,
        pinFailuresBeforeSuspend = pinFailuresBeforeSuspend,
    )

    private suspend fun setValue(key: String, value: String) =
        execute("""update app_settings set "value" = '$value' where "key" = '$key'""")

    private suspend fun storedValue(key: String): String =
        strings("""select "value" from app_settings where "key" = '$key'""").single()

    private companion object {
        /**
         * The acting admin, as a [PersonId] since ERT-190.
         *
         * `app_settings.updated_by` references `users(id)`, so this id must have a row behind it —
         * [seedActingAdmin] writes one. That constraint is the point of the change: "who raised the
         * expiry ceiling" is a question about a person, and the database now refuses an answer that
         * names nobody.
         */
        val ACTOR: PersonId = personId("HRA00001")
        const val TAKEN_AUDIT_ID = "ENT000000001"
        const val ADAPTER = "src/data/repository/ExposedAppSettingsRepository.kt"
        const val MAPPER = "src/data/mapper/AppSettingMapper.kt"
    }
}
