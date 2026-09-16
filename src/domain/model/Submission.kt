package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import java.time.Instant

/**
 * One uploaded file against one requirement.
 *
 * Versioned from the start, with nullable validity windows, so Phase 4 document-lifecycle tracking
 * costs a migration rather than a redesign (PRD 9.3).
 *
 * The database holds the storage key and metadata only; bytes live in object storage that is never
 * publicly readable (PRD 11, 12). No portal response may carry [originalFilename] — filenames leak
 * content (`NBI_Clearance_DelaCruz_1998.pdf`) as surely as the document does (SEC-02).
 */
data class Submission(
    val id: EntityId,
    val employeeRequirementId: EntityId,
    val version: Int,
    val fileKey: String,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploadedAt: Instant,
    val status: RequirementStatus,
    /** Phase 4 validity window. Null for documents that do not expire. */
    val validFrom: Instant?,
    val validUntil: Instant?,
    /** The [HrUser] who approved or rejected. A [PersonId] since ERT-190, not a typed-in name. */
    val reviewedBy: PersonId?,
    val reviewedAt: Instant?,
    val rejectionReason: String?,
    val isCurrent: Boolean,
) {
    companion object {
        /** PRD 7.1 — comfortably fits a phone photo or a scanned PDF. */
        const val MAX_FILE_SIZE_BYTES: Long = 10L * 1024 * 1024

        /** PRD 7.1 — backstop against pathological cases. */
        const val MAX_TOTAL_BYTES_PER_EMPLOYEE: Long = 100L * 1024 * 1024

        /** PRD 7.1 — current plus the last 4. Suspended entirely while an anomaly flag is open. */
        const val VERSIONS_RETAINED = 5
    }
}
