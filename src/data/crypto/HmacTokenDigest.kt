package com.pgsystem.employee.requirement.tracker.data.crypto

import com.pgsystem.employee.requirement.tracker.core.crypto.TokenDigest
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA-256 keyed by a server-side pepper.
 *
 * Deterministic on purpose, which is the whole point of the type — see [TokenDigest]. The pepper is
 * what separates "the database leaked" from "every live link is now resolvable": a digest cannot be
 * recomputed from a stolen `token_hash` column without the key, which lives in the environment and
 * not in the database.
 *
 * **A fresh [Mac] per call.** `Mac` is stateful and not thread-safe; a shared instance field would
 * interleave `update`/`doFinal` across concurrent portal requests and return digests belonging to
 * neither caller — a bug that appears only under load and looks like data corruption. Constructing
 * one costs microseconds, against the ~100 ms of the bcrypt call this replaces on the lookup path.
 *
 * ### Known gap: the pepper cannot be rotated
 *
 * Rotating it changes every digest, so every live upload link and portal session stops resolving at
 * once. There is no credential re-issue flow — recovery would mean re-inviting every in-flight hire
 * by hand, through the bulk path PRD 8.2 deliberately makes slow. Recorded rather than solved: the
 * fix is a re-issue flow, which is its own ticket. Until then, treat `TOKEN_PEPPER` as permanent for
 * the life of an environment.
 */
class HmacTokenDigest(pepper: String) : TokenDigest {

    init {
        require(pepper.length >= MIN_PEPPER_LENGTH) {
            "TOKEN_PEPPER must be at least $MIN_PEPPER_LENGTH characters; got ${pepper.length}."
        }
    }

    private val key = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), ALGORITHM)

    override fun digest(token: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        return ENCODER.encodeToString(mac.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val ALGORITHM = "HmacSHA256"

        /**
         * Without a floor the "is it configured" check below is satisfied by `TOKEN_PEPPER=x`, which
         * is configuration theatre — the key would add no work to an offline attempt at all.
         */
        const val MIN_PEPPER_LENGTH = 32

        const val PEPPER_VARIABLE = "TOKEN_PEPPER"

        /** 43 characters, URL-safe, matching how `SecureTokenGenerator` encodes the token itself. */
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        private val log = LoggerFactory.getLogger(HmacTokenDigest::class.java)

        /**
         * Reads [PEPPER_VARIABLE], refusing to start without one outside dev — the same contract
         * `JWT_SECRET` has in `Security.kt`, for the same reason: a service that boots on a default
         * or absent key is worse than one that does not boot.
         *
         * **Absence is forgiven in dev; weakness never is.** A dev run with nothing configured gets
         * an ephemeral pepper so a fresh checkout works with no setup, matching the in-memory H2
         * default. A pepper that is present but too short fails in every mode, because that is a
         * mistake rather than a default.
         *
         * **Blank counts as absent.** `?:` catches `null` but not `""`, and sourcing a `.env` copied
         * from `.env.example` supplies exactly the empty string — so without this, following the
         * documentation would bypass the check above.
         *
         * [pepper] is a parameter rather than an inline `System.getenv` so the refusal is provable:
         * a JVM test cannot unset an environment variable in its own process.
         */
        fun fromEnvironment(
            devMode: Boolean,
            pepper: String? = System.getenv(PEPPER_VARIABLE),
        ): HmacTokenDigest {
            val configured = pepper?.takeUnless(String::isBlank)

            if (configured != null) return HmacTokenDigest(configured)

            check(devMode) {
                "$PEPPER_VARIABLE must be set outside dev. Refusing to start without a token pepper."
            }
            log.warn(
                "$PEPPER_VARIABLE is not set. Using an ephemeral development pepper; links issued " +
                    "before a restart will stop resolving."
            )
            return HmacTokenDigest(ephemeralPepper())
        }

        private fun ephemeralPepper(): String =
            ENCODER.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}
