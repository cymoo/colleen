package com.example.app.db

import com.example.app.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

class Database(config: DatabaseConfig) : AutoCloseable {
    private val dataSource: HikariDataSource
    val dsl: DSLContext

    init {
        System.setProperty("org.jooq.no-logo", "true")

        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = config.maximumPoolSize
                minimumIdle = 1
                poolName = "ColleenPostgresPool"
            }
        )

        if (config.migrateOnStart) {
            migrate()
        }

        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
    }

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun ready(): Boolean {
        return dsl.fetchValue("select 1") == 1
    }

    override fun close() {
        dataSource.close()
    }
}
