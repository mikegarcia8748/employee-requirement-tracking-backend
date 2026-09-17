package com.pgsystem.employee.requirement.tracker.data.notify

/**
 * The origin an invitation link points at (ERT-440).
 *
 * The invitation body contains a link, and until this ticket nothing among the documented
 * environment variables configured the host it points at — so the body would have carried a path
 * with no origin. `.env.example` documents `PORTAL_BASE_URL`; this reads it.
 *
 * **It fails closed outside dev**, the same shape `JwtConfig.fromEnvironment` and
 * `HmacTokenDigest.fromEnvironment` take, and for the same reason: a deployment that forgets it must
 * abort at boot rather than send an invitation nobody can open. An invitation is not retryable by
 * re-rendering — the token is gone by then — so a bad link means reissuing the credential to every
 * hire invited since the deploy, which is precisely the bulk-send path §8.2 makes hard on purpose.
 *
 * `takeUnless(isBlank)`, not a bare read, for the reason `JwtConfig` records: a `.env` copied from
 * `.env.example` supplies the empty string rather than nothing, and `== null` catches an unset
 * variable but not an empty one. Without it, `PORTAL_BASE_URL=` boots outside dev and renders
 * `/portal/abc` with no host — the exact failure the check exists to prevent, reached by following
 * the documentation.
 */
@JvmInline
value class PortalBaseUrl(val value: String) {

    /** The address an invitation points the hire at. */
    fun linkTo(token: String): String = "$value/portal/$token"

    companion object {
        const val DEV_DEFAULT = "http://localhost:8080"

        fun fromEnvironment(
            devMode: Boolean,
            raw: String? = System.getenv("PORTAL_BASE_URL"),
        ): Configured {
            val warnings = mutableListOf<String>()
            val configured = raw?.takeUnless(String::isBlank)?.trimEnd('/')

            if (configured == null) {
                check(devMode) {
                    "PORTAL_BASE_URL must be set outside dev. Refusing to start: an invitation " +
                        "rendered without an origin is a link nobody can open, and it cannot be " +
                        "re-rendered afterwards because the token is not stored."
                }
                warnings += "PORTAL_BASE_URL is not set. Invitation links will point at $DEV_DEFAULT."
            }

            return Configured(
                url = PortalBaseUrl(configured ?: DEV_DEFAULT),
                warnings = warnings,
            )
        }
    }

    /**
     * The value plus anything the caller should log.
     *
     * Warnings are returned rather than logged, for the reason `JwtConfig.Configured` gives: this is
     * constructed from `di/`, and `org.slf4j` is the wrong dependency for a value object — the
     * caller holds an `Application.log` that tags the line with the right logger.
     */
    data class Configured(val url: PortalBaseUrl, val warnings: List<String>)
}
