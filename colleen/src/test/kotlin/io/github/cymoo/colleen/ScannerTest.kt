package io.github.cymoo.colleen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Controller("/api")
class TestController {
    @Use
    fun authMiddleware(ctx: Context, next: Next) {
        // middleware logic
    }

    @Get("/users")
    fun getUsers() = "users"

    @Post("/users")
    fun createUser() = "create"

    @Put("/users/:id")
    fun updateUser() = "update"

    @Delete("/users/:id")
    fun deleteUser() = "delete"

    fun helperMethod() = "helper"
}

@Controller
class DefaultPathController {
    @Get("/home")
    fun home() = "home"
}

class NoControllerAnnotation {
    @Get("/test")
    fun test() = "test"
}

@Controller("/admin")
class MultipleMiddlewaresController {
    @Use
    fun firstMiddleware(ctx: Context, next: Next) {
    }

    @Use
    fun secondMiddleware(ctx: Context, next: Next) {
    }

    @Get("/dashboard")
    fun dashboard() = "dashboard"
}

@Controller("/missing-next")
class InvalidMiddlewareController1 {
    @Use
    fun badMiddleware(ctx: Context) {
    }
}

@Controller("/invalid-parameter-types")
class InvalidMiddlewareController2 {
    @Use
    fun badMiddleware(str: String, num: Int) {
    }
}

@Controller("/invalid-return-type")
class InvalidMiddlewareController3 {
    @Use
    fun badMiddleware(ctx: Context, next: Next): String {
        return "invalid"
    }
}

class ControllerScannerTest {

    @Test
    fun `test scan basic controller with routes`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        assertEquals("/api", info.basePath)
        assertEquals(1, info.middlewares.size)
        assertEquals(4, info.routes.size)
        assertEquals(controller, info.obj)
    }

    @Test
    fun `test scan finds correct base path`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        assertEquals("/api", info.basePath)
    }

    @Test
    fun `test scan with default base path`() {
        val controller = DefaultPathController()
        val info = ControllerScanner.scan(controller)

        assertEquals("/", info.basePath)
    }

    @Test
    fun `test scan without controller annotation`() {
        val controller = NoControllerAnnotation()
        val info = ControllerScanner.scan(controller)

        assertEquals("/", info.basePath)
    }

    @Test
    fun `test finds all HTTP method routes`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        val routes = info.routes
        assertEquals(4, routes.size)

        val getRoute = routes.find { it.method == "GET" }
        assertNotNull(getRoute)
        assertEquals("/users", getRoute?.path)
        assertEquals("getUsers", getRoute?.handler?.name)

        val postRoute = routes.find { it.method == "POST" }
        assertNotNull(postRoute)
        assertEquals("/users", postRoute?.path)
        assertEquals("createUser", postRoute?.handler?.name)

        val putRoute = routes.find { it.method == "PUT" }
        assertNotNull(putRoute)
        assertEquals("/users/:id", putRoute?.path)
        assertEquals("updateUser", putRoute?.handler?.name)

        val deleteRoute = routes.find { it.method == "DELETE" }
        assertNotNull(deleteRoute)
        assertEquals("/users/:id", deleteRoute?.path)
        assertEquals("deleteUser", deleteRoute?.handler?.name)
    }


    @Test
    fun `test finds all HTTP method routes of a method`() {
        @Controller("/base")
        class MyController {
            @Get
            @Get("/home")
            fun index() = "index"
        }

        val info = ControllerScanner.scan(MyController())

        assertEquals(2, info.routes.size)
    }

    @Test
    fun `test finds middlewares`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        assertEquals(1, info.middlewares.size)
        assertEquals("authMiddleware", info.middlewares[0].name)
    }

    @Test
    fun `test finds multiple middlewares`() {
        val controller = MultipleMiddlewaresController()
        val info = ControllerScanner.scan(controller)

        assertEquals(2, info.middlewares.size)
        assertTrue(info.middlewares.any { it.name == "firstMiddleware" })
        assertTrue(info.middlewares.any { it.name == "secondMiddleware" })
    }

    @Test
    fun `test validates middleware signature - wrong parameter count`() {
        val controller = InvalidMiddlewareController1()

        val exception = assertThrows<IllegalArgumentException> {
            ControllerScanner.scan(controller)
        }

        assertTrue(exception.message!!.contains("must have exactly 2 parameters"))
    }

    @Test
    fun `test validates middleware signature - wrong parameter types`() {
        val controller = InvalidMiddlewareController2()

        val exception = assertThrows<IllegalArgumentException> {
            ControllerScanner.scan(controller)
        }

        assertTrue(exception.message!!.contains("First parameter must be Context"))
    }

    @Test
    fun `test validates middleware signature - wrong return type`() {
        val controller = InvalidMiddlewareController3()

        val exception = assertThrows<IllegalArgumentException> {
            ControllerScanner.scan(controller)
        }

        assertTrue(exception.message!!.contains("Return type must be void/Unit"))
    }

    @Test
    fun `test route handler is accessible`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        info.routes.forEach { route ->
            assertTrue(route.handler.trySetAccessible())
        }
    }

    @Test
    fun `test middleware is accessible`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        info.middlewares.forEach { middleware ->
            assertTrue(middleware.trySetAccessible())
        }
    }

    @Test
    fun `test ignores non-annotated methods`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        // helperMethod不应该被识别为路由
        val helperRoute = info.routes.find { it.handler.name == "helperMethod" }
        assertEquals(null, helperRoute)
    }

    @Test
    fun `test controller info contains correct object reference`() {
        val controller = TestController()
        val info = ControllerScanner.scan(controller)

        assertEquals(controller, info.obj)
        assertTrue(info.obj is TestController)
    }

    @Test
    fun `test empty routes when no HTTP annotations`() {
        @Controller("/empty")
        class EmptyController

        val controller = EmptyController()
        val info = ControllerScanner.scan(controller)

        assertEquals(0, info.routes.size)
        assertEquals(0, info.middlewares.size)
    }

    @Test
    fun `test route with empty path`() {
        @Controller("/base")
        class EmptyPathController {
            @Get
            fun index() = "index"
        }

        val controller = EmptyPathController()
        val info = ControllerScanner.scan(controller)

        assertEquals(1, info.routes.size)
        assertEquals("/", info.routes[0].path)
    }
}