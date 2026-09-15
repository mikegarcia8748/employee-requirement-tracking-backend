package com.pgsystem.employee.requirement.tracker.data.db.table

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable

/**
 * The two keyed-table shapes this schema uses.
 *
 * Deliberately **not** `UUIDTable`, for two reasons beyond the column type:
 *
 *  - `UUIDTable` builds its id as `javaUUID(...).autoGenerate().entityId()`, and `autoGenerate()`
 *    installs a client default that fires whenever an insert omits `it[id]`. The injected generator
 *    is then bypassed in silence and the row gets an identifier nobody chose — which also defeats
 *    the determinism those ports exist to provide. There is no default here, so an insert must name
 *    its id or fail. `MigrationTest` asserts that absence.
 *  - `varchar`, never `char`. PostgreSQL blank-pads `char(n)` and returns the padding, so an
 *    8-character [PersonId] written into a 12-wide column would come back with four trailing spaces
 *    and fail its own validation. Exposed's `char()` also hardcodes `CHAR(n)` regardless of dialect.
 *
 * Both are `IdTable<String>`, so Exposed cannot tell them apart: `reference("x", Employees)` from a
 * table expecting an [EntityId] compiles. The width mismatch is caught by the database on insert and
 * by the column-width test in `MigrationTest`, not by the compiler.
 */
abstract class PersonIdTable(name: String) : IdTable<String>(name) {
    final override val id: Column<EntityID<String>> = varchar("id", PersonId.LENGTH).entityId()
    final override val primaryKey = PrimaryKey(id)
}

/** A table keyed by a 12-character [EntityId]. See [PersonIdTable] for the shared reasoning. */
abstract class EntityIdTable(name: String) : IdTable<String>(name) {
    final override val id: Column<EntityID<String>> = varchar("id", EntityId.LENGTH).entityId()
    final override val primaryKey = PrimaryKey(id)
}
