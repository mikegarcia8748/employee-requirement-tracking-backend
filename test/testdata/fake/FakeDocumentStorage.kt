package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.domain.port.DocumentStorage
import java.time.Duration

/**
 * Object storage, in memory.
 *
 * ### [signedUrlRequests] is invariant 1, inverted
 *
 * `signedUrlFor` is HR-side only: the portal reports document status and never returns content, a
 * signed URL, or an original filename (PRD 8.6, SEC-02). `ArchitectureTest` enforces that
 * structurally — a portal route importing [DocumentStorage] fails the build — but a use case can
 * still reach the port through a collaborator that is not itself a portal file. Recording every
 * request means a portal use-case test can assert the list is **empty**, which is the behavioural
 * half of the same rule.
 *
 * ### The malware gate is a stub, and the stub is the point
 *
 * PRD 12 makes scanning a P0 control, and no scanner has been chosen (roadmap E5). [isClean] returns
 * true by default so the gate is *wired* from the start, and [markInfected] exists so ERT-810 can
 * test the refusal path before a real scanner lands. A green suite here does not mean documents are
 * scanned — it means the code asks.
 *
 * [signedUrlFor] on a key that was never [put] throws. Real object storage would happily sign a URL
 * for a key that does not exist, and the download would 404 much later; in a test, signing a key
 * nothing stored is a use case building the wrong key, and that should fail where it happens.
 */
class FakeDocumentStorage : DocumentStorage {

    val failure = FakeFailure()

    private val objects = mutableMapOf<String, StoredObject>()
    private val infected = mutableSetOf<String>()
    private val signings = mutableListOf<SignedUrlRequest>()
    private val deletions = mutableListOf<String>()

    /** Everything currently stored, by key. */
    val stored: Map<String, StoredObject> get() = objects.toMap()

    /**
     * Every `signedUrlFor` call, in order.
     *
     * A portal use-case test asserts this is empty. An HR one asserts what it contains.
     */
    val signedUrlRequests: List<SignedUrlRequest> get() = signings.toList()

    /** Every [delete] call, in order, including keys that held nothing. */
    val deleted: List<String> get() = deletions.toList()

    data class StoredObject(val bytes: ByteArray, val mimeType: String) {
        // ByteArray uses identity equality, which would make two identical uploads unequal.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is StoredObject && mimeType == other.mimeType && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    data class SignedUrlRequest(val key: String, val validFor: Duration)

    override suspend fun put(key: String, bytes: ByteArray, mimeType: String) {
        failure.check()
        objects[key] = StoredObject(bytes.copyOf(), mimeType)
    }

    override suspend fun signedUrlFor(key: String, validFor: Duration): String {
        failure.check()
        check(key in objects) { "No object stored at '$key'; signing a key nothing was put under is a bug" }

        signings += SignedUrlRequest(key, validFor)
        return "https://storage.invalid/$key?expires=${validFor.seconds}"
    }

    override suspend fun delete(key: String) {
        failure.check()
        objects.remove(key)
        deletions += key
    }

    override suspend fun isClean(key: String): Boolean {
        failure.check()
        return key !in infected
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /** Seed an object without recording a put. */
    fun given(key: String, bytes: ByteArray = "document bytes".toByteArray(), mimeType: String = "application/pdf") =
        apply { objects[key] = StoredObject(bytes, mimeType) }

    /** Make the scan gate refuse this key, so the ERT-810 refusal path has something to refuse. */
    fun markInfected(key: String): FakeDocumentStorage = apply { infected += key }

    // ── Assert ──────────────────────────────────────────────────────────────────────────────────

    fun holds(key: String): Boolean = key in objects
}
