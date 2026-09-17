package com.pgsystem.employee.requirement.tracker

import io.kotest.assertions.withClue
import com.pgsystem.employee.requirement.tracker.testdata.withoutComments
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
        const val HR_ROUTE_DIR = "src/route/hr"
        const val PORTAL_DTO_DIR = "src/route/dto/portal"
        const val USE_CASE_DIR = "src/domain/usecase"
        const val PORTAL_ROUTE_DIR = "src/route/portal"
        const val PLUGIN_PACKAGE = "com.pgsystem.employee.requirement.tracker.plugin"
        const val DOMAIN_PORT_PACKAGE = "com.pgsystem.employee.requirement.tracker.domain.port"

        val HANDLER = Regex("""\b(get|post|put|patch|delete)\(\s*"([^"]+)"\s*\)\s*\{""")

        val GATES = listOf("hrUserOrRefuse", "hrAdminOrRefuse", "hrPrincipalOrRefuse")

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
