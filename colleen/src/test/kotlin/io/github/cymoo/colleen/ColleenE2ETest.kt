package io.github.cymoo.colleen

import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * E2E test suite for the Colleen Web Framework.
 *
 * Tests real HTTP server behavior with actual network requests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColleenE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "http://127.0.0.1:8888"

    @BeforeAll
    fun setup() {
        // Arrange: Initialize HTTP client with reasonable timeouts
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        // Arrange: Create and configure the application
        app = Colleen()
        setupRoutes()

        // Act: Start the server on a test port
        app.listen(port = 8888, host = "127.0.0.1")

        // Wait for server to be ready
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        // Cleanup: Stop the server
        app.stop()
    }

    private fun setupRoutes() {
        // Basic routes
        app.get("/") { "Hello, World!" }
        app.get("/json") { mapOf("message" to "success", "code" to 200) }
        app.post("/echo") { ctx -> ctx.text() ?: "empty" }

        // Path parameters
        app.get("/users/{id}") { ctx ->
            val id = ctx.pathParam("id")
            mapOf("userId" to id)
        }

        app.get("/users/{userId}/posts/{postId}") { ctx ->
            mapOf(
                "userId" to ctx.pathParam("userId"),
                "postId" to ctx.pathParam("postId")
            )
        }

        // Query parameters
        app.get("/search") { ctx ->
            mapOf(
                "query" to ctx.query("q"),
                "page" to ctx.query("page")
            )
        }

        // Headers
        app.get("/headers") { ctx ->
            mapOf(
                "userAgent" to ctx.header("User-Agent"),
                "accept" to ctx.header("Accept")
            )
        }

        // Status codes
        app.get("/created") { ctx ->
            ctx.status(201).json(mapOf("created" to true))
        }

        app.get("/not-found") { ctx ->
            throw NotFound("Resource not found")
        }

        app.get("/bad-request") { ctx ->
            throw BadRequest("Invalid request")
        }

        // Redirect
        app.get("/redirect") { ctx ->
            ctx.redirect("/")
        }

        // Middleware
        app.use("/api") { ctx, next ->
            ctx.response.header("X-API-Version", "1.0")
            next()
        }

        app.get("/api/data") { mapOf("data" to "test") }

        // Error handling
        app.get("/error") {
            throw RuntimeException("Intentional error")
        }

        app.onError<Exception> { err, ctx ->
            val (status, message) = when (err) {
                is HttpException -> err.status to err.message
                else -> 500 to ""
            }
            ctx.status(status).json(mapOf("error" to message))
        }

        // Request body
        app.post("/users") { ctx ->
            data class User(val name: String, val age: Int)

            val user = ctx.json<User>()
            mapOf("received" to user)
        }

        // Form data
        app.post("/form") { ctx ->
            mapOf(
                "name" to ctx.form("name"),
                "email" to ctx.form("email")
            )
        }

        // Cookies
        app.get("/set-cookie") { ctx ->
            ctx.response.cookie("session", "abc123", maxAge = 3600)
            "Cookie set"
        }

        app.get("/read-cookie") { ctx ->
            mapOf("session" to ctx.request.cookie("session"))
        }

        // Service injection
        class UserService {
            fun getUser(id: String) = "User($id)"
        }

        app.provide(UserService())

        app.get("/service/{id}") { ctx ->
            val service = ctx.getService<UserService>()
            mapOf("user" to service.getUser(ctx.pathParam("id")!!))
        }

        // Grouped routes
        app.group("/v1") {
            get("/health") { mapOf("status" to "ok") }
            get("/version") { mapOf("version" to "1.0.0") }
        }

        // Mounted sub-application
        val subApp = Colleen()
        subApp.get("/info") { mapOf("app" to "sub") }
        app.mount("/admin", subApp)
    }

    // ========================================================================
    // Basic Route Tests
    // ========================================================================

    @Test
    fun `GET root should return hello world`() {
        // Arrange: Create request to root endpoint
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/"))
            .GET()
            .build()

        // Act: Send request
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert: Verify response
        assertEquals(200, response.statusCode())
        assertEquals("Hello, World!", response.body())
    }

    @Test
    fun `GET json should return JSON response`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/json"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").get().contains("application/json"))
        assertTrue(response.body().contains("\"message\""))
        assertTrue(response.body().contains("\"success\""))
    }

    @Test
    fun `POST echo should return request body`() {
        // Arrange
        val body = "Test message"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/echo"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertEquals(body, response.body())
    }

    // ========================================================================
    // Path Parameters Tests
    // ========================================================================

    @Test
    fun `GET with path parameter should extract correctly`() {
        // Arrange
        val userId = "12345"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/$userId"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(userId))
    }

    @Test
    fun `GET with multiple path parameters should extract all`() {
        // Arrange
        val userId = "user123"
        val postId = "post456"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/$userId/posts/$postId"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(userId))
        assertTrue(response.body().contains(postId))
    }

    // ========================================================================
    // Query Parameters Tests
    // ========================================================================

    @Test
    fun `GET with query parameters should parse correctly`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/search?q=kotlin&page=2"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("kotlin"))
        assertTrue(response.body().contains("2"))
    }

    // ========================================================================
    // Headers Tests
    // ========================================================================

    @Test
    fun `GET should read request headers correctly`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/headers"))
            .header("User-Agent", "TestClient/1.0")
            .header("Accept", "application/json")
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("TestClient/1.0"))
        assertTrue(response.body().contains("application/json"))
    }

    // ========================================================================
    // Status Code Tests
    // ========================================================================

    @Test
    fun `GET created should return 201 status`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/created"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(201, response.statusCode())
        assertTrue(response.body().contains("created"))
    }

    @Test
    fun `GET not-found should return 404 status`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/not-found"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(404, response.statusCode())
    }

    @Test
    fun `GET bad-request should return 400 status`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/bad-request"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(400, response.statusCode())
    }

    @Test
    fun `GET non-existent route should return 404`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/does-not-exist"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(404, response.statusCode())
    }

    // ========================================================================
    // Redirect Tests
    // ========================================================================

    @Test
    fun `GET redirect should return 302 with Location header`() {
        // Arrange: Create client that doesn't follow redirects
        val noRedirectClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/redirect"))
            .GET()
            .build()

        // Act
        val response = noRedirectClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(302, response.statusCode())
        assertEquals("/", response.headers().firstValue("Location").orElse(null))
    }

    // ========================================================================
    // Middleware Tests
    // ========================================================================

    @Test
    fun `GET api endpoint should have middleware header`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/data"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertEquals("1.0", response.headers().firstValue("X-API-Version").orElse(null))
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    fun `GET error should trigger error handler`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/error"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(500, response.statusCode())
        assertTrue(response.body().contains("error"))
    }

    // ========================================================================
    // Request Body Tests
    // ========================================================================

    @Test
    fun `POST with JSON body should parse correctly`() {
        // Arrange
        val json = """{"name":"Alice","age":30}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Alice"))
        assertTrue(response.body().contains("30"))
    }

    @Test
    fun `POST with form data should parse correctly`() {
        // Arrange
        val formData = "name=Bob&email=bob@example.com"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/form"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formData))
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Bob"))
        assertTrue(response.body().contains("bob@example.com"))
    }

    // ========================================================================
    // Cookie Tests
    // ========================================================================

    @Test
    fun `GET set-cookie should set cookie in response`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/set-cookie"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        val setCookie = response.headers().firstValue("Set-Cookie").orElse(null)
        assertNotNull(setCookie)
        assertTrue(setCookie.contains("session=abc123"))
    }

    @Test
    fun `GET with cookie should read cookie from request`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/read-cookie"))
            .header("Cookie", "session=abc123")
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("abc123"))
    }

    // ========================================================================
    // Service Injection Tests
    // ========================================================================

    @Test
    fun `GET service endpoint should use injected service`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/service/123"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("User(123)"))
    }

    // ========================================================================
    // Grouped Routes Tests
    // ========================================================================

    @Test
    fun `GET grouped route should work correctly`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/health"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("ok"))
    }

    @Test
    fun `GET another grouped route should work correctly`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/version"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("1.0.0"))
    }

    // ========================================================================
    // Mounted Sub-Application Tests
    // ========================================================================

    @Test
    fun `GET mounted sub-app route should work correctly`() {
        // Arrange
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/admin/info"))
            .GET()
            .build()

        // Act
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("sub"))
    }

    @Test
    fun `mount should succeed for non-running app without parent`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        // Act
        parent.mount("/child", child)

        // Assert
        assertSame(parent, child.parent)
        assertEquals("/child", child.mountPath)
    }

    @Test
    fun `mount should fail if target app is already running`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        child.listen(9000) // make child running

        // Act & Assert
        val ex = assertThrows<IllegalStateException> {
            parent.mount("/child", child)
        }

        assertTrue(
            ex.message!!.contains("already running"),
            "Expected error message to mention running state"
        )
    }

    @Test
    fun `mount should fail if target app already has a parent`() {
        // Arrange
        val root = Colleen()
        val parent = Colleen()
        val child = Colleen()

        parent.mount("/child", child)

        // Act & Assert
        val ex = assertThrows<IllegalStateException> {
            root.mount("/child2", child)
        }

        assertTrue(
            ex.message!!.contains("already mounted"),
            "Expected error message to mention mounted state"
        )
    }

    // ========================================================================
    // Listen Tests
    // ========================================================================

    @Test
    fun `listen should fail if app is mounted as sub-app`() {
        // Arrange
        val parent = Colleen()
        val child = Colleen()

        parent.mount("/child", child)

        // Act & Assert
        val ex = assertThrows<IllegalStateException> {
            child.listen()
        }

        assertTrue(
            ex.message!!.contains("mounted"),
            "Expected error message to mention mounted state"
        )
    }

    @Test
    fun `listen should fail if server is already running`() {
        // Arrange
        val app = Colleen()

        // Explicit port: the default 8000 may be taken by unrelated local processes
        app.listen(9002)

        // Act & Assert
        val ex = assertThrows<IllegalStateException> {
            app.listen(9002)
        }

        assertTrue(
            ex.message!!.contains("already running"),
            "Expected error message to mention running state"
        )
    }

    @Test
    fun `listen should succeed for root app`() {
        // Arrange
        val app = Colleen()

        // Act
        app.listen(9001)

        // Assert
        assertTrue(app.running.get())
    }

    // ========================================================================
    // Performance Tests
    // ========================================================================

    @Test
    fun `performance test - sequential requests should complete quickly`() {
        // Arrange
        val requestCount = 100
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/json"))
            .GET()
            .build()

        // Act
        val startTime = System.currentTimeMillis()
        repeat(requestCount) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
        }
        val duration = System.currentTimeMillis() - startTime

        // Assert: Should complete in reasonable time (< 5 seconds for 100 requests)
        assertTrue(duration < 5000, "Sequential requests took too long: ${duration}ms")
        println("Sequential performance: $requestCount requests in ${duration}ms (${duration.toDouble() / requestCount}ms/req)")
    }

    @Test
    fun `performance test - concurrent requests should handle load`() {
        // Arrange
        val requestCount = 100
        val latch = CountDownLatch(requestCount)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/json"))
            .GET()
            .build()

        // Act
        val startTime = System.currentTimeMillis()
        repeat(requestCount) {
            Thread {
                try {
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 200) {
                        successCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        // Wait for all requests to complete (max 10 seconds)
        val completed = latch.await(10, TimeUnit.SECONDS)
        val duration = System.currentTimeMillis() - startTime

        // Assert
        assertTrue(completed, "Not all concurrent requests completed in time")
        assertTrue(errorCount.get() < requestCount / 10, "Too many errors: ${errorCount.get()}")
        println("Concurrent performance: $requestCount requests in ${duration}ms, success: ${successCount.get()}, errors: ${errorCount.get()}")
    }

    @Test
    fun `performance test - large response body should stream efficiently`() {
        // Arrange: Create endpoint with large response
        val largeData = (1..10000).map { mapOf("id" to it, "name" to "Item $it") }
        app.get("/large") { largeData }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/large"))
            .GET()
            .build()

        // Act
        val startTime = System.currentTimeMillis()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val duration = System.currentTimeMillis() - startTime

        // Assert
        assertEquals(200, response.statusCode())
        assertTrue(response.body().length > 10000, "Response body should be large")
        assertTrue(duration < 1000, "Large response took too long: ${duration}ms")
        println("Large response performance: ${response.body().length} bytes in ${duration}ms")
    }

    @Test
    fun `performance test - path parameter extraction should be fast`() {
        // Arrange
        val requestCount = 1000
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/12345"))
            .GET()
            .build()

        // Act
        val startTime = System.currentTimeMillis()
        repeat(requestCount) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
        }
        val duration = System.currentTimeMillis() - startTime

        // Assert
        val avgTime = duration.toDouble() / requestCount
        println("Path parameter extraction: $requestCount requests in ${duration}ms (${avgTime}ms/req)")
        assertTrue(avgTime < 10, "Path parameter extraction too slow: ${avgTime}ms per request")
    }

    @Test
    fun `stress test - sustained load should remain stable`() {
        // Arrange
        val durationSeconds = 3
        val threadCount = 10
        val requestsPerThread = 50

        val latch = CountDownLatch(threadCount)
        val totalSuccess = AtomicInteger(0)
        val totalErrors = AtomicInteger(0)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/json"))
            .GET()
            .build()

        // Act
        val startTime = System.currentTimeMillis()
        repeat(threadCount) { threadId ->
            Thread {
                try {
                    repeat(requestsPerThread) {
                        try {
                            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                            if (response.statusCode() == 200) {
                                totalSuccess.incrementAndGet()
                            } else {
                                totalErrors.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            totalErrors.incrementAndGet()
                        }
                        // Small delay to simulate real usage
                        Thread.sleep(10)
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        // Wait for completion
        val completed = latch.await(30, TimeUnit.SECONDS)
        val duration = System.currentTimeMillis() - startTime

        // Assert
        assertTrue(completed, "Stress test did not complete in time")
        val totalRequests = threadCount * requestsPerThread
        val successRate = (totalSuccess.get().toDouble() / totalRequests) * 100

        println("Stress test: $totalRequests requests in ${duration}ms")
        println("Success: ${totalSuccess.get()}, Errors: ${totalErrors.get()}, Success rate: ${"%.2f".format(successRate)}%")

        assertTrue(successRate > 95, "Success rate too low: $successRate%")
    }
}