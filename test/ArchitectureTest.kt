package com.pgsystem.employee.requirement.tracker

import io.kotest.assertions.withClue
import com.pgsystem.employee.requirement.tracker.testdata.withoutComments
import com.pgsystem.employee.requirement.tracker.testdata.withoutStringLiterals
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The dependency rule and the write-mostly rule, executable.
 *
 * Clean Architecture's central constraint is that source dependencies point inward: the domain
 * knows nothing about Ktor, Exposed, Koin or coroutine dispatchers. Written in a document, that rule
 * survives until the first busy week. Written here, breaking it fails the build.
 *
 * `core/` is checked alongside `domain/` because domain models depend on it (`EmailAddress`,
 * `Clock`), so a framework type leaking into core would reach the domain by the back door.
 *
 * Why banning `Dispatchers` matters as much as banning Ktor: a use case that picks its own
 * dispatcher cannot be tested on a virtual-time scheduler, and it hides an I/O decision inside a
 * business rule. Dispatcher choice belongs in `DatabaseFactory`, at the edge.
 *
 * ### Guards are pure functions, driven two ways (ERT-170)
 *
 * Each rule below is a function over `(path, source)` pairs, run against **the real tree** — which is
 * what fails the build — and against **synthetic sources** that assert the rule itself. The second
 * half is not redundant. `src/route/dto/portal/` and `src/route/portal/` hold no files yet, so a
 * real-tree-only guard would pass without examining anything, and would go on passing after someone
 * broke the rule it is named for. The synthetic cases prove the guard can see a violation today, and
 * [GuardOutcome.Vacuous] makes "it examined nothing" a visible state rather than a silent pass.
 */
class ArchitectureTest {

    private val forbiddenInInnerLayers = listOf(
        "io.ktor",
        "org.slf4j",
        "org.jetbrains.exposed",
        "org.koin",
        "com.zaxxer.hikari",
        "kotlinx.serialization",
        "kotlinx.coroutines.Dispatchers",
        "javax.sql",
        "java.sql",
    )

    // ── The inner layers ────────────────────────────────────────────────────────────────────────

    @Test
    fun `identifier discipline - no domain or core file imports java util UUID - ids are value types`() {
        // Separate from forbiddenInInnerLayers because java.util in general is legitimate in core/.
        // Scoped to src/ only: MigrationTest uses UUID.randomUUID() to name a throwaway in-memory
        // database, which is test plumbing rather than an entity identifier.
        val violations = innerLayerFiles().flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .filter { it.startsWith("import java.util.UUID") }
                .map { "${file.relativeTo(projectDir)}: ${it.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    @Test
    fun `dependency rule - domain and core import no framework types - inner layers stay pure`() {
        val violations = innerLayerFiles().flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .filter { line -> forbiddenInInnerLayers.any { line.startsWith("import $it") } }
                .map { "${file.relativeTo(projectDir)}: ${it.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    @Test
    fun `dependency rule - the guard is pointed at real files - inner layers are non-empty`() {
        // A file walk that silently matches nothing would make the check above pass forever.
        (innerLayerFiles().size > 10) shouldBe true
    }

    @Test
    fun `portal safety - no domain port hands document bytes or URLs to the portal - storage stays HR-side`() {
        // PRD 8.6 / SEC-02: the portal reports status, never content. The only signed-URL entry
        // point lives on DocumentStorage, which portal use cases must not depend on.
        val storagePort = File(projectDir, "src/domain/port/DocumentStorage.kt")
        storagePort.exists() shouldBe true
        storagePort.readText().contains("HR-side only") shouldBe true
    }

    // ── The route layer ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `dependency rule - a route imports Exposed directly - the build fails`() {
        // Currently unguarded despite being in the layer table from the start: a route that queries
        // is a business decision reachable only through a handler. Non-vacuous today -- src/route/
        // holds real files -- so this is enforcement, not a placeholder.
        val real = guard(sourcesUnder("src/route")) { forbiddenImports(it, "org.jetbrains.exposed") }
        real shouldBe GuardOutcome.Checked(scanned = real.scannedOrZero(), violations = emptyList())
        (real.scannedOrZero() > 0) shouldBe true

        forbiddenImports(
            listOf(source("src/route/hr/HireRoutes.kt", "import org.jetbrains.exposed.v1.jdbc.selectAll")),
            "org.jetbrains.exposed",
        ).size shouldBe 1
    }

    @Test
    fun `dependency rule - a route imports plugin - the dependency runs plugin to route not the reverse`() {
        // ERT-145 made `plugin` depend on `route.mapper` and said the arrow never reverses. That was
        // recorded in a ticket and nowhere else, so a route author could only learn it by reading
        // one. It is the rule that decided where ERT-150 mounts /metrics.
        val real = guard(sourcesUnder("src/route")) { forbiddenImports(it, PLUGIN_PACKAGE) }
        real shouldBe GuardOutcome.Checked(scanned = real.scannedOrZero(), violations = emptyList())

        forbiddenImports(
            listOf(source("src/route/MetricsRoutes.kt", "import $PLUGIN_PACKAGE.HR_AUTH")),
            PLUGIN_PACKAGE,
        ).size shouldBe 1
    }

    // ── The write-mostly rule (PRD 8.6, SEC-02) ─────────────────────────────────────────────────

    @Test
    fun `write-mostly portal - a portal dto declares a file key or original filename - the build fails`() {
        // The realistic failure is not a deliberate preview feature. It is `mimeType` "for the icon"
        // and `originalFilename` "for the confirmation toast", both of which read as reasonable in
        // review. A filename leaks content as surely as the document does:
        // NBI_Clearance_DelaCruz_1998.pdf says everything.
        val dto = source(
            "src/route/dto/portal/ChecklistItemDto.kt",
            """
            @Serializable
            data class ChecklistItemDto(
                val requirementId: String,
                val status: String,
                val originalFilename: String,
                val mimeType: String,
            )
            """.trimIndent(),
        )

        val violations = documentFieldViolations(listOf(dto))

        violations.size shouldBe 2
        violations.any { it.contains("originalFilename") } shouldBe true
        violations.any { it.contains("mimeType") } shouldBe true
    }

    @Test
    fun `write-mostly portal - a portal dto names a preview url the ban list never anticipated - the build fails`() {
        // The named list would not have caught `previewUrl`, which is exactly the shape the next
        // well-meant addition takes. Any property ending in Url or Filename trips the guard, so the
        // rule is "no document handles", not "not these six spellings".
        val violations = documentFieldViolations(
            listOf(source("src/route/dto/portal/UploadAckDto.kt", "    val previewUrl: String,"))
        )

        violations.size shouldBe 1
    }

    @Test
    fun `write-mostly portal - a portal dto renames the field only on the wire - the build fails`() {
        // @SerialName is the obvious way around a property-name check, and it is the one that
        // actually ships the field.
        val violations = documentFieldViolations(
            listOf(
                source(
                    "src/route/dto/portal/UploadAckDto.kt",
                    """
                    @Serializable
                    data class UploadAckDto(@SerialName("original_filename") val received: String)
                    """.trimIndent(),
                )
            )
        )

        violations.size shouldBe 1
    }

    @Test
    fun `write-mostly portal - an HR dto carrying an original filename - does not trip the guard`() {
        // PRD 8.4 explicitly requires originalFilename, sizeBytes and mimeType on the HR side. A
        // blanket rule would block ERT-820, so the guard is scoped by path, not by field name alone.
        documentFieldViolations(
            listOf(
                source(
                    "src/route/dto/hr/SubmissionDto.kt",
                    "data class SubmissionDto(val originalFilename: String, val mimeType: String)",
                )
            )
        ).shouldBeEmpty()
    }

    @Test
    fun `write-mostly portal - a portal route imports DocumentStorage - the build fails`() {
        forbiddenImports(
            listOf(
                source(
                    "src/route/portal/ChecklistRoutes.kt",
                    "import com.pgsystem.employee.requirement.tracker.domain.port.DocumentStorage",
                )
            ),
            "$DOMAIN_PORT_PACKAGE.DocumentStorage",
        ).size shouldBe 1
    }

    @Test
    fun `write-mostly portal - every portal dto in the tree - declares no document field`() {
        // Two tests rather than one with two assertions: a failing first assertion would otherwise
        // hide whether the second rule is also broken, and these two fail for different reasons.
        documentFieldViolations(sourcesUnder(PORTAL_DTO_DIR)).shouldBeEmpty()
    }

    @Test
    fun `write-mostly portal - every portal route in the tree - reaches no document storage`() {
        forbiddenImports(sourcesUnder(PORTAL_ROUTE_DIR), "$DOMAIN_PORT_PACKAGE.DocumentStorage").shouldBeEmpty()
    }

    @Test
    fun `guard integrity - the portal dto directory is empty - the guard reports vacuous rather than passing`() {
        // THIS TEST IS A TRIPWIRE, NOT A BUG.
        //
        // The two assertions in the test above examine nothing today, because no portal DTO or
        // portal route exists yet -- so they pass, and would keep passing if the guard were broken.
        // Vacuity is asserted here so that state is visible rather than indistinguishable from
        // enforcement.
        //
        // WHEN THIS FAILS: the first portal DTO or portal route has landed and the guard is now
        // doing real work. That is the goal, not a regression. Change the expectation below to
        // GuardOutcome.Checked for whichever directory is now populated -- do not delete the test,
        // and do not delete the assertions it is guarding.
        val message = "A portal source now exists: flip this expectation to Checked. See the comment above."

        withClue(message) {
            guard(sourcesUnder(PORTAL_DTO_DIR)) { documentFieldViolations(it) } shouldBe GuardOutcome.Vacuous
            guard(sourcesUnder(PORTAL_ROUTE_DIR)) {
                forbiddenImports(it, "$DOMAIN_PORT_PACKAGE.DocumentStorage")
            } shouldBe GuardOutcome.Vacuous
        }
    }

    @Test
    fun `guard integrity - a populated directory - reports checked so the tripwire above can fire`() {
        // Proves Vacuous and Checked are actually distinguishable. Without this, a guard that
        // returned Vacuous unconditionally would satisfy the tripwire forever.
        guard(listOf(source("src/route/dto/portal/Any.kt", "val status: String"))) {
            documentFieldViolations(it)
        } shouldBe GuardOutcome.Checked(scanned = 1, violations = emptyList())
    }

    // ── Use case tracing (ERT-195) ──────────────────────────────────────────────────────────────

    @Test
    fun `dependency rule - a use case logs directly instead of through the port - the build fails`() {
        // org.slf4j joined the ban list with this rule. A use case holding its own logger has no
        // DomainResult to read, so it would log by hand -- which is where an argument gets written
        // to a file. Routing it through UseCaseTracer means the outcome is all there is to say.
        forbiddenImports(
            listOf(source("src/domain/usecase/CreateHireUseCase.kt", "import org.slf4j.LoggerFactory")),
            "org.slf4j",
        ).size shouldBe 1
    }

    @Test
    fun `use case tracing - every use case in the tree - is traced`() {
        untracedUseCases(useCaseSources()).shouldBeEmpty()
    }

    @Test
    fun `use case tracing - a use case that takes no tracer - the build fails`() {
        val violations = untracedUseCases(
            listOf(
                source(
                    "src/domain/usecase/CreateHireUseCase.kt",
                    """
                    class CreateHireUseCase(private val employees: EmployeeRepository) {
                        suspend operator fun invoke(command: CreateHire): DomainResult<Employee> = execute(command)
                    }
                    """.trimIndent(),
                )
            )
        )

        violations.size shouldBe 2
        violations.any { it.contains("UseCaseTracer") } shouldBe true
        violations.any { it.contains("trace") } shouldBe true
    }

    @Test
    fun `use case tracing - a use case tracing under a copied name - the build fails`() {
        // The name is a string literal, so the realistic failure is a file copied as a starting
        // point: the new use case compiles, runs, and reports every invocation under the old name.
        val violations = untracedUseCases(
            listOf(
                source(
                    "src/domain/usecase/RevokeLinkUseCase.kt",
                    """
                    class RevokeLinkUseCase(private val tracer: UseCaseTracer) {
                        suspend operator fun invoke(id: EntityId): DomainResult<Unit> =
                            tracer.trace("ExtendLinkUseCase") { execute(id) }
                    }
                    """.trimIndent(),
                )
            )
        )

        violations.size shouldBe 1
        violations.single() shouldContain "ExtendLinkUseCase"
    }

    @Test
    fun `use case tracing - a use case wired to the port under its own name - passes`() {
        untracedUseCases(
            listOf(
                source(
                    "src/domain/usecase/CreateHireUseCase.kt",
                    """
                    class CreateHireUseCase(
                        private val employees: EmployeeRepository,
                        private val tracer: UseCaseTracer,
                    ) {
                        suspend operator fun invoke(command: CreateHire): DomainResult<Employee> =
                            tracer.trace("CreateHireUseCase") { execute(command) }
                    }
                    """.trimIndent(),
                )
            )
        ).shouldBeEmpty()
    }

    @Test
    fun `guard integrity - the use case directory is populated - the guard reports checked`() {
        // THE TRIPWIRE FIRED, AS DESIGNED. ERT-195 asserted Vacuous here and said, in the comment
        // this replaces: "the day ERT-430 lands the first use case it fails -- that is the signal,
        // not a regression. Flip the expectation to Checked; do not delete the test."
        //
        // ERT-190 got there first, with six use cases. Flipped rather than deleted, so the assertion
        // above is now known to be examining real files instead of silently examining none.
        val outcome = guard(useCaseSources()) { untracedUseCases(it) }

        withClue("The use case guard must be examining real files, not an empty directory.") {
            outcome shouldBe GuardOutcome.Checked(scanned = outcome.scannedOrZero(), violations = emptyList())
            (outcome.scannedOrZero() > 0) shouldBe true
        }
    }

    @Test
    fun `guard integrity - a file beside a use case that is not one - does not make the guard look enforcing`() {
        // The filter runs before guard(), not inside the check. Otherwise the day a command or a
        // mapper lands in src/domain/usecase/ with no use case beside it, guard() would report
        // Checked(scanned = 1) -- an enforcing signal for a rule that examined nothing -- and the
        // tripwire above would stop firing without anybody noticing. A deliberate divergence from
        // documentFieldViolations, which scopes by path inside the check instead.
        useCaseSourcesIn(listOf(source("src/domain/usecase/CreateHireCommand.kt", "data class CreateHire(val x: String)")))
            .shouldBeEmpty()
    }

    // ── Guards ──────────────────────────────────────────────────────────────────────────────────

    private data class SourceFile(val path: String, val text: String)

    private sealed interface GuardOutcome {
        /** The walk matched no files. The rule was not exercised — do not read this as a pass. */
        data object Vacuous : GuardOutcome
        data class Checked(val scanned: Int, val violations: List<String>) : GuardOutcome
    }

    private fun GuardOutcome.scannedOrZero(): Int = (this as? GuardOutcome.Checked)?.scanned ?: 0

    private fun guard(files: List<SourceFile>, check: (List<SourceFile>) -> List<String>): GuardOutcome =
        if (files.isEmpty()) GuardOutcome.Vacuous
        else GuardOutcome.Checked(scanned = files.size, violations = check(files))

    /**
     * Property names a **portal** DTO may not declare (PRD 8.6, SEC-02).
     *
     * Matched on `val`/`var` declarations and on `@SerialName` values, so renaming the field only on
     * the wire does not slip past. Exact names plus two suffix rules: the suffixes are what catch
     * the next well-meant addition, which will be called `previewUrl` or `storedFilename` rather
     * than anything on a list written today. Exact rather than substring matching, so `urlPattern`
     * is not a violation.
     *
     * **The path scope is part of the rule, not of the call site.** PRD 8.4 explicitly requires
     * `originalFilename`, `sizeBytes` and `mimeType` on the HR side, so a blanket ban would block
     * ERT-820. Filtering here rather than in the caller means a future call cannot widen the rule to
     * the HR tree by passing it a broader file list — which is exactly what the first draft of this
     * guard did, and what the HR test below caught.
     */
    private fun documentFieldViolations(files: List<SourceFile>): List<String> =
        files.filter { it.path.replace('\\', '/').contains(PORTAL_DTO_DIR) }.flatMap { file ->
            (DECLARATION.findAll(file.text) + SERIAL_NAME.findAll(file.text))
                .map { it.groupValues[1] }
                .filter { name ->
                    name.lowercase() in BANNED_FIELDS ||
                        BANNED_SUFFIXES.any { name.length > it.length && name.endsWith(it, ignoreCase = true) }
                }
                .map { "${file.path}: declares `$it`, which names document content (PRD 8.6)" }
                .toList()
        }

    /**
     * Every use case routes its invocation through [UseCaseTracer], under its own name (ERT-195).
     *
     * Unlike every other guard here this one requires a string rather than banning one, and that is
     * strictly weaker: `// TODO: add UseCaseTracer .trace(` satisfies it, and no text rule can tell
     * a call from a comment. It is still worth having -- the realistic failure is a use case written
     * without the tracer at all, not one written to defeat the check -- but do not read a pass here
     * as proof that tracing happens, only that it was not forgotten outright.
     *
     * The third rule is the one that earns its keep: the traced name is a string literal, so copying
     * a use case as a starting point produces something that compiles, runs, and reports every
     * invocation under the name of the file it came from.
     */
    private fun untracedUseCases(files: List<SourceFile>): List<String> =
        files.flatMap { file ->
            val expected = file.path.substringAfterLast('/').removeSuffix(".kt")
            val violations = mutableListOf<String>()

            if (!TRACER_PARAMETER.containsMatchIn(file.text)) {
                violations += "${file.path}: takes no `: UseCaseTracer` constructor parameter (ERT-195)"
            }

            val tracedAs = TRACE_CALL.findAll(file.text).map { it.groupValues[1] }.toList()
            when {
                tracedAs.isEmpty() ->
                    violations += "${file.path}: never calls trace(\"$expected\") (ERT-195)"
                expected !in tracedAs ->
                    violations += "${file.path}: traces as `${tracedAs.first()}` rather than `$expected` (ERT-195)"
            }

            violations
        }

    /**
     * The use cases in the real tree.
     *
     * Filtered to `*UseCase.kt` **before** [guard] sees the list, so vacuity is measured over the
     * files actually being checked. See the guard-integrity test for what goes wrong otherwise.
     */
    private fun useCaseSources(): List<SourceFile> = useCaseSourcesIn(sourcesUnder(USE_CASE_DIR))

    private fun useCaseSourcesIn(files: List<SourceFile>): List<SourceFile> =
        files.filter { it.path.substringAfterLast('/').endsWith("UseCase.kt") }

    private fun forbiddenImports(files: List<SourceFile>, prefix: String): List<String> =
        files.flatMap { file ->
            file.text.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("import $prefix") }
                .map { "${file.path}: $it" }
                .toList()
        }

    // ── The HR password-change gate ─────────────────────────────────────────────────────────────

    /**
     * Every HR handler opens with a gate, and the build fails on one that does not (ERT-1245).
     *
     * `authenticate(HR_AUTH)` in `Routing.kt` proves *who* the caller is. It says nothing about
     * whether they still owe a password change, and that second gate is **per-handler** — so three
     * routes shipped without it, readable by the bootstrap admin before first sign-in and by anyone
     * whose password an admin had just reset.
     *
     * The two-line fix is not the deliverable; this is. The comment in `ReferenceRoutes.kt` that
     * claimed the gate was "applied once, around every HR route" is how the omission spread from one
     * file to the next, and a comment cannot stop the fourth route. A guard can.
     *
     * [PUBLIC_HR_ROUTES] is an allow-list rather than an inferred rule, because "this endpoint is
     * deliberately unauthenticated" is a decision that should cost someone an edit here and a
     * reviewer's attention — which is exactly what an inferred rule would give away.
     */
    @Test
    fun `hr routes - every handler under route hr - opens with an authorisation gate`() {
        val ungated = ungatedHandlersIn(sourcesUnder(HR_ROUTE_DIR))

        assertTrue(
            ungated.isEmpty(),
            "These HR handlers do not call one of $GATES before touching a repository. " +
                "authenticate(HR_AUTH) is not this gate -- it proves identity, not standing:\n" +
                ungated.joinToString("\n"),
        )
    }

    @Test
    fun `hr routes - a handler that skips the gate - the build fails`() {
        // The guard's own tripwire. Without it a refactor that broke the detection would leave the
        // test above passing over nothing, which is the failure mode this file exists to avoid.
        val offender = source(
            "src/route/hr/PayrollRoutes.kt",
            """
            fun Route.payrollRoutes(payroll: PayrollRepository) {
                get("/api/payroll") {
                    call.respondOk(payroll.findAll())
                }
            }
            """.trimIndent(),
        )

        ungatedHandlersIn(listOf(offender)) shouldHaveSize 1
    }

    @Test
    fun `hr routes - a handler that opens with the gate - passes`() {
        val gated = source(
            "src/route/hr/PayrollRoutes.kt",
            """
            fun Route.payrollRoutes(payroll: PayrollRepository) {
                get("/api/payroll") {
                    hrUserOrRefuse() ?: return@get
                    call.respondOk(payroll.findAll())
                }
            }
            """.trimIndent(),
        )

        ungatedHandlersIn(listOf(gated)).shouldBeEmpty()
    }

    @Test
    fun `guard integrity - the hr route directory is populated - the guard reports checked`() {
        // Same tripwire as the use-case guard above: a guard that walks an empty directory passes
        // vacuously, and reports success for having checked nothing.
        val handlers = sourcesUnder(HR_ROUTE_DIR).sumOf { countHandlers(it) }

        assertTrue(handlers >= 8, "expected the HR route surface to be non-trivial; found $handlers handlers")
    }

    /**
     * Every handler that reads a body publishes its schema, and the build fails on one that does not
     * (ERT-146).
     *
     * `OpenApiDocSource.Routing` reads the route tree and never the handler body, so it infers a
     * request body no more than ERT-145 found it infers a response one. Five POST routes shipped with
     * `responses { }` and no `requestBody { }`: Swagger UI rendered no body editor for any of them,
     * its "Try it out" sent a POST with no payload and no `Content-Type`, and `POST /api/auth/login`
     * answered 415 from an endpoint that was working perfectly.
     *
     * The five `describe { }` blocks are not the deliverable; this is. `api-contract.md` had carried
     * the response half of the rule since ERT-145 and simply never had the request half written
     * down — and a rule that exists only in prose is what let the same omission repeat five times.
     */
    @Test
    fun `documented request bodies - every handler that reads a body - publishes its request schema`() {
        val undocumented = undocumentedRequestBodiesIn(sourcesUnder(ROUTE_DIR))

        assertTrue(
            undocumented.isEmpty(),
            "These handlers read a request body but publish no requestBody schema. Swagger UI shows " +
                "no body editor for them, so \"Try it out\" posts nothing and the route answers 415:\n" +
                undocumented.joinToString("\n"),
        )
    }

    @Test
    fun `documented request bodies - a handler that receives without describing one - the build fails`() {
        // The guard's own tripwire, as above: without it, a refactor that broke the detection would
        // leave the test above passing over nothing.
        val offender = source(
            "src/route/hr/PayrollRoutes.kt",
            """
            fun Route.payrollRoutes(payroll: PayrollRepository) {
                post("/api/payroll") {
                    val body = call.receive<PayrollRequest>()
                    call.respondOk(payroll.save(body))
                }.describe {
                    summary = "Record payroll"
                    responses { response(200) { schema = jsonSchema<PayrollDto>() } }
                }
            }
            """.trimIndent(),
        )

        undocumentedRequestBodiesIn(listOf(offender)) shouldHaveSize 1
    }

    @Test
    fun `documented request bodies - a handler that describes its request body - passes`() {
        val documented = source(
            "src/route/hr/PayrollRoutes.kt",
            """
            fun Route.payrollRoutes(payroll: PayrollRepository) {
                post("/api/payroll") {
                    val body = call.receive<PayrollRequest>()
                    call.respondOk(payroll.save(body))
                }.describe {
                    summary = "Record payroll"
                    requestBody { schema = jsonSchema<PayrollRequest>() }
                    responses { response(200) { schema = jsonSchema<PayrollDto>() } }
                }
            }
            """.trimIndent(),
        )

        undocumentedRequestBodiesIn(listOf(documented)).shouldBeEmpty()
    }

    @Test
    fun `guard integrity - the route tree holds handlers that read a body - the guard reports checked`() {
        // A guard that finds no `receive` at all passes vacuously. Five routes read a body today.
        val receiving = sourcesUnder(ROUTE_DIR).sumOf { countBodyReadingHandlers(it) }

        assertTrue(receiving >= 5, "expected the route tree to read request bodies; found $receiving handlers")
    }

    // ── Port coverage ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `port coverage - every domain port - has a fake`() {
        // The whole correctness argument for the use-case suite is that a fake and its adapter
        // agree. A port with NO fake is the case before that argument can even be made, and it has
        // happened twice: `ReferenceDataRepository` (C9), then `HrUserRepository` and
        // `AccessTokenIssuer`, which ERT-190 added after ERT-210 wrote the criterion.
        val real = guard(sourcesUnder(DOMAIN_PORT_DIR)) { portsWithoutFakes(it) }

        real shouldBe GuardOutcome.Checked(scanned = real.scannedOrZero(), violations = emptyList())
        (real.scannedOrZero() > 0) shouldBe true
    }

    @Test
    fun `port coverage - a port with no fake - the build fails`() {
        portsWithoutFakes(
            listOf(source("src/domain/port/Repositories.kt", "interface AppointmentRepository {")),
        ).size shouldBe 1
    }

    @Test
    fun `port coverage - a port whose fake exists - does not trip the guard`() {
        // Paired with the test above, so "the guard fires" and "the guard fires at everything" are
        // distinguishable. `AuditLog` has `FakeAuditLog.kt` beside the others.
        portsWithoutFakes(
            listOf(source("src/domain/port/AuditLog.kt", "interface AuditLog {")),
        ).shouldBeEmpty()
    }

    @Test
    fun `port coverage - every implementation of a port in the test tree - lives in the fake directory`() {
        // HAR-20: the guard above asserts a port HAS a fake. Nothing asserted it has only ONE, and
        // `ReferenceDataRepository` had two -- the shared fake, which sorts both list reads by name
        // because the port says so, and a private one inside `ReferenceRoutesTest` that returned the
        // list as given. `ReferenceRoutesTest` then asserted an ordering against the fake that did
        // not order, so it passed whether or not ordering survived the route.
        //
        // That is HAR-01's defect -- two implementations of one rule drifting -- inside the ticket
        // that closed HAR-01. One fake per port is what `test/contract/` can actually hold to its
        // adapter; a second one is invisible to it by construction.
        val real = guard(sourcesUnder(TEST_DIR)) { portImplementorsOutsideFakes(it) }

        real shouldBe GuardOutcome.Checked(scanned = real.scannedOrZero(), violations = emptyList())
        (real.scannedOrZero() > 0) shouldBe true
    }

    @Test
    fun `port coverage - a second fake for a port outside the fake directory - the build fails`() {
        portImplementorsOutsideFakes(
            listOf(
                source(
                    "test/route/hr/ReferenceRoutesTest.kt",
                    "    private class FakeReferenceData(\n" +
                        "        private val departments: List<Department> = emptyList(),\n" +
                        "    ) : ReferenceDataRepository {\n    }\n",
                ),
            ),
        ).size shouldBe 1
    }

    @Test
    fun `port coverage - a contract suite naming a port it does not implement - does not trip the guard`() {
        // The negative control that matters, because it is the one a careless pattern breaks on.
        // Every one of the 13 bases in `test/contract/` declares `protected abstract val x: SomePort`
        // INSIDE its body, so a guard that scanned from `class` to the next port name would report
        // all 13 and get loosened until it matched nothing. Only the supertype list counts.
        portImplementorsOutsideFakes(
            listOf(
                source(
                    "test/contract/ReferenceDataRepositoryContract.kt",
                    "abstract class ReferenceDataRepositoryContract {\n" +
                        "    protected abstract val reference: ReferenceDataRepository\n}\n",
                ),
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `guard integrity - the fake directory implements ports - the guard reports checked`() {
        // The other half of the sweep above: it passes trivially if `portImplementorsOutsideFakes`
        // has stopped recognising a supertype at all. Point it at the fake directory, which is the
        // one place a port SHOULD be implemented, and require that it still sees them there.
        val fakes = portImplementorsOutsideFakes(sourcesUnder(FAKE_DIR), excluded = emptySet())

        assertTrue(fakes.size >= 13, "expected the fake directory to implement every port; found ${fakes.size}")
    }

    @Test
    fun `test discovery - every class holding a test - is named so the scan discovers it`() {
        // Amper runs the suite with `--scan-class-path` and NO `--include-classname`, so JUnit's own
        // default applies: `^(Test.*|.+[.$]Test.*|.*Tests?)$`. A concrete class named
        // `EmployeeRepositoryContractSuite` is therefore never instantiated, contributes zero tests,
        // and reports nothing — `--fail-if-no-tests` and CI's `require_tests` are both whole-run
        // floors and neither sees one silently empty class.
        //
        // That is this project's own vacuity failure with a new door, and it is the specific risk of
        // putting shared tests on an abstract base: the base is correctly skipped, and a
        // misnamed subclass is skipped identically.
        //
        // Swept over the WHOLE suite rather than over `test/contract/` alone. The trap is not
        // particular to contract suites -- it is particular to this toolchain -- and a guard aimed
        // at one directory of nine would have let the next instance land anywhere else.
        val misnamed = sourcesUnder(TEST_DIR)
            .flatMap { file -> classesHoldingATestIn(file) }
            .filterNot { it.substringAfterLast(": ").matches(JUNIT_CLASS_NAME) }

        misnamed.shouldBeEmpty()
    }

    @Test
    fun `guard integrity - the test tree holds classes that hold tests - the guard reports checked`() {
        // A walk that matched no class would make the check above pass forever. The suite holds
        // well over a hundred test classes; the floor is deliberately far below that.
        val holders = sourcesUnder(TEST_DIR).sumOf { classesHoldingATestIn(it).size }

        assertTrue(holders >= 50, "expected classes holding tests; found $holders")
    }

    @Test
    fun `test discovery - a class holding a test but named for the scan to skip - the build fails`() {
        classesHoldingATestIn(
            source("test/contract/EmployeeContractSuite.kt", "class EmployeeContractSuite {\n    @Test\n    fun x() {}\n}"),
        ).filterNot { it.substringAfterLast(": ").matches(JUNIT_CLASS_NAME) }.size shouldBe 1
    }

    @Test
    fun `guard integrity - the port directory is populated - the guard reports checked`() {
        // The count C9 and C30 each corrected in prose, held by an assertion instead. ERT-210's
        // criterion said ten; `Repositories.kt` alone declares eight, and there are thirteen in all.
        // Moving this number is a decision, and it belongs in a diff rather than in a third recount.
        declaredPorts(sourcesUnder(DOMAIN_PORT_DIR)) shouldHaveSize 13

        // The other half: the fake directory has to be readable from here, or every port would look
        // covered by an empty listing exactly as easily as by a full one.
        (fakeFileNames().size > 13) shouldBe true
    }

    /**
     * Handlers whose body does not begin with a gate call.
     *
     * Deliberately crude: it looks at the first non-blank code line of each handler body rather than
     * parsing Kotlin. A gate that is not the first statement is a finding in itself — anything before
     * it runs unauthorised, and the one route entitled to a weaker gate says so at its first line.
     */
    private fun ungatedHandlersIn(files: List<SourceFile>): List<String> =
        files.flatMap { file ->
            val lines = file.text.withoutComments().lines()
            lines.withIndex().mapNotNull { (index, line) ->
                val match = HANDLER.find(line) ?: return@mapNotNull null
                val path = match.groupValues[2]
                if (path in PUBLIC_HR_ROUTES) return@mapNotNull null
                val firstStatement = lines.drop(index + 1).firstOrNull { it.isNotBlank() }.orEmpty()
                if (GATES.any { it in firstStatement }) null else "${file.path}: ${match.groupValues[1]} $path"
            }
        }

    private fun countHandlers(file: SourceFile): Int =
        HANDLER.findAll(file.text.withoutComments()).count()

    /**
     * Handlers that read a request body without publishing its schema (ERT-146).
     *
     * Crude by the same standard as the gate guard, and in the same way: a handler's span runs from
     * its own `post("…") {` to the next handler's, which covers the body and the `.describe { }`
     * chained onto it. A span that calls `receive` and never says `requestBody` is the finding.
     * Comments are stripped first, so prose about `requestBody` cannot satisfy the guard.
     *
     * [RECEIVES_BODY] matches `receive<T>`, `receiveNullable<T>` and `receiveMultipart()` only.
     * `receiveText` and `receiveParameters` want a *different* content type declared and have no
     * call site in this project yet; inventing the rule for them here, in one file, would leave the
     * first real caller to re-invent it — the reasoning C26 already records.
     */
    private fun undocumentedRequestBodiesIn(files: List<SourceFile>): List<String> =
        files.flatMap { file ->
            val text = file.text.withoutComments()
            val handlers = HANDLER.findAll(text).toList()
            handlers.mapIndexedNotNull { index, match ->
                val end = handlers.getOrNull(index + 1)?.range?.first ?: text.length
                val span = text.substring(match.range.first, end)
                if (RECEIVES_BODY.containsMatchIn(span) && "requestBody" !in span) {
                    "${file.path}: ${match.groupValues[1]} ${match.groupValues[2]}"
                } else {
                    null
                }
            }
        }

    private fun countBodyReadingHandlers(file: SourceFile): Int {
        val text = file.text.withoutComments()
        val handlers = HANDLER.findAll(text).toList()
        return handlers.withIndex().count { (index, match) ->
            val end = handlers.getOrNull(index + 1)?.range?.first ?: text.length
            RECEIVES_BODY.containsMatchIn(text.substring(match.range.first, end))
        }
    }

    /**
     * Ports declared under `src/domain/port/` with no `Fake<Name>.kt` beside the others.
     *
     * Parses interface *declarations* rather than file names, because `Repositories.kt` declares
     * eight of the thirteen in one file — a guard keyed on files would see one port there and call
     * the other seven covered.
     *
     * `fakes` is a parameter so a synthetic port can be checked against the real directory listing
     * without writing a file, which is how the two tests above tell "the guard fires" apart from
     * "the guard fires at everything".
     */
    private fun portsWithoutFakes(
        files: List<SourceFile>,
        fakes: Set<String> = fakeFileNames(),
    ): List<String> =
        declaredPorts(files)
            .filterNot { "Fake$it.kt" in fakes }
            .map { "$it has no Fake$it.kt under $FAKE_DIR" }

    /**
     * Classes implementing a domain port from outside `test/testdata/fake/` (HAR-20, ERT-250).
     *
     * The companion to [portsWithoutFakes]: that one says every port has a fake, this one says it has
     * only one. Port names come from [declaredPorts] over the real port directory, so the two guards
     * cannot disagree about what counts as a port.
     *
     * **Only the supertype list counts, and that is the whole difficulty.** Every contract base in
     * `test/contract/` declares `protected abstract val x: SomePort` in its body, so a pattern that
     * ran from `class` to the next port name would report all thirteen of them. The supertype list is
     * the span after the type parameters and the constructor parameter list -- both of which may
     * themselves contain a `:` -- and before the class body, so it is walked with balanced delimiters
     * rather than matched.
     *
     * **The declaration pattern is deliberately not anchored to column zero**, unlike [CONCRETE_CLASS]
     * and [PORT_INTERFACE]. HAR-20's offender was a `private class` nested inside a test class; an
     * anchored pattern misses exactly the case this guard exists for. Anonymous `object : Port { }` is
     * matched too, since it is the obvious way to reintroduce a local implementation without naming it.
     *
     * String literals are stripped along with comments, because this file carries its own synthetic
     * fixtures as strings and would otherwise report its own negative controls.
     *
     * `excluded` and `ports` are parameters so the guard can be aimed at the fake directory itself,
     * which is how the anti-vacuity test tells "no violations" apart from "no longer recognises a
     * supertype".
     */
    private fun portImplementorsOutsideFakes(
        files: List<SourceFile>,
        ports: Set<String> = declaredPorts(sourcesUnder(DOMAIN_PORT_DIR)).toSet(),
        excluded: Set<String> = setOf(FAKE_DIR),
    ): List<String> =
        files
            .filterNot { file -> excluded.any { file.path.replace('\\', '/').startsWith(it) } }
            .flatMap { file ->
                val text = file.text.withoutStringLiterals().withoutComments()
                TYPE_DECLARATION.findAll(text).mapNotNull { match ->
                    val name = match.groupValues[1].ifEmpty { "an anonymous object" }
                    supertypesOf(text, match.range.last + 1)
                        .firstOrNull { it in ports }
                        ?.let { "${file.path}: $name implements $it outside $FAKE_DIR" }
                }
            }

    /**
     * The supertypes named by a declaration whose name ends at [from].
     *
     * Skips a type-parameter list and a constructor parameter list, then reads to the class body.
     * Splitting the list on commas is not depth-aware, which is sufficient here: a generic supertype
     * splits into fragments, and a fragment is not a port name, so it cannot produce a false positive.
     */
    private fun supertypesOf(text: String, from: Int): List<String> {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                ' ', '\t', '\n', '\r' -> i++
                '<' -> i = skipBalanced(text, i, '<', '>') ?: return emptyList()
                '(' -> i = skipBalanced(text, i, '(', ')') ?: return emptyList()
                else -> break
            }
        }
        if (i >= text.length || text[i] != ':') return emptyList()

        var depth = 0
        var end = text.length
        for (index in i + 1 until text.length) {
            when (text[index]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '{' -> if (depth <= 0) { end = index; break }
            }
        }
        return text.substring(i + 1, end)
            .split(',')
            .map { entry -> entry.trim().takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' } }
            .map { it.substringAfterLast('.') }
            .filter { it.isNotEmpty() }
    }

    /** The index just past the delimiter that closes the one opening at [start], or null if unbalanced. */
    private fun skipBalanced(text: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return null
    }

    /**
     * Concrete classes in one file that actually carry a `@Test`.
     *
     * The body of each class is taken as the span up to the next top-level `class`, which is crude
     * and sufficient: a helper class with no test in it is not a discovery risk, and an abstract
     * base is never instantiated by JUnit whatever it is called.
     */
    private fun classesHoldingATestIn(file: SourceFile): List<String> {
        val text = file.text.withoutComments()
        val classes = CONCRETE_CLASS.findAll(text).toList()
        return classes.mapIndexedNotNull { index, match ->
            val end = classes.getOrNull(index + 1)?.range?.first ?: text.length
            "${file.path}: ${match.groupValues[1]}"
                .takeIf { "@Test" in text.substring(match.range.first, end) }
        }
    }

    private fun declaredPorts(files: List<SourceFile>): List<String> =
        files.flatMap { file ->
            PORT_INTERFACE.findAll(file.text.withoutComments()).map { it.groupValues[1] }
        }

    private fun fakeFileNames(): Set<String> =
        File(projectDir, FAKE_DIR)
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .map { it.name }
            .toSet()

    private fun source(path: String, text: String) = SourceFile(path, text)

    private fun sourcesUnder(dir: String): List<SourceFile> =
        File(projectDir, dir)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { SourceFile(it.relativeTo(projectDir).path, it.readText()) }
            .toList()

    private val projectDir: File
        get() = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "module.yaml").exists() }

    private fun innerLayerFiles(): List<File> =
        listOf("src/domain", "src/core")
            .map { File(projectDir, it) }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }

    private companion object {
        const val ROUTE_DIR = "src/route"
        const val HR_ROUTE_DIR = "src/route/hr"
        const val PORTAL_DTO_DIR = "src/route/dto/portal"
        const val USE_CASE_DIR = "src/domain/usecase"
        const val PORTAL_ROUTE_DIR = "src/route/portal"
        const val PLUGIN_PACKAGE = "com.pgsystem.employee.requirement.tracker.plugin"
        const val DOMAIN_PORT_PACKAGE = "com.pgsystem.employee.requirement.tracker.domain.port"
        const val DOMAIN_PORT_DIR = "src/domain/port"
        const val FAKE_DIR = "test/testdata/fake"
        const val TEST_DIR = "test"

        /** A top-level `class Name` that is not abstract — the ones JUnit tries to instantiate. */
        val CONCRETE_CLASS = Regex("""^class\s+([A-Z][A-Za-z0-9_]*)""", RegexOption.MULTILINE)

        /**
         * Any `class` or `object` declaration, at any indentation, plus an anonymous `object :`.
         *
         * Not anchored, unlike [CONCRETE_CLASS] — the declaration this exists to catch was a
         * `private class` nested inside a test class, and anchoring is what let it hide. Group 1 is
         * the name, and is empty for the anonymous form.
         */
        val TYPE_DECLARATION = Regex("""\b(?:class|object)\s+([A-Z][A-Za-z0-9_]*)|\bobject\s*(?=:)""")

        /** JUnit's own default, copied from `TestDiscoveryOptions`. */
        val JUNIT_CLASS_NAME = Regex("""^(Test.*|.+[.$]Test.*|.*Tests?)$""")

        /**
         * A top-level `interface Name` declaration, anchored at column zero.
         *
         * Anchored rather than free, because `\binterface` also matches `sealed interface
         * DeliveryResult` in `Notifier.kt` — a result type, not a port, and it has no fake for the
         * same reason `TokenGrant` does not. A port is a plain top-level interface; anything
         * indented is nested and anything prefixed is sealed or private.
         */
        val PORT_INTERFACE = Regex("""^interface\s+([A-Z][A-Za-z0-9_]*)""", RegexOption.MULTILINE)

        val HANDLER = Regex("""\b(get|post|put|patch|delete)\(\s*"([^"]+)"\s*\)\s*\{""")

        val GATES = listOf("hrUserOrRefuse", "hrAdminOrRefuse", "hrPrincipalOrRefuse")

        val RECEIVES_BODY = Regex("""call\.receive(?:Nullable)?\s*<|call\.receiveMultipart\s*\(""")

        /**
         * HR paths that are deliberately reachable without a gate.
         *
         * Sign-in is the only one, and it must be: it is how a caller obtains the credential every
         * other route requires. Adding to this list is the point — it is a decision that should be
         * visible in a diff rather than inferred from an absent call.
         */
        val PUBLIC_HR_ROUTES = setOf("/api/auth/login")

        val DECLARATION = Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")
        val TRACER_PARAMETER = Regex(""":\s*UseCaseTracer\b""")
        val TRACE_CALL = Regex("""\.trace\s*\(\s*"([^"]+)"""")
        val SERIAL_NAME = Regex("""@SerialName\s*\(\s*"([^"]+)"\s*\)""")

        val BANNED_FIELDS = setOf(
            "filekey", "file_key",
            "originalfilename", "original_filename",
            "url",
            "downloadurl", "download_url",
            "signedurl", "signed_url",
            "mimetype", "mime_type",
        )

        /** `previewUrl`, `thumbnailUrl`, `storedFilename` — the spellings a ban list never predicts. */
        val BANNED_SUFFIXES = listOf("url", "filename", "_url", "_filename")
    }
}
