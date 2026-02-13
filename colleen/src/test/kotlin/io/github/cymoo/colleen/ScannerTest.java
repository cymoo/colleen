package io.github.cymoo.colleen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ControllerScanner functionality
 */
@DisplayName("ControllerScanner Tests")
class ControllerScannerJavaTest {

    // ========== Test Controllers ==========

    @Controller("/api")
    static class BasicController {
        @Get("/users")
        public void getUsers(Context ctx) {
        }

        @Post("/users")
        public void createUser(Context ctx) {
        }

        @Put("/users/{id}")
        public void updateUser(Context ctx) {
        }

        @Delete("/users/{id}")
        public void deleteUser(Context ctx) {
        }
    }

    @Controller
    static class DefaultPathController {
        @Get("/home")
        public void home(Context ctx) {
        }
    }

    static class MultipleRoutesController {
        @Get("/")
        @Get("/home")
        @Get("/index")
        public void homePage(Context ctx) {
        }

        @Get("/user/{id}")
        @Put("/user/{id}")
        @Delete("/user/{id}")
        public void handleUser(Context ctx) {
        }
    }

    static class MiddlewareController {
        @Use
        public void authMiddleware(Context ctx, Next next) {
        }

        @Use
        public void loggingMiddleware(Context ctx, Next next) {
        }

        @Get("/protected")
        public void protectedRoute(Context ctx) {
        }
    }

    static class PathNormalizationController {
        @Get("")
        public void emptyPath(Context ctx) {
        }

        @Get("no-slash")
        public void noLeadingSlash(Context ctx) {
        }

        @Get("/trailing/")
        public void trailingSlash(Context ctx) {
        }

        @Get("/")
        public void rootPath(Context ctx) {
        }
    }

    // Invalid controllers for testing error cases
    static class InvalidMiddlewareController1 {
        @Use
        public void wrongParamCount(Context ctx) {
        } // Only 1 parameter
    }

    static class InvalidMiddlewareController2 {
        @Use
        public void wrongParamTypes(String str, Integer num) {
        } // Wrong types
    }

    static class InvalidMiddlewareController3 {
        @Use
        public String wrongReturnType(Context ctx, Next next) { // Returns String
            return "invalid";
        }
    }

    // ========== Basic Scanning Tests ==========

    @Nested
    @DisplayName("Basic Controller Scanning")
    class BasicScanningTests {

        @Test
        @DisplayName("Should scan basic controller with correct base path")
        void testBasicControllerBasePath() {
            BasicController controller = new BasicController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            assertEquals("/api", info.getBasePath());
            assertSame(controller, info.getObj());
        }

        @Test
        @DisplayName("Should discover all HTTP method routes")
        void testAllHttpMethods() {
            BasicController controller = new BasicController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            List<RouteInfo> routes = info.getRoutes();
            assertEquals(4, routes.size());

            var gets = routes.stream().filter(route -> route.getMethod().equals("GET") && route.getPath().equals("/users")).toList();
            assertEquals(1, gets.size());

            var posts = routes.stream().filter(route -> route.getMethod().equals("POST") && route.getPath().equals("/users")).toList();
            assertEquals(1, posts.size());

            var puts = routes.stream().filter(route -> route.getMethod().equals("PUT") && route.getPath().equals("/users/{id}")).toList();
            assertEquals(1, posts.size());

            var deletes = routes.stream().filter(route -> route.getMethod().equals("DELETE") && route.getPath().equals("/users/{id}")).toList();
            assertEquals(1, deletes.size());
        }

        @Test
        @DisplayName("Should use default path when @Controller has no value")
        void testDefaultPath() {
            DefaultPathController controller = new DefaultPathController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            assertEquals("/", info.getBasePath());
        }

        @Test
        @DisplayName("Should make handler methods accessible")
        void testMethodAccessibility() {
            BasicController controller = new BasicController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            for (RouteInfo route : info.getRoutes()) {
                assertTrue(route.getHandler().isAccessible(),
                        "Method " + route.getHandler().getName() + " should be accessible");
            }
        }
    }

    // ========== Multiple Routes Tests ==========

    @Nested
    @DisplayName("Multiple Routes Per Method")
    class MultipleRoutesTests {

        @Test
        @DisplayName("Should handle multiple @Get annotations on same method")
        void testMultipleGetAnnotations() {
            MultipleRoutesController controller = new MultipleRoutesController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            List<RouteInfo> homeRoutes = info.getRoutes().stream()
                    .filter(r -> r.getHandler().getName().equals("homePage"))
                    .toList();

            assertEquals(3, homeRoutes.size());

            List<String> paths = homeRoutes.stream()
                    .map(RouteInfo::getPath)
                    .sorted()
                    .collect(Collectors.toList());

            assertEquals(List.of("/", "/home", "/index"), paths);

            // All should be GET
            assertTrue(homeRoutes.stream().allMatch(r -> r.getMethod().equals("GET")));
        }

        @Test
        @DisplayName("Should handle multiple different HTTP methods on same method")
        void testMultipleDifferentHttpMethods() {
            MultipleRoutesController controller = new MultipleRoutesController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            List<RouteInfo> userRoutes = info.getRoutes().stream()
                    .filter(r -> r.getHandler().getName().equals("handleUser"))
                    .toList();

            assertEquals(3, userRoutes.size());

            Map<String, String> methodMap = userRoutes.stream()
                    .collect(Collectors.toMap(RouteInfo::getMethod, RouteInfo::getPath));

            assertEquals("/user/{id}", methodMap.get("GET"));
            assertEquals("/user/{id}", methodMap.get("PUT"));
            assertEquals("/user/{id}", methodMap.get("DELETE"));
        }

        @Test
        @DisplayName("Should refer to same Method instance for routes from same handler")
        void testSameMethodInstance() {
            MultipleRoutesController controller = new MultipleRoutesController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            List<Method> homeMethods = info.getRoutes().stream()
                    .map(RouteInfo::getHandler)
                    .filter(handler -> handler.getName().equals("homePage"))
                    .distinct()
                    .toList();

            assertEquals(1, homeMethods.size(), "All routes should reference the same Method object");
        }
    }

    // ========== Middleware Tests ==========

    @Nested
    @DisplayName("Middleware Discovery")
    class MiddlewareTests {

        @Test
        @DisplayName("Should discover all @Use middleware methods")
        void testMiddlewareDiscovery() {
            MiddlewareController controller = new MiddlewareController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            List<Method> middlewares = info.getMiddlewares();
            assertEquals(2, middlewares.size());

            List<String> names = middlewares.stream()
                    .map(Method::getName)
                    .sorted()
                    .collect(Collectors.toList());

            assertEquals(List.of("authMiddleware", "loggingMiddleware"), names);
        }

        @Test
        @DisplayName("Should make middleware methods accessible")
        void testMiddlewareAccessibility() {
            MiddlewareController controller = new MiddlewareController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            for (Method middleware : info.getMiddlewares()) {
                assertTrue(middleware.isAccessible(),
                        "Middleware " + middleware.getName() + " should be accessible");
            }
        }

        @Test
        @DisplayName("Should not confuse middleware with routes")
        void testMiddlewareSeparation() {
            MiddlewareController controller = new MiddlewareController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            assertEquals(2, info.getMiddlewares().size());
            assertEquals(1, info.getRoutes().size());
            assertEquals("/protected", info.getRoutes().getFirst().getPath());
        }
    }

    // ========== Error Handling Tests ==========

    @Nested
    @DisplayName("Error Handling and Validation")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should throw when middleware has wrong parameter count")
        void testMiddlewareWrongParamCount() {
            InvalidMiddlewareController1 controller = new InvalidMiddlewareController1();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ControllerScanner.INSTANCE.scan(controller)
            );

            assertTrue(exception.getMessage().contains("must have exactly 2 parameters"));
            assertTrue(exception.getMessage().contains("wrongParamCount"));
        }

        @Test
        @DisplayName("Should throw when middleware has wrong parameter types")
        void testMiddlewareWrongParamTypes() {
            InvalidMiddlewareController2 controller = new InvalidMiddlewareController2();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ControllerScanner.INSTANCE.scan(controller)
            );

            assertTrue(exception.getMessage().contains("must be Context") ||
                    exception.getMessage().contains("must be Next"));
        }

        @Test
        @DisplayName("Should throw when middleware has wrong return type")
        void testMiddlewareWrongReturnType() {
            InvalidMiddlewareController3 controller = new InvalidMiddlewareController3();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ControllerScanner.INSTANCE.scan(controller)
            );

            assertTrue(exception.getMessage().contains("must be void/Unit"));
            assertTrue(exception.getMessage().contains("wrongReturnType"));
        }

        @Test
        @DisplayName("Should handle controller with no routes or middlewares")
        void testEmptyController() {
            @Controller("/empty")
            class EmptyController {
            }

            EmptyController controller = new EmptyController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            assertEquals("/empty", info.getBasePath());
            assertTrue(info.getRoutes().isEmpty());
            assertTrue(info.getMiddlewares().isEmpty());
        }
    }

    // ========== Integration Tests ==========

    @Nested
    @DisplayName("Integration Scenarios")
    class IntegrationTests {

        @Test
        @DisplayName("Should handle complex controller with everything")
        void testComplexController() {
            @Controller("/api/v1")
            class ComplexController {
                @Use
                public void auth(Context ctx, Next next) {
                }

                @Use
                public void logging(Context ctx, Next next) {
                }

                @Get
                @Get("/index")
                public void home(Context ctx) {
                }

                @Get("/users")
                public void listUsers(Context ctx) {
                }

                @Post("/users")
                public void createUser(Context ctx) {
                }

                @Get("/users/{id}")
                @Put("/users/{id}")
                @Delete("/users/{id}")
                public void handleUser(Context ctx) {
                }
            }

            ComplexController controller = new ComplexController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            assertEquals("/api/v1", info.getBasePath());
            assertEquals(2, info.getMiddlewares().size());
            assertEquals(7, info.getRoutes().size()); // 2 + 1 + 1 + 3

            // Verify route distribution
            Map<String, Long> methodCounts = info.getRoutes().stream()
                    .collect(Collectors.groupingBy(RouteInfo::getMethod, Collectors.counting()));

            assertEquals(4L, methodCounts.get("GET"));
            assertEquals(1L, methodCounts.get("PUT"));
            assertEquals(1L, methodCounts.get("POST"));
            assertEquals(1L, methodCounts.get("DELETE"));
        }

        @Test
        @DisplayName("Should preserve method metadata")
        void testMethodMetadataPreservation() {
            BasicController controller = new BasicController();
            ControllerInfo info = ControllerScanner.INSTANCE.scan(controller);

            RouteInfo getUsersRoute = info.getRoutes().stream()
                    .filter(r -> r.getPath().equals("/users") && r.getMethod().equals("GET"))
                    .findFirst()
                    .orElseThrow();

            Method handler = getUsersRoute.getHandler();
            assertEquals("getUsers", handler.getName());
            assertEquals(1, handler.getParameterCount());
            assertEquals(Context.class, handler.getParameterTypes()[0]);
        }
    }
}
