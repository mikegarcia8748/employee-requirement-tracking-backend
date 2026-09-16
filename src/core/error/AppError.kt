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

    /**
     * The caller did not prove who they are.
     *
     * **HR-side only, and the distinction from [Denied] is the whole reason it exists.** A portal
     * failure must be indistinguishable from an unmatched route, so [Denied] maps to 404 and renders
     * the body a mistyped path produces. A *sign-in* failure has no such constraint and must not
     * borrow one: answering `POST /api/auth/login` with a 404 would say the endpoint is absent, and a
     * client cannot tell "these credentials are wrong" from "this deployment has no sign-in" — so the
     * one place a password is checked would be the one place the status code lies.
     *
     * **A `data object`, not a `data class`, for exactly the reason [Denied] is one.** An unknown
     * email, a wrong password and a deactivated account are all this value, so two call sites cannot
     * construct two different instances and render two different bodies. Without that, "all three
     * responses are byte-identical" is a convention someone has to remember, and the endpoint becomes
     * an oracle for who works in HR.
     *
     * Do **not** reach for this on a portal path. `AppErrorMapperTest` has no way to catch that, and
     * a 401 where §6.6 requires a 404 re-opens SEC-01.
     */
    data object AuthenticationFailed : AppError {
        override val code: String = "authentication_failed"
    }

    /**
     * The caller is known but this action is not theirs to take.
     *
     * Separate from [AuthenticationFailed] because the remedy differs and the client must be able to
     * tell them apart: a 401 means sign in again, a 403 means do not bother. Carrying no detail is
     * deliberate — naming the role required would let an `HR_OFFICER` enumerate the admin surface by
     * probing it.
     */
    data object Forbidden : AppError {
        override val code: String = "forbidden"
    }

    /** A rule requires an explicit, recorded human justification that was not supplied. */
    data class ReasonRequired(override val code: String, val action: String) : AppError

    /**
     * Several inputs failed at once.
     *
     * [Validation] names one field, which is right for a value object like `PersonId.of` but wrong
     * for a form: reporting `POST /api/employees` one field at a time costs the caller a round trip
     * per mistake. A use case that checks a whole command accumulates into this instead.
     *
     * The element type is [Validation] rather than a parallel field-error class, so there is one
     * description of "a field that did not validate" and no second shape to keep in step. Both
     * cases render through the same wire shape, so a client never branches on how many failed.
     *
     * The `require` is a construction precondition rather than a domain failure: an empty list is a
     * programming error, and it would render an empty `details` array that says nothing.
     */
    data class ValidationFailed(val errors: List<Validation>) : AppError {
        override val code: String = "validation_failed"

        init {
            require(errors.isNotEmpty()) { "ValidationFailed needs at least one error" }
        }
    }
}
