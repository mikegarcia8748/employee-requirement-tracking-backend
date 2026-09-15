package com.pgsystem.employee.requirement.tracker.data.trace

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.trace.NoOpUseCaseTracer
import com.pgsystem.employee.requirement.tracker.core.trace.UseCaseTracer
import org.slf4j.LoggerFactory

/**
 * Writes one line per use case invocation to the `usecase` logger, at DEBUG.
 *
 * ```
 * usecase - CreateHireUseCase ok in 42ms
 * usecase - CreateHireUseCase email_invalid in 3ms
 * usecase - UploadDocumentUseCase threw:ExposedSQLException in 12ms
 * ```
 *
 * **The logger name is a fixed string, not this class.** It is the handle `logback.xml` targets and
 * an operator greps for, so it is a contract; deriving it from the class name would move that
 * contract every time the file is renamed.
 *
 * **Elapsed time comes from [System.nanoTime], not from the injected `Clock`.** Two reasons, because
 * "use the Clock we already inject" is the first thing review will say. `Clock` is a wall clock,
 * so an NTP step lands in the middle of a measurement as a negative or wildly large duration. And
 * ERT-220's `FixedClock` never advances, so every duration in every test would be `0` and every
 * assertion about timing would pass without measuring anything. Monotonic time is not a domain
 * concern and this adapter is the right layer to read it.
 */
class Slf4jUseCaseTracer : UseCaseTracer {

    override suspend fun <T> trace(useCase: String, block: suspend () -> DomainResult<T>): DomainResult<T> {
        // Skipping the clock read when the level is off keeps the bound-but-quiet case near free,
        // so `TRACE_USECASES` and the log level are independent switches rather than one of each.
        if (!log.isDebugEnabled) return block()

        val startedAt = System.nanoTime()
        try {
            val result = block()
            log.debug(traceLine(useCase, outcomeOf(result), elapsedMillisSince(startedAt)))
            return result
        } catch (cause: Throwable) {
            // Rethrown unchanged: a use case that throws is the case most worth seeing, and
            // swallowing it here would turn a 500 into a silent success. The class name only --
            // a message from Exposed or Hikari carries SQL, a column value or a digest, which
            // StatusPages already refuses to let reach a client (PRD 12).
            log.debug(traceLine(useCase, "threw:${cause.javaClass.simpleName}", elapsedMillisSince(startedAt)))
            throw cause
        }
    }

    companion object {
        const val TRACE_VARIABLE = "TRACE_USECASES"

        /** See the class KDoc: a stable handle for `logback.xml`, deliberately not the class name. */
        const val LOGGER_NAME = "usecase"

        private val log = LoggerFactory.getLogger(LOGGER_NAME)

        /**
         * Picks the tracer from the environment: [NoOpUseCaseTracer] unless [TRACE_VARIABLE] is
         * explicitly `true`.
         *
         * [enabled] is a parameter rather than an inline `System.getenv` for the reason
         * `HmacTokenDigest.fromEnvironment` records: a JVM test cannot unset an environment variable
         * in its own process, so neither branch would otherwise be provable.
         *
         * **Gated on its own variable rather than on `isDevMode()`.** `APP_ENV` already defaults to
         * dev when unset and already decides four things; a deployment that forgets it would then
         * silently start tracing too. Tracing is off unless somebody asked for it.
         *
         * An unparseable value warns rather than refusing to boot. Refusing would make a debug flag
         * able to take production down, but silently ignoring `TRACE_USECASES=1` would send someone
         * hunting for a tracer that was never on.
         */
        fun fromEnvironment(
            devMode: Boolean,
            enabled: String? = System.getenv(TRACE_VARIABLE),
        ): UseCaseTracer {
            // Blank rather than null: sourcing a copied .env.example exports "" (see Security.kt).
            val configured = enabled?.takeUnless(String::isBlank)?.trim() ?: return NoOpUseCaseTracer

            val on = configured.lowercase().toBooleanStrictOrNull()
            if (on == null) {
                log.warn(
                    "$TRACE_VARIABLE is \"$configured\", which is neither true nor false. " +
                        "Use case tracing stays off."
                )
                return NoOpUseCaseTracer
            }
            if (!on) return NoOpUseCaseTracer

            if (!devMode) {
                log.warn(
                    "$TRACE_VARIABLE is enabled outside dev. Use case names, outcomes and durations " +
                        "will be logged for every request."
                )
            }
            return Slf4jUseCaseTracer()
        }
    }
}

/**
 * The whole line, as a pure function, so what it may say is testable without an appender.
 *
 * Three fields and no fourth. Adding the command, the entity id or the error object would each read
 * as an improvement in review and each would put caller-supplied text into a log file.
 */
internal fun traceLine(useCase: String, outcome: String, millis: Long): String =
    "$useCase $outcome in ${millis}ms"

/**
 * The outcome word: `ok`, or the failure's `code`.
 *
 * **The code, never the error.** `AppError.Validation` and `AppError.Conflict` carry a `detail`
 * holding whatever the caller typed — an email address, a filename — and interpolating the error
 * would ship it. `AppError.Denied` is a single `data object` whose code is `not_found`, so a wrong
 * PIN and an unknown token render identically here, as invariant 3 requires of a log outcome.
 */
internal fun outcomeOf(result: DomainResult<*>): String = when (result) {
    is DomainResult.Ok -> "ok"
    is DomainResult.Err -> result.error.code
}

private fun elapsedMillisSince(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000
