package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.domain.model.HrRole
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.anAuditEntry
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * HR accounts against real SQL (ERT-190).
 *
 * What is tested here is everything only the database can answer: that a row round-trips through the
 * mapper, that case-insensitive uniqueness actually bites, and that the four actor foreign keys
 * refuse to let an acting user be deleted. The use-case tests own the rules.
 *
 * **H2 in PostgreSQL mode is not PostgreSQL** — `RepositoryTestBase` says why at length. The
 * constraint assertions below are the ones most worth repeating on a real Postgres before launch,
 * since constraint behaviour is precisely where the two engines diverge.
 */
class ExposedHrUserRepositoryTest : RepositoryTestBase() {

    private val users by lazy { ExposedHrUserRepository(factory) }

    // ── Round trip ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user persistence - a user - round-trips including role and active flag`() = runTest {
        val admin = anHrAdmin(id = personId("HRA00001"))

        users.save(admin)

        users.findById(admin.id) shouldBe admin
    }

    @Test
    fun `hr user persistence - a saved user - is found by email`() = runTest {
        val user = anHrUser(email = anEmail("ana.reyes@example.com"))

        users.save(user)

        users.findByEmail(user.email) shouldBe user
    }

    @Test
    fun `hr user persistence - an address in another case - resolves to the same account`() = runTest {
        // EmailAddress.of lower-cases on construction, so this cannot fail through the application's
        // own path. It is asserted because findByEmail compares lower(email) in SQL, and a body that
        // dropped the lowerCase() would still pass every other test here.
        val user = anHrUser(email = anEmail("ana.reyes@example.com"))
        users.save(user)

        users.findByEmail(anEmail("ANA.REYES@EXAMPLE.COM")) shouldBe user
    }

    @Test
    fun `hr user persistence - a second save of the same id - updates rather than inserting`() = runTest {
        // save() is read-then-insert-or-update rather than an upsert, because `on conflict` is
        // banned by the portability sweep and spelled differently by the two engines.
        val user = anHrUser(passwordHash = "first-hash")
        users.save(user)

        users.save(user.copy(passwordHash = "second-hash", passwordChangeRequired = true))

        users.countAll() shouldBe 1L
        users.findById(user.id)!!.passwordHash shouldBe "second-hash"
    }

    @Test
    fun `hr user persistence - an update - writes every mutable column`() = runTest {
        // The write side is shared by insert and update through one `writeTo`. Two hand-written
        // column lists drift, and the realistic failure is a field wired into the insert only -- so
        // a password change would silently keep the old hash.
        val user = anHrUser()
        users.save(user)

        val changed = user.copy(
            email = anEmail("renamed@example.com"),
            fullName = "Renamed Person",
            passwordHash = "changed-hash",
            role = HrRole.HR_ADMIN,
            isActive = false,
            passwordChangeRequired = true,
        )
        users.save(changed)

        users.findById(user.id) shouldBe changed
    }

    @Test
    fun `hr user persistence - the account list - is in email order and includes deactivated accounts`() =
        runTest {
            // Deactivated ones included: an admin needs to see who has been switched off, and there
            // is no delete for them to have used instead.
            users.save(anHrUser(id = personId("HRU00002"), email = anEmail("zara@example.com")))
            users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com"), isActive = false))

            users.findAll().map { it.email.value } shouldBe listOf("ana@example.com", "zara@example.com")
        }

    @Test
    fun `hr user persistence - an empty table - counts zero`() = runTest {
        // Bootstrap turns on exactly this answer.
        users.countAll() shouldBe 0L
    }

    // ── Uniqueness ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user persistence - the same email in another case - is rejected`() = runTest {
        users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

        // Written as raw SQL on purpose. EmailAddress.of lower-cases, so the application CANNOT
        // produce this row -- and the constraint exists for the writers it does not control: a
        // migration, an import, or a person at a psql prompt. Going through save() would only
        // re-test the value class.
        assertFailsWith<Exception> { insertRawUser(id = "HRU00002", email = "ANA@example.com") }
    }

    @Test
    fun `hr user persistence - the same email in the same case - is rejected`() = runTest {
        users.save(anHrUser(id = personId("HRU00001"), email = anEmail("ana@example.com")))

        assertFailsWith<Exception> { insertRawUser(id = "HRU00002", email = "ana@example.com") }
    }

    @Test
    fun `hr user persistence - two different addresses - are both accepted`() = runTest {
        // Non-vacuity: without this, a broken insert helper would make both rejection tests pass.
        insertRawUser(id = "HRU00001", email = "ana@example.com")
        insertRawUser(id = "HRU00002", email = "zara@example.com")

        users.countAll() shouldBe 2L
    }

    // ── The actor foreign keys ──────────────────────────────────────────────────────────────────

    @Test
    fun `actor reference - deleting a user who created a hire - is refused by the database`() = runTest {
        // `on delete restrict` throughout: an audit trail naming a row that no longer exists is not
        // a trail. This is also why accounts are deactivated rather than deleted.
        val creator = anHrUser(id = personId("HRU00001"))
        users.save(creator)
        givenEmployeeCreatedBy(creator)

        assertFailsWith<Exception> { execute("delete from users where id = '${creator.id.value}'") }

        users.findById(creator.id) shouldNotBe null
    }

    @Test
    fun `actor reference - deleting a user who created nothing - is allowed`() = runTest {
        // Non-vacuity again: the refusal above must come from the foreign key rather than from users
        // being undeletable in general.
        val unused = anHrUser(id = personId("HRU00009"))
        users.save(unused)

        execute("delete from users where id = '${unused.id.value}'")

        users.findById(unused.id) shouldBe null
    }

    @Test
    fun `actor reference - a hire naming a user who does not exist - is refused by the database`() =
        runTest {
            // The other direction, and the one that makes created_by worth narrowing at all: before
            // ERT-190 the column held whatever someone typed.
            assertFailsWith<Exception> {
                givenEmployeeCreatedBy(anHrUser(id = personId("NOSUCH01")))
            }
        }

    @Test
    fun `audit trail - a row written by a user - round-trips the acting user id`() = runTest {
        // THE ROW BELOW IS THE ONE THAT MATTERS, AND IT WAS WRITTEN AFTER THE BUG IT CATCHES.
        // `audit_logs.actor_user_id` is nullable, so an insert that omits it writes NULL rather than
        // failing -- and for a while ExposedAuditLog omitted it on the way in and the mapper dropped
        // it on the way out. Every use case set it, every FAKE stored it, and every row in the real
        // database was null: §8.13's exception report would have joined on a column nothing
        // populates. The null-case test below passed throughout, because everything was null.
        //
        // So this asserts the NON-null direction, through the real adapter, end to end.
        val actor = anHrUser(id = personId("HRU00001"))
        users.save(actor)

        val log = ExposedAuditLog(factory)
        val entry = anAuditEntry(
            actor = actor.email.value,
            actorUserId = actor.id,
            entityId = actor.id,
            entity = HrUser.AUDIT_ENTITY,
        )

        log.record(entry)

        log.findFor(actor.id).single() shouldBe entry
    }

    @Test
    fun `audit trail - a row written by no user - stores a null actor user id`() = runTest {
        // The fifth actor column stays free text with a NULLABLE reference beside it, because the
        // trail must record the seed, the expiry sweep and future import jobs. An append-only trail
        // that can refuse a write because it cannot name a user is worse than one carrying a string.
        execute(
            """
            insert into audit_logs (id, actor, "action", entity, entity_id, "timestamp", metadata)
            values ('AUD000000001', 'system.sweep', 'LINK_REVOKED', 'upload_link', 'LNK000000001',
                    current_timestamp, '{}')
            """.trimIndent()
        )

        strings("select count(*) from audit_logs where actor_user_id is null").single() shouldBe "1"
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Bypasses [HrUser] and [com.pgsystem.employee.requirement.tracker.core.value.EmailAddress]. */
    private suspend fun insertRawUser(id: String, email: String) = execute(
        """
        insert into users (id, email, full_name, password_hash, "role", is_active,
                           password_change_required, created_at)
        values ('$id', '$email', 'Raw Insert', 'x', 'HR_OFFICER', true, false, current_timestamp)
        """.trimIndent()
    )

    /** A hire pointing at [creator], using the reference data V2 seeds. */
    private suspend fun givenEmployeeCreatedBy(creator: HrUser) {
        val departmentId = strings("select id from departments order by id limit 1").single()
        val employmentTypeId = strings("select id from employment_types order by id limit 1").single()

        execute(
            """
            insert into employees (id, first_name, last_name, department_id, "position",
                                   employment_type_id, email, packet_status, submitted_by_hr,
                                   anomaly_flags, created_at, created_by)
            values ('EMP00001', 'Jose', 'Dela Cruz', '$departmentId', 'Store Associate',
                    '$employmentTypeId', 'jose@example.com', 'DRAFT_COLLECTING', false, '',
                    '${FixedClock.DEFAULT}', '${creator.id.value}')
            """.trimIndent()
        )
    }
}
