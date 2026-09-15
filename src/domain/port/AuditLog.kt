package com.pgsystem.employee.requirement.tracker.domain.port

import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry

/** Append-only record of HR-side actions (PRD 12). */
interface AuditLog {
    suspend fun record(entry: AuditEntry)
    suspend fun findFor(entityId: Identifier): List<AuditEntry>
}
