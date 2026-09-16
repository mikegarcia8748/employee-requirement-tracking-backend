package com.pgsystem.employee.requirement.tracker.testdata

import com.pgsystem.employee.requirement.tracker.core.error.AppError
import com.pgsystem.employee.requirement.tracker.core.error.DomainResult

/**
 * `DomainResult` unwrapped, for assertions.
 *
 * A test asserting on a success should say so by casting: if the call returned an `Err`, the cast
 * fails and names the line, which is a better failure than a `when` that quietly asserts nothing.
 *
 * These began in `PersonIdTest` and moved here when the first repository adapter started returning
 * `DomainResult` — the same move ERT-240 made for `projectDir` and `freshDatabase()`. Test helpers
 * shared across packages should not live in a file named for one value object.
 */

fun <T> DomainResult<T>.ok(): T = (this as DomainResult.Ok<T>).value

fun DomainResult<*>.err(): AppError = (this as DomainResult.Err).error

fun DomainResult<*>.errCode(): String = err().code
