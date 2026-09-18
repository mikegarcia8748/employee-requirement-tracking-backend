package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.UploadLink
import com.pgsystem.employee.requirement.tracker.domain.port.UploadLinkRepository

/**
 * Upload links, resolved **by token hash only**.
 *
 * There is deliberately no `findByToken(plaintext)` here, and adding one would be a defect rather
 * than a convenience. The plaintext token exists only in the invitation email; a lookup by plaintext
 * would imply it was recoverable from storage, and a fake that offered one would let a use case be
 * written against a path the real adapter cannot supply.
 *
 * [save] rejects a token hash already held by a different link, mirroring `upload_links.token_hash
 * uniqueIndex`. Without that, a test could arrange two links sharing a digest — a state the database
 * refuses — and a use case relying on uniqueness would pass here and fail on first insert.
 */
class FakeUploadLinkRepository(vararg seed: UploadLink) : UploadLinkRepository {

    val failure = FakeFailure()

    private val links = seed.associateBy { it.id }.toMutableMap()
    private val savedLinks = mutableListOf<UploadLink>()

    /** Every [save] call, in order. Seeded links do not appear here. */
    val saved: List<UploadLink> get() = savedLinks.toList()

    val all: List<UploadLink> get() = links.values.toList()

    init {
        links.values.groupBy { it.tokenHash }.forEach { (hash, sharing) ->
            check(sharing.size == 1) { "Seeded ${sharing.size} links sharing token hash '$hash'; the column is unique" }
        }
    }

    override suspend fun findByTokenHash(tokenHash: String): UploadLink? {
        failure.check()
        return links.values.firstOrNull { it.tokenHash == tokenHash }
    }

    /**
     * `status == ACTIVE`, the most recently issued first.
     *
     * `COMPLETED` also opens the portal (PRD 6.3) but is not *active*: a completed packet's
     * read-only confirmation is not a link a new invitation should reuse. The ordering is here so
     * the answer is deterministic if a test ever arranges two, which the schema permits.
     */
    /**
     * Newest first, with the id breaking a tie — the order
     * `ExposedUploadLinkRepository.findActiveForEmployee` promises (ERT-250).
     *
     * This was `maxByOrNull { it.issuedAt }` until the contract suite compared the two, and
     * `maxByOrNull` returns the **first** maximal element in iteration order rather than the last
     * (HAR-01 f). The adapter orders by `issued_at DESC, id DESC` and limits to one.
     *
     * **The tie is the default case in this harness, not an edge case.** Every builder issues at
     * `FixedClock.DEFAULT`, so two links sharing an instant is what a test gets unless it goes out
     * of its way — and `FakesTest`'s ordering test set them a day apart, so it never reached the tie
     * the adapter's KDoc exists to describe.
     */
    override suspend fun findActiveForEmployee(employeeId: PersonId): UploadLink? {
        failure.check()
        return links.values
            .filter { it.employeeId == employeeId && it.status == LinkStatus.ACTIVE }
            .sortedWith(compareByDescending<UploadLink> { it.issuedAt }.thenByDescending { it.id.value })
            .firstOrNull()
    }

    override suspend fun save(link: UploadLink): UploadLink {
        failure.check()
        refuseDuplicateDigest(link)
        links[link.id] = link
        savedLinks += link
        return link
    }

    // ── Arrange ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Adds links without recording a save — and refuses a duplicate digest, as [save] and the
     * constructor already do.
     *
     * **This was the unguarded third door (HAR-01 e).** Two of the three ways into this fake's state
     * enforced `upload_links_token_hash_unique` and explained at length why; this one was a plain
     * map put, so a test could arrange a pair the database has refused since V1 and then assert on
     * which of them `findByTokenHash` returned.
     */
    fun given(vararg links: UploadLink): FakeUploadLinkRepository = apply {
        links.forEach {
            refuseDuplicateDigest(it)
            this.links[it.id] = it
        }
    }

    /**
     * `upload_links_token_hash_unique`, in memory.
     *
     * Re-saving the *same* id is an update and must pass: a link keeps its digest across a
     * revocation or an extension, so refusing every repeat of a hash already held would make the
     * fake stricter than the column.
     */
    private fun refuseDuplicateDigest(link: UploadLink) {
        val clash = links.values.firstOrNull { it.tokenHash == link.tokenHash && it.id != link.id }

        check(clash == null) {
            "Link ${link.id.value} carries the token hash already held by ${clash?.id?.value}; " +
                "upload_links.token_hash is unique"
        }
    }
}
