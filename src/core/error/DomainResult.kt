package com.pgsystem.employee.requirement.tracker.core.error

/**
 * The return shape for every use case: either a value or an [AppError].
 *
 * Deliberately not [kotlin.Result], which carries a [Throwable] and invites exceptions back into
 * the domain where the failure modes are part of the specification.
 */
sealed interface DomainResult<out T> {
    data class Ok<out T>(val value: T) : DomainResult<T>
    data class Err(val error: AppError) : DomainResult<Nothing>
}

fun <T> T.asOk(): DomainResult<T> = DomainResult.Ok(this)
fun AppError.asErr(): DomainResult<Nothing> = DomainResult.Err(this)

inline fun <T, R> DomainResult<T>.map(transform: (T) -> R): DomainResult<R> = when (this) {
    is DomainResult.Ok -> DomainResult.Ok(transform(value))
    is DomainResult.Err -> this
}

inline fun <T, R> DomainResult<T>.flatMap(transform: (T) -> DomainResult<R>): DomainResult<R> =
    when (this) {
        is DomainResult.Ok -> transform(value)
        is DomainResult.Err -> this
    }
