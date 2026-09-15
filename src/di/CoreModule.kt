package com.pgsystem.employee.requirement.tracker.di

import com.pgsystem.employee.requirement.tracker.core.crypto.Hasher
import com.pgsystem.employee.requirement.tracker.core.crypto.TokenDigest
import com.pgsystem.employee.requirement.tracker.core.id.EntityIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PersonIdGenerator
import com.pgsystem.employee.requirement.tracker.core.id.PinGenerator
import com.pgsystem.employee.requirement.tracker.core.id.TokenGenerator
import com.pgsystem.employee.requirement.tracker.core.time.Clock
import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.data.crypto.HmacTokenDigest
import com.pgsystem.employee.requirement.tracker.data.id.SecureEntityIdGenerator
import com.pgsystem.employee.requirement.tracker.data.id.SecurePersonIdGenerator
import com.pgsystem.employee.requirement.tracker.data.id.SecurePinGenerator
import com.pgsystem.employee.requirement.tracker.data.id.SecureTokenGenerator
import com.pgsystem.employee.requirement.tracker.data.time.SystemClock
import com.pgsystem.employee.requirement.tracker.plugin.isDevMode
import org.koin.dsl.module

/**
 * The infrastructure a use case is allowed to depend on: time, identity, randomness, hashing.
 *
 * Each is bound to an interface the domain owns, so a test substitutes a fixed clock or a
 * predictable generator without touching the rule under test.
 *
 * [Hasher] and [TokenDigest] are deliberately two bindings, not one. A PIN is verified against a
 * known row and needs a work factor; a token is looked up by its digest and needs reproducibility.
 * Collapsing them either makes link lookup impossible or makes PIN cracking cheap (ERT-160).
 *
 * `TokenDigest` must stay a `single`: in dev the pepper is generated per instance, so a `factory`
 * here would digest a token differently at issue and at lookup, and every dev link would fail to
 * resolve.
 */
val coreModule = module {
    single<Clock> { SystemClock() }
    single<EntityIdGenerator> { SecureEntityIdGenerator() }
    single<PersonIdGenerator> { SecurePersonIdGenerator() }
    single<TokenGenerator> { SecureTokenGenerator() }
    single<PinGenerator> { SecurePinGenerator() }
    single<Hasher> { BcryptHasher() }
    single<TokenDigest> { HmacTokenDigest.fromEnvironment(isDevMode()) }
}
