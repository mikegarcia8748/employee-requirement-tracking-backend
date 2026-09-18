package com.pgsystem.employee.requirement.tracker.contract

import com.pgsystem.employee.requirement.tracker.data.MigratedDatabase
import com.pgsystem.employee.requirement.tracker.data.db.DatabaseFactory
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import kotlinx.coroutines.runBlocking

/**
 * A migrated database with the foreign keys a builder default cannot satisfy on its own.
 *
 * Every `Fixtures` id a builder points at — `DEPARTMENT_ID`, `EMPLOYMENT_TYPE_ID`, `HR_USER_ID` —
 * is an `on delete restrict` foreign key, and none of the three is present after migration: the V2
 * seed holds `d00000000001` and `e00000000001`..`4`, and `users` is empty because the bootstrap
 * admin is a startup use case rather than a seed row. ERT-410 predicted every later adapter ticket
 * would hit this and recommended inserting under the `Fixtures` ids rather than re-pointing every
 * builder call, which is what `ExposedEmployeeRepositoryTest` and `ExposedUploadLinkRepositoryTest`
 * both do.
 *
 * It is gathered here rather than copied a third and fourth time **because ERT-250 is a ticket about
 * two copies of one rule drifting apart.** Writing the seed out again in each adapter-side contract
 * subclass would be that mistake in the file complaining about it.
 *
 * The existing adapter tests are deliberately left alone: they are `RepositoryTestBase` subclasses
 * with their own `@BeforeTest`, and rewriting seven passing test classes is not this ticket's work.
 */
internal class ContractDatabase {

    private val database = MigratedDatabase()

    val factory: DatabaseFactory get() = database.factory

    fun open() {
        database.open()
        runBlocking { seedFixtureReferences() }
    }

    fun close() {
        database.close()
    }

    suspend fun execute(sql: String) {
        factory.transaction { exec(sql) }
    }

    /** A hire written past the mapper, for tests that need a row rather than a domain object. */
    suspend fun insertHire(id: String, email: String) = execute(
        """
        insert into employees (id, first_name, last_name, department_id, "position",
                               employment_type_id, email, packet_status, submitted_by_hr,
                               anomaly_flags, created_at, created_by)
        values ('$id', 'Jose', 'Dela Cruz', '${Fixtures.DEPARTMENT_ID.value}', 'Store Associate',
                '${Fixtures.EMPLOYMENT_TYPE_ID.value}', '$email', 'DRAFT_COLLECTING', false,
                '', current_timestamp, '${Fixtures.HR_USER_ID.value}')
        """.trimIndent()
    )

    private suspend fun seedFixtureReferences() {
        execute(
            """
            insert into users (id, email, full_name, password_hash, "role", is_active,
                               password_change_required, created_at)
            values ('${Fixtures.HR_USER_ID.value}', 'hr.officer@example.com', 'Ana Reyes', 'x',
                    'HR_OFFICER', true, false, current_timestamp)
            """.trimIndent()
        )
        execute(
            """
            insert into departments (id, "name")
            values ('${Fixtures.DEPARTMENT_ID.value}', 'Fixture Department')
            """.trimIndent()
        )
        execute(
            """
            insert into employment_types (id, "name")
            values ('${Fixtures.EMPLOYMENT_TYPE_ID.value}', 'Fixture Employment Type')
            """.trimIndent()
        )
    }
}
