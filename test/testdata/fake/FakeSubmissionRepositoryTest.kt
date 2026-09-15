package com.pgsystem.employee.requirement.tracker.testdata.fake

import com.pgsystem.employee.requirement.tracker.domain.model.Submission
import com.pgsystem.employee.requirement.tracker.testdata.Fixtures
import com.pgsystem.employee.requirement.tracker.testdata.aRequirementSet
import com.pgsystem.employee.requirement.tracker.testdata.aSubmission
import com.pgsystem.employee.requirement.tracker.testdata.aVersionChain
import com.pgsystem.employee.requirement.tracker.testdata.entityId
import com.pgsystem.employee.requirement.tracker.testdata.personId
import com.pgsystem.employee.requirement.tracker.testdata.requirementId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The submission fake against PRD 7.1.
 *
 * The retention rule is the reason this fake exists before real storage does: "current plus the last
 * four, unless a flag is open" is a use-case decision, and it has to be testable before anything can
 * write a byte.
 */
class FakeSubmissionRepositoryTest {

    private val requirement = Fixtures.REQUIREMENT_ID

    @Test
    fun `fake submission repository - six versions with retention of five - purges the oldest`() = runTest {
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 6))

        repository.purgeBeyondRetention(requirement, keep = Submission.VERSIONS_RETAINED)

        repository.findVersions(requirement).map { it.version } shouldBe listOf(6, 5, 4, 3, 2)
        repository.purges.single().removed.map { it.version } shouldBe listOf(1)
    }

    @Test
    fun `fake submission repository - purge is never called - every version survives`() = runTest {
        // The retention freeze lives in the use case, not here (PRD 7.1, SEC-13). Asserting that
        // purges is empty is how ERT-734 proves the use case declined to call at all.
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 9))

        repository.findVersions(requirement) shouldHaveSize 9
        repository.purges.shouldBeEmpty()
    }

    @Test
    fun `fake submission repository - a purge that removes nothing - is still recorded`() = runTest {
        // "Purged nothing" and "never asked" are different facts about a use case, and only one of
        // them means the freeze was honoured.
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 2))

        repository.purgeBeyondRetention(requirement, keep = 5)

        repository.purges shouldHaveSize 1
        repository.purges.single().removed.shouldBeEmpty()
        repository.findVersions(requirement) shouldHaveSize 2
    }

    @Test
    fun `fake submission repository - a purge - leaves another requirement's versions alone`() = runTest {
        val other = requirementId(1)
        val repository = FakeSubmissionRepository()
            .given(aVersionChain(requirement, count = 6))
            .given(aVersionChain(other, count = 6).map { it.copy(id = entityId("OTH" + it.version.toString().padStart(9, '0'))) })

        repository.purgeBeyondRetention(requirement, keep = 5)

        repository.findVersions(other) shouldHaveSize 6
    }

    @Test
    fun `fake submission repository - purge with a negative retention - fails loudly`() = runTest {
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 2))

        assertFailsWith<IllegalArgumentException> { repository.purgeBeyondRetention(requirement, keep = -1) }
    }

    @Test
    fun `fake submission repository - findVersions - returns the newest first`() = runTest {
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 3))

        repository.findVersions(requirement).map { it.version } shouldBe listOf(3, 2, 1)
    }

    @Test
    fun `fake submission repository - findCurrentFor - returns the version marked current`() = runTest {
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 4))

        repository.findCurrentFor(requirement)!!.version shouldBe 4
    }

    @Test
    fun `fake submission repository - two versions marked current - fails loudly`() = runTest {
        // A state the flag cannot legitimately be in. Silently returning one of them would let a use
        // case that forgot to supersede the old version look correct.
        val repository = FakeSubmissionRepository().given(
            aVersionChain(requirement, count = 2).map { it.copy(isCurrent = true) }
        )

        assertFailsWith<IllegalStateException> { repository.findCurrentFor(requirement) }
    }

    @Test
    fun `fake submission repository - save - does not supersede the previous version`() = runTest {
        // Superseding is ERT-733's decision, expressed in the use case. A helpful fake that flipped
        // isCurrent would cover for a use case that forgot to.
        val repository = FakeSubmissionRepository().given(aSubmission(version = 1, isCurrent = true))

        repository.save(aSubmission(id = entityId("SUB000000002"), version = 2, isCurrent = true))

        repository.all.count { it.isCurrent } shouldBe 2
        repository.saved shouldHaveSize 1
    }

    @Test
    fun `fake submission repository - totalBytesFor - sums every version including superseded ones`() = runTest {
        // Counting only the current version would put the 100 MB cap out of reach in practice: five
        // retained versions of five documents is what actually fills it (PRD 7.1).
        val owners = RequirementOwners()
        owners.register(requirement, ownedBy = Fixtures.EMPLOYEE_ID)
        val repository = FakeSubmissionRepository(owners = owners)
            .given(aVersionChain(requirement, count = 4, sizeBytes = 1_000_000))

        repository.totalBytesFor(Fixtures.EMPLOYEE_ID) shouldBe 4_000_000L
    }

    @Test
    fun `fake submission repository - totalBytesFor - excludes another employee's submissions`() = runTest {
        val owners = RequirementOwners()
        val otherRequirement = requirementId(1)
        val otherEmployee = personId("EMP00002")
        owners.register(requirement, ownedBy = Fixtures.EMPLOYEE_ID)
        owners.register(otherRequirement, ownedBy = otherEmployee)

        val repository = FakeSubmissionRepository(owners = owners)
            .given(aSubmission(employeeRequirementId = requirement, sizeBytes = 100))
            .given(aSubmission(id = entityId("SUB000000002"), employeeRequirementId = otherRequirement, sizeBytes = 900))

        repository.totalBytesFor(Fixtures.EMPLOYEE_ID) shouldBe 100L
        repository.totalBytesFor(otherEmployee) shouldBe 900L
    }

    @Test
    fun `fake submission repository - totalBytesFor over an unregistered requirement - fails loudly`() = runTest {
        // A silent zero would make the storage-cap assertion pass for a reason unrelated to the rule.
        val repository = FakeSubmissionRepository().given(aSubmission())

        assertFailsWith<IllegalStateException> { repository.totalBytesFor(Fixtures.EMPLOYEE_ID) }
    }

    @Test
    fun `fake submission repository - ownership registered through the employee repository - resolves the total`() = runTest {
        // The shared-index arrangement the two fakes are meant to be used in.
        val owners = RequirementOwners()
        val employees = FakeEmployeeRepository(owners = owners)
        val submissions = FakeSubmissionRepository(owners = owners)

        employees.saveRequirements(aRequirementSet(required = 1).requirements)
        submissions.given(aSubmission(employeeRequirementId = requirementId(0), sizeBytes = 2_048))

        submissions.totalBytesFor(Fixtures.EMPLOYEE_ID) shouldBe 2_048L
    }

    @Test
    fun `fake submission repository - configured to fail - throws rather than returning an empty result`() = runTest {
        val repository = FakeSubmissionRepository().given(aVersionChain(requirement, count = 2))
        repository.failure.failNextCall()

        assertFailsWith<IllegalStateException> { repository.findVersions(requirement) }
        repository.findVersions(requirement) shouldHaveSize 2
    }
}
