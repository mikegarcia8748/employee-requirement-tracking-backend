package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.UploadLinks
import com.pgsystem.employee.requirement.tracker.data.mapper.toUploadLink
import com.pgsystem.employee.requirement.tracker.data.mapper.writeTo
import com.pgsystem.employee.requirement.tracker.domain.model.LinkStatus
import com.pgsystem.employee.requirement.tracker.domain.model.UploadLink
import com.pgsystem.employee.requirement.tracker.domain.port.UploadLinkRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Upload links against `upload_links` (ERT-420, PRD 6.3, 6.4, 12).
 *
 * **This adapter takes no `TokenDigest`, and that is the point of the port's shape.**
 * [findByTokenHash] is handed a digest that has already been computed; digesting a presented token
 * happens in the use case. An adapter holding the digest port would be one refactor away from the
 * by-plaintext overload the port forbids, and a lookup by plaintext would imply the token was
 * recoverable from storage. It is not: `token_hash` is an HMAC keyed by `TOKEN_PEPPER` (ERT-160),
 * reproducible so a link can be *found* and keyed so a leaked column cannot be digested against a
 * candidate list.
 *
 * **[save] is the house read-then-insert-or-update, unlike `ExposedEmployeeRepository`.** That
 * adapter splits `create` from `save` because a `PersonId` draws from 62^8 and a taken id must be
 * redrawn rather than read as "update this row". A link carries an
 * [com.pgsystem.employee.requirement.tracker.core.value.EntityId], 62^12, where a collision is
 * negligible — the same asymmetry that keeps `saveRequirements` a single method, and the whole
 * reason the two identifier widths are separate types. Exposed's `upsert` is not used, for the
 * reason `ExposedHrUserRepository` gives: it compiles to `on conflict`, which
 * `MigrationTest.migration portability` bans outright.
 *
 * ### [findActiveForEmployee] takes no clock, deliberately
 *
 * C15 grew a `now` parameter on `PortalSessionRepository.findActiveForLink` because that port could
 * not otherwise tell a live session from a lapsed one — a session records only `started_at`,
 * `expires_at` and `ended_at`, so "active" there could only mean "not explicitly ended". **A link is
 * different: it carries a stored [LinkStatus].** ERT-1020 states the rule this depends on — expiry
 * is evaluated lazily at access time by ERT-644 against the stored dates, and the sweep exists only
 * to send the warning email and keep the status tidy for the HR list, so the portal is never blocked
 * on a background job having run. Filtering on the stored status here is therefore the whole answer,
 * and adding a clock would put a second definition of expiry in the one layer that must not hold
 * business rules. This is not C15 unfixed; it is C15's question asked of a port that already has the
 * column.
 */
class ExposedUploadLinkRepository(private val factory: DatabaseFactory) : UploadLinkRepository {

    override suspend fun findByTokenHash(tokenHash: String): UploadLink? = factory.transaction {
        UploadLinks.selectAll()
            .where { UploadLinks.tokenHash eq tokenHash }
            .singleOrNull()
            ?.toUploadLink()
    }

    /**
     * The employee's live link, most recently issued.
     *
     * **`ACTIVE` only, not every status that opens the portal.** [LinkStatus.COMPLETED] also has
     * `opensPortal = true` (PRD 6.3), and including it here would be the plausible mistake: a
     * completed packet's read-only confirmation page is reachable, but it is not a link that
     * `resend-link` should reuse or that a new invitation should point at. `FakeUploadLinkRepository`
     * makes the same distinction, and the two must agree or a use case passes against fakes and
     * behaves differently against SQL.
     *
     * The ordering is not decoration. The schema permits an employee to hold two active links —
     * nothing constrains it, and ERT-1030's `resend-link` is what will produce the pair — so without
     * an `ORDER BY` this returns whichever row the engine happens to hand back first, which can
     * differ between two requests against the same data. Newest wins, with the id as the tiebreaker
     * for two links issued in the same instant.
     */
    override suspend fun findActiveForEmployee(employeeId: PersonId): UploadLink? =
        factory.transaction {
            UploadLinks.selectAll()
                .where {
                    (UploadLinks.employeeId eq employeeId.value) and
                        (UploadLinks.status eq LinkStatus.ACTIVE.name)
                }
                .orderBy(UploadLinks.issuedAt to SortOrder.DESC, UploadLinks.id to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toUploadLink()
        }

    override suspend fun save(link: UploadLink): UploadLink = factory.transaction {
        val exists = UploadLinks.selectAll()
            .where { UploadLinks.id eq link.id.value }
            .empty()
            .not()

        if (exists) {
            UploadLinks.update({ UploadLinks.id eq link.id.value }) { it.writeTo(link) }
        } else {
            // `UploadLinks` is an EntityIdTable, which installs no autoGenerate() default, so an
            // insert that forgot its id fails rather than silently taking one nobody chose.
            UploadLinks.insert {
                it[id] = link.id.value
                it.writeTo(link)
            }
        }

        link
    }
}
