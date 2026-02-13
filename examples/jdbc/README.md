# JdbcClient – Lightweight Kotlin JDBC Utility

A simple JDBC helper library.
It simplifies common database operations while keeping full flexibility of raw JDBC.

## Features

* Minimal abstraction over JDBC
* Positional and named SQL parameters
* Query mapping with lambdas
* Batch execution support
* Transaction with savepoint-based nesting
* Connection pooling (HikariCP)

## Create a client

### PostgreSQL

```kotlin
val client = JdbcClient.forPostgres(
    host = "localhost",
    database = "testdb",
    username = "postgres",
    password = "secret"
)
```

### SQLite

```kotlin
val client = JdbcClient.forSQLite("app.db")
```

### Custom configuration

```kotlin
val client = JdbcClient.create(
    JdbcConfig(
        url = "jdbc:postgresql://localhost/mydb",
        username = "user",
        password = "pass",
        poolSize = 20
    )
)
```

## Basic Queries

### Query list of objects

```kotlin
data class User(val id: Long, val name: String)

val users = client.query("SELECT id, name FROM users") { rs ->
    User(
        id = rs.getLong("id"),
        name = rs.getString("name")
    )
}
```

### Query for single result

```kotlin
val user = client.queryOne(
    "SELECT id, name FROM users WHERE id = ?",
    listOf(1)
) { rs ->
    User(rs.getLong("id"), rs.getString("name"))
}
```

Returns `null` if no result.

### Query as Map

```kotlin
val rows: List<Map<String, Any?>> =
    client.queryForMap("SELECT * FROM users")
```

### Named Parameters

Supports `:name` style parameters.

```kotlin
val users = client.query(
    "SELECT * FROM users WHERE age > :age AND status = :status",
    mapOf(
        "age" to 18,
        "status" to "ACTIVE"
    )
) { rs ->
    rs.toMap()
}
```

## Updates

### Simple update

```kotlin
val affected = client.update(
    "UPDATE users SET status = ? WHERE id = ?",
    listOf("DISABLED", 5)
)
```

### Insert and return generated key

```kotlin
val id = client.insertAndGetKey(
    "INSERT INTO users(name, email) VALUES(?, ?)",
    listOf("Neo", "neo@test.com")
)
```

### Named parameter insert

```kotlin
val id = client.insertAndGetKey(
    "INSERT INTO users(name, email) VALUES(:name, :email)",
    mapOf(
        "name" to "Trinity",
        "email" to "trinity@test.com"
    )
)
```

## Batch Operations

```kotlin
val total = client.batchUpdate(
    "INSERT INTO logs(message, level) VALUES(?, ?)",
    listOf(
        listOf("hello", "INFO"),
        listOf("error happened", "ERROR")
    )
)
```

With named parameters:

```kotlin
client.batchUpdate(
    "INSERT INTO logs(message, level) VALUES(:msg, :level)",
    listOf(
        mapOf("msg" to "a", "level" to "INFO"),
        mapOf("msg" to "b", "level" to "DEBUG")
    )
)
```

## Transactions

```kotlin
client.transaction { tx ->
    tx.update("UPDATE account SET balance = balance - 100 WHERE id = 1")
    tx.update("UPDATE account SET balance = balance + 100 WHERE id = 2")
}
```

If any exception occurs, the whole transaction is rolled back.

### Nested Transactions

Nested calls automatically use **JDBC Savepoints**:

```kotlin
client.transaction { tx ->
    tx.update("...")

    tx.transaction { inner ->
        inner.update("...")
    }
}
```

* Inner failure → rollback to savepoint
* Outer failure → rollback entire transaction

## ResultSet Utilities

Convenient nullable getters:

```kotlin
val age: Int? = rs.getIntOrNull("age")
val created: LocalDateTime? = rs.getLocalDateTimeOrNull("created_at")
```

Convert current row to map:

```kotlin
val map = rs.toMap()
```

## Monitoring

When using HikariCP, you can inspect pool status:

```kotlin
val stats = client.getHikariPoolStats()

println(stats)
// {active=2, idle=8, total=10, waiting=0}
```

## Thread Safety

* `JdbcClient` instances backed by a DataSource are thread-safe
* Transactional clients should be used only within the transaction scope
* ResultSet and PreparedStatement are not thread-safe (standard JDBC rule)

## When to Use

Ideal when you need:

* More control than ORM frameworks
* Simpler code than raw JDBC
* Lightweight alternative to JDBI / Spring JdbcTemplate
* Small services, tools, or microservices
* Direct SQL with Kotlin style

## Limitations

JdbcClient intentionally does **not** provide:

* Entity mapping
* Automatic schema migration
* Query builders
* Connection retry logic

It focuses purely on making JDBC easier.
