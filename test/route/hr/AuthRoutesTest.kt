package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.data.auth.JwtIssuer
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.domain.model.HrUser
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.usecase.AuthenticateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ChangeHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.plugin.HR_AUTH
import com.pgsystem.employee.requirement.tracker.plugin.configureSecurity
import com.pgsystem.employee.requirement.tracker.plugin.configureSerialization
import com.pgsystem.employee.requirement.tracker.plugin.configureStatusPages
import com.pgsystem.employee.requirement.tracker.rootModule
import com.pgsystem.employee.requirement.tracker.testdata.FixedClock
import com.pgsystem.employee.requirement.tracker.testdata.FixedEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.testdata.anEmail
import com.pgsystem.employee.requirement.tracker.testdata.anHrAdmin
import com.pgsystem.employee.requirement.tracker.testdata.anHrUser
import com.pgsystem.employee.requirement.tracker.testdata.authenticatedAs
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeAuditLog
import com.pgsystem.employee.requirement.tracker.testdata.fake.FakeHrUserRepository
import com.pgsystem.employee.requirement.tracker.testdata.testJwtConfig
import com.pgsystem.employee.requirement.tracker.testdata.tokenSignedWithAnotherKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * Sign-in and the account routes (ERT-190).
 *
 * ### This is the file that closes ERT-340's recorded gap
 *
 * `RequirementTemplateRoutesTest` and `ReferenceRoutesTest` both carry a note saying that "requires
 * HR auth" could only be tested in the negative, because the Q4 placeholder signed with a key
 * generated per run and **no test could mint a token this application accepts**. `testdata/HrTokens`
 * mints one now, through the real `JwtIssuer`, and `configureSecurity` takes the config it was signed
 * with — so the positive half is testable for the first time, on every HR route.
 *
 * ### Why the real security plugin, and not a fake principal
 *
 * Mounting the handler against a stub principal would test the handler and nothing else. The
 * interesting failures are all in the seam: a claim named differently by issuer and verifier, a
 * missing `pwd_change` reading as exempt, a subject that will not parse. Those only appear when the
 * real verifier is asked to read a real token.
 *
 * `rootModule()` is used only for the refusal cases. It resolves `JwtConfig` from the environment,
 * which in dev generates a per-run key, so nothing can sign a token it would accept — which is
 * exactly what makes it right for "no credentials" and wrong for everything else.
 */
class AuthRoutesTest {

    private val hasher: Hasher = BcryptHasher(cost = 4)
    private val audit = FakeAuditLog()

    private val officer = anHrUser(
        email = anEmail("ana.reyes@example.com"),
        passwordHash = hasher.hash(PASSWORD),
    )

    /**
     * The clock the **sign-in** use case runs on, pinned to now rather than to [FixedClock.DEFAULT].
     *
     * The same boundary `testdata/HrTokens` documents, reached from the other side. A token this
     * route issues is stamped with the clock the use case was given, and its `exp` is then checked by
     * the auth0 verifier against the real system clock — so a token minted at the fixed instant is
     * eight months expired before the test presents it. Every other clock in this file stays fixed.
     *
     * Still a [FixedClock] rather than a `SystemClock`: time must not move between issuing and
     * presenting, it just has to start in the right place.
     */
    private val issuingClock = FixedClock(java.time.Instant.now())

    // ── Sign in ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `hr sign in - valid credentials - returns a token the authenticate block accepts`() =
        testApplication {
            // The end-to-end property the whole ticket exists for: a token minted by this
            // application's own sign-in reaches a handler behind `authenticate(HR_AUTH)`.
            mountAuth(officer)

            val token = signIn(officer.email.value, PASSWORD).let { response ->
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().jsonString("token")
            }

            val me = client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }

            me.status shouldBe HttpStatusCode.OK
            me.bodyAsText() shouldContain officer.email.value
        }

    @Test
    fun `hr sign in - valid credentials - returns the role and expiry beside the token`() =
        testApplication {
            mountAuth(officer)

            val body = signIn(officer.email.value, PASSWORD).bodyAsText()

            body shouldContain """"role":"HR_OFFICER""""
            body shouldContain """"expiresAt""""
            body shouldContain """"passwordChangeRequired":false"""
        }

    @Test
    fun `hr sign in - an unknown email and a wrong password - return byte-identical responses`() =
        testApplication {
            mountAuth(officer)

            val unknown = signIn("nobody@example.com", PASSWORD)
            val wrongPassword = signIn(officer.email.value, "not-the-password")

            unknown.status shouldBe HttpStatusCode.Unauthorized
            wrongPassword.status shouldBe HttpStatusCode.Unauthorized
            unknown.bodyAsText() shouldBe wrongPassword.bodyAsText()
        }

    @Test
    fun `hr sign in - a deactivated account and a malformed address - are identical to the other two`() =
        testApplication {
            val deactivated = anHrUser(
                email = anEmail("gone@example.com"),
                passwordHash = hasher.hash(PASSWORD),
                isActive = false,
            )
            mountAuth(officer, deactivated)

            val bodies = listOf(
                signIn("nobody@example.com", PASSWORD),
                signIn(officer.email.value, "wrong"),
                signIn(deactivated.email.value, PASSWORD),
                signIn("not-an-address", PASSWORD),
            ).map { it.status to it.bodyAsText() }

            bodies.distinct().size shouldBe 1
            bodies.first().first shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `hr sign in - a failure body - names neither the address nor the account`() = testApplication {
        mountAuth(officer)

        val body = signIn(officer.email.value, "wrong").bodyAsText()

        // The prose says "Email or password is incorrect", which is the point -- it covers both
        // causes without separating them. What must not appear is anything specific to this attempt.
        body shouldNotContain officer.email.value
        body shouldNotContain officer.fullName
        body shouldNotContain officer.passwordHash
        body shouldNotContain officer.id.value
        body shouldContain """"code":"authentication_failed""""
    }

    @Test
    fun `hr sign in - a successful response - carries no password hash`() = testApplication {
        mountAuth(officer)

        signIn(officer.email.value, PASSWORD).bodyAsText() shouldNotContain officer.passwordHash
    }

    // ── The authenticate block ──────────────────────────────────────────────────────────────────

    @Test
    fun `hr routes - a token minted by this application - are reachable`() = testApplication {
        mountAuth(officer)

        client.get("/api/auth/me") { authenticatedAs(officer) }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `hr routes - a token signed with another key - are refused`() = testApplication {
        // Structurally valid, correct claims, wrong signature. Without this test, a verifier that
        // skipped signature checking would pass every other test in this file.
        mountAuth(officer)

        val response = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${tokenSignedWithAnotherKey(officer)}")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `hr routes - no credentials - are refused`() = testApplication {
        application { rootModule() }

        client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/users").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `hr sign in - the sign-in route itself - is reachable without credentials`() = testApplication {
        // It is how a caller OBTAINS a token, so requiring one would make the application
        // unreachable. Asserted against the real route tree, since this is a mounting property.
        application { rootModule() }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nobody@example.com","password":"whatever-it-is"}""")
        }

        // 401 rather than 200: there are no accounts. What matters is that it is not the 401 the
        // `authenticate` block produces -- this one carries the mapped body.
        response.status shouldBe HttpStatusCode.Unauthorized
        response.bodyAsText() shouldContain "authentication_failed"
    }

    // ── The password-change gate ────────────────────────────────────────────────────────────────

    @Test
    fun `password change required - any other hr route - is refused until the password is changed`() =
        testApplication {
            val owing = anHrUser(passwordHash = hasher.hash(PASSWORD), passwordChangeRequired = true)
            mountAuth(owing)

            val response = client.get("/api/auth/me") { authenticatedAs(owing) }

            // 409, not 403: the caller is entitled to this route and will be again the moment they
            // act. 403 would read as "not for you" and send them to an administrator.
            response.status shouldBe HttpStatusCode.Conflict
            response.bodyAsText() shouldContain "password_change_required"
        }

    @Test
    fun `password change required - the change-password route - is reachable`() = testApplication {
        // The exception that makes the gate usable at all. Without it the bootstrap account is
        // locked out of the one thing it exists to do.
        val owing = anHrUser(passwordHash = hasher.hash(PASSWORD), passwordChangeRequired = true)
        mountAuth(owing)

        val response = changePassword(owing, PASSWORD, "a-brand-new-password")

        response.status shouldBe HttpStatusCode.NoContent
    }

    @Test
    fun `password change required - a token with no pwd_change claim - is refused`() = testApplication {
        // Fail closed. A token minted by anything that does not set the claim must land in the gate
        // rather than bypass it, so forgetting the claim can never be a silent exemption.
        mountAuth(officer)

        val tokenWithoutClaim = com.auth0.jwt.JWT.create()
            .withIssuer(testJwtConfig().issuer)
            .withAudience(testJwtConfig().audience)
            .withSubject(officer.id.value)
            .withClaim("role", officer.role.name)
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(testJwtConfig().secret))

        val response = client.get("/api/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $tokenWithoutClaim")
        }

        response.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `password change - a wrong current password - is refused`() = testApplication {
        mountAuth(officer)

        changePassword(officer, "not-the-current-password", "a-brand-new-password")
            .status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `password change - a new password below the minimum - is refused with the field named`() =
        testApplication {
            mountAuth(officer)

            val response = changePassword(officer, PASSWORD, "short")

            response.status shouldBe HttpStatusCode.UnprocessableEntity
            response.bodyAsText() shouldContain "password.too_short"
        }

    @Test
    fun `password change - a successful change - lets the new password sign in and the old one not`() =
        testApplication {
            // The round trip that proves the change reached storage rather than only the response.
            mountAuth(officer)

            changePassword(officer, PASSWORD, "a-brand-new-password").status shouldBe
                HttpStatusCode.NoContent

            signIn(officer.email.value, "a-brand-new-password").status shouldBe HttpStatusCode.OK
            signIn(officer.email.value, PASSWORD).status shouldBe HttpStatusCode.Unauthorized
        }

    // ── The generated spec ──────────────────────────────────────────────────────────────────────

    @Test
    fun `api docs - the auth routes are mounted - appear in the generated spec with their contract`() =
        testApplication {
            application { rootModule() }

            val spec = client.get("/swagger/documentation.yaml").bodyAsText()

            spec shouldContain "/api/auth/login"
            spec shouldContain "/api/auth/change-password"
            // As in ERT-340: the path lines pass against an operation with no body type, so a DTO
            // name is what proves the schema was published. Both directions are pinned since
            // ERT-146 -- the request half was the half that was missing, and the half nobody
            // noticed, because this test only ever asked about the response.
            spec shouldContain "SignInResponse"
            spec shouldContain "SignInRequest"
            spec shouldContain "ChangePasswordRequest"
            spec shouldContain "hr-jwt"
        }

    @Test
    fun `api docs - a route that reads a request body - publishes it on the operation itself`() =
        testApplication {
            // A DTO name in `components.schemas` is not enough, and the test above would accept one:
            // what Swagger UI builds a body editor from is `requestBody` on the *operation*. Without
            // it, "Try it out" sends a POST carrying no body and no `Content-Type`, which this
            // application answers 415 -- from an endpoint that works perfectly (ERT-146).
            //
            // Navigated rather than string-matched. The spec is served as JSON despite the `.yaml`
            // path, and a `shouldContain` over the whole document cannot tell an operation's own
            // body from a component definition sitting elsewhere in the file -- which is exactly the
            // distinction this test exists to make.
            application { rootModule() }

            val spec = Json.parseToJsonElement(client.get("/swagger/documentation.yaml").bodyAsText())

            val ref = spec.jsonObject["paths"]!!.jsonObject["/api/auth/login"]!!
                .jsonObject["post"]!!.jsonObject["requestBody"]!!
                .jsonObject["content"]!!.jsonObject["application/json"]!!
                .jsonObject["schema"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content

            ref shouldBe "#/components/schemas/SignInRequest"
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The real security plugin, the real routes, and fakes for storage.
     *
     * Assembled by hand rather than through `rootModule()` so the verifier and `HrTokens` share one
     * config — `JwtConfig.fromEnvironment` generates a key per instance in dev, so a `rootModule()`
     * application cannot be handed a token it will accept.
     */
    private fun ApplicationTestBuilder.mountAuth(vararg seed: HrUser) {
        val users = FakeHrUserRepository(*seed)

        application {
            configureStatusPages()
            configureSerialization()
            configureSecurity(testJwtConfig())

            routing {
                // The REAL JwtIssuer, not FakeAccessTokenIssuer. A route test needs a token the
                // verifier accepts; the fake deliberately returns a readable placeholder so a *use
                // case* test does not depend on a signing algorithm. Both intents stay expressible
                // because the swap happens here rather than in the fake.
                signInRoutes(
                    AuthenticateHrUserUseCase(
                        users = users,
                        hasher = hasher,
                        tokens = JwtIssuer(testJwtConfig()),
                        audit = audit,
                        clock = issuingClock,
                        ids = FixedEntityIdGenerator(),
                        tracer = NoOpUseCaseTracer,
                    )
                )

                authenticate(HR_AUTH) {
                    accountRoutes(
                        ChangeHrPasswordUseCase(
                            users = users,
                            hasher = hasher,
                            audit = audit,
                            clock = FixedClock(),
                            ids = FixedEntityIdGenerator(),
                            tracer = NoOpUseCaseTracer,
                        ),
                        users,
                    )
                }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signIn(email: String, password: String) =
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password"}""")
        }

    private suspend fun ApplicationTestBuilder.changePassword(
        user: HrUser,
        current: String,
        new: String,
    ) = client.post("/api/auth/change-password") {
        authenticatedAs(user)
        contentType(ContentType.Application.Json)
        setBody("""{"currentPassword":"$current","newPassword":"$new"}""")
    }

    /** The value of one top-level string field, without pulling in a JSON parser for four uses. */
    private fun String.jsonString(field: String): String =
        substringAfter(""""$field":"""").substringBefore('"')

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
    }
}
