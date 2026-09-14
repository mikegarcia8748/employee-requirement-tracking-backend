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
 * Defaults to in-memory H2 when `DATABASE_URL` is unset, so tests and a fresh checkout run with no
 * external service. Postgres is the production target.
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
) {
    companion object {
        fun fromEnvironment(): DatabaseConfig {
            val url = System.getenv("DATABASE_URL")
                ?: "jdbc:h2:mem:ert;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            return DatabaseConfig(
                url = url,
                user = System.getenv("DATABASE_USER") ?: "sa",
                password = System.getenv("DATABASE_PASSWORD") ?: "",
                driverClassName = if (url.startsWith("jdbc:postgresql")) {
                    "org.postgresql.Driver"
                } else {
                    "org.h2.Driver"
                },
                maxPoolSize = System.getenv("DATABASE_MAX_POOL_SIZE")?.toIntOrNull() ?: 10,
            )
        }
    }
}
