package com.pgsystem.employee.requirement.tracker.route

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.route.mapper.orDenied
import com.pgsystem.employee.requirement.tracker.route.mapper.orNotFound
import com.pgsystem.employee.requirement.tracker.route.mapper.toApiError
import com.pgsystem.employee.requirement.tracker.route.mapper.toStatus
import com.pgsystem.employee.requirement.tracker.testdata.personId
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlin.test.Test

/**
 * The rule that where an identifier was read decides its failure status (ERT-140).
 *
 * No server: these are pure functions, and the reason they exist is a decision rather than a piece
 * of plumbing. [ErrorMappingTest] proves the same rule reaches the wire.
 *
 * `orDenied` has no production caller until ERT-630. It is tested now for the same reason it is
 * written now — the portal rule has to be settled before the first portal route, not audited after.
 */
class PathIdsTest {

    private val malformed: DomainResult<PersonId> = PersonId.of("nope")

    @Test
    fun `path identifier - a malformed id in a path - becomes the not found a lookup miss produces`() {
        val result = malformed.orNotFound("employee")

        result shouldBe DomainResult.Err(AppError.NotFound(code = "employee_not_found", entity = "employee"))
    }

    @Test
    fun `path identifier - a malformed id in a path - never renders the validation code that names the format`() {
        // 422 person_id.invalid_format tells the caller their guess was the wrong *shape*, which
        // separates malformed from unknown and starts an enumeration (PRD 6.6).
        val error = (malformed.orNotFound("employee") as DomainResult.Err).error

        error.toStatus() shouldBe HttpStatusCode.NotFound
        error.toApiError().code shouldBe "employee_not_found"
    }

    @Test
    fun `path identifier - a well formed id - passes through untouched`() {
        val ok: DomainResult<PersonId> = DomainResult.Ok(personId("aB3xK9Lm"))

        ok.orNotFound("employee") shouldBe ok
        ok.orDenied() shouldBe ok
    }

    @Test
    fun `portal failure - a validation failure - collapses to the single denied value`() {
        malformed.orDenied() shouldBe DomainResult.Err(AppError.Denied)
    }

    @Test
    fun `portal failure - a not found and a conflict - collapse to the same denied value`() {
        // Every portal failure is the same failure. A 409 escaping the portal would say "this
        // requirement exists and is locked", which names a hire.
        val fromMiss = DomainResult.Err(AppError.NotFound("employee_not_found", "employee")).orDenied()
        val fromLock = DomainResult.Err(AppError.Conflict("requirement_locked", "Under review")).orDenied()

        fromMiss shouldBe fromLock
        fromMiss shouldBe DomainResult.Err(AppError.Denied)
    }

    @Test
    fun `denied rendering - every denied value - is the same object so no two bodies can differ`() {
        // The guarantee is structural: AppError.Denied is a data object, so there is nothing to
        // compare. This test exists so that turning it back into a data class fails the build.
        AppError.Denied.toApiError() shouldBe AppError.Denied.toApiError()
        AppError.Denied.toApiError().details shouldBe null
        AppError.Denied.code shouldBe "not_found"
    }
}
