package com.pgsystem.employee.requirement.tracker.core.error

/**
 * Domain-meaningful failures, modelled as data rather than thrown.
 *
 * Use cases return these so unhappy paths are ordinary values a test can assert on, and so the
 * compiler reminds route adapters to handle them. Adding a case here is a specification change.
 */
sealed interface AppError {
    val code: String

    /** Input did not satisfy a format or range rule. */
    data class Validation(override val code: String, val field: String, val detail: String) : AppError

    /** The entity referenced does not exist. */
    data class NotFound(override val code: String, val entity: String) : AppError

    /**
     * The action is legal in general but not in the current state — e.g. uploading against a
     * requirement locked by review (PRD 8.7). Enforced server-side, never only in the UI.
     */
    data class Conflict(override val code: String, val detail: String) : AppError

    /**
     * The caller may not perform this action.
     *
     * Portal failures deliberately collapse into a single indistinguishable case: a wrong PIN and
     * an unknown token must be impossible to tell apart (PRD 6.6), so the endpoint cannot be used
     * as an oracle for whether a link exists.
     *
     * **A `data object`, not a `data class`, and that is the control.** Carrying a code would let
     * two call sites construct two different `Denied` values and render two different bodies, which
     * makes architecture 12 invariant 3 a convention someone has to remember rather than something
     * the type system enforces. There is exactly one `Denied`, so differing responses are
     * unrepresentable.
     *
     * The code reads `not_found` because it must also be indistinguishable from the body an
     * unmatched route produces — see `configureStatusPages`.
     */
    data object Denied : AppError {
        override val code: String = "not_found"
    }

    /** A rule requires an explicit, recorded human justification that was not supplied. */
    data class ReasonRequired(override val code: String, val action: String) : AppError
}
