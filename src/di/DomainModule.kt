package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.domain.usecase.AuthenticateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ChangeHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.EnsureBootstrapHrUserUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.ResetHrPasswordUseCase
import com.pgsystem.employee.requirement.tracker.domain.usecase.SetHrUserActiveUseCase
import org.koin.dsl.module

/**
 * The use cases (ERT-190).
 *
 * `AppModule`'s note said a `domainModule` would hold these "once they exist". They exist.
 *
 * **`factory`, not `single`.** A use case holds no state worth sharing — every field is a port or an
 * injected clock, all of which are singles themselves — so a shared instance would buy one allocation
 * per request and cost the guarantee that two concurrent calls cannot interfere.
 *
 * **`factory` does not mean one instance per request**, and `AuthenticateHrUserUseCase` is where that
 * distinction matters. `Routing.kt` resolves it once through `by inject` and captures it in the route
 * closure, so the application holds exactly one for its lifetime — which is what makes that use
 * case's `by lazy` decoy hash a one-off cost on the first failed sign-in rather than ~100 ms of
 * bcrypt added to every one. Were a handler ever to resolve one per call instead, that cost would
 * land on the failure path on every request, and the first-request asymmetry the lazy creates would
 * become a permanent one.
 *
 * Nothing in `domain/` references Koin: dependencies arrive through constructors, which is why every
 * one of these can be instantiated in a test with plain fakes and no container at all.
 */
val domainModule = module {
    factory { AuthenticateHrUserUseCase(get(), get(), get(), get(), get(), get(), get()) }
    factory { ChangeHrPasswordUseCase(get(), get(), get(), get(), get(), get()) }
    factory { CreateHrUserUseCase(get(), get(), get(), get(), get(), get(), get()) }
    factory { SetHrUserActiveUseCase(get(), get(), get(), get(), get()) }
    factory { ResetHrPasswordUseCase(get(), get(), get(), get(), get(), get()) }
    factory { EnsureBootstrapHrUserUseCase(get(), get(), get(), get(), get(), get(), get()) }
}
