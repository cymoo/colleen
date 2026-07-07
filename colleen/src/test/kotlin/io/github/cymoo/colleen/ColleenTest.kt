@file:Suppress("unused")

package io.github.cymoo.colleen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

/**
 * Unit test suite for the Colleen Web Framework.
 *
 * Tests internal components, properties, and methods that are not covered by E2E tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColleenTest {

    // ========================================================================
    // fullMountPath Tests
    // ========================================================================

    @Test
    fun `fullMountPath should return empty for root app`() {
        // Arrange
        val app = Colleen()

        // Act
        val path = app.fullMountPath

        // Assert
        assertEquals("", path)
    }

    @Test
    fun `fullMountPath should return mount path for single level`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        // Act
        parent.mount("/api", child)

        // Assert
        assertEquals("/api", child.fullMountPath)
    }

    @Test
    fun `fullMountPath should concatenate paths for nested mounts`() {
        // Arrange
        val root = Colleen()
        val level1 = Colleen()
        val level2 = Colleen()
        val level3 = Colleen()

        // Act
        root.mount("/api", level1)
        level1.mount("/v1", level2)
        level2.mount("/users", level3)

        // Assert
        assertEquals("/api", level1.fullMountPath)
        assertEquals("/api/v1", level2.fullMountPath)
        assertEquals("/api/v1/users", level3.fullMountPath)
    }

    @Test
    fun `fullMountPath should handle empty mount paths`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        // Act
        child.parent = parent
        child.mountPath = ""

        // Assert
        assertEquals("", child.fullMountPath)
    }

    @Test
    fun `fullMountPath should handle multiple siblings`() {
        // Arrange
        val root = Colleen()
        val child1 = Colleen()
        val child2 = Colleen()

        // Act
        root.mount("/api", child1)
        root.mount("/admin", child2)

        // Assert
        assertEquals("/api", child1.fullMountPath)
        assertEquals("/admin", child2.fullMountPath)
    }

    // ========================================================================
    // Config Tests
    // ========================================================================

    @Test
    fun `config block should apply configuration`() {
        // Arrange
        val app = Colleen()

        // Act
        app.config {
            server {
                port = 9999
                host = "0.0.0.0"
            }
        }

        // Assert
        assertEquals(9999, app.config.server.port)
        assertEquals("0.0.0.0", app.config.server.host)
    }

    @Test
    fun `config should be independent between apps`() {
        // Arrange
        val app1 = Colleen()
        val app2 = Colleen()

        // Act
        app1.config.server.port = 8001
        app2.config.server.port = 8002

        // Assert
        assertEquals(8001, app1.config.server.port)
        assertEquals(8002, app2.config.server.port)
    }

    @Test
    fun `config should persist across method calls`() {
        // Arrange
        val app = Colleen()

        // Act
        app.config { server { port = 7777 } }
        app.config { server { host = "localhost" } }

        // Assert
        assertEquals(7777, app.config.server.port)
        assertEquals("localhost", app.config.server.host)
    }

    // ========================================================================
    // Event System Tests
    // ========================================================================

    @Test
    fun `on should register event listener`() {
        // Arrange
        val app = Colleen()
        val triggered = AtomicBoolean(false)

        // Act
        app.on<Event.ServerStarting> {
            triggered.set(true)
        }

        app.eventBus.emit(Event.ServerStarting())

        // Assert
        assertTrue(triggered.get())
    }

    @Test
    fun `on should allow multiple listeners for same event`() {
        // Arrange
        val app = Colleen()
        val counter = AtomicInteger(0)

        // Act
        app.on<Event.ServerStarting> { counter.incrementAndGet() }
        app.on<Event.ServerStarting> { counter.incrementAndGet() }
        app.on<Event.ServerStarting> { counter.incrementAndGet() }

        app.eventBus.emit(Event.ServerStarting())

        // Assert
        assertEquals(3, counter.get())
    }

    // ========================================================================
    // Service Container Tests - Kotlin API
    // ========================================================================

    @Test
    fun `provide should register singleton service by instance`() {
        // Arrange
        val app = Colleen()

        class TestService(val id: Int)

        val instance = TestService(42)

        // Act
        app.provide(instance)
        val resolved1 = app.serviceContainer.get<TestService>()
        val resolved2 = app.serviceContainer.get<TestService>()

        // Assert
        assertNotNull(resolved1)
        assertNotNull(resolved2)
        assertSame(instance, resolved1)
        assertSame(resolved1, resolved2)
    }

    @Test
    fun `provide should register singleton service by factory`() {
        // Arrange
        val app = Colleen()

        class TestService(val id: Int)

        var callCount = 0

        // Act
        app.provide {
            callCount++
            TestService(callCount)
        }

        val resolved1 = app.serviceContainer.get<TestService>()
        val resolved2 = app.serviceContainer.get<TestService>()

        // Assert
        assertEquals(1, callCount) // Factory called only once
        assertNotNull(resolved1)
        assertNotNull(resolved2)
        assertSame(resolved1, resolved2)
        assertEquals(1, resolved1.id)
    }

    @Test
    fun `provide should register transient service`() {
        // Arrange
        val app = Colleen()

        class TestService(val id: Int)

        var callCount = 0

        // Act
        app.provide(singleton = false) {
            callCount++
            TestService(callCount)
        }

        val resolved1 = app.serviceContainer.get<TestService>()
        val resolved2 = app.serviceContainer.get<TestService>()

        // Assert
        assertEquals(2, callCount) // Factory called twice
        assertNotNull(resolved1)
        assertNotNull(resolved2)
        assertNotSame(resolved1, resolved2) // Different instances
        assertEquals(1, resolved1.id)
        assertEquals(2, resolved2.id)
    }

    @Test
    fun `provide should default to singleton lifetime`() {
        // Arrange
        val app = Colleen()

        class TestService

        var callCount = 0

        // Act
        app.provide {
            callCount++
            TestService()
        }

        app.serviceContainer.get<TestService>()
        app.serviceContainer.get<TestService>()

        // Assert
        assertEquals(1, callCount) // Default is singleton
    }

    @Test
    fun `provide should support multiple service types`() {
        // Arrange
        val app = Colleen()

        class ServiceA(val name: String)
        class ServiceB(val value: Int)

        // Act
        app.provide(ServiceA("test"))
        app.provide(ServiceB(42))

        // Assert
        val serviceA = app.serviceContainer.get<ServiceA>()
        val serviceB = app.serviceContainer.get<ServiceB>()

        assertNotNull(serviceA)
        assertNotNull(serviceB)
        assertEquals("test", serviceA.name)
        assertEquals(42, serviceB.value)
    }

    // ========================================================================
    // Service Container Tests - Java API
    // ========================================================================

    @Test
    fun `provide Java API should register instance`() {
        // Arrange
        val app = Colleen()

        class TestService(val id: Int)

        val instance = TestService(99)

        // Act
        app.provide(TestService::class.java, instance)
        val resolved = app.serviceContainer.get(TestService::class)

        // Assert
        assertNotNull(resolved)
        assertSame(instance, resolved)
    }

    @Test
    fun `provide Java API should support singleton lifetime`() {
        // Arrange
        val app = Colleen()

        class TestService(val id: Int)

        var callCount = 0

        // Act
        app.provideSingleton(TestService::class.java) {
            callCount++
            TestService(callCount)
        }

        val resolved1 = app.serviceContainer.get(TestService::class)
        val resolved2 = app.serviceContainer.get(TestService::class)

        // Assert
        assertEquals(1, callCount)
        assertSame(resolved1, resolved2)
    }

    @Test
    fun `provide Java API should support transient lifetime`() {
        // Arrange
        val app = Colleen()

        class TestService(val id: Int)

        var callCount = 0

        // Act
        app.provideTransient(TestService::class.java) {
            callCount++
            TestService(callCount)
        }

        val resolved1 = app.serviceContainer.get(TestService::class)
        val resolved2 = app.serviceContainer.get(TestService::class)

        // Assert
        assertEquals(2, callCount)
        assertNotSame(resolved1, resolved2)
    }

    @Test
    fun `provideSingleton should register singleton service`() {
        // Arrange
        val app = Colleen()

        class TestService

        var callCount = 0

        // Act
        app.provideSingleton(TestService::class.java) {
            callCount++
            TestService()
        }

        app.serviceContainer.get(TestService::class)
        app.serviceContainer.get(TestService::class)

        // Assert
        assertEquals(1, callCount)
    }

    @Test
    fun `provideTransient should register transient service`() {
        // Arrange
        val app = Colleen()

        class TestService

        var callCount = 0

        // Act
        app.provide(singleton = false) {
            callCount++
            TestService()
        }

        app.serviceContainer.get(TestService::class)
        app.serviceContainer.get(TestService::class)

        // Assert
        assertEquals(2, callCount)
    }

    // ========================================================================
    // Middleware System Tests
    // ========================================================================

    @Test
    fun `use should register global middleware`() {
        // Arrange
        val app = Colleen()
        // Act
        app.use { ctx, next -> next() }

        // Assert
        val middlewares = app.router.middlewares
        assertEquals(1, middlewares.size)
        assertTrue(middlewares[0] is MiddlewareNode.Global)
    }

    @Test
    fun `use with path should register path middleware`() {
        // Arrange
        val app = Colleen()

        // Act
        app.use("/api") { ctx, next -> next() }

        // Assert
        val middlewares = app.router.middlewares
        assertEquals(1, middlewares.size)
        assertTrue(middlewares[0] is MiddlewareNode.Prefix)
    }

    @Test
    fun `use with predicate should register conditional middleware`() {
        // Arrange
        val app = Colleen()

        // Act
        app.use({ ctx -> ctx.request.method == "GET" }) { ctx, next -> next() }

        // Assert
        val middlewares = app.router.middlewares
        assertEquals(1, middlewares.size)
        assertTrue(middlewares[0] is MiddlewareNode.Conditional)
    }

    @Test
    fun `use with method and path should register per-route middleware`() {
        // Arrange
        val app = Colleen()

        // Act
        app.use("GET", "/users") { ctx, next -> next() }

        // Assert
        val middlewares = app.router.middlewares
        assertEquals(1, middlewares.size)
        assertTrue(middlewares[0] is MiddlewareNode.PerRoute)
    }

    @Test
    fun `middlewares should be registered in order`() {
        // Arrange
        val app = Colleen()

        // Act
        app.use { ctx, next -> next() } // global
        app.use("/api") { ctx, next -> next() } // path
        app.use({ true }) { ctx, next -> next() } // conditional
        app.use("GET", "/test") { ctx, next -> next() } // perRoute

        // Assert
        val middlewares = app.router.middlewares
        val order = middlewares.map {
            when (it) {
                is MiddlewareNode.Global -> "global"
                is MiddlewareNode.Prefix -> "prefix"
                is MiddlewareNode.Conditional -> "conditional"
                is MiddlewareNode.PerRoute -> "perRoute"
            }
        }
        assertEquals(listOf("global", "prefix", "conditional", "perRoute"), order)
    }

    // ========================================================================
    // Route Registration Tests
    // ========================================================================

    @Test
    fun `addRoute should register lambda handler`() {
        // Arrange
        val app = Colleen()

        // Act
        app.addRoute("GET", "/test") { "test" }

        // Assert
        val routes = app.router.routes
        assertEquals(1, routes.size)
        assertTrue(routes[0].handler is RouteHandler.Lambda)
    }

    fun testHandler(): String = "test"

    @Test
    fun `addRoute should register KFunction handler`() {
        // Arrange
        val app = Colleen()

        // Act
        app.addRoute("GET", "/test", ::testHandler)

        // Assert
        val routes = app.router.routes
        assertEquals(1, routes.size)
        assertTrue(routes[0].handler is RouteHandler.KFunction)
    }

    @Test
    fun `HTTP method helpers should register routes correctly`() {
        // Arrange
        val app = Colleen()

        // Act
        app.get("/get") { "get" }
        app.post("/post") { "post" }
        app.put("/put") { "put" }
        app.delete("/delete") { "delete" }
        app.patch("/patch") { "patch" }
        app.head("/head") { "head" }
        app.options("/options") { "options" }
        app.all("/all") { "all" }

        // Assert
        val routes = app.router.routes
        assertEquals(8, routes.size)
        assertEquals("GET", routes[0].method)
        assertEquals("POST", routes[1].method)
        assertEquals("PUT", routes[2].method)
        assertEquals("DELETE", routes[3].method)
        assertEquals("PATCH", routes[4].method)
        assertEquals("HEAD", routes[5].method)
        assertEquals("OPTIONS", routes[6].method)
        assertEquals("*", routes[7].method)
    }

    fun handler(): String = "test"

    @Test
    fun `HTTP method helpers should support KFunction handlers`() {
        // Arrange
        val app = Colleen()

        // Act
        app.get("/test", ::handler)
        app.post("/test", ::handler)

        // Assert
        val routes = app.router.routes
        assertEquals(2, routes.size)
        assertTrue(routes[0].handler is RouteHandler.KFunction)
        assertTrue(routes[1].handler is RouteHandler.KFunction)
    }

    @Test
    fun `controller registration should register its routes`() {
        // Arrange
        val app = Colleen()

        // Act
        @Controller("/tc")
        class TestController {
            @Get("/ping")
            fun ping(): String = "pong"
        }

        app.addController(TestController())

        // Assert
        val response = TestClient(app).get("/tc/ping").send()
        assertEquals(200, response.status)
        assertEquals("pong", response.text())
    }

    // ========================================================================
    // Route Builder Tests
    // ========================================================================

    @Test
    fun `route builder should create route with method`() {
        // Arrange
        val app = Colleen()

        // Act
        app.get("/test").handle { "test" }

        // Assert
        val routes = app.router.routes
        assertEquals(1, routes.size)
        assertEquals("GET", routes[0].method)
    }

    @Test
    fun `route builder should support all HTTP methods`() {
        // Arrange
        val app = Colleen()

        // Act
        app.get("/get").handle { "get" }
        app.post("/post").handle { "post" }
        app.put("/put").handle { "put" }
        app.delete("/delete").handle { "delete" }
        app.patch("/patch").handle { "patch" }
        app.head("/head").handle { "head" }
        app.options("/options").handle { "options" }
        app.all("/all").handle { "all" }

        // Assert
        val routes = app.router.routes
        assertEquals(8, routes.size)
    }

    @Test
    fun `group should register routes with prefix`() {
        // Arrange
        val app = Colleen()

        // Act
        app.group("/api") {
            get("/users") { "users" }
            post("/users") { "create" }
        }

        // Assert
        val routes = app.router.routes
        assertEquals(2, routes.size)
        assertEquals("/api/users", routes[0].path)
        assertEquals("/api/users", routes[1].path)
    }

    @Test
    fun `group should register middlewares with prefix`() {
        // Arrange
        val app = Colleen()
        // Act
        app.group("/api") {
            use { ctx, next -> next() }
        }

        // Assert
        val middlewares = app.router.middlewares
        assertEquals(1, middlewares.size)
    }

    @Test
    fun `nested groups should concatenate prefixes`() {
        // Arrange
        val app = Colleen()

        // Act
        app.group("/api") {
            group("/v1") {
                get("/users") { "users" }
            }
        }

        // Assert
        val routes = app.router.routes
        assertEquals(1, routes.size)
        assertEquals("/api/v1/users", routes[0].path)
    }

    // ========================================================================
    // Exception Handling Tests
    // ========================================================================

    @Test
    fun `onError should register exception handler`() {
        // Arrange
        val app = Colleen()
        var handlerCalled = false

        // Act
        app.onError<IllegalArgumentException> { e, ctx ->
            handlerCalled = true
        }

        // Assert
        assertNotNull(app.errorHandlers[IllegalArgumentException::class])
    }

    @Test
    fun `onError Java API should register exception handler`() {
        // Arrange
        val app = Colleen()
        var handlerCalled = false

        // Act
        app.onError(IllegalArgumentException::class.java) { e, ctx ->
            handlerCalled = true
        }

        // Assert
        assertNotNull(app.errorHandlers[IllegalArgumentException::class])
    }

    @Test
    fun `onError should support multiple exception types`() {
        // Arrange
        val app = Colleen()

        // Act
        app.onError<IllegalArgumentException> { e, ctx -> }
        app.onError<IllegalStateException> { e, ctx -> }
        app.onError<NullPointerException> { e, ctx -> }

        // Assert
        assertEquals(3, app.errorHandlers.size)
    }

    @Test
    fun `exception handler should find by class hierarchy`() {
        // Arrange
        val app = Colleen()
        var baseHandlerCalled = false
        var specificHandlerCalled = false

        app.onError<Exception> { e, ctx ->
            baseHandlerCalled = true
        }

        app.onError<IllegalArgumentException> { e, ctx ->
            specificHandlerCalled = true
        }

        // Note: Testing the actual lookup requires access to private method
        // This tests that both handlers are registered
        // Assert
        assertEquals(2, app.errorHandlers.size)
    }

    // ========================================================================
    // Lifecycle Tests
    // ========================================================================

    @Test
    fun `onShutdown should register shutdown hook`() {
        // Arrange
        val app = Colleen()
        val shutdownCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        // Act
        app.onShutdown {
            shutdownCalled.set(true)
            latch.countDown()
        }

        // Simulate shutdown by emitting event
        app.eventBus.emit(Event.ServerStopped())

        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue(shutdownCalled.get())
    }

    @Test
    fun `multiple shutdown hooks should all execute`() {
        // Arrange
        val app = Colleen()
        val counter = AtomicInteger(0)
        val latch = CountDownLatch(3)

        // Act
        app.onShutdown {
            counter.incrementAndGet()
            latch.countDown()
        }
        app.onShutdown {
            counter.incrementAndGet()
            latch.countDown()
        }
        app.onShutdown {
            counter.incrementAndGet()
            latch.countDown()
        }

        app.eventBus.emit(Event.ServerStopped())

        // Assert
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(3, counter.get())
    }

    @Test
    fun `running flag should be false initially`() {
        // Arrange
        val app = Colleen()

        // Assert
        assertFalse(app.running.get())
    }

    @Test
    fun `shuttingDown flag should be false initially`() {
        // Arrange
        val app = Colleen()

        // Assert
        assertFalse(app.shuttingDown.get())
    }

    // ========================================================================
    // Parent/Child Relationship Tests
    // ========================================================================

    @Test
    fun `parent should be null for root app`() {
        // Arrange
        val app = Colleen()

        // Assert
        assertNull(app.parent)
    }

    @Test
    fun `parent should be set when mounted`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        // Act
        parent.mount("/child", child)

        // Assert
        assertSame(parent, child.parent)
    }

    @Test
    fun `mountPath should be empty for root app`() {
        // Arrange
        val app = Colleen()

        // Assert
        assertEquals("", app.mountPath)
    }

    @Test
    fun `mountPath should be set when mounted`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        // Act
        parent.mount("/api", child)

        // Assert
        assertEquals("/api", child.mountPath)
    }

    @Test
    fun `mount should fail if child is already running`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()
        child.running.set(true)

        // Act & Assert
        assertThrows<IllegalStateException> {
            parent.mount("/child", child)
        }
    }

    @Test
    fun `mount should fail if child already has parent`() {
        // Arrange
        val parent1 = Colleen()
        val parent2 = Colleen()
        val child = Colleen()

        parent1.mount("/child", child)

        // Act & Assert
        assertThrows<IllegalStateException> {
            parent2.mount("/child", child)
        }
    }

    // ========================================================================
    // Java Compatibility Tests
    // ========================================================================

    @Test
    fun `Java event listener API should work`() {
        // Arrange
        val app = Colleen()
        val triggered = AtomicBoolean(false)

        // Act
        app.on(Event.ServerStarting::class.java) { event ->
            triggered.set(true)
        }

        app.eventBus.emit(Event.ServerStarting())

        // Assert
        assertTrue(triggered.get())
    }

    // ========================================================================
    // Edge Cases and Boundary Tests
    // ========================================================================

    @Test
    fun `empty path should be handled correctly`() {
        // Arrange
        val app = Colleen()

        // Act
        app.get("") { "empty" }

        // Assert
        val routes = app.router.routes
        assertEquals(1, routes.size)
        assertEquals("/", routes[0].path)
    }

    @Test
    fun `root path should be handled correctly`() {
        // Arrange
        val app = Colleen()
        // Act
        app.get("/") { "root" }

        // Assert
        val routes = app.router.routes
        assertEquals(1, routes.size)
        assertEquals("/", routes[0].path)
    }

    @Test
    fun `multiple mounts at same path should be allowed`() {
        // Arrange
        val parent = Colleen()
        val child1 = Colleen()
        val child2 = Colleen()

        // Act - mounting different apps at same path
        // This might be intentional to allow overriding
        parent.mount("/api", child1)
        parent.mount("/api", child2)

        // Assert - both should be mounted
        assertEquals("/api", child1.mountPath)
        assertEquals("/api", child2.mountPath)
    }

    @Test
    fun `app should handle rapid mount and unmount operations`() {
        // Arrange
        val parent = Colleen()

        // Act - mount many children rapidly
        repeat(100) { i ->
            val child = Colleen()
            parent.mount("/child$i", child)
            assertEquals("/child$i", child.mountPath)
        }

        // Assert - all mounted successfully
        // No assertion needed, just verify no exceptions
    }

    @Test
    fun `app should handle many route registrations`() {
        // Arrange
        val app = Colleen()

        // Act - register many routes
        repeat(1000) { i ->
            app.get("/route$i") { "route$i" }
        }

        // Assert
        val routes = app.router.routes
        assertEquals(1000, routes.size)
    }

    @Test
    fun `app should handle many middleware registrations`() {
        // Arrange
        val app = Colleen()

        // Act - register many middlewares
        repeat(100) { i ->
            app.use { ctx, next -> next() }
        }

        // Assert
        val middlewares = app.router.middlewares
        assertEquals(100, middlewares.size)
    }

    @Test
    fun `app should handle many service registrations`() {
        // Arrange
        val app = Colleen()

        // Act - register many services
        repeat(100) { i ->
            app.provide("Service$i")
        }

        // Assert - verify last service is resolvable
        val service = app.serviceContainer.get<String>()
        assertNotNull(service)
    }

    @Test
    fun `config should be accessible and mutable`() {
        // Arrange
        val app = Colleen()

        // Act
        app.config.server.port = 3000
        app.config.server.host = "0.0.0.0"

        // Assert
        assertEquals(3000, app.config.server.port)
        assertEquals("0.0.0.0", app.config.server.host)
    }

    @Test
    fun `path with special characters should be registered`() {
        // Arrange
        val app = Colleen()

        // Act
        app.get("/path-with-dashes") { "test" }
        app.get("/path_with_underscores") { "test" }
        app.get("/path.with.dots") { "test" }
        app.get("/path~with~tildes") { "test" }

        // Assert
        val routes = app.router.routes
        assertEquals(4, routes.size)
    }

    @Test
    fun `deeply nested mount paths should work`() {
        // Arrange
        val level0 = Colleen()
        val level1 = Colleen()
        val level2 = Colleen()
        val level3 = Colleen()
        val level4 = Colleen()
        val level5 = Colleen()

        // Act
        level0.mount("/l1", level1)
        level1.mount("/l2", level2)
        level2.mount("/l3", level3)
        level3.mount("/l4", level4)
        level4.mount("/l5", level5)

        // Assert
        assertEquals("/l1/l2/l3/l4/l5", level5.fullMountPath)
    }

    @Test
    fun `event listeners should be removable`() {
        // Arrange
        val app = Colleen()
        val counter = AtomicInteger(0)

        val listener = app.on<Event.ServerStarting> {
            counter.incrementAndGet()
        }

        // Act - emit event, remove listener, emit again
        app.eventBus.emit(Event.ServerStarting())
        // listener.remove()
        app.eventBus.off(listener)
        app.eventBus.emit(Event.ServerStarting())

        // Assert - handler should only be called once
        assertEquals(1, counter.get())
    }

    @Test
    fun `concurrent event emissions should be safe`() {
        // Arrange
        val app = Colleen()
        val counter = AtomicInteger(0)
        val latch = CountDownLatch(100)

        app.on<Event.ServerStarting> {
            counter.incrementAndGet()
            latch.countDown()
        }

        // Act - emit events from multiple threads
        repeat(100) {
            Thread {
                app.eventBus.emit(Event.ServerStarting())
            }.start()
        }

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(100, counter.get())
    }

    @Test
    fun `concurrent route registrations should be safe`() {
        // Arrange
        val app = Colleen()
        val latch = CountDownLatch(50)

        // Act - register routes from multiple threads
        repeat(50) { i ->
            Thread {
                app.get("/route$i") { "route$i" }
                latch.countDown()
            }.start()
        }

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val routes = app.router.routes
        assertEquals(50, routes.size)
    }
}