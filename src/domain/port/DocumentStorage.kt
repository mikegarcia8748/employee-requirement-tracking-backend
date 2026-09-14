package com.pgsystem.employee.requirement.tracker.domain.port

import java.time.Duration

/**
 * Object storage for document bytes. Never publicly readable (PRD 12).
 *
 * [signedUrlFor] is reachable from the **HR** side only. No portal endpoint may call it: the portal
 * reports document status and never returns content, a signed URL, or an original filename
 * (PRD 8.6). That single rule is what turns a compromised mailbox from a bulk disclosure of one
 * person's birth certificate, IDs and medical results into a fraudulent upload HR should catch
 * (SEC-02) — and it is the compensating control that makes the accepted single-channel risk in
 * PRD 12 survivable.
 */
interface DocumentStorage {
    suspend fun put(key: String, bytes: ByteArray, mimeType: String)

    /** HR-side only. */
    suspend fun signedUrlFor(key: String, validFor: Duration): String

    suspend fun delete(key: String)

    /** Malware scan gate — a file must not become previewable before it passes (PRD 12). */
    suspend fun isClean(key: String): Boolean
}
