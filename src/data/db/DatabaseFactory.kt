package com.pgsystem.employee.requirement.tracker.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import javax.sql.DataSource

/**
 * Connection pooling and the single transaction entry point.
 *
 * Exposed's JDBC driver blocks, so [transaction] hops to [Dispatchers.IO]. Confining that hop here
 * is what keeps `Dispatchers` out of the domain: a use case awaits a suspending repository and
 * never chooses a dispatcher itself, which is also why use cases are testable without a thread pool.
 *
 * Defaults to in-memory H2 when `DATABASE_URL` is unset **in dev only**. Postgres is the production
 * target, and outside dev an absent URL is a startup failure rather than a silent fallback — see
 * [DatabaseConfig.fromEnvironment].
 */
class DatabaseFactory(private val config: DatabaseConfig) {

    private lateinit var dataSource: HikariDataSource
    lateinit var database: Database
        private set

    fun connect(): Database {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                driverClassName = config.driverClassName
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"

                // A fixed-size pool. Hikari's own guidance, and it matters more on Cloud Run than on
                // a long-lived VM: an instance that has to grow its pool does so during the first
                // burst of traffic, which is exactly when the extra TLS handshakes cost most.
                minimumIdle = config.maxPoolSize

                // The Hikari default is 30 seconds. `Dispatchers.IO` has 64 threads and the pool has
                // five or ten, so a contended instance parks dozens of coroutines for half a minute
                // while Cloud Run's request timeout has not noticed anything is wrong. Failing fast
                // with a 503 is both the correct answer and the signal that makes Cloud Run scale out.
                connectionTimeout = config.connectionTimeoutMs
                validationTimeout = config.validationTimeoutMs

                // maxLifetime must be shorter than any idle drop between here and Postgres --
                // Cloud SQL, a VPC route, a load balancer. Without it a connection the network has
                // already closed is handed out as healthy, and the symptom is "connection reset by
                // peer" on the first request after a quiet period, which is precisely what a
                // min-instances=1 service does overnight.
                maxLifetime = config.maxLifetimeMs
                keepaliveTime = config.keepaliveTimeMs

                // Zero disables it. On by default in dev and UAT, off in production, because the
                // detection itself walks the pool and the value that is useful while writing a
                // repository is noise once one is in service.
                leakDetectionThreshold = config.leakDetectionThresholdMs

                poolName = "ert-pool"
                validate()
            }
        )
        database = Database.connect(dataSource as DataSource)
        return database
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    /** Every repository call routes through here. */
    suspend fun <T> transaction(block: suspend org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = database) { block() } }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val driverClassName: String,
    val maxPoolSize: Int,
    // Defaulted so a caller that only cares about the destination -- the test harness building a
    // fresh H2 per test -- does not restate five pool timings it has no opinion about. The values
    // are the companion's, so there is one set of numbers rather than two that can drift.
    val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    val validationTimeoutMs: Long = DEFAULT_VALIDATION_TIMEOUT_MS,
    val maxLifetimeMs: Long = DEFAULT_MAX_LIFETIME_MS,
    val keepaliveTimeMs: Long = DEFAULT_KEEPALIVE_MS,
    val leakDetectionThresholdMs: Long = DEFAULT_LEAK_DETECTION_MS,
    /** Anything the caller should log. Same contract as `JwtIssuer.Configured`, for the same reason. */
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val URL_VARIABLE = "DATABASE_URL"
        const val POOL_SIZE_VARIABLE = "DATABASE_MAX_POOL_SIZE"

        const val DEFAULT_POOL_SIZE = 10
        const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L
        const val DEFAULT_VALIDATION_TIMEOUT_MS = 5_000L
        const val DEFAULT_MAX_LIFETIME_MS = 1_800_000L
        const val DEFAULT_KEEPALIVE_MS = 120_000L
        const val DEFAULT_LEAK_DETECTION_MS = 20_000L

        /**
         * A pool larger than this is refused rather than warned about.
         *
         * The number that actually matters is `max_instances × maxPoolSize`, and this process cannot
         * see `max_instances` — Cloud Run's default is 100, which at a pool of ten is a thousand
         * Postgres backends from one service, exhausting any tier during a traffic spike. Refusing an
         * absurd per-instance pool is the only part of that budget the code can enforce; the rest is
         * documented in `docs/deployment.md` with the arithmetic.
         */
        const val MAX_POOL_SIZE = 100

        /** The dev fallback. `DB_CLOSE_DELAY=-1` pins the database for the life of the JVM. */
        const val DEV_URL = "jdbc:h2:mem:ert;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

        /**
         * Reads the environment, refusing to start outside dev without a URL.
         *
         * **This is the check that was missing.** `JWT_SECRET`, `TOKEN_PEPPER` and `PORTAL_BASE_URL`
         * all refuse outside dev; `DATABASE_URL` fell through to in-memory H2 with user `sa` and an
         * empty password. A production deploy that lost the variable therefore came up *green* — it
         * migrated a fresh schema into memory, created an admin account from `HR_BOOTSTRAP_*`, and
         * answered `/health` with 200 while every hire HR created evaporated at the next recycle.
         *
         * `takeUnless(isBlank)` rather than `?:`, for the reason every other reader here uses it:
         * sourcing a file copied from `.env.example` exports the empty string, so `?:` would let the
         * documented path walk straight past the check.
         *
         * Pool values behave the opposite way. They are tuning knobs rather than destinations, so an
         * unparseable one falls back and warns instead of taking a deployment down — the same trade
         * `JWT_TTL_MINUTES` makes. An out-of-range pool size is different again: that is not a typo
         * with a sane default, it is a request the database cannot serve, and it throws.
         *
         * Every value arrives as a parameter so the rules are provable: a JVM test cannot unset an
         * environment variable in its own process.
         */
        fun fromEnvironment(
            devMode: Boolean,
            url: String? = System.getenv(URL_VARIABLE),
            user: String? = System.getenv("DATABASE_USER"),
            password: String? = System.getenv("DATABASE_PASSWORD"),
            maxPoolSize: String? = System.getenv(POOL_SIZE_VARIABLE),
            connectionTimeoutMs: String? = System.getenv("DATABASE_CONNECTION_TIMEOUT_MS"),
            validationTimeoutMs: String? = System.getenv("DATABASE_VALIDATION_TIMEOUT_MS"),
            maxLifetimeMs: String? = System.getenv("DATABASE_MAX_LIFETIME_MS"),
            keepaliveTimeMs: String? = System.getenv("DATABASE_KEEPALIVE_MS"),
            leakDetectionThresholdMs: String? = System.getenv("DATABASE_LEAK_DETECTION_MS"),
        ): DatabaseConfig {
            val warnings = mutableListOf<String>()
            val configured = url?.takeUnless(String::isBlank)

            if (configured == null) {
                check(devMode) {
                    "$URL_VARIABLE must be set outside dev. Refusing to start on the in-memory " +
                        "development database — it would accept writes and lose them on restart."
                }
            }

            val resolved = configured ?: DEV_URL
            val pool = positiveInt(maxPoolSize, DEFAULT_POOL_SIZE, POOL_SIZE_VARIABLE, warnings)
            require(pool in 1..MAX_POOL_SIZE) {
                "$POOL_SIZE_VARIABLE is $pool, which is outside 1..$MAX_POOL_SIZE. The budget that " +
                    "matters is max_instances × pool size; see docs/deployment.md."
            }

            return DatabaseConfig(
                url = resolved,
                user = user?.takeUnless(String::isBlank) ?: "sa",
                password = password ?: "",
                driverClassName = if (resolved.startsWith("jdbc:postgresql")) {
                    "org.postgresql.Driver"
                } else {
                    "org.h2.Driver"
                },
                maxPoolSize = pool,
                connectionTimeoutMs = positiveLong(
                    connectionTimeoutMs, DEFAULT_CONNECTION_TIMEOUT_MS, "DATABASE_CONNECTION_TIMEOUT_MS", warnings
                ),
                validationTimeoutMs = positiveLong(
                    validationTimeoutMs, DEFAULT_VALIDATION_TIMEOUT_MS, "DATABASE_VALIDATION_TIMEOUT_MS", warnings
                ),
                maxLifetimeMs = positiveLong(
                    maxLifetimeMs, DEFAULT_MAX_LIFETIME_MS, "DATABASE_MAX_LIFETIME_MS", warnings
                ),
                keepaliveTimeMs = positiveLong(
                    keepaliveTimeMs, DEFAULT_KEEPALIVE_MS, "DATABASE_KEEPALIVE_MS", warnings
                ),
                // Leak detection is a development aid. On in dev, off in production, where the walk
                // it performs buys nothing a properly sized pool and a bounded timeout do not.
                leakDetectionThresholdMs = positiveLong(
                    leakDetectionThresholdMs,
                    if (devMode) DEFAULT_LEAK_DETECTION_MS else 0L,
                    "DATABASE_LEAK_DETECTION_MS",
                    warnings,
                    allowZero = true,
                ),
                warnings = warnings,
            )
        }

        private fun positiveInt(
            raw: String?,
            fallback: Int,
            variable: String,
            warnings: MutableList<String>,
        ): Int = positiveLong(raw, fallback.toLong(), variable, warnings).toInt()

        private fun positiveLong(
            raw: String?,
            fallback: Long,
            variable: String,
            warnings: MutableList<String>,
            allowZero: Boolean = false,
        ): Long {
            val value = raw?.takeUnless(String::isBlank) ?: return fallback
            val parsed = value.toLongOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }
            if (parsed == null) {
                warnings += "$variable is '$value', which is not a " +
                    "${if (allowZero) "non-negative" else "positive"} whole number. Using $fallback."
                return fallback
            }
            return parsed
        }
    }
}
