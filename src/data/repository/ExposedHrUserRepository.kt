package com.pgsystem.employee.requirement.tracker.data.repository

import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.data.db.table.Users
import com.pgsystem.employee.requirement.tracker.data.mapper.toHrUser
import com.pgsystem.employee.requirement.tracker.data.mapper.writeTo
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * HR staff accounts, against `users` (ERT-190).
 *
 * **[findByEmail] compares `lower(email)` rather than `email`, even though [EmailAddress] is already
 * lower-cased.** Belt and braces on the one lookup that decides who signs in: the value class
 * protects addresses this application constructed, and the predicate protects against a row some
 * other writer put there in mixed case. The matching functional unique index is in
 * `V4__hr_users.sql`, so the two halves cannot disagree — a row the index would have refused is a
 * row this query would not have found anyway.
 *
 * **[save] is read-then-insert-or-update rather than an upsert.** Exposed's `upsert` compiles to
 * `on conflict`, which `MigrationTest.migration portability` bans outright and which H2 and
 * PostgreSQL spell differently. Both branches run inside one transaction, so the check and the write
 * do not race each other; a genuinely concurrent insert of the same id loses to the primary key,
 * which is the correct outcome and is loud.
 */
class ExposedHrUserRepository(private val factory: DatabaseFactory) : HrUserRepository {

    override suspend fun findById(id: PersonId): HrUser? = factory.transaction {
        Users.selectAll().where { Users.id eq id.value }.singleOrNull()?.toHrUser()
    }

    override suspend fun findByEmail(email: EmailAddress): HrUser? = factory.transaction {
        Users.selectAll()
            .where { Users.email.lowerCase() eq email.value }
            .singleOrNull()
            ?.toHrUser()
    }

    override suspend fun findAll(): List<HrUser> = factory.transaction {
        Users.selectAll()
            .orderBy(Users.email to SortOrder.ASC)
            .map { it.toHrUser() }
    }

    override suspend fun save(user: HrUser): HrUser = factory.transaction {
        val exists = Users.selectAll().where { Users.id eq user.id.value }.empty().not()

        if (exists) {
            Users.update({ Users.id eq user.id.value }) { it.writeTo(user) }
        } else {
            // The id comes from the user. `Users` is a PersonIdTable, which installs no
            // autoGenerate() default, so an insert that forgot its id fails rather than silently
            // taking one nobody chose.
            Users.insert {
                it[id] = user.id.value
                it.writeTo(user)
            }
        }

        user
    }

    override suspend fun countAll(): Long = factory.transaction {
        Users.selectAll().count()
    }
}
