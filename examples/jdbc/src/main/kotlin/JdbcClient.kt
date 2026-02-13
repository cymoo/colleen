import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.sql.*
import java.time.LocalDate
import java.time.LocalDateTime
import javax.sql.DataSource

// ========================================================
// Configuration
// ========================================================

/**
 * JDBC configuration object used to create a DataSource.
 *
 * Supports optional connection pooling via HikariCP.
 */
data class JdbcConfig(
    val url: String,
    val username: String? = null,
    val password: String? = null,

    val poolSize: Int = 10,
    val minIdleConnections: Int? = null,
    val maxLifetimeMs: Long? = null,
    val idleTimeoutMs: Long? = null,
    val connectionTimeoutMs: Long? = null,

    val driver: String? = null
)

// ========================================================
// Named Parameter Support
// ========================================================

/**
 * Result of parsing a SQL string with named parameters.
 *
 * @param sql JDBC-compatible SQL with '?' placeholders
 * @param paramNames ordered list of parameter names
 */
private data class ParsedSql(
    val sql: String,
    val paramNames: List<String>
)

/**
 * Utility object to parse SQL statements containing named parameters
 * in the form :paramName and convert them to JDBC positional parameters.
 */
private object NamedParameterParser {
    private val PATTERN = Regex(":(\\w+)")

    /**
     * Parses SQL with named parameters and converts it to JDBC SQL.
     */
    fun parse(sql: String): ParsedSql {
        val paramNames = mutableListOf<String>()
        val jdbcSql = PATTERN.replace(sql) { matchResult ->
            paramNames.add(matchResult.groupValues[1])
            "?"
        }
        return ParsedSql(jdbcSql, paramNames)
    }

    /**
     * Maps named parameters into an ordered list based on parsed parameter names.
     */
    fun mapToList(paramMap: Map<String, Any?>, paramNames: List<String>): List<Any?> {
        return paramNames.map { name ->
            if (!paramMap.containsKey(name))
                throw IllegalArgumentException("Missing parameter: :$name")
            paramMap[name]
        }
    }
}

// ========================================================
// DataSource Factory
// ========================================================

/**
 * Factory responsible for creating DataSource instances
 */
object DataSourceFactory {

    fun create(config: JdbcConfig): DataSource {
        // Optionally load JDBC driver class explicitly
        config.driver?.let { driverClass ->
            runCatching { Class.forName(driverClass) }
                .onFailure { ex ->
                    throw IllegalArgumentException("Driver not found: $driverClass", ex)
                }
        }

        return createHikari(config)
    }

    /**
     * Creates a HikariCP-backed pooled DataSource.
     */
    private fun createHikari(config: JdbcConfig): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.url
            username = config.username
            password = config.password

            maximumPoolSize = config.poolSize

            minimumIdle = config.minIdleConnections ?: minOf(2, config.poolSize)

            config.idleTimeoutMs?.let { idleTimeout = it }
            config.connectionTimeoutMs?.let { connectionTimeout = it }
            config.maxLifetimeMs?.let { maxLifetime = it }

            // SQLite specific
            if (jdbcUrl.contains("sqlite", ignoreCase = true)) {
                // No need for sqlite
                connectionTestQuery = null
                // Reduce idle timeout: connection for sqlite is very cheap.
                idleTimeout = 60000  // 1 minute
            }
        })
    }
}

// ========================================================
// Core JDBC Client
// ========================================================

/**
 * A simple JDBC helper providing convenient query, update,
 * batch and transaction operations.
 */
class JdbcClient private constructor(
    private val connectionProvider: ConnectionProvider
) : AutoCloseable {

    /**
     * Abstraction used to supply database connections.
     */
    private sealed interface ConnectionProvider {
        fun <T> withConnection(block: (Connection) -> T): T
        fun close()
        fun isTransactional(): Boolean = false
        fun getConnection(): Connection = error("Not transactional")
    }

    /**
     * Connection provider based on a DataSource (typically pooled).
     */
    private class PooledProvider(val dataSource: DataSource) : ConnectionProvider {
        override fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

        override fun close() {
            if (dataSource is AutoCloseable) {
                dataSource.close()
            }
        }
    }

    /**
     * Provider used when executing inside a transaction.
     */
    private class TransactionalProvider(private val connection: Connection) : ConnectionProvider {
        override fun <T> withConnection(block: (Connection) -> T): T = block(connection)
        override fun close() {}
        override fun isTransactional() = true
        override fun getConnection() = connection
    }

    // --------------------------------------------------------
    // Query APIs (positional parameters)
    // --------------------------------------------------------

    fun <T> query(sql: String, params: List<Any?>, mapper: (ResultSet) -> T): List<T> =
        execute(sql, params) { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapper(rs))
                }
            }
        }

    fun <T> query(sql: String, mapper: (ResultSet) -> T) = query(sql, emptyList(), mapper)


    fun forEach(sql: String, params: List<Any?>, handler: (ResultSet) -> Unit) {
        execute(sql, params) { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) handler(rs)
            }
        }
    }

    fun forEach(sql: String, handler: (ResultSet) -> Unit) = forEach(sql, emptyList(), handler)

    fun <T> queryOne(sql: String, params: List<Any?>, mapper: (ResultSet) -> T): T? =
        execute(sql, params) { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapper(rs) else null
            }
        }

    fun <T> queryOne(sql: String, mapper: (ResultSet) -> T) = queryOne(sql, emptyList(), mapper)

    fun queryMaps(sql: String, params: List<Any?>): List<Map<String, Any?>> =
        query(sql, params) { it.toMap() }

    fun queryMaps(sql: String) = queryMaps(sql, emptyList())

    fun queryMap(sql: String, params: List<Any?>): Map<String, Any?>? =
        execute(sql, params) { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toMap() else null
            }
        }

    fun queryMap(sql: String) = queryMap(sql, emptyList())

    fun update(sql: String, params: List<Any?>): Int =
        execute(sql, params) { it.executeUpdate() }

    fun update(sql: String) = update(sql, emptyList())

    /**
     * Executes an INSERT statement and returns the generated key if available.
     */
    fun insertAndGetKey(sql: String, params: List<Any?>): Long? =
        connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
                bindParams(stmt, params)
                stmt.executeUpdate()
                stmt.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else null
                }
            }
        }

    fun insertAndGetKey(sql: String) = insertAndGetKey(sql, emptyList())

    /**
     * Executes batch updates efficiently with optional chunking.
     */
    fun batchUpdate(
        sql: String,
        paramList: List<List<Any?>>,
        batchSize: Int = 1000,
        onResult: ((Int) -> Unit)? = null
    ): Int {
        if (paramList.isEmpty()) return 0

        return connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var totalAffected = 0
                paramList.chunked(batchSize).forEach { chunk ->
                    chunk.forEach { params ->
                        stmt.clearParameters()
                        bindParams(stmt, params)
                        stmt.addBatch()
                    }
                    val batchResults = stmt.executeBatch()
                    onResult?.let { cb ->
                        batchResults.forEach(cb)
                    }

                    totalAffected += batchResults.count { it > 0 || it == Statement.SUCCESS_NO_INFO }
                    stmt.clearBatch()
                }

                totalAffected
            }
        }
    }

    // --------------------------------------------------------
    // Query APIs (named parameters)
    // --------------------------------------------------------

    fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet) -> T): List<T> {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        return query(jdbcSql, paramList, mapper)
    }

    fun <T> queryOne(sql: String, params: Map<String, Any?>, mapper: (ResultSet) -> T): T? {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        return queryOne(jdbcSql, paramList, mapper)
    }

    fun queryMaps(sql: String, params: Map<String, Any?>): List<Map<String, Any?>> =
        query(sql, params) { it.toMap() }

    fun queryMap(sql: String, params: Map<String, Any?>): Map<String, Any?>? {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        return queryMap(jdbcSql, paramList)
    }

    fun forEach(sql: String, params: Map<String, Any?>, handler: (ResultSet) -> Unit) {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        forEach(jdbcSql, paramList, handler)
    }

    fun update(sql: String, params: Map<String, Any?>): Int {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        return update(jdbcSql, paramList)
    }

    fun insertAndGetKey(sql: String, params: Map<String, Any?>): Long? {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        return insertAndGetKey(jdbcSql, paramList)
    }

    @JvmName("batchUpdateNamed")
    fun batchUpdate(
        sql: String,
        paramMaps: List<Map<String, Any?>>,
        batchSize: Int = 1000,
        onResult: ((Int) -> Unit)? = null
    ): Int {
        if (paramMaps.isEmpty()) return 0

        val parsed = NamedParameterParser.parse(sql)
        val paramList = paramMaps.map { paramMap ->
            NamedParameterParser.mapToList(paramMap, parsed.paramNames)
        }
        return batchUpdate(parsed.sql, paramList, batchSize, onResult)
    }

    private fun parseNamed(sql: String, params: Map<String, Any?>): Pair<String, List<Any?>> {
        val parsed = NamedParameterParser.parse(sql)
        return parsed.sql to NamedParameterParser.mapToList(params, parsed.paramNames)
    }

    // --------------------------------------------------------
    // Transaction Handling
    // --------------------------------------------------------

    /**
     * Executes a block inside a transaction.
     *
     * Supports nested transactions using JDBC save points.
     */
    fun <T> transaction(block: (JdbcClient) -> T): T {
        // Nested transaction
        if (connectionProvider.isTransactional()) {
            val conn = connectionProvider.getConnection()
            val savepoint = conn.setSavepoint()
            try {
                val result = block(this)
                conn.releaseSavepoint(savepoint)
                return result
            } catch (e: Exception) {
                runCatching { conn.rollback(savepoint) }
                throw e
            }
        }

        // New transaction
        return connectionProvider.withConnection { conn ->
            val originalAutoCommit = conn.autoCommit
            try {
                conn.autoCommit = false
                val txClient = JdbcClient(TransactionalProvider(conn))
                val result = block(txClient)
                conn.commit()
                result
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = originalAutoCommit }
            }
        }
    }

    // --------------------------------------------------------
    // Core Execution Logic
    // --------------------------------------------------------

    /**
     * Executes arbitrary logic using a prepared statement.
     */
    fun <T> execute(sql: String, params: List<Any?>, block: (PreparedStatement) -> T): T =
        connectionProvider.withConnection { conn ->
            try {
                conn.prepareStatement(sql).use { stmt ->
                    bindParams(stmt, params)
                    block(stmt)
                }
            } catch (e: SQLException) {
                throw SQLException(buildErrorMessage(sql, params, e), e)
            }
        }

    fun <T> execute(sql: String, block: (PreparedStatement) -> T) = execute(sql, emptyList(), block)
    fun execute(sql: String, params: List<Any?>) = execute(sql, params) { stmt -> stmt.executeUpdate() }
    fun execute(sql: String) = execute(sql, emptyList())

    fun <T> execute(sql: String, params: Map<String, Any?>, block: (PreparedStatement) -> T): T {
        val (jdbcSql, paramList) = parseNamed(sql, params)
        return execute(jdbcSql, paramList, block)
    }

    /**
     * Execute a PRAGMA statement.
     */
    fun executePragma(pragma: String): String? {
        return connectionProvider.withConnection { conn ->
            conn.createStatement().use { stmt ->
                val hasResults = stmt.execute(pragma)
                if (hasResults) {
                    stmt.resultSet.use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                } else {
                    null
                }
            }
        }
    }

    /**
     * Binds Kotlin values to a PreparedStatement using common JDBC mappings.
     */
    private fun bindParams(stmt: PreparedStatement, params: List<Any?>) {
        params.forEachIndexed { index, value ->
            val paramIndex = index + 1
            when (value) {
                null -> stmt.setNull(paramIndex, Types.NULL)
                is String -> stmt.setString(paramIndex, value)
                is Int -> stmt.setInt(paramIndex, value)
                is Long -> stmt.setLong(paramIndex, value)
                is Double -> stmt.setDouble(paramIndex, value)
                is Float -> stmt.setFloat(paramIndex, value)
                is Boolean -> stmt.setBoolean(paramIndex, value)
                is BigDecimal -> stmt.setBigDecimal(paramIndex, value)
                is ByteArray -> stmt.setBytes(paramIndex, value)
                is LocalDate -> stmt.setObject(paramIndex, value)  // JDBC 4.2+
                is LocalDateTime -> stmt.setObject(paramIndex, value)
                is Enum<*> -> stmt.setString(paramIndex, value.name)
                else -> stmt.setObject(paramIndex, value)
            }
        }
    }

    override fun close() {
        connectionProvider.close()
    }

    private fun Any?.preview(): String = when (this) {
        null -> "null"
        is String -> {
            val text = if (length > 80) take(80) + "...(truncated)" else this
            "\"$text\""
        }

        is ByteArray -> "<ByteArray[${size}]>"
        else -> toString().take(80)
    }

    private fun buildErrorMessage(sql: String, params: List<Any?>, cause: SQLException): String {
        val paramInfo = params
            .take(10)
            .mapIndexed { i, v -> "[$i]=${v.preview()}" }
            .joinToString(", ")
            .let { if (params.size > 10) "$it, ... (${params.size - 10} more)" else it }

        val sqlPreview = sql
            .replace('\n', ' ')
            .take(500)
            .let { if (sql.length > 500) "$it...(truncated)" else it }

        return """
        SQL execution failed
        SQL: $sqlPreview
        Parameters: $paramInfo
        Error: ${cause.message}
    """.trimIndent()
    }

    // --------------------------------------------------------
    // Monitoring
    // --------------------------------------------------------

    /**
     * Returns runtime pool statistics when using HikariCP.
     */
    fun getHikariPoolStats(): Map<String, Any>? {
        val provider = connectionProvider as? PooledProvider ?: return null
        val dataSource = provider.dataSource as? HikariDataSource ?: return null

        return mapOf(
            "active" to dataSource.hikariPoolMXBean.activeConnections,
            "idle" to dataSource.hikariPoolMXBean.idleConnections,
            "total" to dataSource.hikariPoolMXBean.totalConnections,
            "waiting" to dataSource.hikariPoolMXBean.threadsAwaitingConnection
        )
    }

    // --------------------------------------------------------
    // Factory Methods
    // --------------------------------------------------------

    companion object {

        fun create(config: JdbcConfig): JdbcClient {
            val dataSource = DataSourceFactory.create(config)
            return JdbcClient(PooledProvider(dataSource))
        }

        /**
         * Convenience factory for SQLite connections.
         *
         * @param path Database file path (e.g., "app.db", ":memory:", or "file::memory:?cache=shared")
         * @param enableWAL Enable Write-Ahead Logging mode for better concurrency (default: true, disabled for in-memory databases)
         * @param poolSize Maximum number of connections in the pool (default: 3, recommended for SQLite)
         */
        fun forSQLite(
            path: String,
            enableWAL: Boolean = true,
            poolSize: Int = 3
        ): JdbcClient {
            // Normalize JDBC URL format
            val url = when {
                path.startsWith("jdbc:sqlite:") -> path
                path.startsWith("file:") -> "jdbc:sqlite:$path"
                else -> "jdbc:sqlite:$path"
            }

            val client = create(
                JdbcConfig(
                    url = url,
                    poolSize = poolSize,
                    driver = "org.sqlite.JDBC",
                    connectionTimeoutMs = 30000
                )
            )

            // Configures SQLite for optimal performance and concurrency.
            if (enableWAL && !url.contains(":memory:", ignoreCase = true)) {
                client.executePragma("PRAGMA journal_mode=WAL")
                client.executePragma("PRAGMA synchronous=NORMAL")
                client.executePragma("PRAGMA temp_store=MEMORY")
                client.executePragma("PRAGMA cache_size=-64000")  // 64MB
            }

            return client
        }

        /**
         * Convenience factory for PostgreSQL connections.
         *
         * @param host Database host, optionally with port (e.g., "localhost" or "localhost:5433")
         * @param database Database name
         * @param username Database username
         * @param password Database password
         * @param poolSize Maximum number of connections in the pool (default: 10)
         * @param schema Default schema to use (default: null, uses "public")
         */
        fun forPostgres(
            host: String,
            database: String,
            username: String,
            password: String,
            poolSize: Int = 10,
            schema: String? = null
        ): JdbcClient {
            // Add default port if not specified
            val hostWithPort = if (host.contains(":")) host else "$host:5432"

            // Build JDBC URL with recommended parameters
            val url = buildString {
                append("jdbc:postgresql://$hostWithPort/$database")

                val params = mutableListOf<String>()

                // Set default schema if specified
                schema?.let { params.add("currentSchema=$it") }

                // Performance optimizations
                params.add("prepareThreshold=5")
                params.add("preparedStatementCacheQueries=256")

                // Connection settings
                params.add("connectTimeout=10")
                params.add("socketTimeout=60")
                params.add("tcpKeepAlive=true")

                if (params.isNotEmpty()) {
                    append("?${params.joinToString("&")}")
                }
            }

            return create(
                JdbcConfig(
                    url = url,
                    username = username,
                    password = password,
                    poolSize = poolSize,
                    connectionTimeoutMs = 30_000,
                    idleTimeoutMs = 600_000,
                    maxLifetimeMs = 1_800_000,
                    driver = "org.postgresql.Driver"
                )
            )
        }

        /**
         * Convenience factory for MySQL connections.
         *
         * @param host Database host, optionally with port (e.g., "localhost" or "localhost:3307")
         * @param database Database name
         * @param username Database username
         * @param password Database password
         * @param poolSize Maximum number of connections in the pool (default: 10)
         */
        fun forMySQL(
            host: String,
            database: String,
            username: String,
            password: String,
            poolSize: Int = 10
        ): JdbcClient {
            // Add default port if not specified
            val hostWithPort = if (host.contains(":")) host else "$host:3306"

            // Build JDBC URL with recommended parameters
            val params = listOf(
                "useUnicode=true",
                "characterEncoding=UTF-8",
                "serverTimezone=UTC",
                "cachePrepStmts=true",
                "prepStmtCacheSize=250",
                "useServerPrepStmts=true",
                "rewriteBatchedStatements=true"
            )

            val url = "jdbc:mysql://$hostWithPort/$database?${params.joinToString("&")}"

            return create(
                JdbcConfig(
                    url = url,
                    username = username,
                    password = password,
                    poolSize = poolSize,
                    connectionTimeoutMs = 30000,
                    idleTimeoutMs = 600000,
                    maxLifetimeMs = 1800000,
                    driver = "com.mysql.cj.jdbc.Driver"
                )
            )
        }
    }
}

// ========================================================
// ResultSet Extensions
// ========================================================

/**
 * Converts the current row of a ResultSet into a Map.
 */
fun ResultSet.toMap(): Map<String, Any?> {
    val meta = metaData
    return buildMap {
        for (i in 1..meta.columnCount) {
            put(meta.getColumnLabel(i), getObject(i))
        }
    }
}

// Nullable getters for common JDBC types

fun ResultSet.getIntOrNull(column: String): Int? =
    getInt(column).takeUnless { wasNull() }

fun ResultSet.getLongOrNull(column: String): Long? =
    getLong(column).takeUnless { wasNull() }

fun ResultSet.getDoubleOrNull(column: String): Double? =
    getDouble(column).takeUnless { wasNull() }

fun ResultSet.getBooleanOrNull(column: String): Boolean? =
    getBoolean(column).takeUnless { wasNull() }

fun ResultSet.getStringOrNull(column: String): String? =
    getString(column)

fun ResultSet.getLocalDateOrNull(column: String): LocalDate? =
    runCatching { getObject(column, LocalDate::class.java) }.getOrNull()

fun ResultSet.getLocalDateTimeOrNull(column: String): LocalDateTime? =
    runCatching { getObject(column, LocalDateTime::class.java) }.getOrNull()

fun ResultSet.getBigDecimalOrNull(column: String): BigDecimal? =
    getBigDecimal(column)
