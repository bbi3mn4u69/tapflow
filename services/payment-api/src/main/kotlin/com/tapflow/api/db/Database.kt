package com.tapflow.api.db

import com.tapflow.api.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection

class Database private constructor(
    private val pool: HikariDataSource,
    private val migrations: Map<String, Int?>,
    poolSize: Int,
) : AutoCloseable {
    private val dispatcher = Dispatchers.IO.limitedParallelism(poolSize)

    companion object {
        fun open(config: DatabaseConfig): Database {
            val pool = HikariDataSource(HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.poolSize
                minimumIdle = 0
                connectionTimeout = 5_000
                validationTimeout = 2_000
                initializationFailTimeout = 10_000
                schema = "tapflow"
                poolName = "tapflow-db"
                addDataSourceProperty("options", "-c timezone=UTC -c statement_timeout=10000 -c lock_timeout=3000")
            })
            try {
                val flyway = Flyway.configure().dataSource(pool).schemas("tapflow").defaultSchema("tapflow")
                    .validateMigrationNaming(true).cleanDisabled(true).load()
                flyway.migrate()
                val migrations = flyway.info().applied().filter { it.version != null }
                    .associate { it.version.toString() to it.checksum }
                check(migrations.isNotEmpty()) { "Database migrations are missing" }
                return Database(pool, migrations, config.poolSize)
            } catch (failure: Throwable) {
                pool.close()
                throw failure
            }
        }
    }

    suspend fun ready(): Boolean = withContext(dispatcher) {
        if (pool.isClosed) return@withContext false
        try {
            // Compare the startup-validated history in one round trip, including on remote databases.
            val current = pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.maxRows = migrations.size + 1
                    statement.executeQuery("select version,checksum,success from flyway_schema_history where version is not null").use { rows ->
                        buildMap {
                            var count = 0
                            while (rows.next()) {
                                if (++count > migrations.size || !rows.getBoolean(3)) return@withContext false
                                put(rows.getString(1), rows.getObject(2, Int::class.javaObjectType))
                            }
                        }
                    }
                }
            }
            current == migrations
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            false
        }
    }

    suspend fun <T> query(block: (Connection) -> T): T = withContext(dispatcher) { transaction(block) }

    fun <T> transaction(block: (Connection) -> T): T = pool.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    override fun close() = pool.close()
}
