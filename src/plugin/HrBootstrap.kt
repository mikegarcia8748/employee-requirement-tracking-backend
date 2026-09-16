package com.pgsystem.employee.requirement.tracker.plugin

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.domain.port.HrUserRepository
import com.pgsystem.employee.requirement.tracker.domain.usecase.BootstrapHrUser
import com.pgsystem.employee.requirement.tracker.domain.usecase.EnsureBootstrapHrUserUseCase
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.runBlocking

/**
 * The first `HR_ADMIN`, at startup (ERT-190).
 *
 * With no SSO and no self-registration the first account has to come from somewhere. Every
 * alternative is worse: a seeded row in a migration ships a known password in version control, and a
 * CLI is a second entry point to secure.
 *
 * ### Three layers, split so each can be tested without the others
 *
 * [bootstrapDecision] is a pure function of four values and owns every branch — it is where "refuse
 * to start" is decided, and it needs no environment, no container and no database. This function is
 * the wiring that reads the environment and executes that decision.
 * [EnsureBootstrapHrUserUseCase] owns the business rule, and only creates when `users` is empty.
 *
 * The same split `HmacTokenDigest.fromEnvironment` and `Slf4jUseCaseTracer.fromEnvironment` already
 * use, and the same reason `configureSecurity` takes its [com.pgsystem.employee.requirement.tracker.data.auth.JwtConfig]
 * as a parameter: a plugin that reaches for its own inputs cannot be assembled in a test.
 *
 * ### `runBlocking`, once, on the startup thread
 *
 * The repository suspends and module assembly does not. Blocking here is correct rather than
 * tolerated: nothing may serve a request before this resolves — a window in which the table is empty
 * and the bootstrap has not run is a window in which a second caller could race it — and it happens
 * once, before the connector binds.
 */
fun Application.configureHrBootstrap(
    users: HrUserRepository,
    ensureBootstrap: EnsureBootstrapHrUserUseCase,
    devMode: Boolean = isDevMode(),
    email: String? = System.getenv(BOOTSTRAP_EMAIL),
    password: String? = System.getenv(BOOTSTRAP_PASSWORD),
) {
    runBlocking {
        val decision = bootstrapDecision(
            devMode = devMode,
            email = email,
            password = password,
            // Read lazily: only the "variables are unset" branch needs it, and an established
            // deployment that has since dropped them from its environment must start normally.
            // Refusing there would turn tidying up a secret store into an outage.
            accountCount = { users.countAll() },
        )

        when (decision) {
            is BootstrapDecision.Skip -> log.info("HR bootstrap: ${decision.reason}")
            is BootstrapDecision.Refuse -> error(decision.message)
            is BootstrapDecision.Warn -> log.warn(decision.message)

            is BootstrapDecision.Create -> when (
                val result = ensureBootstrap(BootstrapHrUser(decision.email, decision.password))
            ) {
                is DomainResult.Ok -> when (val created = result.value) {
                    // Never the password, and never the id alone -- an operator needs to know which
                    // address to sign in as, and that address is one they just configured.
                    null -> log.info("HR bootstrap: accounts already exist, nothing to do.")
                    else -> log.warn(
                        "HR bootstrap: created the first HR_ADMIN as ${created.email.value}. " +
                            "It must change its password at first sign-in."
                    )
                }

                // A rejected address or a too-short password is a configuration fault that leaves the
                // deployment with no way in. Failing here means it is noticed now rather than by the
                // first person who tries to sign in.
                is DomainResult.Err -> error(
                    "HR bootstrap: $BOOTSTRAP_EMAIL / $BOOTSTRAP_PASSWORD are set but not usable " +
                        "(${result.error.code}). Refusing to start with no way in."
                )
            }
        }
    }
}

/**
 * What to do about the bootstrap account, as data.
 *
 * Pure, and exhaustive over a sealed interface so adding a case is a compile error at the one call
 * site rather than a branch that silently does nothing.
 */
internal sealed interface BootstrapDecision {
    /** Both variables are set: hand them to the use case, which decides whether anything is created. */
    data class Create(val email: String, val password: String) : BootstrapDecision

    /** Nothing to do, and nothing wrong. */
    data class Skip(val reason: String) : BootstrapDecision

    /** Startable, but nobody can sign in. Dev only. */
    data class Warn(val message: String) : BootstrapDecision

    /** Do not start. */
    data class Refuse(val message: String) : BootstrapDecision
}

/**
 * The whole of the startup rule (ERT-190).
 *
 * **Outside dev, an empty `users` table with no bootstrap variables set is a startup failure.** Same
 * shape as `JWT_SECRET` and `TOKEN_PEPPER`, for the same reason: a deployment that comes up with no
 * way in, or with a known way in, is worse than one that does not come up.
 *
 * It is deliberately narrower than those two in one respect — it reads the **table** before deciding
 * anything is fatal. A long-running deployment with real accounts that has since dropped the
 * variables from its environment starts normally; refusing there would turn tidying up a secret store
 * into an outage. That is why [accountCount] is a lambda: on the ordinary path it is never called.
 *
 * In dev with nothing set it warns and continues, because a fresh checkout must run with no
 * configuration at all — the same promise `DATABASE_URL` and `JWT_SECRET` make.
 *
 * `isBlank` rather than `== null`: a `.env` copied from `.env.example` supplies the empty string, and
 * an empty `HR_BOOTSTRAP_PASSWORD` must not be read as a configured one.
 */
internal suspend fun bootstrapDecision(
    devMode: Boolean,
    email: String?,
    password: String?,
    accountCount: suspend () -> Long,
): BootstrapDecision {
    val configuredEmail = email?.takeUnless(String::isBlank)
    val configuredPassword = password?.takeUnless(String::isBlank)

    if (configuredEmail != null && configuredPassword != null) {
        return BootstrapDecision.Create(configuredEmail, configuredPassword)
    }

    if (accountCount() > 0L) {
        return BootstrapDecision.Skip("accounts already exist, nothing to do.")
    }

    val unset = "The users table is empty and $BOOTSTRAP_EMAIL / $BOOTSTRAP_PASSWORD are not set."

    return if (devMode) {
        BootstrapDecision.Warn("$unset Nobody can sign in. Set both and restart.")
    } else {
        BootstrapDecision.Refuse("$unset Refusing to start with no way in.")
    }
}

internal const val BOOTSTRAP_EMAIL = "HR_BOOTSTRAP_EMAIL"
internal const val BOOTSTRAP_PASSWORD = "HR_BOOTSTRAP_PASSWORD"
