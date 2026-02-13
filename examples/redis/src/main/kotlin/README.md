# Redis Utilities (Jedis 7.x)

A simple Kotlin utility layer for **Jedis 7.x**.

## Design Goals

* Easy creation of Redis clients
* Clean transaction and pipeline APIs
* Small set of practical extension methods
* Thin wrapper over Jedis

---

## 1. Creating Redis Clients

### Standalone Client

```kotlin
val redis = newRedisClient(
    host = "localhost",
    port = 6379,
    password = "secret",
    database = 0
)
```

### Create from URI

```kotlin
val redis = newRedisClient("redis://localhost:6379/0")
```

or

```kotlin
val redis = newRedisClient(URI("redis://localhost:6379/0"))
```

### Cluster Client

```kotlin
val cluster = newRedisClusterClient(
    "10.0.0.1:6379",
    "10.0.0.2:6379",
    "10.0.0.3:6379",
    password = "secret"
)
```

---

## 2. Transactions

### Basic Usage

```kotlin
redis.transaction { tx ->
    tx.set("a", "1")
    tx.incr("counter")
}
```

### Returning Results from Transaction

```kotlin
val result = redis.transaction { tx ->
    val r1 = tx.get("key1")
    val r2 = tx.incr("counter")

    // you can return anything you want
    r1.get() to r2.get()
}

println(result)   // (valueOfKey1, newCounterValue)
```

#### Behavior

* All commands inside the block are executed atomically.
* If an exception occurs, the transaction is automatically discarded.
* You can return any custom structure from the block.

---

## 3. Pipelines

Pipeline support lets you batch multiple commands into a single network round-trip.

### Example

```kotlin
val responses = redis.pipeline { pipe ->
    val r1 = pipe.get("k1")
    val r2 = pipe.get("k2")
    val r3 = pipe.incr("counter")

    listOf(r1, r2, r3)
}

val values = responses.map { it.get() }
```

---

## 4. Other Useful Extensions

A few small helpers are included for convenience:

### Connection Health Check

```kotlin
if (redis.isAlive()) {
    println("Redis is reachable")
}
```

### Bulk Delete

```kotlin
redis.delAll(listOf("a", "b", "c"))
```

### Key Existence Checks

```kotlin
redis.existsAll("k1", "k2")
redis.existsAny("k1", "k2")
```

### Increment with Expiry

```kotlin
val (count, isNew) = redis.incrWithExpiry("rate:ip", 60)
```

### Get with Default Value

```kotlin
val name = redis.getOrDefault("username", "guest")
```
