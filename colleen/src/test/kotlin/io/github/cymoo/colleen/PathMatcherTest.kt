package io.github.cymoo.colleen

import org.junit.jupiter.api.assertThrows
import kotlin.test.*

// ============================================================================
// PathSegment.parse() Tests
// ============================================================================

class PathSegmentParseTest {

    // ------------------------------------------------------------------------
    // Static Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `parse static segment - simple word`() {
        // Arrange
        val input = "users"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Static)
        assertEquals("users", result.value)
    }

    @Test
    fun `parse static segment - with hyphen`() {
        // Arrange
        val input = "user-profile"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Static)
        assertEquals("user-profile", result.value)
    }

    @Test
    fun `parse static segment - with numbers`() {
        // Arrange
        val input = "api-v2"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Static)
        assertEquals("api-v2", result.value)
    }

    // ------------------------------------------------------------------------
    // Parameter Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `parse parameter segment - simple name`() {
        // Arrange
        val input = "{id}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Param)
        assertEquals("id", result.name)
    }

    @Test
    fun `parse parameter segment - underscore name`() {
        // Arrange
        val input = "{user_id}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Param)
        assertEquals("user_id", result.name)
    }

    @Test
    fun `parse parameter segment - camelCase name`() {
        // Arrange
        val input = "{userId}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Param)
        assertEquals("userId", result.name)
    }

    @Test
    fun `parse parameter segment - starts with underscore`() {
        // Arrange
        val input = "{_internal}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Param)
        assertEquals("_internal", result.name)
    }

    @Test
    fun `parse parameter segment - with spaces trimmed`() {
        // Arrange
        val input = "{  id  }"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Param)
        assertEquals("id", result.name)
    }

    @Test
    fun `parse parameter segment - reject hyphen in name`() {
        // Arrange
        val input = "{user-id}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Invalid parameter name"))
    }

    @Test
    fun `parse parameter segment - reject starting with number`() {
        // Arrange
        val input = "{123id}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Invalid parameter name"))
    }

    @Test
    fun `parse parameter segment - reject empty name`() {
        // Arrange
        val input = "{}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Empty parameter name"))
    }

    @Test
    fun `parse parameter segment - reject only spaces`() {
        // Arrange
        val input = "{   }"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Empty parameter name"))
    }

    // ------------------------------------------------------------------------
    // Wildcard Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `parse wildcard segment - simple name`() {
        // Arrange
        val input = "{path...}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Wildcard)
        assertEquals("path", result.name)
    }

    @Test
    fun `parse wildcard segment - underscore name`() {
        // Arrange
        val input = "{file_path...}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Wildcard)
        assertEquals("file_path", result.name)
    }

    @Test
    fun `parse wildcard segment - with spaces`() {
        // Arrange
        val input = "{  path  ...}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Wildcard)
        assertEquals("path", result.name)
    }

    @Test
    fun `parse wildcard segment - reject empty name`() {
        // Arrange
        val input = "{...}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Empty wildcard name"))
    }

    @Test
    fun `parse wildcard segment - reject invalid name`() {
        // Arrange
        val input = "{path-name...}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Invalid wildcard name"))
    }

    // ------------------------------------------------------------------------
    // Complex Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `parse complex segment - param with suffix`() {
        // Arrange
        val input = "{name}-bar"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Complex)
        assertEquals(2, result.parts.size)
        assertTrue(result.parts[0] is PathSegment.Complex.Part.Param)
        assertEquals("name", (result.parts[0] as PathSegment.Complex.Part.Param).name)
        assertTrue(result.parts[1] is PathSegment.Complex.Part.Text)
        assertEquals("-bar", (result.parts[1] as PathSegment.Complex.Part.Text).value)
    }

    @Test
    fun `parse complex segment - param with prefix`() {
        // Arrange
        val input = "prefix-{id}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Complex)
        assertEquals(2, result.parts.size)
        assertTrue(result.parts[0] is PathSegment.Complex.Part.Text)
        assertEquals("prefix-", (result.parts[0] as PathSegment.Complex.Part.Text).value)
        assertTrue(result.parts[1] is PathSegment.Complex.Part.Param)
        assertEquals("id", (result.parts[1] as PathSegment.Complex.Part.Param).name)
    }

    @Test
    fun `parse complex segment - param with prefix and suffix`() {
        // Arrange
        val input = "file-{name}.txt"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Complex)
        assertEquals(3, result.parts.size)
        assertEquals("file-", (result.parts[0] as PathSegment.Complex.Part.Text).value)
        assertEquals("name", (result.parts[1] as PathSegment.Complex.Part.Param).name)
        assertEquals(".txt", (result.parts[2] as PathSegment.Complex.Part.Text).value)
    }

    @Test
    fun `parse complex segment - two params with separator`() {
        // Arrange
        val input = "{lang}-{region}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Complex)
        assertEquals(3, result.parts.size)
        assertEquals("lang", (result.parts[0] as PathSegment.Complex.Part.Param).name)
        assertEquals("-", (result.parts[1] as PathSegment.Complex.Part.Text).value)
        assertEquals("region", (result.parts[2] as PathSegment.Complex.Part.Param).name)
    }

    @Test
    fun `parse complex segment - multiple params and text`() {
        // Arrange
        val input = "v{major}.{minor}.{patch}"

        // Act
        val result = PathSegment.parse(input)

        // Assert
        assertTrue(result is PathSegment.Complex)
        assertEquals(6, result.parts.size)
        assertEquals("v", (result.parts[0] as PathSegment.Complex.Part.Text).value)
        assertEquals("major", (result.parts[1] as PathSegment.Complex.Part.Param).name)
        assertEquals(".", (result.parts[2] as PathSegment.Complex.Part.Text).value)
        assertEquals("minor", (result.parts[3] as PathSegment.Complex.Part.Param).name)
        assertEquals(".", (result.parts[4] as PathSegment.Complex.Part.Text).value)
    }

    @Test
    fun `parse complex segment - reject adjacent parameters`() {
        // Arrange
        val input = "{a}{b}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Adjacent parameters"))
    }

    @Test
    fun `parse complex segment - reject wildcard in complex`() {
        // Arrange
        val input = "{path...}.txt"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Wildcard not allowed in complex segment"))
    }

    @Test
    fun `parse complex segment - reject empty param name`() {
        // Arrange
        val input = "{}-bar"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Empty parameter name"))
    }

    // ------------------------------------------------------------------------
    // Edge Cases
    // ------------------------------------------------------------------------

    @Test
    fun `parse segment - reject unmatched opening brace`() {
        // Arrange
        val input = "{id"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parse(input)
        }
        assertTrue(exception.message!!.contains("Unmatched '{'"))
    }

    @Test
    fun `parse segment - text with braces but not param pattern`() {
        // Arrange
        val input = "text{notparam"

        // Act
        assertThrows<IllegalArgumentException> {
            PathSegment.parse(input)
        }
    }
}

// ============================================================================
// PathSegment.parseAll() Tests
// ============================================================================

class PathSegmentParseAllTest {

    @Test
    fun `parseAll - empty path returns empty list`() {
        // Arrange
        val path = ""

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseAll - root path returns empty list`() {
        // Arrange
        val path = "/"

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseAll - simple static path`() {
        // Arrange
        val path = "/users/profile"

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertEquals(2, result.size)
        assertEquals("users", (result[0] as PathSegment.Static).value)
        assertEquals("profile", (result[1] as PathSegment.Static).value)
    }

    @Test
    fun `parseAll - path with parameters`() {
        // Arrange
        val path = "/users/{id}/posts/{postId}"

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertEquals(4, result.size)
        assertEquals("users", (result[0] as PathSegment.Static).value)
        assertEquals("id", (result[1] as PathSegment.Param).name)
        assertEquals("posts", (result[2] as PathSegment.Static).value)
        assertEquals("postId", (result[3] as PathSegment.Param).name)
    }

    @Test
    fun `parseAll - path with wildcard at end`() {
        // Arrange
        val path = "/files/{path...}"

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertEquals(2, result.size)
        assertEquals("files", (result[0] as PathSegment.Static).value)
        assertEquals("path", (result[1] as PathSegment.Wildcard).name)
    }

    @Test
    fun `parseAll - path with complex segments`() {
        // Arrange
        val path = "/v{version}/file-{name}.txt"

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertEquals(2, result.size)
        assertTrue(result[0] is PathSegment.Complex)
        assertTrue(result[1] is PathSegment.Complex)
    }

    @Test
    fun `parseAll - reject duplicate parameter names`() {
        // Arrange
        val path = "/users/{id}/posts/{id}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parseAll(path)
        }
        assertTrue(exception.message!!.contains("Duplicate parameter name 'id'"))
    }
    @Test
    fun `priority - reject too many path segments to prevent overflow`() {
        // Arrange
        val path = (1..32).joinToString(separator = "/", prefix = "/") { "a" }

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parseAll(path)
        }

        assertTrue(
            exception.message!!.contains("Too many path segments")
        )
    }

    @Test
    fun `parseAll - reject duplicate parameter names in complex segment`() {
        // Arrange
        val path = "/{id}-{id}"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parseAll(path)
        }
        assertTrue(exception.message!!.contains("Duplicate parameter name 'id'"))
    }

    @Test
    fun `parseAll - reject duplicate parameter names across different segment types`() {
        // Arrange
        val path = "/users/{id}/file-{id}.txt"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parseAll(path)
        }
        assertTrue(exception.message!!.contains("Duplicate parameter name 'id'"))
    }

    @Test
    fun `parseAll - reject wildcard not at end`() {
        // Arrange
        val path = "/files/{path...}/extra"

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            PathSegment.parseAll(path)
        }
        assertTrue(exception.message!!.contains("Wildcard parameter must be the last segment"))
    }

    @Test
    fun `parseAll - allow different parameter names`() {
        // Arrange
        val path = "/users/{userId}/posts/{postId}/comments/{commentId}"

        // Act
        val result = PathSegment.parseAll(path)

        // Assert
        assertEquals(6, result.size)
        // No exception should be thrown
    }
}

// ============================================================================
// PathSegment.priority() Tests
// ============================================================================

class PathSegmentPriorityTest {

    @Test
    fun `priority - static is higher than param`() {
        // Arrange
        val staticPath = listOf(PathSegment.Static("users"))
        val paramPath = listOf(PathSegment.Param("id"))

        // Act
        val staticPriority = PathSegment.priority(staticPath)
        val paramPriority = PathSegment.priority(paramPath)

        // Assert
        assertTrue(staticPriority > paramPriority)
    }

    @Test
    fun `priority - complex is higher than param`() {
        // Arrange
        val complexParts = listOf(
            PathSegment.Complex.Part.Text("file-"),
            PathSegment.Complex.Part.Param("name")
        )
        val complexPath = listOf(PathSegment.Complex(complexParts))
        val paramPath = listOf(PathSegment.Param("id"))

        // Act
        val complexPriority = PathSegment.priority(complexPath)
        val paramPriority = PathSegment.priority(paramPath)

        // Assert
        assertTrue(complexPriority > paramPriority)
    }

    @Test
    fun `priority - param is higher than wildcard`() {
        // Arrange
        val paramPath = listOf(PathSegment.Param("id"))
        val wildcardPath = listOf(PathSegment.Wildcard("path"))

        // Act
        val paramPriority = PathSegment.priority(paramPath)
        val wildcardPriority = PathSegment.priority(wildcardPath)

        // Assert
        assertTrue(paramPriority > wildcardPriority)
    }

    @Test
    fun `priority - more specific path wins`() {
        // Arrange
        val specificPath = listOf(
            PathSegment.Static("users"),
            PathSegment.Static("admin")
        )
        val genericPath = listOf(
            PathSegment.Static("users"),
            PathSegment.Param("id")
        )

        // Act
        val specificPriority = PathSegment.priority(specificPath)
        val genericPriority = PathSegment.priority(genericPath)

        // Assert
        assertTrue(specificPriority > genericPriority)
    }

    @Test
    fun `priority - longer path with static has higher priority`() {
        // Arrange
        val longPath = listOf(
            PathSegment.Static("api"),
            PathSegment.Static("v1"),
            PathSegment.Static("users")
        )
        val shortPath = listOf(
            PathSegment.Static("api"),
            PathSegment.Param("resource")
        )

        // Act
        val longPriority = PathSegment.priority(longPath)
        val shortPriority = PathSegment.priority(shortPath)

        // Assert
        assertTrue(longPriority > shortPriority)
    }

    @Test
    fun `priority - empty path has zero priority`() {
        // Arrange
        val emptyPath = emptyList<PathSegment>()

        // Act
        val result = PathSegment.priority(emptyPath)

        // Assert
        assertEquals(0, result)
    }
}

// ============================================================================
// PathMatcher Tests
// ============================================================================

class PathMatcherTest {

    // ------------------------------------------------------------------------
    // Empty Pattern Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - empty pattern with exact match on root path`() {
        // Arrange
        val requestPath = "/"
        val pathPattern = "/"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertTrue(result.params.isEmpty())
        assertEquals(0, result.consumedSegments)
    }

    @Test
    fun `match - empty pattern with exact match on non-root path fails`() {
        // Arrange
        val requestPath = "/users"
        val pathPattern = "/"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    @Test
    fun `match - empty pattern with prefix match allows any path`() {
        // Arrange
        val requestPath = "/users/123/posts"
        val pathPattern = "/"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = false)

        // Assert
        assertTrue(result.matched)
        assertEquals(0, result.consumedSegments)
    }

    // ------------------------------------------------------------------------
    // Static Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - exact static path match`() {
        // Arrange
        val requestPath = "/users/profile"
        val pathPattern = "/users/profile"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertTrue(result.params.isEmpty())
        assertEquals(2, result.consumedSegments)
    }

    @Test
    fun `match - static path mismatch`() {
        // Arrange
        val requestPath = "/users/settings"
        val pathPattern = "/users/profile"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    @Test
    fun `match - static path prefix match`() {
        // Arrange
        val requestPath = "/users/profile/settings"
        val pathPattern = "/users/profile"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = false)

        // Assert
        assertTrue(result.matched)
        assertEquals(2, result.consumedSegments)
    }

    @Test
    fun `match - request path shorter than pattern fails`() {
        // Arrange
        val requestPath = "/users"
        val pathPattern = "/users/profile"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    // ------------------------------------------------------------------------
    // Parameter Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - single parameter extraction`() {
        // Arrange
        val requestPath = "/users/123"
        val pathPattern = "/users/{id}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("123", result.params["id"])
        assertEquals(2, result.consumedSegments)
    }

    @Test
    fun `match - multiple parameters extraction`() {
        // Arrange
        val requestPath = "/users/123/posts/456"
        val pathPattern = "/users/{userId}/posts/{postId}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("123", result.params["userId"])
        assertEquals("456", result.params["postId"])
        assertEquals(4, result.consumedSegments)
    }

    @Test
    fun `match - parameter with special characters`() {
        // Arrange
        val requestPath = "/users/user@example.com"
        val pathPattern = "/users/{email}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("user@example.com", result.params["email"])
    }

    @Test
    fun `match - parameter segment missing in request fails`() {
        // Arrange
        val requestPath = "/users"
        val pathPattern = "/users/{id}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    // ------------------------------------------------------------------------
    // Wildcard Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - wildcard captures single segment`() {
        // Arrange
        val requestPath = "/files/readme.txt"
        val pathPattern = "/files/{path...}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("readme.txt", result.params["path"])
        assertEquals(2, result.consumedSegments)
    }

    @Test
    fun `match - wildcard captures multiple segments`() {
        // Arrange
        val requestPath = "/files/docs/2024/report.pdf"
        val pathPattern = "/files/{path...}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("docs/2024/report.pdf", result.params["path"])
        assertEquals(4, result.consumedSegments)
    }

    @Test
    fun `match - wildcard captures empty path`() {
        // Arrange
        val requestPath = "/files"
        val pathPattern = "/files/{path...}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("", result.params["path"])
        assertEquals(1, result.consumedSegments)
    }

    // ------------------------------------------------------------------------
    // Complex Segment Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - complex segment with suffix`() {
        // Arrange
        val requestPath = "/readme-v2.txt"
        val pathPattern = "/{name}-v2.txt"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("readme", result.params["name"])
    }

    @Test
    fun `match - complex segment with prefix`() {
        // Arrange
        val requestPath = "/file-readme.txt"
        val pathPattern = "/file-{name}.txt"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("readme", result.params["name"])
    }

    @Test
    fun `match - complex segment with prefix and suffix`() {
        // Arrange
        val requestPath = "/v2-api"
        val pathPattern = "/v{version}-api"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("2", result.params["version"])
    }

    @Test
    fun `match - complex segment with two parameters`() {
        // Arrange
        val requestPath = "/en-US"
        val pathPattern = "/{lang}-{region}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("en", result.params["lang"])
        assertEquals("US", result.params["region"])
    }

    @Test
    fun `match - complex segment with version pattern`() {
        // Arrange
        val requestPath = "/v1.2.3"
        val pathPattern = "/v{major}.{minor}.{patch}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("1", result.params["major"])
        assertEquals("2", result.params["minor"])
        assertEquals("3", result.params["patch"])
    }

    @Test
    fun `match - complex segment greedy matching`() {
        // Arrange
        val requestPath = "/foo-1.2.3.txt"
        val pathPattern = "/{name}-{version}.txt"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("foo", result.params["name"])
        assertEquals("1.2.3", result.params["version"])
    }

    @Test
    fun `match - complex segment static text not found fails`() {
        // Arrange
        val requestPath = "/readme.md"
        val pathPattern = "/{name}.txt"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    @Test
    fun `match - complex segment empty parameter value fails`() {
        // Arrange
        val requestPath = "/-.txt"
        val pathPattern = "/{name}-.txt"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    @Test
    fun `match - complex segment parameter at end captures rest`() {
        // Arrange
        val requestPath = "/prefix-rest-of-name"
        val pathPattern = "/prefix-{name}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("rest-of-name", result.params["name"])
    }

    // ------------------------------------------------------------------------
    // Mixed Segment Type Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - mix of all segment types`() {
        // Arrange
        val requestPath = "/api/v2/users/123/files/docs/report.pdf"
        val pathPattern = "/api/v{version}/users/{id}/files/{path...}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("2", result.params["version"])
        assertEquals("123", result.params["id"])
        assertEquals("docs/report.pdf", result.params["path"])
    }

    @Test
    fun `match - complex real-world pattern`() {
        // Arrange
        val requestPath = "/en-US/products/laptop-123/reviews"
        val pathPattern = "/{lang}-{region}/products/{productId}/reviews"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertTrue(result.matched)
        assertEquals("en", result.params["lang"])
        assertEquals("US", result.params["region"])
        assertEquals("laptop-123", result.params["productId"])
    }

    // ------------------------------------------------------------------------
    // Exact Match vs Prefix Match Tests
    // ------------------------------------------------------------------------

    @Test
    fun `match - exact match requires full path consumption`() {
        // Arrange
        val requestPath = "/users/123/extra"
        val pathPattern = "/users/{id}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    @Test
    fun `match - prefix match allows extra segments`() {
        // Arrange
        val requestPath = "/users/123/posts/456"
        val pathPattern = "/users/{id}"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = false)

        // Assert
        assertTrue(result.matched)
        assertEquals("123", result.params["id"])
        assertEquals(2, result.consumedSegments)
    }

    // ------------------------------------------------------------------------
    // Edge Cases
    // ------------------------------------------------------------------------

    @Test
    fun `match - case sensitive matching`() {
        // Arrange
        val requestPath = "/Users/Profile"
        val pathPattern = "/users/profile"

        // Act
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        // Assert
        assertFalse(result.matched)
    }

    @Test
    fun `match - empty segment in request path`() {
        // Arrange
        val requestPath = "/users//123"  // Double slash
        val pathPattern = "/users/{id}"

        // Act
        // This depends on how PathUtils.split handles empty segments
        // Assuming it filters them out, this should match
        val result = PathMatcher.match(requestPath, pathPattern, exactMatch = true)

        assertTrue(result.matched)
    }
}
