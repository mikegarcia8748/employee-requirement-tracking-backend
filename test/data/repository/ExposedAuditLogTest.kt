package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.data.RepositoryTestBase
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.codeOf
import com.pgsystem.employee.requirement.tracker.testdata.sourceOf
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.anAuditEntry
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

/**
 * The append-only audit trail, against real SQL (ERT-330).
 *
 * Two properties are the point of this table rather than incidental to it, and both are asserted
 * here rather than left as convention: nothing may mutate a row once written, and the metadata
 * column may never carry a credential (PRD 12).
 */
class ExposedAuditLogTest : RepositoryTestBase() {

    private val log by lazy { ExposedAuditLog(factory) }

    // ── Recording and reading ───────────────────────────────────────────────────────────────────

    @Test
    fun `audit log - an entry is recorded - is readable by entity id with actor and timestamp`() = runTest {
        val entry = anAuditEntry(
            action = AuditAction.HIRE_CREATED,
            metadata = mapOf("reason" to "Duplicate address overridden"),
        )

        log.record(entry)

        log.findFor(Fixtures.EMPLOYEE_ID) shouldContainExactly listOf(entry)
    }

    @Test
    fun `audit log - several entries for one entity - are returned in chronological order`() = runTest {
        val clock = FixedClock()
        // Recorded newest-first so a passing result cannot come from insertion order.
        val third = anAuditEntry(id = entityId("AUD000000003"), timestamp = clock.advance(20.minutes))
        val second = anAuditEntry(id = entityId("AUD000000002"), timestamp = FixedClock.DEFAULT.plusSeconds(600))
        val first = anAuditEntry(id = entityId("AUD000000001"), timestamp = FixedClock.DEFAULT)

        listOf(third, second, first).forEach { log.record(it) }

        log.findFor(Fixtures.EMPLOYEE_ID) shouldContainExactly listOf(first, second, third)
    }

    @Test
    fun `audit log - entries for another entity - are not returned`() = runTest {
        log.record(anAuditEntry(entityId = Fixtures.EMPLOYEE_ID))

        log.findFor(Fixtures.LINK_ID).shouldBeEmpty()
    }

    @Test
    fun `audit log - a person id and an entity id - address different rows`() = runTest {
        // entity_id is polymorphic and carries no foreign key, so the two widths share one column.
        // A lookup must not confuse them.
        val onEmployee = anAuditEntry(id = entityId("AUD000000001"), entityId = Fixtures.EMPLOYEE_ID)
        val onLink = anAuditEntry(
            id = entityId("AUD000000002"),
            entity = "upload_link",
            entityId = Fixtures.LINK_ID,
            action = AuditAction.LINK_ISSUED,
        )
        log.record(onEmployee)
        log.record(onLink)

        log.findFor(Fixtures.EMPLOYEE_ID) shouldContainExactly listOf(onEmployee)
        log.findFor(Fixtures.LINK_ID) shouldContainExactly listOf(onLink)
    }

    @Test
    fun `audit log - every AuditAction - round-trips through the action column`() = runTest {
        // Catches a column too narrow for the longest constant, which would otherwise surface for
        // the first time on whichever action a later ticket happens to record first.
        val entries = AuditAction.entries.mapIndexed { index, action ->
            anAuditEntry(id = auditId(index), action = action, timestamp = FixedClock.DEFAULT.plusSeconds(index.toLong()))
        }

        entries.forEach { log.record(it) }

        log.findFor(Fixtures.EMPLOYEE_ID).map { it.action } shouldContainExactly AuditAction.entries.toList()
    }

    @Test
    fun `audit log - the action sweep above - covers all twenty-six actions`() {
        // Without this the sweep passes just as happily against an enum someone emptied.
        // 25 since ERT-190 added the eight HR-account actions; 26 since ERT-434 added
        // INVITATION_DELIVERY_FAILED, which HAR-02 needs because a failed outbox INSERT writes no
        // row for the delivery-failure indicator to be derived from.
        AuditAction.entries.size shouldBe 26
    }

    @Test
    fun `audit log - an entry with no metadata - stores an empty json object rather than null`() = runTest {
        log.record(anAuditEntry(metadata = emptyMap()))

        strings("""select metadata from audit_logs""") shouldContainExactly listOf("{}")
        log.findFor(Fixtures.EMPLOYEE_ID).single().metadata shouldBe emptyMap()
    }

    @Test
    fun `audit log - two entries recorded under the same id - the second is refused by the primary key`() = runTest {
        log.record(anAuditEntry(id = Fixtures.AUDIT_ID))

        assertFailsWith<Exception> { log.record(anAuditEntry(id = Fixtures.AUDIT_ID, actor = "someone.else")) }
    }

    // ── Metadata ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `audit log - metadata carrying a pin-shaped value - is refused`() = runTest {
        // PRD 12: the PIN is never logged, and an audit log is still a log.
        assertFailsWith<IllegalArgumentException> {
            log.record(anAuditEntry(metadata = mapOf("pin" to "123456")))
        }

        countOf("audit_logs") shouldBe 0
    }

    @Test
    fun `audit log - a stored metadata value that is not json - fails rather than returning a broken entry`() = runTest {
        execute(
            """
            insert into audit_logs (id, actor, "action", entity, entity_id, "timestamp", metadata)
            values ('AUD000000001', 'hr', 'HIRE_CREATED', 'employee', 'EMP00001', timestamp '2026-01-15 09:00:00', 'not json')
            """.trimIndent()
        )

        assertFailsWith<Exception> { log.findFor(Fixtures.EMPLOYEE_ID) }
    }

    // ── Append-only ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `audit log - the adapter source - offers no update or delete path`() {
        // Read as text rather than by reflection: reflection sees public methods, and the risk here
        // is an internal helper added later for a well-meant "correction" an auditor must never see.
        val forbidden = listOf("update(", "deleteWhere", "deleteAll", "deleteIgnoreWhere", "upsert", "replace")

        forbidden.filter { it in adapterCode() }.shouldBeEmpty()
    }

    @Test
    fun `audit log - the no-mutation sweep above - is pointed at code rather than at comments`() {
        // Two ways the sweep could pass while enforcing nothing, and both have happened: a mistyped
        // path reads an empty string, and a comment-stripper that ate the whole file would too.
        // This also pins the stripping itself — the KDoc above `record` names `update(` in prose,
        // and that must not count as a mutation path.
        val code = adapterCode()

        code.contains("insert") shouldBe true
        code.contains("AuditLogs") shouldBe true
        adapterSource().contains("update(") shouldBe true
    }

    private fun adapterSource(): String = sourceOf(ADAPTER)

    private fun adapterCode(): String = codeOf(ADAPTER)

    private fun auditId(index: Int): EntityId =
        entityId("AUD" + (index + 1).toString().padStart(EntityId.LENGTH - 3, '0'))

    private companion object {
        const val ADAPTER = "src/data/repository/ExposedAuditLog.kt"
    }
}
