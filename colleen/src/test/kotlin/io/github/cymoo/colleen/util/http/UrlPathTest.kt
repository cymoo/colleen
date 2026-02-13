package io.github.cymoo.colleen.util.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlPathTest {

    // ========================================
    // normalize() - Basic Functionality Tests
    // ========================================

    @Test
    fun `normalize should return root for empty string`() {
        // Arrange & Act
        val result = UrlPath.normalize("")

        // Assert
        assertEquals("/", result)
    }

    @Test
    fun `normalize should return root for single slash`() {
        // Arrange & Act
        val result = UrlPath.normalize("/")

        // Assert
        assertEquals("/", result)
    }

    @Test
    fun `normalize should handle simple path`() {
        // Arrange & Act
        val result = UrlPath.normalize("/api/users")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `normalize should remove trailing slash`() {
        // Arrange & Act
        val result = UrlPath.normalize("/api/users/")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `normalize should remove redundant slashes`() {
        // Arrange & Act
        val result = UrlPath.normalize("//api///users//")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `normalize should decode percent-encoded characters`() {
        // Arrange & Act
        val result = UrlPath.normalize("/api/users/%E4%B8%AD%E6%96%87")

        // Assert
        assertEquals("/api/users/中文", result)
    }

    @Test
    fun `normalize should decode spaces`() {
        // Arrange & Act
        val result = UrlPath.normalize("/api/hello%20world")

        // Assert
        assertEquals("/api/hello world", result)
    }

    @Test
    fun `normalize should handle path without leading slash`() {
        // Arrange & Act
        val result = UrlPath.normalize("api/users")

        // Assert
        assertEquals("/api/users", result)
    }

    // ========================================
    // normalize() - Security Tests
    // ========================================

    @Test
    fun `normalize should reject dot segment`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.normalize("/api/./users")
        }
    }

    @Test
    fun `normalize should reject double dot segment`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.normalize("/api/../users")
        }
    }

    @Test
    fun `normalize should reject encoded dot segment`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.normalize("/api/%2E/users")
        }
    }

    @Test
    fun `normalize should reject encoded double dot segment`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.normalize("/api/%2E%2E/users")
        }
    }

    // ========================================
    // split() - Basic Functionality Tests
    // ========================================

    @Test
    fun `split should return empty list for root path`() {
        // Arrange & Act
        val result = UrlPath.split("/")

        // Assert
        assertEquals(emptyList(), result)
    }

    @Test
    fun `split should return segments for simple path`() {
        // Arrange & Act
        val result = UrlPath.split("/api/users")

        // Assert
        assertEquals(listOf("api", "users"), result)
    }

    @Test
    fun `split should handle trailing slash`() {
        // Arrange & Act
        val result = UrlPath.split("/api/users/")

        // Assert
        assertEquals(listOf("api", "users"), result)
    }

    @Test
    fun `split should handle multiple slashes`() {
        // Arrange & Act
        val result = UrlPath.split("//api///users//")

        // Assert
        assertEquals(listOf("api", "users"), result)
    }

    @Test
    fun `split should decode percent-encoded segments`() {
        // Arrange & Act
        val result = UrlPath.split("/api/%E4%B8%AD%E6%96%87/test")

        // Assert
        assertEquals(listOf("api", "中文", "test"), result)
    }

    @Test
    fun `split should handle single segment`() {
        // Arrange & Act
        val result = UrlPath.split("/health")

        // Assert
        assertEquals(listOf("health"), result)
    }

    @Test
    fun `split should reject dot segments`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.split("/api/./users")
        }
    }

    // ========================================
    // join() - Basic Functionality Tests
    // ========================================

    @Test
    fun `join should combine base and single child`() {
        // Arrange & Act
        val result = UrlPath.join("/api", "users")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `join should handle trailing slashes`() {
        // Arrange & Act
        val result = UrlPath.join("/api/", "/users/")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `join should handle root base with child`() {
        // Arrange & Act
        val result = UrlPath.join("/", "health")

        // Assert
        assertEquals("/health", result)
    }

    @Test
    fun `join should combine multiple children`() {
        // Arrange & Act
        val result = UrlPath.join("/api", "v1", "users", "profile")

        // Assert
        assertEquals("/api/v1/users/profile", result)
    }

    @Test
    fun `join should handle root base and root child`() {
        // Arrange & Act
        val result = UrlPath.join("/", "/")

        // Assert
        assertEquals("/", result)
    }

    @Test
    fun `join should handle empty children array`() {
        // Arrange & Act
        val result = UrlPath.join("/api")

        // Assert
        assertEquals("/api", result)
    }

    @Test
    fun `join should normalize redundant slashes`() {
        // Arrange & Act
        val result = UrlPath.join("//api//", "//users//")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `join should handle complex multi-segment children`() {
        // Arrange & Act
        val result = UrlPath.join("/api", "users/123", "profile/settings")

        // Assert
        assertEquals("/api/users/123/profile/settings", result)
    }

    @Test
    fun `join should handle root with multiple root children`() {
        // Arrange & Act
        val result = UrlPath.join("/", "/api", "/users")

        // Assert
        assertEquals("/api/users", result)
    }

    @Test
    fun `join should decode percent-encoded paths`() {
        // Arrange & Act
        val result = UrlPath.join("/api", "%E4%B8%AD%E6%96%87")

        // Assert
        assertEquals("/api/中文", result)
    }

    // ========================================
    // join() - Edge Cases
    // ========================================

    @Test
    fun `join should reject dot segments in base`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.join("/api/./test", "users")
        }
    }

    @Test
    fun `join should reject dot segments in children`() {
        // Arrange & Act & Assert
        assertFailsWith<IllegalArgumentException> {
            UrlPath.join("/api", "../users")
        }
    }

    @Test
    fun `join should handle base without leading slash`() {
        // Arrange & Act
        val result = UrlPath.join("api", "users")

        // Assert
        assertEquals("/api/users", result)
    }
}