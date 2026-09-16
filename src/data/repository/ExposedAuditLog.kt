package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.AuditLogs
import com.pgsystem.employee.requirement.tracker.data.mapper.toAuditEntry
import com.pgsystem.employee.requirement.tracker.data.mapper.toMetadataJson
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import com.pgsystem.employee.requirement.tracker.domain.port.AuditLog
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The append-only audit trail (ERT-330, PRD 12).
 *
 * **There is no update or delete path, and that absence is the feature.** A trail that can be
 * corrected is not a trail, so the class offers `record` and `findFor` and nothing else —
 * `ExposedAuditLogTest` reads this file as text and fails the build on `update(`, `deleteWhere`,
 * `upsert` or `replace` appearing anywhere in it. Reflection would not do: the realistic addition is
 * a private helper written for a well-meant correction.
 *
 * The insert itself lives in [insertAuditEntry] rather than inline, because
 * `ExposedAppSettingsRepository` needs to write a settings change and its audit row in **one**
 * transaction — see that class for why calling through the port instead would have rested on
 * undocumented framework behaviour.
 */
class ExposedAuditLog(private val factory: DatabaseFactory) : AuditLog {

    override suspend fun record(entry: AuditEntry) {
        factory.transaction { insertAuditEntry(entry) }
    }

    override suspend fun findFor(entityId: Identifier): List<AuditEntry> = factory.transaction {
        AuditLogs.selectAll()
            .where { AuditLogs.entityId eq entityId.value }
            // Chronological, then by id. The id is a tiebreaker for determinism only — production
            // ids are random, so id order is not time order and must never be read as though it
            // were. Two entries sharing an instant are genuinely unordered.
            .orderBy(AuditLogs.timestamp to SortOrder.ASC, AuditLogs.id to SortOrder.ASC)
            .map { it.toAuditEntry() }
    }
}

/**
 * The one place an audit row is written.
 *
 * Takes a [JdbcTransaction] receiver rather than opening its own, so a caller that is already in a
 * transaction — `ExposedAppSettingsRepository.updateLinkPolicy` — gets atomicity by construction
 * rather than by Exposed's ambient-transaction behaviour.
 *
 * The id comes from the entry. `AuditLogs` is an `EntityIdTable`, which deliberately installs no
 * `autoGenerate()` default, so an insert that forgot its id fails rather than silently taking one
 * nobody chose.
 */
internal fun JdbcTransaction.insertAuditEntry(entry: AuditEntry) {
    val metadata = entry.metadata.toMetadataJson()

    AuditLogs.insert {
        it[id] = entry.id.value
        it[actor] = entry.actor
        it[action] = entry.action.name
        it[entity] = entry.entity
        it[entityId] = entry.entityId.value
        // Nullable, so omitting it would write NULL on every row rather than failing -- an audit
        // trail that silently forgets who acted, with §8.13's exception report joining on a column
        // that is never populated (ERT-190).
        it[actorUserId] = entry.actorUserId?.value
        it[timestamp] = entry.timestamp
        it[AuditLogs.metadata] = metadata
    }
}
