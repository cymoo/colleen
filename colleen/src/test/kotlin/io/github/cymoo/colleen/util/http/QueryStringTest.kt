package io.github.cymoo.colleen.util.http

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryStringTest {

    // ========================================
    // Basic Parsing
    // ========================================

    @Test
    fun `parse - single key-value pair`() {
        // Arrange
        val queryString = "name=John"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John"), result["name"])
        assertEquals(1, result.size)
    }

    @Test
    fun `parse - multiple key-value pairs`() {
        // Arrange
        val queryString = "name=John&age=30&city=NYC"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John"), result["name"])
        assertEquals(listOf("30"), result["age"])
        assertEquals(listOf("NYC"), result["city"])
        assertEquals(3, result.size)
    }

    @Test
    fun `parse - multiple values for same key`() {
        // Arrange
        val queryString = "tag=kotlin&tag=java&tag=scala"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("kotlin", "java", "scala"), result["tag"])
        assertEquals(1, result.size)
    }

    @Test
    fun `parse - mixed single and multiple values`() {
        // Arrange
        val queryString = "name=John&tag=a&tag=b&age=30&tag=c"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John"), result["name"])
        assertEquals(listOf("a", "b", "c"), result["tag"])
        assertEquals(listOf("30"), result["age"])
        assertEquals(3, result.size)
    }

    // ========================================
    // Empty and Blank Handling
    // ========================================

    @Test
    fun `parse - empty string returns empty map`() {
        // Arrange
        val queryString = ""

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse - blank string returns empty map`() {
        // Arrange
        val queryString = "   "

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse - empty value preserved`() {
        // Arrange
        val queryString = "key="

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf(""), result["key"])
    }

    @Test
    fun `parse - empty key ignored`() {
        // Arrange
        val queryString = "=value"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse - empty key with empty value ignored`() {
        // Arrange
        val queryString = "="

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertTrue(result.isEmpty())
    }

    // ========================================
    // Flag Parameters (No Equals Sign)
    // ========================================

    @Test
    fun `parse - flag without value`() {
        // Arrange
        val queryString = "debug"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf(""), result["debug"])
    }

    @Test
    fun `parse - multiple flags`() {
        // Arrange
        val queryString = "debug&verbose&trace"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf(""), result["debug"])
        assertEquals(listOf(""), result["verbose"])
        assertEquals(listOf(""), result["trace"])
        assertEquals(3, result.size)
    }

    @Test
    fun `parse - mixed flags and key-value pairs`() {
        // Arrange
        val queryString = "name=John&debug&age=30&verbose"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John"), result["name"])
        assertEquals(listOf(""), result["debug"])
        assertEquals(listOf("30"), result["age"])
        assertEquals(listOf(""), result["verbose"])
        assertEquals(4, result.size)
    }

    // ========================================
    // Separator Edge Cases
    // ========================================

    @Test
    fun `parse - consecutive ampersands ignored`() {
        // Arrange
        val queryString = "a=1&&b=2"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("1"), result["a"])
        assertEquals(listOf("2"), result["b"])
        assertEquals(2, result.size)
    }

    @Test
    fun `parse - leading ampersand ignored`() {
        // Arrange
        val queryString = "&key=value"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value"), result["key"])
        assertEquals(1, result.size)
    }

    @Test
    fun `parse - trailing ampersand ignored`() {
        // Arrange
        val queryString = "key=value&"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value"), result["key"])
        assertEquals(1, result.size)
    }

    @Test
    fun `parse - leading and trailing ampersands ignored`() {
        // Arrange
        val queryString = "&key=value&"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value"), result["key"])
        assertEquals(1, result.size)
    }

    @Test
    fun `parse - multiple consecutive ampersands`() {
        // Arrange
        val queryString = "a=1&&&b=2"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("1"), result["a"])
        assertEquals(listOf("2"), result["b"])
        assertEquals(2, result.size)
    }

    // ========================================
    // URL Encoding - Basic
    // ========================================

    @Test
    fun `parse - space encoded as percent-20`() {
        // Arrange
        val queryString = "name=John%20Doe"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John Doe"), result["name"])
    }

    @Test
    fun `parse - space encoded as plus`() {
        // Arrange
        val queryString = "name=John+Doe"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John Doe"), result["name"])
    }

    @Test
    fun `parse - mixed space encodings`() {
        // Arrange
        val queryString = "first=John+Doe&last=Jane%20Smith"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John Doe"), result["first"])
        assertEquals(listOf("Jane Smith"), result["last"])
    }

    // ========================================
    // URL Encoding - Special Characters
    // ========================================

    @Test
    fun `parse - encoded ampersand in value`() {
        // Arrange
        val queryString = "key=a%26b"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("a&b"), result["key"])
    }

    @Test
    fun `parse - encoded ampersand in key`() {
        // Arrange
        val queryString = "a%26b=value"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value"), result["a&b"])
    }

    @Test
    fun `parse - encoded equals in value`() {
        // Arrange
        val queryString = "key=a%3Db"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("a=b"), result["key"])
    }

    @Test
    fun `parse - encoded equals in key`() {
        // Arrange
        val queryString = "a%3Db=value"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value"), result["a=b"])
    }

    @Test
    fun `parse - encoded percent sign`() {
        // Arrange
        val queryString = "key=100%25"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("100%"), result["key"])
    }

    @Test
    fun `parse - multiple encoded special characters`() {
        // Arrange
        val queryString = "query=%3Fname%3DJohn%26age%3D30%23"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("?name=John&age=30#"), result["query"])
    }

    // ========================================
    // URL Encoding - Unicode
    // ========================================

    @Test
    fun `parse - emoji encoding`() {
        // Arrange
        val queryString = "emoji=%F0%9F%98%80"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("😀"), result["emoji"])
    }

    @Test
    fun `parse - chinese characters encoding`() {
        // Arrange
        val queryString = "greeting=%E4%BD%A0%E5%A5%BD"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("你好"), result["greeting"])
    }

    @Test
    fun `parse - mixed unicode and ascii`() {
        // Arrange
        val queryString = "name=John&emoji=%F0%9F%98%80&city=%E4%B8%9C%E4%BA%AC"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("John"), result["name"])
        assertEquals(listOf("😀"), result["emoji"])
        assertEquals(listOf("东京"), result["city"])
    }

    // ========================================
    // Malformed Encoding
    // ========================================

    @Test
    fun `parse - incomplete percent encoding preserved`() {
        // Arrange
        val queryString = "key=value%"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value%"), result["key"])
    }

    @Test
    fun `parse - invalid hex in percent encoding preserved`() {
        // Arrange
        val queryString = "key=value%ZZ"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value%ZZ"), result["key"])
    }

    @Test
    fun `parse - single hex digit in percent encoding preserved`() {
        // Arrange
        val queryString = "key=value%2"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("value%2"), result["key"])
    }

    // ========================================
    // Complex Scenarios
    // ========================================

    @Test
    fun `parse - real world example with multiple parameters`() {
        // Arrange
        val queryString = "q=kotlin+tutorial&sort=date&filter=beginner&filter=advanced&page=1"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("kotlin tutorial"), result["q"])
        assertEquals(listOf("date"), result["sort"])
        assertEquals(listOf("beginner", "advanced"), result["filter"])
        assertEquals(listOf("1"), result["page"])
        assertEquals(4, result.size)
    }

    @Test
    fun `parse - search query with special characters`() {
        // Arrange
        val queryString = "q=%22exact+phrase%22&lang=en&safe=on"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("\"exact phrase\""), result["q"])
        assertEquals(listOf("en"), result["lang"])
        assertEquals(listOf("on"), result["safe"])
    }

    @Test
    fun `parse - oauth callback with encoded url`() {
        // Arrange
        val queryString = "code=abc123&state=xyz&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("abc123"), result["code"])
        assertEquals(listOf("xyz"), result["state"])
        assertEquals(listOf("https://example.com/callback"), result["redirect_uri"])
    }

    @Test
    fun `parse - mixed empty values and flags`() {
        // Arrange
        val queryString = "a=1&flag&b=&c=3&another_flag&d="

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("1"), result["a"])
        assertEquals(listOf(""), result["flag"])
        assertEquals(listOf(""), result["b"])
        assertEquals(listOf("3"), result["c"])
        assertEquals(listOf(""), result["another_flag"])
        assertEquals(listOf(""), result["d"])
        assertEquals(6, result.size)
    }

    // ========================================
    // Order Preservation
    // ========================================

    @Test
    fun `parse - preserves value order for duplicate keys`() {
        // Arrange
        val queryString = "key=first&key=second&key=third"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("first", "second", "third"), result["key"])
    }

    @Test
    fun `parse - preserves value order in complex scenario`() {
        // Arrange
        val queryString = "tag=a&other=x&tag=b&tag=c&other=y"

        // Act
        val result = QueryString.parse(queryString)

        // Assert
        assertEquals(listOf("a", "b", "c"), result["tag"])
        assertEquals(listOf("x", "y"), result["other"])
    }
}