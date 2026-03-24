package io.github.cymoo.colleen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// ============================================================================
// Fixtures
// ============================================================================

private interface Repository
private interface Cache

private class UserRepository : Repository
private class OrderRepository : Repository
private class MemoryCache : Cache

private data class Named(val name: String)

// Qualifier objects — preferred over strings for type safety
private object Primary
private object Replica

// ============================================================================
// Tests
// ============================================================================

class ServiceContainerTest {

    private lateinit var container: ServiceContainer

    @BeforeEach
    fun setUp() {
        container = ServiceContainer()
    }

    // ========================================================================
    // registerInstance
    // ========================================================================

    @Nested
    inner class RegisterInstance {

        @Test
        fun `returns the exact instance that was registered`() {
            val instance = UserRepository()
            container.registerInstance<Repository>(instance)
            assertSame(instance, container.get<Repository>())
        }

        @Test
        fun `overwrites a previously registered instance of the same type`() {
            val first = UserRepository()
            val second = UserRepository()
            container.registerInstance<Repository>(first)
            container.registerInstance<Repository>(second)
            assertSame(second, container.get<Repository>())
        }

        @Test
        fun `overwrites a previously registered factory of the same type`() {
            // registerInstance should take precedence over a prior registerSingleton
            container.registerSingleton<Repository> { OrderRepository() }
            val instance = UserRepository()
            container.registerInstance<Repository>(instance)
            assertSame(instance, container.get<Repository>())
        }

        @Test
        fun `supports string qualifier`() {
            val primary = UserRepository()
            val replica = OrderRepository()
            container.registerInstance<Repository>(primary, qualifier = "primary")
            container.registerInstance<Repository>(replica, qualifier = "replica")
            assertSame(primary, container.get<Repository>("primary"))
            assertSame(replica, container.get<Repository>("replica"))
        }

        @Test
        fun `supports object qualifier`() {
            val primary = UserRepository()
            val replica = OrderRepository()
            container.registerInstance<Repository>(primary, qualifier = Primary)
            container.registerInstance<Repository>(replica, qualifier = Replica)
            assertSame(primary, container.get<Repository>(Primary))
            assertSame(replica, container.get<Repository>(Replica))
        }

        @Test
        fun `no-qualifier and qualified registrations coexist independently`() {
            val default = UserRepository()
            val qualified = OrderRepository()
            container.registerInstance<Repository>(default)
            container.registerInstance<Repository>(qualified, qualifier = Primary)
            assertSame(default, container.get<Repository>())
            assertSame(qualified, container.get<Repository>(Primary))
        }

        @Test
        fun `supports chaining`() {
            val result = container
                .registerInstance(UserRepository())
                .registerInstance(MemoryCache())
            assertSame(container, result)
        }
    }

    // ========================================================================
    // registerSingleton
    // ========================================================================

    @Nested
    inner class RegisterSingleton {

        @Test
        fun `factory is invoked lazily - not called until first retrieval`() {
            var invocations = 0
            container.registerSingleton<Repository> { invocations++; UserRepository() }
            assertEquals(0, invocations)
            container.get<Repository>()
            assertEquals(1, invocations)
        }

        @Test
        fun `factory is invoked exactly once across multiple sequential retrievals`() {
            var invocations = 0
            container.registerSingleton<Repository> { invocations++; UserRepository() }
            repeat(5) { container.get<Repository>() }
            assertEquals(1, invocations)
        }

        @Test
        fun `returns the same instance on every retrieval`() {
            container.registerSingleton { UserRepository() }
            val a = container.get<UserRepository>()
            val b = container.get<UserRepository>()
            assertSame(a, b)
        }

        @Test
        fun `qualifiers produce independent singleton instances`() {
            var primaryCount = 0
            var replicaCount = 0
            container.registerSingleton<Repository>(Primary) { primaryCount++; UserRepository() }
            container.registerSingleton<Repository>(Replica) { replicaCount++; OrderRepository() }

            repeat(3) {
                container.get<Repository>(Primary)
                container.get<Repository>(Replica)
            }

            assertEquals(1, primaryCount)
            assertEquals(1, replicaCount)
        }

        @Test
        fun `factory is invoked exactly once under concurrent access`() {
            // Validates that computeIfAbsent (or equivalent) is used internally,
            // not Kotlin's getOrPut which does not guarantee single invocation.
            val invocations = AtomicInteger(0)
            val threadCount = 50
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)

            container.registerSingleton<Repository> {
                invocations.incrementAndGet()
                Thread.sleep(10) // widen the race window
                UserRepository()
            }

            val futures = (1..threadCount).map {
                executor.submit { latch.await(); container.get<Repository>() }
            }
            latch.countDown()
            futures.forEach { it.get() }
            executor.shutdown()

            assertEquals(1, invocations.get())
        }

        @Test
        fun `all concurrent callers receive the same instance`() {
            val threadCount = 50
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val results = mutableListOf<Repository>()

            container.registerSingleton<Repository> {
                Thread.sleep(10)
                UserRepository()
            }

            val futures = (1..threadCount).map {
                executor.submit<Repository> { latch.await(); container.get() }
            }
            latch.countDown()
            futures.forEach { results.add(it.get()) }
            executor.shutdown()

            // Every thread must have received the exact same instance
            val first = results.first()
            assertTrue(results.all { it === first })
        }
    }

    // ========================================================================
    // registerTransient
    // ========================================================================

    @Nested
    inner class RegisterTransient {

        @Test
        fun `factory is invoked on every retrieval`() {
            var invocations = 0
            container.registerTransient<Repository> { invocations++; UserRepository() }
            repeat(3) { container.get<Repository>() }
            assertEquals(3, invocations)
        }

        @Test
        fun `each retrieval returns a distinct instance`() {
            container.registerTransient { UserRepository() }
            val a = container.get<UserRepository>()
            val b = container.get<UserRepository>()
            assertNotSame(a, b)
        }

        @Test
        fun `qualified transient services are independent`() {
            var primaryCount = 0
            var replicaCount = 0
            container.registerTransient<Repository>(Primary) { primaryCount++; UserRepository() }
            container.registerTransient<Repository>(Replica) { replicaCount++; OrderRepository() }

            repeat(3) {
                container.get<Repository>(Primary)
                container.get<Repository>(Replica)
            }

            assertEquals(3, primaryCount)
            assertEquals(3, replicaCount)
        }
    }

    // ========================================================================
    // get / getOrNull / getOrDefault / getOrElse
    // ========================================================================

    @Nested
    inner class Retrieval {

        @Test
        fun `get throws NoSuchElementException for unregistered service`() {
            assertThrows<NoSuchElementException> { container.get<Repository>() }
        }

        @Test
        fun `get error message includes the type name`() {
            val ex = assertThrows<NoSuchElementException> { container.get<Repository>() }
            assertTrue(ex.message!!.contains("Repository"))
        }

        @Test
        fun `get error message includes the qualifier`() {
            val ex = assertThrows<NoSuchElementException> {
                container.get<Repository>("primary")
            }
            assertTrue(ex.message!!.contains("primary"))
        }

        @Test
        fun `getOrNull returns null for unregistered service`() {
            assertNull(container.getOrNull<Repository>())
        }

        @Test
        fun `getOrNull returns null when qualifier does not match`() {
            container.registerInstance<Repository>(UserRepository(), qualifier = Primary)
            assertNull(container.getOrNull<Repository>(Replica))
            assertNull(container.getOrNull<Repository>())
        }

        @Test
        fun `getOrDefault returns the registered instance when present`() {
            val instance = UserRepository()
            container.registerInstance<Repository>(instance)
            assertSame(instance, container.getOrDefault<Repository>(OrderRepository()))
        }

        @Test
        fun `getOrDefault returns the default value when not registered`() {
            val default = UserRepository()
            assertSame(default, container.getOrDefault<Repository>(default))
        }

        @Test
        fun `getOrDefault respects qualifier`() {
            val instance = UserRepository()
            container.registerInstance<Repository>(instance, qualifier = Primary)
            val default = OrderRepository()
            // Wrong qualifier → default
            assertSame(default, container.getOrDefault<Repository>(default, qualifier = Replica))
            // Correct qualifier → registered instance
            assertSame(instance, container.getOrDefault<Repository>(default, qualifier = Primary))
        }

        @Test
        fun `getOrElse returns the registered instance when present`() {
            val instance = UserRepository()
            container.registerInstance<Repository>(instance)
            assertSame(instance, container.getOrElse<Repository> { OrderRepository() })
        }

        @Test
        fun `getOrElse invokes the fallback factory when not registered`() {
            val fallback = UserRepository()
            val result = container.getOrElse<Repository> { fallback }
            assertSame(fallback, result)
        }

        @Test
        fun `getOrElse fallback factory is not invoked when service is present`() {
            container.registerInstance<Repository>(UserRepository())
            var factoryInvoked = false
            container.getOrElse<Repository> { factoryInvoked = true; OrderRepository() }
            assertFalse(factoryInvoked)
        }

        @Test
        fun `instance map takes precedence over factory map for the same key`() {
            // Register a factory first, then override with a direct instance.
            val instance = UserRepository()
            container.registerSingleton<Repository> { OrderRepository() }
            container.registerInstance<Repository>(instance)
            assertSame(instance, container.get<Repository>())
        }
    }

    // ========================================================================
    // getAll
    // ========================================================================

    @Nested
    inner class GetAll {

        @Test
        fun `returns empty list when no services of the type are registered`() {
            assertTrue(container.getAll<Repository>().isEmpty())
        }

        @Test
        fun `returns all instances regardless of qualifier`() {
            val a = UserRepository()
            val b = OrderRepository()
            container.registerInstance<Repository>(a)
            container.registerInstance<Repository>(b, qualifier = Primary)
            val all = container.getAll<Repository>()
            assertEquals(2, all.size)
            assertTrue(all.contains(a))
            assertTrue(all.contains(b))
        }

        @Test
        fun `resolves unresolved singleton factories eagerly`() {
            container.registerSingleton<Repository> { UserRepository() }
            container.registerSingleton<Repository>(Primary) { OrderRepository() }
            assertEquals(2, container.getAll<Repository>().size)
        }

        @Test
        fun `does not include services of a different type`() {
            container.registerInstance<Repository>(UserRepository())
            container.registerInstance<Cache>(MemoryCache())
            assertEquals(1, container.getAll<Repository>().size)
        }

        @Test
        fun `singleton factory is cached after getAll resolves it`() {
            var invocations = 0
            container.registerSingleton<Repository> { invocations++; UserRepository() }
            container.getAll<Repository>()  // triggers resolution and caching
            container.get<Repository>()     // should use cached instance
            assertEquals(1, invocations)
        }

        @Test
        fun `already-cached instances are not re-created during getAll`() {
            var invocations = 0
            container.registerSingleton<Repository> { invocations++; UserRepository() }
            container.get<Repository>()     // resolve and cache
            container.getAll<Repository>()  // must not invoke factory again
            assertEquals(1, invocations)
        }
    }

    // ========================================================================
    // has
    // ========================================================================

    @Nested
    inner class Has {

        @Test
        fun `returns false for unregistered service`() {
            assertFalse(container.has<Repository>())
        }

        @Test
        fun `returns true after registerInstance`() {
            container.registerInstance<Repository>(UserRepository())
            assertTrue(container.has<Repository>())
        }

        @Test
        fun `returns true after registerSingleton`() {
            container.registerSingleton<Repository> { UserRepository() }
            assertTrue(container.has<Repository>())
        }

        @Test
        fun `returns true after registerTransient`() {
            container.registerTransient<Repository> { UserRepository() }
            assertTrue(container.has<Repository>())
        }

        @Test
        fun `qualifier must match exactly`() {
            container.registerInstance<Repository>(UserRepository(), qualifier = Primary)
            assertFalse(container.has<Repository>())          // no qualifier
            assertFalse(container.has<Repository>(Replica))   // wrong qualifier
            assertTrue(container.has<Repository>(Primary))    // correct qualifier
        }
    }

    // ========================================================================
    // remove
    // ========================================================================

    @Nested
    inner class Remove {

        @Test
        fun `returns false when service is not registered`() {
            assertFalse(container.remove<Repository>())
        }

        @Test
        fun `returns true and removes a registered instance`() {
            container.registerInstance<Repository>(UserRepository())
            assertTrue(container.remove<Repository>())
            assertFalse(container.has<Repository>())
        }

        @Test
        fun `returns true and removes a registered factory`() {
            container.registerSingleton<Repository> { UserRepository() }
            assertTrue(container.remove<Repository>())
            assertFalse(container.has<Repository>())
        }

        @Test
        fun `removes both instance and factory when both exist for the same key`() {
            // Manually put both to simulate the edge case
            container.registerSingleton<Repository> { UserRepository() }
            container.get<Repository>() // triggers caching into instances map
            // Now both maps contain the key; remove should clear both
            assertTrue(container.remove<Repository>())
            assertFalse(container.has<Repository>())
            assertNull(container.getOrNull<Repository>())
        }

        @Test
        fun `only removes the entry matching the qualifier`() {
            container.registerInstance<Repository>(UserRepository())
            container.registerInstance<Repository>(OrderRepository(), qualifier = Primary)
            container.remove<Repository>(Primary)
            assertTrue(container.has<Repository>())
            assertFalse(container.has<Repository>(Primary))
        }

        @Test
        fun `service is not retrievable after removal`() {
            container.registerInstance<Repository>(UserRepository())
            container.remove<Repository>()
            assertNull(container.getOrNull<Repository>())
        }
    }

    // ========================================================================
    // clear
    // ========================================================================

    @Nested
    inner class Clear {

        @Test
        fun `removes all registered services across all types`() {
            container.registerInstance<Repository>(UserRepository())
            container.registerSingleton<Cache> { MemoryCache() }
            container.registerTransient { Named("x") }
            container.clear()
            assertFalse(container.has<Repository>())
            assertFalse(container.has<Cache>())
            assertFalse(container.has<Named>())
        }

        @Test
        fun `registeredServices is empty after clear`() {
            container.registerInstance<Repository>(UserRepository())
            container.clear()
            assertTrue(container.registeredServices.isEmpty())
        }

        @Test
        fun `container is usable again after clear`() {
            container.registerInstance<Repository>(UserRepository())
            container.clear()
            val newInstance = OrderRepository()
            container.registerInstance<Repository>(newInstance)
            assertSame(newInstance, container.get<Repository>())
        }
    }

    // ========================================================================
    // registeredServices
    // ========================================================================

    @Nested
    inner class RegisteredServices {

        @Test
        fun `is empty for a fresh container`() {
            assertTrue(container.registeredServices.isEmpty())
        }

        @Test
        fun `contains keys for all registered services`() {
            container.registerInstance<Repository>(UserRepository())
            container.registerSingleton<Cache> { MemoryCache() }
            val keys = container.registeredServices
            assertTrue(keys.contains(ServiceKey(Repository::class)))
            assertTrue(keys.contains(ServiceKey(Cache::class)))
        }

        @Test
        fun `includes qualifier in the reported key`() {
            container.registerInstance<Repository>(UserRepository(), qualifier = Primary)
            val keys = container.registeredServices
            assertTrue(keys.contains(ServiceKey(Repository::class, Primary)))
            assertFalse(keys.contains(ServiceKey(Repository::class)))
        }

        @Test
        fun `reflects removal - key is absent after remove`() {
            container.registerInstance<Repository>(UserRepository())
            container.remove<Repository>()
            assertFalse(container.registeredServices.contains(ServiceKey(Repository::class)))
        }
    }

    // ========================================================================
    // ServiceKey
    // ========================================================================

    @Nested
    inner class ServiceKeyTest {

        @Test
        fun `keys with same type and no qualifier are equal`() {
            assertEquals(ServiceKey(Repository::class), ServiceKey(Repository::class))
        }

        @Test
        fun `keys with same type and same string qualifier are equal`() {
            assertEquals(
                ServiceKey(Repository::class, "primary"),
                ServiceKey(Repository::class, "primary"),
            )
        }

        @Test
        fun `keys with same type and same object qualifier are equal`() {
            assertEquals(
                ServiceKey(Repository::class, Primary),
                ServiceKey(Repository::class, Primary),
            )
        }

        @Test
        fun `keys with same type but different qualifiers are not equal`() {
            assertNotEquals(
                ServiceKey(Repository::class, Primary),
                ServiceKey(Repository::class, Replica),
            )
        }

        @Test
        fun `key with null qualifier differs from key with non-null qualifier`() {
            assertNotEquals(
                ServiceKey(Repository::class, null),
                ServiceKey(Repository::class, Primary),
            )
        }

        @Test
        fun `keys with different types are not equal`() {
            assertNotEquals(
                ServiceKey(Repository::class),
                ServiceKey(Cache::class),
            )
        }

        @Test
        fun `string qualifier and object with same toString are not equal`() {
            // "Primary" string != Primary object — qualifier equality is by identity/equals,
            // not by string representation
            assertNotEquals(
                ServiceKey(Repository::class, "Primary"),
                ServiceKey(Repository::class, Primary),
            )
        }
    }

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    @Nested
    inner class JavaCompatibility {

        @Test
        fun `registerInstance via Class returns the registered instance`() {
            val instance = UserRepository()
            container.registerInstance(Repository::class.java, instance)
            assertSame(instance, container.get<Repository>())
        }

        @Test
        fun `registerSingleton via Class invokes factory exactly once`() {
            var invocations = 0
            container.registerSingleton(Repository::class.java) { invocations++; UserRepository() }
            repeat(3) { container.get<Repository>() }
            assertEquals(1, invocations)
        }

        @Test
        fun `registerTransient via Class invokes factory on every retrieval`() {
            var invocations = 0
            container.registerTransient(Repository::class.java) { invocations++; UserRepository() }
            repeat(3) { container.get<Repository>() }
            assertEquals(3, invocations)
        }

        @Test
        fun `Java registration with qualifier is retrievable with the same qualifier`() {
            val instance = UserRepository()
            container.registerInstance(Repository::class.java, instance, qualifier = Primary)
            assertSame(instance, container.get<Repository>(Primary))
            assertNull(container.getOrNull<Repository>())
        }

        @Test
        fun `Java singleton is thread-safe - invoked exactly once under concurrency`() {
            val invocations = AtomicInteger(0)
            val threadCount = 50
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)

            container.registerSingleton(Repository::class.java) {
                invocations.incrementAndGet()
                Thread.sleep(10)
                UserRepository()
            }

            val futures = (1..threadCount).map {
                executor.submit { latch.await(); container.get<Repository>() }
            }
            latch.countDown()
            futures.forEach { it.get() }
            executor.shutdown()

            assertEquals(1, invocations.get())
        }
    }

    // ========================================================================
    // AutoCloseable lifecycle
    // ========================================================================

    @Nested
    inner class AutoCloseableLifecycle {

        private inner class CloseableService : AutoCloseable {
            var closedCount = 0
            override fun close() { closedCount++ }
        }

        private inner class FailingCloseService : AutoCloseable {
            var closedAttempted = false
            override fun close() {
                closedAttempted = true
                throw RuntimeException("close failed")
            }
        }

        private inner class NonCloseableService

        // --- remove ---

        @Test
        fun `remove closes AutoCloseable instance`() {
            val svc = CloseableService()
            container.registerInstance(svc)
            container.remove<CloseableService>()
            assertEquals(1, svc.closedCount)
        }

        @Test
        fun `remove closes resolved singleton instance`() {
            val svc = CloseableService()
            container.registerSingleton<CloseableService> { svc }
            container.get<CloseableService>() // resolve and cache
            container.remove<CloseableService>()
            assertEquals(1, svc.closedCount)
        }

        @Test
        fun `remove does not close unresolved singleton factory`() {
            // factory never invoked → nothing to close
            val svc = CloseableService()
            container.registerSingleton<CloseableService> { svc }
            container.remove<CloseableService>()
            assertEquals(0, svc.closedCount)
        }

        @Test
        fun `remove does not close transient instances - they are not owned by container`() {
            val svc = CloseableService()
            container.registerTransient<CloseableService> { svc }
            container.remove<CloseableService>()
            assertEquals(0, svc.closedCount)
        }

        @Test
        fun `remove closes instance with qualifier`() {
            val primary = CloseableService()
            val replica = CloseableService()
            container.registerInstance(primary, qualifier = Primary)
            container.registerInstance(replica, qualifier = Replica)
            container.remove<CloseableService>(Primary)
            assertEquals(1, primary.closedCount)
            assertEquals(0, replica.closedCount)
        }

        @Test
        fun `remove does not throw when close fails`() {
            container.registerInstance(FailingCloseService())
            assertDoesNotThrow { container.remove<FailingCloseService>() }
        }

        @Test
        fun `remove still returns true when close fails`() {
            container.registerInstance(FailingCloseService())
            assertTrue(container.remove<FailingCloseService>())
        }

        @Test
        fun `remove does not attempt to close non-AutoCloseable service`() {
            // Just verifying no ClassCastException or unexpected behavior
            container.registerInstance(NonCloseableService())
            assertDoesNotThrow { container.remove<NonCloseableService>() }
        }

        // --- clear ---

        @Test
        fun `clear closes all AutoCloseable instances`() {
            val a = CloseableService()
            val b = CloseableService()
            container.registerInstance(a)
            container.registerInstance(b, qualifier = Primary)
            container.clear()
            assertEquals(1, a.closedCount)
            assertEquals(1, b.closedCount)
        }

        @Test
        fun `clear closes resolved singleton but not unresolved factory`() {
            val resolved = CloseableService()
            val unresolved = CloseableService()
            container.registerSingleton<CloseableService> { resolved }
            container.get<CloseableService>() // resolve
            container.registerSingleton<CloseableService>(Primary) { unresolved }
            // unresolved factory → unresolved instance never created
            container.clear()
            assertEquals(1, resolved.closedCount)
            assertEquals(0, unresolved.closedCount)
        }

        @Test
        fun `clear does not throw when one service fails to close`() {
            container.registerInstance(FailingCloseService())
            container.registerInstance(CloseableService())
            assertDoesNotThrow { container.clear() }
        }

        @Test
        fun `clear closes remaining services even if one close fails`() {
            val good = CloseableService()
            container.registerInstance(FailingCloseService())
            container.registerInstance(good)
            container.clear()
            // good must still be closed despite the other one throwing
            assertEquals(1, good.closedCount)
        }

        @Test
        fun `clear does not close the same instance twice when both instance and factory maps held it`() {
            // After get(), singleton is in both factories and instances maps
            val svc = CloseableService()
            container.registerSingleton<CloseableService> { svc }
            container.get<CloseableService>()
            // clear() iterates instances only — should close exactly once
            container.clear()
            assertEquals(1, svc.closedCount)
        }
    }
}