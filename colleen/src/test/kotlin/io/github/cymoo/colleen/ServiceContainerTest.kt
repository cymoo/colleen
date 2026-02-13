package io.github.cymoo.colleen

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

// Test interfaces and classes
interface UserService
class UserServiceImpl : UserService

interface DatabaseService
class DatabaseServiceImpl : DatabaseService

class SimpleService
class AnotherService

class ServiceContainerTest {

    private lateinit var container: ServiceContainer

    @BeforeEach
    fun setUp() {
        container = ServiceContainer()
    }

    // ==================== registerInstance Tests ====================

    @Test
    fun `registerInstance should store a singleton instance`() {
        val service = SimpleService()
        container.registerInstance(service)

        val retrieved = container.get<SimpleService>()
        assertEquals(service, retrieved)
    }

    @Test
    fun `registerInstance should return the container for chaining`() {
        val result = container.registerInstance(SimpleService())
        assertEquals(container, result)
    }

    @Test
    fun `registerInstance should return the same instance on multiple retrieval`() {
        val service = SimpleService()
        container.registerInstance(service)

        val first = container.get<SimpleService>()
        val second = container.get<SimpleService>()
        assertEquals(first, second)
    }

    @Test
    fun `registerInstance should override previous instance`() {
        val service1 = SimpleService()
        val service2 = SimpleService()

        container.registerInstance(service1)
        container.registerInstance(service2)

        val retrieved = container.get<SimpleService>()
        assertEquals(service2, retrieved)
    }

    // ==================== registerTransient Tests ====================

    @Test
    fun `registerTransient should create a new instance each time`() {
        container.registerTransient { SimpleService() }

        val first = container.get<SimpleService>()
        val second = container.get<SimpleService>()

        assertTrue(first !== second)
    }

    @Test
    fun `registerTransient should return the container for chaining`() {
        val result = container.registerTransient { SimpleService() }
        assertEquals(container, result)
    }

    @Test
    fun `registerTransient should support parameterized creation`() {
        var creationCount = 0
        container.registerTransient {
            creationCount++
            SimpleService()
        }

        container.get<SimpleService>()
        container.get<SimpleService>()

        assertEquals(2, creationCount)
    }

    // ==================== registerSingleton Tests ====================

    @Test
    fun `registerSingleton should create instance only once`() {
        var creationCount = 0
        container.registerSingleton {
            creationCount++
            SimpleService()
        }

        val first = container.get<SimpleService>()
        val second = container.get<SimpleService>()

        assertEquals(first, second)
        assertEquals(1, creationCount)
    }

    @Test
    fun `registerSingleton should return the container for chaining`() {
        val result = container.registerSingleton { SimpleService() }
        assertEquals(container, result)
    }

    @Test
    fun `registerSingleton should cache instance after first retrieval`() {
        container.registerSingleton { SimpleService() }
        container.get<SimpleService>()

        assertTrue(container.instances.containsKey(SimpleService::class))
    }

    // ==================== bind Tests ====================

    @Test
    fun `bind should map interface to implementation`() {
        container.registerInstance<UserServiceImpl>(UserServiceImpl())
        container.bind<UserService, UserServiceImpl>()

        val userService = container.get<UserService>()
        assertTrue(userService is UserServiceImpl)
    }

    @Test
    fun `bind should return the container for chaining`() {
        container.registerInstance<UserServiceImpl>(UserServiceImpl())
        val result = container.bind<UserService, UserServiceImpl>()
        assertEquals(container, result)
    }

    @Test
    fun `bind should throw when implementation is not registered`() {
        container.bind<UserService, UserServiceImpl>()
        assertThrows<NoSuchElementException> {
            container.get<UserService>()
        }
    }

    @Test
    fun `bind should work with factory registered implementation`() {
        container.registerTransient { UserServiceImpl() }
        container.bind<UserService, UserServiceImpl>()

        val first = container.get<UserService>()
        val second = container.get<UserService>()

        assertTrue(first is UserServiceImpl)
        assertTrue(first !== second)
    }

    // ==================== get Tests ====================

    @Test
    fun `get should retrieve registered instance`() {
        val service = SimpleService()
        container.registerInstance(service)

        val retrieved = container.get<SimpleService>()
        assertEquals(service, retrieved)
    }

    @Test
    fun `get should throw NoSuchElementException when service not registered`() {
        assertThrows<NoSuchElementException> {
            container.get<SimpleService>()
        }
    }

    @Test
    fun `get with KClass should retrieve registered service`() {
        val service = SimpleService()
        container.registerInstance(service)

        val retrieved = container.get(SimpleService::class)
        assertEquals(service, retrieved)
    }

    @Test
    fun `get with KClass should throw when service not registered`() {
        assertThrows<NoSuchElementException> {
            container.get(SimpleService::class)
        }
    }

    @Test
    fun `get should prioritize instance over factory`() {
        val instance = SimpleService()
        container.registerInstance(instance)
        container.registerTransient { SimpleService() }

        val retrieved = container.get<SimpleService>()
        assertEquals(instance, retrieved)
    }

    // ==================== getOrNull Tests ====================

    @Test
    fun `getOrNull should return service if registered`() {
        val service = SimpleService()
        container.registerInstance(service)

        val retrieved = container.getOrNull<SimpleService>()
        assertEquals(service, retrieved)
    }

    @Test
    fun `getOrNull should return null if not registered`() {
        val retrieved = container.getOrNull<SimpleService>()
        assertNull(retrieved)
    }

    @Test
    fun `getOrNull with KClass should return service if registered`() {
        val service = SimpleService()
        container.registerInstance(service)

        val retrieved = container.getOrNull(SimpleService::class)
        assertEquals(service, retrieved)
    }

    @Test
    fun `getOrNull with KClass should return null if not registered`() {
        val retrieved = container.getOrNull(SimpleService::class)
        assertNull(retrieved)
    }

    // ==================== getOrDefault Tests ====================

    @Test
    fun `getOrDefault should return registered service`() {
        val service = SimpleService()
        val default = SimpleService()
        container.registerInstance(service)

        val retrieved = container.getOrDefault(default)
        assertEquals(service, retrieved)
    }

    @Test
    fun `getOrDefault should return default if not registered`() {
        val default = SimpleService()
        val retrieved = container.getOrDefault(default)
        assertEquals(default, retrieved)
    }

    // ==================== getOrElse Tests ====================

    @Test
    fun `getOrElse should return registered service`() {
        val service = SimpleService()
        container.registerInstance(service)

        val retrieved = container.getOrElse { SimpleService() }
        assertEquals(service, retrieved)
    }

    @Test
    fun `getOrElse should call factory if not registered`() {
        var factoryCalled = false
        container.getOrElse {
            factoryCalled = true
            SimpleService()
        }
        assertTrue(factoryCalled)
    }

    // ==================== has Tests ====================

    @Test
    fun `has should return true if instance registered`() {
        container.registerInstance(SimpleService())
        assertTrue(container.has<SimpleService>())
    }

    @Test
    fun `has should return true if factory registered`() {
        container.registerTransient { SimpleService() }
        assertTrue(container.has<SimpleService>())
    }

    @Test
    fun `has should return true if singleton registered`() {
        container.registerSingleton { SimpleService() }
        assertTrue(container.has<SimpleService>())
    }

    @Test
    fun `has should return false if not registered`() {
        assertFalse(container.has<SimpleService>())
    }

    // ==================== remove Tests ====================

    @Test
    fun `remove should remove registered instance`() {
        container.registerInstance(SimpleService())
        val removed = container.remove<SimpleService>()

        assertTrue(removed)
        assertFalse(container.has<SimpleService>())
    }

    @Test
    fun `remove should remove registered factory`() {
        container.registerTransient { SimpleService() }
        val removed = container.remove<SimpleService>()

        assertTrue(removed)
        assertFalse(container.has<SimpleService>())
    }

    @Test
    fun `remove should return false if service not registered`() {
        val removed = container.remove<SimpleService>()
        assertFalse(removed)
    }

    @Test
    fun `remove should make service unavailable`() {
        container.registerInstance(SimpleService())
        container.remove<SimpleService>()

        assertThrows<NoSuchElementException> {
            container.get<SimpleService>()
        }
    }

    // ==================== clear Tests ====================

    @Test
    fun `clear should remove all instances`() {
        container.registerInstance(SimpleService())
        container.registerInstance(AnotherService())
        container.clear()

        assertFalse(container.has<SimpleService>())
        assertFalse(container.has<AnotherService>())
    }

    @Test
    fun `clear should remove all factories`() {
        container.registerTransient { SimpleService() }
        container.registerTransient { AnotherService() }
        container.clear()

        assertFalse(container.has<SimpleService>())
        assertFalse(container.has<AnotherService>())
    }

    @Test
    fun `clear should clear both instances and factories`() {
        container.registerInstance(SimpleService())
        container.registerTransient { AnotherService() }
        container.clear()

        assertEquals(0, container.registeredServices.size)
    }

    // ==================== registeredServices Tests ====================

    @Test
    fun `registeredServices should return all registered service types`() {
        container.registerInstance(SimpleService())
        container.registerTransient { AnotherService() }

        val services = container.registeredServices
        assertEquals(2, services.size)
        assertTrue(SimpleService::class in services)
        assertTrue(AnotherService::class in services)
    }

    @Test
    fun `registeredServices should be empty initially`() {
        assertEquals(0, container.registeredServices.size)
    }

    @Test
    fun `registeredServices should not duplicate services`() {
        container.registerInstance(SimpleService())
        container.registerTransient { SimpleService() }

        val services = container.registeredServices
        assertEquals(1, services.count { it == SimpleService::class })
    }

    // ==================== Integration Tests ====================

    @Test
    fun `should support method chaining`() {
        val result = container
            .registerInstance(SimpleService())
            .registerTransient { AnotherService() }
            .registerSingleton { UserServiceImpl() }

        assertEquals(container, result)
        assertEquals(3, container.registeredServices.size)
    }

    @Test
    fun `should handle multiple service types`() {
        val simpleService = SimpleService()
        container.registerInstance(simpleService)
        container.registerTransient { AnotherService() }
        container.registerSingleton { UserServiceImpl() }

        assertEquals(simpleService, container.get<SimpleService>())
        assertNotNull(container.get<AnotherService>())
        assertNotNull(container.get<UserServiceImpl>())
    }

    @Test
    fun `should support complex service graph`() {
        container.registerSingleton { UserServiceImpl() }
        container.registerSingleton { DatabaseServiceImpl() }
        container.bind<UserService, UserServiceImpl>()
        container.bind<DatabaseService, DatabaseServiceImpl>()

        val userService = container.get<UserService>()
        val databaseService = container.get<DatabaseService>()

        assertTrue(userService is UserServiceImpl)
        assertTrue(databaseService is DatabaseServiceImpl)
    }
}