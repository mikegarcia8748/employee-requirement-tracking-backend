package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.id.IdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PinGenerator
import com.pgsystem.employee.requirement.tracker.core.id.TokenGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.data.id.RandomIdGenerator
import com.pgsystem.employee.requirement.tracker.data.id.SecurePinGenerator
import com.pgsystem.employee.requirement.tracker.data.id.SecureTokenGenerator
import com.pgsystem.employee.requirement.tracker.data.time.SystemClock
import org.koin.dsl.module

/**
 * The infrastructure a use case is allowed to depend on: time, identity, randomness, hashing.
 *
 * Each is bound to an interface the domain owns, so a test substitutes a fixed clock or a
 * predictable generator without touching the rule under test.
 */
val coreModule = module {
    single<Clock> { SystemClock() }
    single<IdGenerator> { RandomIdGenerator() }
    single<TokenGenerator> { SecureTokenGenerator() }
    single<PinGenerator> { SecurePinGenerator() }
    single<Hasher> { BcryptHasher() }
}
