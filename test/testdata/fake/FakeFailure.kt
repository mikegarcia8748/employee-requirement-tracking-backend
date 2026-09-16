package com.pgsystem.employee.requirement.tracker.testdata.fake

/**
 * The injectable failure every fake carries, as a `failure` property.
 *
 * A fake that can only succeed leaves half of PRD 8.1 untestable. Delivery failure is a **specified
 * path** there, not an edge case — the hire is created regardless and HR gets a retry action — and
 * the same is true of a repository that throws mid-transaction. A use case that silently loses a
 * hire when the audit write fails passes every test written against a fake that cannot fail.
 *
 * Two modes, because they catch different bugs. [failNextCall] fires once, which is how you assert
 * that a use case recovers and the *following* call still works; [failEveryCall] persists, which is
 * how you assert that a use case gives up rather than retrying forever.
 *
 * Throwing rather than returning an `AppError` is deliberate. Infrastructure failure is not a domain
 * failure — `StatusPages` exists precisely because a repository throws — so a fake that returned
 * `DomainResult.Err` would be modelling a path the real adapter does not have.
 *
 * There are two exceptions, and both for the same reason — the failure is part of the port's own
 * signature. [FakeNotifier] carries `DeliveryResult.Failed`, and [FakeAppSettingsRepository] carries
 * `DomainResult.Err` because ERT-310 gave `AppSettingsRepository` an error channel for a settings
 * row that cannot be read.
 */
class FakeFailure {
    private var next: Throwable? = null
    private var always: Throwable? = null

    /** The next call into this fake throws, and the one after it behaves normally. */
    fun failNextCall(with: Throwable = IllegalStateException("The fake was asked to fail this call")) {
        next = with
    }

    /** Every call into this fake throws until [stopFailing]. */
    fun failEveryCall(with: Throwable = IllegalStateException("The fake was asked to fail every call")) {
        always = with
    }

    fun stopFailing() {
        next = null
        always = null
    }

    /** Called at the top of every fake method. Consumes a one-shot failure. */
    internal fun check() {
        next?.let {
            next = null
            throw it
        }
        always?.let { throw it }
    }
}
