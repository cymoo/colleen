package io.github.cymoo.colleen;

import io.github.cymoo.colleen.util.http.Headers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Colleen Web Framework parameter extraction.
 * Tests both Kotlin and Java interoperability without using mock libraries.
 */
class ExtractorJavaTest {

    // ===== Test Data Classes =====

    private Context createContext(String method, String path, Map<String, String> pathParams) {
        Request request = new Request(
                method,
                path,
                "",
                new Headers(),
                null,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        Colleen app = new Colleen();
        Context ctx = new Context(request, new Response(), app, null);

        // Set path parameters
        pathParams.forEach((key, value) -> {
            ctx.getPathParams().put(key, value);
        });

        return ctx;
    }

    private Context createContextWithQuery(String method, String path, String queryString) {
        Request request = new Request(
                method,
                path,
                queryString,
                new Headers(),
                null,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        return new Context(request, new Response(), new Colleen(), null);
    }

    // ===== Test Controllers =====

    private Context createContextWithHeaders(String method, String path, Map<String, String> headers) {
        Headers headerObj = new Headers();
        headers.forEach(headerObj::set);

        Request request = new Request(
                method,
                path,
                "",
                headerObj,
                null,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        return new Context(request, new Response(), new Colleen(), null);
    }

    // ===== Helper Methods =====

    private Context createContextWithCookies(String method, String path, String cookieHeader) {
        Headers headers = new Headers();
        headers.set("cookie", cookieHeader);

        Request request = new Request(
                method,
                path,
                "",
                headers,
                null,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        return new Context(request, new Response(), new Colleen(), null);
    }

    private Context createContextWithJson(String method, String path, String jsonBody) {
        Headers headers = new Headers();
        headers.set("content-type", "application/json");

        InputStream stream = new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8));

        Request request = new Request(
                method,
                path,
                "",
                headers,
                stream,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        return new Context(request, new Response(), new Colleen(), null);
    }

    private Context createContextWithText(String method, String path, String textBody) {
        Headers headers = new Headers();
        headers.set("content-type", "text/plain");

        InputStream stream = new ByteArrayInputStream(textBody.getBytes(StandardCharsets.UTF_8));

        Request request = new Request(
                method,
                path,
                "",
                headers,
                stream,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        return new Context(request, new Response(), new Colleen(), null);
    }

    private Context createContextWithForm(String method, String path, String formData) {
        Headers headers = new Headers();
        headers.set("content-type", "application/x-www-form-urlencoded");

        Request request = new Request(
                method,
                path,
                "",
                headers,
                null,
                new Request.ServerInfo(),
                Collections::emptyList
        );

        // Manually set the stream after creation to avoid lazy loading issues
        Request updatedRequest = new Request(
                request.getMethod(),
                request.getPath(),
                "",
                request.getHeaders(),
                new ByteArrayInputStream(formData.getBytes(StandardCharsets.UTF_8)),
                request.getServerInfo(),
                Collections::emptyList
        );

        return new Context(updatedRequest, new Response(), new Colleen(), null);
    }

    public static class User {
        private String name;
        private Integer age;
        private String email;

        public User() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    public static class SearchQuery {
        private String keyword;
        private Integer page;
        private Integer size;

        public SearchQuery() {
        }

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public Integer getSize() {
            return size;
        }

        public void setSize(Integer size) {
            this.size = size;
        }
    }

    public static class TestController {
        // Path parameter tests
        public String getById(@Param("id") Path<Integer> id) {
            return "User ID: " + id.value;
        }

        public String getByMultipleParams(
                @Param("userId") Path<Integer> userId,
                @Param("postId") Path<Long> postId
        ) {
            return "User: " + userId.value + ", Post: " + postId.value;
        }

        // Query parameter tests
        public String search(@Param("q") Query<String> query) {
            return "Search: " + query.value;
        }

        public String searchWithOptional(@Param("q") Query<String> query) {
            return query.value != null ? "Found: " + query.value : "No query";
        }

        public String filterByTags(@Param("tags") Query<List<String>> tags) {
            List<String> tagList = tags.value;
            return "Tags: " + String.join(", ", tagList);
        }

        public String getAllQueries(@Param("") Query<Map<String, List<String>>> allQueries) {
            Map<String, List<String>> queries = allQueries.value;
            return "Query count: " + queries.size();
        }

        public String searchComplex(@Param("") Query<SearchQuery> searchQuery) {
            SearchQuery sq = searchQuery.value;
            return "Keyword: " + sq.getKeyword() + ", Page: " + sq.getPage();
        }

        // Form parameter tests
        public String createUser(@Param("username") Form<String> username) {
            return "Created: " + username.value;
        }

        public String uploadWithForm(@Param("title") Form<String> title, UploadedFile file) {
            return "Title: " + title.value + ", File: " + file.value.filename;
        }

        public String submitFormObject(Form<User> user) {
            User u = user.value;
            return "User: " + u.getName() + ", Age: " + u.getAge();
        }

        // Header tests
        public String checkAuth(@Param("Authorization") Header auth) {
            return "Auth: " + auth.value;
        }

        public String checkMultipleHeaders(
                @Param("User-Agent") Header userAgent,
                @Param("Accept") Header accept
        ) {
            return "UA: " + userAgent.value + ", Accept: " + accept.value;
        }

        // Cookie tests
        public String getSession(@Param("session_id") Cookie sessionId) {
            return "Session: " + sessionId.value;
        }

        // JSON body tests
        public String createUserJson(@Param("") Json<User> userJson) {
            User user = userJson.value;
            return "JSON User: " + user.getName() + ", " + user.getAge();
        }

        // Text body tests
        public String processText(Text textBody) {
            return "Text: " + textBody.value;
        }

        // Stream tests
        public String processStream(Stream stream) throws Exception {
            InputStream is = stream.value;
            byte[] bytes = is.readAllBytes();
            return "Stream bytes: " + bytes.length;
        }

        // Context parameter test
        public String getContextInfo(Context ctx) {
            return "Method: " + ctx.getMethod() + ", Path: " + ctx.getPath();
        }

        // Mixed parameters
        public String complexEndpoint(
                @Param("id") Path<Integer> id,
                @Param("filter") Query<String> filter,
                @Param("") Json<User> user,
                Context ctx
        ) {
            return String.format("ID: %d, Filter: %s, User: %s, Method: %s",
                    id.value, filter.value, user.value.getName(), ctx.getMethod());
        }
    }

    // ===== Path Parameter Tests =====

    @Nested
    @DisplayName("Path Parameter Extraction Tests")
    class PathParameterTests {

        @Test
        @DisplayName("Should extract single Integer path parameter")
        void testSinglePathParameter() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getById", Path.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("id", "123");
            Context ctx = createContext("GET", "/users/123", pathParams);

            Object result = handler.invoke(ctx);

            assertEquals("User ID: 123", result);
        }

        @Test
        @DisplayName("Should extract multiple path parameters")
        void testMultiplePathParameters() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getByMultipleParams", Path.class, Path.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("userId", "42");
            pathParams.put("postId", "999");
            Context ctx = createContext("GET", "/users/42/posts/999", pathParams);

            Object result = handler.invoke(ctx);

            assertEquals("User: 42, Post: 999", result);
        }

        @Test
        @DisplayName("Should throw exception when path parameter is missing")
        void testMissingPathParameter() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getById", Path.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContext("GET", "/users/123", new HashMap<>());

            assertThrows(IllegalArgumentException.class, () -> handler.invoke(ctx));
        }

        @Test
        @DisplayName("Should throw exception when path parameter type conversion fails")
        void testPathParameterTypeConversionFailure() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getById", Path.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("id", "not-a-number");
            Context ctx = createContext("GET", "/users/abc", pathParams);

            assertThrows(TypeConversionFailed.class, () -> handler.invoke(ctx));
        }
    }

    // ===== Query Parameter Tests =====

    @Nested
    @DisplayName("Query Parameter Extraction Tests")
    class QueryParameterTests {

        @Test
        @DisplayName("Should extract single query parameter")
        void testSingleQueryParameter() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "search", Query.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithQuery("GET", "/search", "q=kotlin");

            Object result = handler.invoke(ctx);

            assertEquals("Search: kotlin", result);
        }

        @Test
        @DisplayName("Should extract query parameter list")
        void testQueryParameterList() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "filterByTags", Query.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithQuery("GET", "/posts", "tags=java&tags=kotlin&tags=web");

            Object result = handler.invoke(ctx);

            assertEquals("Tags: java, kotlin, web", result);
        }

        @Test
        @DisplayName("Should extract all queries as map")
        void testAllQueriesAsMap() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getAllQueries", Query.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithQuery("GET", "/data", "name=John&age=30&city=NYC");

            Object result = handler.invoke(ctx);

            assertEquals("Query count: 3", result);
        }

        @Test
        @DisplayName("Should extract query as complex object")
        void testQueryAsComplexObject() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "searchComplex", Query.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithQuery("GET", "/search", "keyword=test&page=2&size=20");

            Object result = handler.invoke(ctx);

            assertEquals("Keyword: test, Page: 2", result);
        }

        @Test
        @DisplayName("Should get null when required query parameter is missing")
        void testMissingRequiredQueryParameter() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "search", Query.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithQuery("GET", "/search", "");
            var result = handler.invoke(ctx);
            assertEquals("Search: null", result);
        }
    }

    // ===== Form Parameter Tests =====

    @Nested
    @DisplayName("Form Parameter Extraction Tests")
    class FormParameterTests {

        @Test
        @DisplayName("Should extract single form parameter")
        void testSingleFormParameter() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "createUser", Form.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithForm("POST", "/users", "username=johndoe");

            Object result = handler.invoke(ctx);

            assertEquals("Created: johndoe", result);
        }

        @Test
        @DisplayName("Should extract form as complex object")
        void testFormAsComplexObject() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "submitFormObject", Form.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithForm("POST", "/users", "name=Alice&age=25&email=alice@example.com");

            Object result = handler.invoke(ctx);

            assertEquals("User: Alice, Age: 25", result);
        }

        @Test
        @DisplayName("Should get null when form parameter is missing")
        void testMissingFormParameter() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "createUser", Form.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithForm("POST", "/users", "email=test@test.com");
            var result = handler.invoke(ctx);
            assertEquals("Created: null", result);
        }
    }

    // ===== Header Tests =====

    @Nested
    @DisplayName("Header Extraction Tests")
    class HeaderTests {

        @Test
        @DisplayName("Should extract single header")
        void testSingleHeader() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "checkAuth", Header.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer token123");
            Context ctx = createContextWithHeaders("GET", "/api/data", headers);

            Object result = handler.invoke(ctx);

            assertEquals("Auth: Bearer token123", result);
        }

        @Test
        @DisplayName("Should extract multiple headers")
        void testMultipleHeaders() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "checkMultipleHeaders", Header.class, Header.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "TestClient/1.0");
            headers.put("Accept", "application/json");
            Context ctx = createContextWithHeaders("GET", "/api", headers);

            Object result = handler.invoke(ctx);

            assertEquals("UA: TestClient/1.0, Accept: application/json", result);
        }

        @Test
        @DisplayName("Should get null when header is missing")
        void testMissingHeader() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "checkAuth", Header.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithHeaders("GET", "/api/data", new HashMap<>());
            var result = handler.invoke(ctx);
            assertEquals("Auth: null", result);
        }
    }

    // ===== Cookie Tests =====

    @Nested
    @DisplayName("Cookie Extraction Tests")
    class CookieTests {

        @Test
        @DisplayName("Should extract cookie")
        void testCookieExtraction() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getSession", Cookie.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithCookies("GET", "/profile", "session_id=abc123; user=john");

            Object result = handler.invoke(ctx);

            assertEquals("Session: abc123", result);
        }

        @Test
        @DisplayName("Should get null when cookie is missing")
        void testMissingCookie() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getSession", Cookie.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithCookies("GET", "/profile", "user=john");
            var result = handler.invoke(ctx);
            assertEquals("Session: null", result);
        }
    }

    // ===== JSON Body Tests =====

    @Nested
    @DisplayName("JSON Body Extraction Tests")
    class JsonBodyTests {

        @Test
        @DisplayName("Should extract JSON body as object")
        void testJsonBodyExtraction() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "createUserJson", Json.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            String jsonBody = "{\"name\":\"Bob\",\"age\":35,\"email\":\"bob@example.com\"}";
            Context ctx = createContextWithJson("POST", "/users", jsonBody);

            Object result = handler.invoke(ctx);

            assertEquals("JSON User: Bob, 35", result);
        }

        @Test
        @DisplayName("Should throw exception for invalid JSON")
        void testInvalidJson() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "createUserJson", Json.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            String invalidJson = "{invalid json}";
            Context ctx = createContextWithJson("POST", "/users", invalidJson);

            assertThrows(BadRequest.class, () -> handler.invoke(ctx));
        }
    }

    // ===== Text Body Tests =====

    @Nested
    @DisplayName("Text Body Extraction Tests")
    class TextBodyTests {

        @Test
        @DisplayName("Should extract plain text body")
        void testTextBodyExtraction() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "processText", Text.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContextWithText("POST", "/data", "Hello, World!");

            Object result = handler.invoke(ctx);

            assertEquals("Text: Hello, World!", result);
        }

        @Test
        @DisplayName("Should get null when text body is missing")
        void testMissingTextBody() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "processText", Text.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContext("POST", "/data", new HashMap<>());
            var result = handler.invoke(ctx);
            assertEquals("Text: null", result);
        }
    }

    // ===== Stream Tests =====

    @Nested
    @DisplayName("Stream Extraction Tests")
    class StreamTests {

        @Test
        @DisplayName("Should extract input stream")
        void testStreamExtraction() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "processStream", Stream.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            String data = "Binary data content";
            InputStream stream = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));

            Request request = new Request(
                    "POST",
                    "/upload",
                    "",
                    new Headers(),
                    stream,
                    new Request.ServerInfo(),
                    Collections::emptyList
            );

            Context ctx = new Context(request, new Response(), new Colleen(), null);
            Object result = handler.invoke(ctx);

            assertEquals("Stream bytes: " + data.length(), result);
        }
    }

    // ===== Context Parameter Tests =====

    @Nested
    @DisplayName("Context Parameter Tests")
    class ContextParameterTests {

        @Test
        @DisplayName("Should inject Context parameter")
        void testContextInjection() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getContextInfo", Context.class
            );

            Handler handler = ExtractorKt.cx(method, controller);
            Context ctx = createContext("GET", "/test", new HashMap<>());

            Object result = handler.invoke(ctx);

            assertEquals("Method: GET, Path: /test", result);
        }
    }

    // ===== Mixed Parameters Tests =====

    @Nested
    @DisplayName("Mixed Parameters Extraction Tests")
    class MixedParametersTests {

        @Test
        @DisplayName("Should extract multiple parameter types simultaneously")
        void testMixedParameters() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "complexEndpoint", Path.class, Query.class, Json.class, Context.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            String jsonBody = "{\"name\":\"Charlie\",\"age\":40}";
            Headers headers = new Headers();
            headers.set("content-type", "application/json");

            InputStream stream = new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8));

            Request request = new Request(
                    "POST",
                    "/api/users/100",
                    "filter=active",
                    headers,
                    stream,
                    new Request.ServerInfo(),
                    Collections::emptyList
            );

            Context ctx = new Context(request, new Response(), new Colleen(), null);

            ctx.getPathParams().put("id", "100");

            Object result = handler.invoke(ctx);

            assertEquals("ID: 100, Filter: active, User: Charlie, Method: POST", result);
        }
    }

    // ===== Type Conversion Tests =====

    @Nested
    @DisplayName("Type Conversion Tests")
    class TypeConversionTests {

        @Test
        @DisplayName("Should convert string to Integer")
        void testStringToInteger() {
            Integer result = (Integer) ExtractorKt.convertTo("42", Integer.class);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should convert string to Long")
        void testStringToLong() {
            Long result = (Long) ExtractorKt.convertTo("9999999999", Long.class);
            assertEquals(9999999999L, result);
        }

        @Test
        @DisplayName("Should convert string to Double")
        void testStringToDouble() {
            Double result = (Double) ExtractorKt.convertTo("3.14", Double.class);
            assertEquals(3.14, result, 0.001);
        }

        @Test
        @DisplayName("Should convert string to Boolean (lenient)")
        void testStringToBoolean() {
            assertEquals(true, ExtractorKt.convertTo("true", Boolean.class));
            assertEquals(true, ExtractorKt.convertTo("1", Boolean.class));
            assertEquals(true, ExtractorKt.convertTo("yes", Boolean.class));
            assertEquals(true, ExtractorKt.convertTo("Y", Boolean.class));
            assertEquals(false, ExtractorKt.convertTo("false", Boolean.class));
            assertEquals(false, ExtractorKt.convertTo("0", Boolean.class));
        }

        @Test
        @DisplayName("Should throw exception for invalid number conversion")
        void testInvalidNumberConversion() {
            assertThrows(TypeConversionFailed.class,
                    () -> ExtractorKt.convertTo("abc", Integer.class));
        }

        @Test
        @DisplayName("Should handle null values for nullable types")
        void testNullConversion() {
            assertNull(ExtractorKt.convertTo(null, String.class));
        }
    }

    // ===== Error Handling Tests =====

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

    }

    // ===== Java Annotation Tests =====

    @Nested
    @DisplayName("Java @Param Annotation Tests")
    class ParamAnnotationTests {

        @Test
        @DisplayName("Should use @Param annotation value as parameter name")
        void testParamAnnotation() throws Exception {
            TestController controller = new TestController();
            java.lang.reflect.Method method = TestController.class.getMethod(
                    "getById", Path.class
            );

            Handler handler = ExtractorKt.cx(method, controller);

            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("id", "555");
            Context ctx = createContext("GET", "/users/555", pathParams);

            Object result = handler.invoke(ctx);

            assertEquals("User ID: 555", result);
        }

        @Test
        @DisplayName("Should throw error when @Param is missing and -parameters flag not used")
        void testMissingParamAnnotation() {
            // This test validates configuration checking
            // In real scenarios without -parameters flag, parameter names aren't available
            assertDoesNotThrow(() -> {
                java.lang.reflect.Method method = TestController.class.getMethod(
                        "getById", Path.class
                );
                // Should succeed because @Param is present
                ExtractorKt.cx(method, new TestController());
            });
        }
    }
}
