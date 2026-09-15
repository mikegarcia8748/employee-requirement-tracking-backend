package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * Where an identifier was read decides its failure status — not the shape of the identifier.
 *
 * `PersonId.of` and `EntityId.of` return `AppError.Validation`, which maps to 422. That is right for
 * a request body, where naming the offending field is useful and discloses nothing. It is wrong for
 * a **path**, because answering 422 `person_id.invalid_format` for a malformed id and 404 for a
 * well-formed unknown one turns the endpoint into an enumeration oracle: the caller learns which of
 * their guesses were the right *shape*, and on the portal that is one step from learning whether a
 * link exists (PRD 6.6, SEC-01).
 *
 * So: **a malformed id in a path is a 404, everywhere.** A path names a resource, and an id that
 * cannot exist names a resource that does not exist. Uniform across HR and portal, so no route
 * author has to make the call again.
 *
 * A body-field id needs neither helper — `Validation` already maps to 422 and names the field.
 */

/**
 * HR surface: a path id that will not parse becomes the same `NotFound` a lookup miss produces, so
 * the two are indistinguishable.
 */
fun <T> DomainResult<T>.orNotFound(entity: String): DomainResult<T> = when (this) {
    is DomainResult.Ok -> this
    is DomainResult.Err -> DomainResult.Err(AppError.NotFound(code = "${entity}_not_found", entity = entity))
}

/**
 * Portal surface: **every** failure — malformed, unknown, not-yours, expired, wrong PIN — collapses
 * to the one `Denied` response.
 *
 * It has no caller until ERT-630, and that is deliberate: the rule has to exist before the first
 * portal route is written, for the same reason ERT-170's write-mostly guard has to exist before the
 * first portal DTO. Afterwards it is an audit, not a guard.
 */
fun <T> DomainResult<T>.orDenied(): DomainResult<T> = when (this) {
    is DomainResult.Ok -> this
    is DomainResult.Err -> DomainResult.Err(AppError.Denied)
}
