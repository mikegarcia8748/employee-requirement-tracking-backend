package com.pgsystem.employee.requirement.tracker.core.trace

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * Diagnostic trace of business logic, as an injected dependency.
 *
 * A use case is the only place a business rule is decided, and until now nothing below the route
 * was visible: `CallLogging` reports `POST /api/employees -> 422` and stops there, so "which rule
 * rejected it, and what did it cost" could only be answered with a debugger. This port makes one
 * line per invocation available in development, and binds to [NoOpUseCaseTracer] otherwise.
 *
 * ### What it may carry, and why the type enforces it
 *
 * The name, the outcome and the elapsed time. **Never an argument.** The block returns
 * [DomainResult] rather than a bare `T` so the tracer reads the outcome itself: no call site has to
 * remember to report one, and none is able to report something richer. Architecture 12 invariants
 * 1–4 forbid a PIN, a token, a filename or anything separating a wrong PIN from an unknown token
 * from reaching a log, and the cheapest way to keep that true is to give a call site nothing to
 * pass.
 *
 * ### Three things not to "fix"
 *
 * Not a `fun interface`, unlike [com.pgsystem.employee.requirement.tracker.core.time.Clock]: a SAM
 * interface's single method may not declare type parameters, so the two cannot be made to match.
 *
 * This is the first `suspend` member in `core/`. That stays within the layer rule — `suspend` is a
 * language feature and `Continuation` ships in the stdlib, and `domain/port/AuditLog` already
 * suspends — but an implementation must never call `withContext` or name a dispatcher. Tracing is
 * not a reason to move work off the caller's thread, and `ArchitectureTest` bans `Dispatchers` in
 * the inner layers for a reason that applies here unchanged.
 *
 * [NoOpUseCaseTracer] lives beside the port rather than in `data/` so a use case test constructs one
 * without reaching outward for a fake.
 */
interface UseCaseTracer {
    suspend fun <T> trace(useCase: String, block: suspend () -> DomainResult<T>): DomainResult<T>
}

/**
 * The production binding, and the one every use case test should pass.
 *
 * An `object`, not a class: it holds nothing, so there is no reason for two of them to exist.
 */
object NoOpUseCaseTracer : UseCaseTracer {
    override suspend fun <T> trace(useCase: String, block: suspend () -> DomainResult<T>): DomainResult<T> = block()
}
