package config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

object DatabaseConfig {
    private const val DB_PATH = "chat.db"
    private const val JDBC_URL = "jdbc:sqlite:$DB_PATH"

    fun createDataSource(dbUrl: String): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 10
            isAutoCommit = true
        }
        return HikariDataSource(config)
    }

    fun runMigrations(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()

        flyway.migrate()
    }

    fun createDSLContext(dataSource: DataSource): DSLContext {
        return DSL.using(dataSource, SQLDialect.SQLITE)
    }
}
