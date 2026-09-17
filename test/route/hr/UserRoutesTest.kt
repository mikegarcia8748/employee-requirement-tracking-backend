package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ResetHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.SetHrUserActiveUseCase
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.FixedPersonIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.authenticatedAs
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * User administration, `HR_ADMIN` only (ERT-190, PRD §8.13).
 *
 * The rule under test is the role gate, and it is tested **through the real verifier** — the role
 * arrives as a signed claim, so a gate reading it from anywhere else would fail here.
 *
 * Testing the 403 on a real route rather than on a mock pipeline is why `AuthGates` are functions a
 * handler calls rather than an interceptor: the interceptor would be stronger at preventing a
 * forgotten gate, but it could not express the change-password exception without a path allowlist a
 * rename would silently break.
 */
class UserRoutesTest {

    private val hasher: Hasher = BcryptHasher(cost = 4)
    private val audit = FakeAuditLog()

    private val admin = anHrAdmin()
    private val officer = anHrUser(email = anEmail("ana.reyes@example.com"))

    // ── The role gate ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr routes - an officer on an admin-only route - is refused with 403`() = testApplication {
        mountUsers()

        val response = client.get("/api/users") { authenticatedAs(officer) }

        // 403, not 404 and not 401: the caller is known and authenticated, and the remedy is not
        // "sign in again". A client has to be able to tell those apart.
        response.status shouldBe HttpStatusCode.Forbidden
        response.bodyAsText() shouldContain """"code":"forbidden""""
    }

    @Test
    fun `hr routes - an officer refused an admin route - has the attempt audited`() = testApplication {
        // A signed-in user reaching past their role is an 8.13 signal. An unauthenticated 401 is
        // background noise on any public endpoint, which is why only this one is recorded.
        mountUsers()

        client.get("/api/users") { authenticatedAs(officer) }

        val entry = audit.entriesFor(AuditAction.ACCESS_DENIED).single()

        entry.actorUserId shouldBe officer.id
        entry.metadata["attempted"] shouldBe "listUsers"
        entry.metadata["role"] shouldBe "HR_OFFICER"
    }

    @Test
    fun `hr routes - a refusal body - does not name the role required`() = testApplication {
        // Naming it would let an officer map the admin surface by probing it.
        mountUsers()

        client.get("/api/users") { authenticatedAs(officer) }.bodyAsText() shouldNotContain "HR_ADMIN"
    }

    @Test
    fun `hr routes - an admin on an admin-only route - is allowed`() = testApplication {
        // Non-vacuity: without this, a gate that refused everyone would pass every test above.
        mountUsers()

        val response = client.get("/api/users") { authenticatedAs(admin) }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain admin.email.value
    }

    @Test
    fun `hr routes - every admin route - refuses an officer`() = testApplication {
        // The gate is called per handler rather than applied once around the block, so "somebody
        // forgot one" is a real failure mode and it gets a real test.
        mountUsers()

        client.get("/api/users") { authenticatedAs(officer) }.status shouldBe HttpStatusCode.Forbidden

        client.post("/api/users") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody(createBody())
        }.status shouldBe HttpStatusCode.Forbidden

        client.post("/api/users/${officer.id.value}/active") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody("""{"isActive":false}""")
        }.status shouldBe HttpStatusCode.Forbidden

        client.post("/api/users/${officer.id.value}/reset-password") {
            authenticatedAs(officer)
            contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"a-long-enough-password"}""")
        }.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `hr routes - an admin owing a password change - is refused before the role is even considered`() =
        testApplication {
            // The password gate runs first. An admin whose credential came from a deployment
            // environment must not be able to create accounts with it.
            val owing = anHrAdmin(id = com.pgsystem.employee.requirement.tracker.testdata.personId("HRA00009"))
                .copy(passwordChangeRequired = true)
            mountUsers(owing)

            client.get("/api/users") { authenticatedAs(owing) }.status shouldBe HttpStatusCode.Conflict
        }

    // ── The surface itself ──────────────────────────────────────────────────────────────────────

    @Test
    fun `hr user listing - an admin - sees deactivated accounts too`() = testApplication {
        val disabled = anHrUser(
            id = com.pgsystem.employee.requirement.tracker.testdata.personId("HRU00009"),
            email = anEmail("gone@example.com"),
            isActive = false,
        )
        mountUsers(admin, officer, disabled)

        val body = client.get("/api/users") { authenticatedAs(admin) }.bodyAsText()

        body shouldContain "gone@example.com"
        body shouldContain """"total":3"""
    }

    @Test
    fun `hr user listing - any account - is rendered without its password hash`() = testApplication {
        mountUsers(anHrUser(passwordHash = "a-very-recognisable-hash"), admin)

        client.get("/api/users") { authenticatedAs(admin) }.bodyAsText() shouldNotContain
            "a-very-recognisable-hash"
    }

    @Test
    fun `hr user creation - an admin - returns 201 with the account owing a password change`() =
        testApplication {
            mountUsers()

            val response = client.post("/api/users") {
                authenticatedAs(admin)
                contentType(ContentType.Application.Json)
                setBody(createBody())
            }

            response.status shouldBe HttpStatusCode.Created
            response.bodyAsText() shouldContain """"passwordChangeRequired":true"""
            response.bodyAsText() shouldNotContain PASSWORD
        }

    @Test
    fun `hr user creation - an unknown role name - is a 422 rather than a 500`() = testApplication {
        mountUsers()

        val response = client.post("/api/users") {
            authenticatedAs(admin)
            contentType(ContentType.Application.Json)
            setBody(createBody(role = "SUPERUSER"))
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "role.invalid"
    }

    @Test
    fun `hr user creation - an address that already has an account - is a 409`() = testApplication {
        mountUsers()

        client.post("/api/users") {
            authenticatedAs(admin)
            contentType(ContentType.Application.Json)
            setBody(createBody(email = officer.email.value))
        }.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `hr user deactivation - an admin deactivating an officer - returns the updated account`() =
        testApplication {
            mountUsers()

            val response = client.post("/api/users/${officer.id.value}/active") {
                authenticatedAs(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"isActive":false}""")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain """"isActive":false"""
        }

    @Test
    fun `hr user deactivation - a malformed id in the path - is a 404 rather than a 422`() =
        testApplication {
            // PathIds' rule: a path names a resource, and an id that cannot exist names a resource
            // that does not exist. Answering 422 would say which guesses were the right *shape*.
            mountUsers()

            client.post("/api/users/not-an-id/active") {
                authenticatedAs(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"isActive":false}""")
            }.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `hr password reset - an admin - returns 204 and forces a change at next sign-in`() =
        testApplication {
            val users = mountUsers()

            client.post("/api/users/${officer.id.value}/reset-password") {
                authenticatedAs(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"newPassword":"a-reset-password-value"}""")
            }.status shouldBe HttpStatusCode.NoContent

            users.current(officer.id)!!.passwordChangeRequired shouldBe true
        }

    // ── The generated spec ──────────────────────────────────────────────────────────────────────

    @Test
    fun `api docs - the user routes are mounted - publish their request as well as their response schemas`() =
        testApplication {
            // This file had no spec assertion at all until ERT-146, which is part of why three POST
            // routes published no request body and nobody noticed. The response DTO is here too, so
            // the test fails whichever half regresses.
            application { rootModule() }

            val spec = client.get("/swagger/documentation.yaml").bodyAsText()

            spec shouldContain "CreateHrUserRequest"
            spec shouldContain "SetActiveRequest"
            spec shouldContain "ResetPasswordRequest"
            spec shouldContain "HrUserDto"
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun ApplicationTestBuilder.mountUsers(vararg seed: HrUser): FakeHrUserRepository {
        val users = FakeHrUserRepository(*(seed.takeIf { it.isNotEmpty() } ?: arrayOf(admin, officer)))
        val clock = FixedClock()
        val ids = FixedEntityIdGenerator()

        application {
            configureStatusPages()
            configureSerialization()
            configureSecurity(testJwtConfig())

            routing {
                authenticate(HR_AUTH) {
                    userAdminRoutes(
                        users = users,
                        createUser = CreateHrUserUseCase(
                            users, hasher, audit, clock, ids, FixedPersonIdGenerator("NEWUSER1"), NoOpUseCaseTracer,
                        ),
                        setActive = SetHrUserActiveUseCase(users, audit, clock, ids, NoOpUseCaseTracer),
                        resetPassword = ResetHrPasswordUseCase(users, hasher, audit, clock, ids, NoOpUseCaseTracer),
                        audit = audit,
                        clock = clock,
                        ids = ids,
                    )
                }
            }
        }

        return users
    }

    private fun createBody(
        email: String = "new.hire.officer@example.com",
        role: String = "HR_OFFICER",
    ) = """{"email":"$email","fullName":"Nueva Oficial","role":"$role","initialPassword":"$PASSWORD"}"""

    private companion object {
        const val PASSWORD = "an-initial-password"
    }
}
