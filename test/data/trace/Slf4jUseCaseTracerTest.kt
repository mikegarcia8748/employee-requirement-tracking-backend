package com.pgsystem.employee.requirement.tracker.data.trace

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.error.asErr
import com.pgsystem.employee.requirement.tracker.core.error.asOk
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.slf4j.LoggerFactory

/**
 * What a trace line is allowed to say.
 *
 * Most of these are not tests of formatting. They are tests that the line carries no argument. The
 * realistic regression is not someone deciding to log a PIN; it is someone widening `error.code` to
 * `$error` to make the line more useful, which interpolates `AppError.Validation.detail` — a field
 * holding whatever the caller typed.
 */
class Slf4jUseCaseTracerTest {

    @Test
    fun `trace line - a use case that succeeds - names the use case and reports ok`() {
        val lines = capturingTraceLines {
            Slf4jUseCaseTracer().trace("CreateHireUseCase") { "anything".asOk() }
        }

        lines.size shouldBe 1
        lines.single() shouldContain "CreateHireUseCase"
        lines.single() shouldContain "ok"
    }

    @Test
    fun `trace line - a use case that fails - reports the error code and not the error detail`() {
        // Validation carries the offending input in `detail`. The code names the rule; the detail
        // names the person. Only the first belongs in a log (PRD 12).
        val error = AppError.Validation(code = "email_invalid", field = "email", detail = "jose@example.com")

        val lines = capturingTraceLines {
            Slf4jUseCaseTracer().trace("CreateHireUseCase") { error.asErr() }
        }

        lines.single() shouldContain "email_invalid"
        lines.single() shouldNotContain "jose@example.com"
    }

    @Test
    fun `trace line - a wrong pin and an unknown token - are indistinguishable in the trace`() {
        // Architecture 12 invariant 3. Both paths return the same AppError.Denied singleton, so this
        // holds by construction -- but the trace is a new place it could be broken, and until now
        // nothing asserted the log half of the invariant at all.
        val wrongPin = capturingTraceLines {
            Slf4jUseCaseTracer().trace("VerifyPortalPinUseCase") { AppError.Denied.asErr() }
        }
        val unknownToken = capturingTraceLines {
            Slf4jUseCaseTracer().trace("VerifyPortalPinUseCase") { AppError.Denied.asErr() }
        }

        withoutDuration(wrongPin.single()) shouldBe withoutDuration(unknownToken.single())
    }

    @Test
    fun `trace line - a use case that throws - reports the exception class and rethrows`() {
        // A repository throws; that is why StatusPages exists. A tracer that logs only on normal
        // return drops the slowest and most interesting case.
        var thrown: Throwable? = null

        val lines = capturingTraceLines {
            thrown = runCatching {
                Slf4jUseCaseTracer().trace<String>("UploadDocumentUseCase") {
                    throw IllegalStateException("jdbc:postgresql://user:hunter2@host/db")
                }
            }.exceptionOrNull()
        }

        (thrown is IllegalStateException) shouldBe true
        lines.single() shouldContain "IllegalStateException"
        lines.single() shouldNotContain "hunter2"
    }

    @Test
    fun `trace line - the elapsed time - is reported in milliseconds`() {
        val lines = capturingTraceLines {
            Slf4jUseCaseTracer().trace("CreateHireUseCase") { Unit.asOk() }
        }

        lines.single() shouldContain "ms"
    }

    @Test
    fun `trace line - the level is above debug - the use case still runs and nothing is emitted`() {
        // The tracer short-circuits before reading the clock. Proving the block still runs matters:
        // a guard clause that skipped the work would turn a quiet log level into missing behaviour.
        var ran = false

        val lines = capturingTraceLines(level = Level.INFO) {
            Slf4jUseCaseTracer().trace("CreateHireUseCase") { ran = true; Unit.asOk() }
        }

        ran shouldBe true
        lines.shouldBeEmpty()
    }

    // ── Selection ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `tracer selection - the flag is unset - binds the no-op`() {
        Slf4jUseCaseTracer.fromEnvironment(devMode = true, enabled = null) shouldBe NoOpUseCaseTracer
    }

    @Test
    fun `tracer selection - the flag is blank - binds the no-op rather than treating it as set`() {
        // Sourcing a copied .env.example exports TRACE_USECASES="" rather than leaving it unset, so
        // a null check alone would read an empty value as a decision. Same reasoning as JWT_SECRET.
        Slf4jUseCaseTracer.fromEnvironment(devMode = true, enabled = "") shouldBe NoOpUseCaseTracer
        Slf4jUseCaseTracer.fromEnvironment(devMode = true, enabled = "   ") shouldBe NoOpUseCaseTracer
    }

    @Test
    fun `tracer selection - the flag is true - binds the logging tracer`() {
        val tracer = Slf4jUseCaseTracer.fromEnvironment(devMode = true, enabled = "true")

        (tracer is Slf4jUseCaseTracer) shouldBe true
    }

    @Test
    fun `tracer selection - the flag is set to something unparseable - binds the no-op`() {
        // Silently doing nothing is the right failure for a diagnostic -- refusing to boot over a
        // debug flag would be worse -- but it is confusing, so the factory warns. See its KDoc.
        Slf4jUseCaseTracer.fromEnvironment(devMode = true, enabled = "1") shouldBe NoOpUseCaseTracer
        Slf4jUseCaseTracer.fromEnvironment(devMode = true, enabled = "yes") shouldBe NoOpUseCaseTracer
    }

    @Test
    fun `tracer selection - the no-op tracer - returns the result and emits nothing`() {
        var returned: DomainResult<String>? = null

        val lines = capturingTraceLines {
            returned = NoOpUseCaseTracer.trace("CreateHireUseCase") { "carried through".asOk() }
        }

        lines.shouldBeEmpty()
        (returned as DomainResult.Ok<String>).value shouldBe "carried through"
    }

    // ── Harness ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs [block] with the `usecase` logger at [level] and a list appender attached, and returns
     * what it emitted.
     *
     * The level is set here rather than relied upon from `resources/logback.xml`, so these
     * assertions describe the tracer rather than the config file. The appender is always detached:
     * the suite is sequential (see `ServerTest`), but the logger is process-global and a leaked
     * appender would make a later test's failure unreadable.
     */
    private fun capturingTraceLines(level: Level = Level.DEBUG, block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(Slf4jUseCaseTracer.LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val restoreTo = logger.level

        logger.level = level
        logger.addAppender(appender)
        try {
            runBlocking { block() }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = restoreTo
        }

        return appender.list.map { it.formattedMessage }
    }

    /** Durations differ run to run; the invariant is about everything else on the line. */
    private fun withoutDuration(line: String): String = line.replace(Regex("""\d+ms"""), "<d>ms")
}
