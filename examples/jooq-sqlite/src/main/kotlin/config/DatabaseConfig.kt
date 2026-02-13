package config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.ResultQuery
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.sql.Connection

object DatabaseConfig {
    private val projectDir: String = System.getProperty("user.dir")
    private val dbUrl = "jdbc:sqlite:${projectDir}/examples/jooq-sqlite/demo.db"

    private var dataSource: HikariDataSource

    init {
        val config = HikariConfig().apply {
            jdbcUrl = dbUrl

            driverClassName = "org.sqlite.JDBC"

            maximumPoolSize = 10
            minimumIdle = 2
            idleTimeout = 30_000
            maxLifetime = 1_800_000
            connectionTimeout = 30_000

            poolName = "SQLite-Hikari-Pool"

            // Performance Tuning: Enable below configurations in production.
            // addDataSourceProperty("journal_mode", "WAL")
            // addDataSourceProperty("busy_timeout", "5000")
        }

        dataSource = HikariDataSource(config)

        System.setProperty("org.jooq.no-logo", "true")
    }

    fun getConnection(): Connection {
        return dataSource.connection
    }

    fun createDSLContext(): DSLContext {
        return DSL.using(dataSource, SQLDialect.SQLITE)
    }

    fun <T> transaction(block: (DSLContext) -> T): T {
        return createDSLContext().transaction(block)
    }

    fun close() {
        dataSource.close()
    }
}

fun <T> DSLContext.transaction(block: (DSLContext) -> T): T {
    return this.transactionResult { config ->
        block(DSL.using(config))
    }
}

inline fun <reified T : Any> ResultQuery<*>.fetchIntoClass(): List<T> {
    return this.fetchInto(T::class.java)
}

inline fun <reified T : Any> ResultQuery<*>.fetchOneIntoClass(): T? {
    return this.fetchOneInto(T::class.java)
}