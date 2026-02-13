package io.github.cymoo.colleen.util.http

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("MediaTypeRegistry Tests")
class MediaTypeRegistryTest {

    // ========================================================================
    // MIME Type Normalization Tests
    // ========================================================================

    @Test
    fun `should normalize file extensions to MIME types`() {
        assertEquals("text/html", MediaTypeRegistry.normalize("html"))
        assertEquals("application/json", MediaTypeRegistry.normalize("json"))
        assertEquals("image/png", MediaTypeRegistry.normalize("png"))
        assertEquals("application/pdf", MediaTypeRegistry.normalize("pdf"))
    }

    @Test
    fun `should normalize full MIME types to lowercase`() {
        assertEquals("text/html", MediaTypeRegistry.normalize("text/html"))
        assertEquals("text/html", MediaTypeRegistry.normalize("TEXT/HTML"))
        assertEquals("application/json", MediaTypeRegistry.normalize("Application/JSON"))
    }

    @Test
    fun `should handle case insensitive extensions`() {
        assertEquals("text/html", MediaTypeRegistry.normalize("HTML"))
        assertEquals("application/json", MediaTypeRegistry.normalize("JSON"))
        assertEquals("image/jpeg", MediaTypeRegistry.normalize("JPEG"))
    }

    @Test
    fun `should return lowercase input for unknown extensions`() {
        assertEquals("unknown", MediaTypeRegistry.normalize("unknown"))
        assertEquals("custom", MediaTypeRegistry.normalize("CUSTOM"))
    }

    @Test
    fun `should handle edge case extensions`() {
        assertEquals("application/javascript", MediaTypeRegistry.normalize("js"))
        assertEquals("application/javascript", MediaTypeRegistry.normalize("javascript"))
        assertEquals("image/jpeg", MediaTypeRegistry.normalize("jpg"))
        assertEquals("image/jpeg", MediaTypeRegistry.normalize("jpeg"))
    }
}

@DisplayName("MediaType Tests")
class MediaTypeTest {

    // ========================================================================
    // MediaType Parsing Tests
    // ========================================================================

    @Test
    fun `should parse simple media type`() {
        val (type, subtype) = MediaType.parse("text/html")
        assertEquals("text", type)
        assertEquals("html", subtype)
    }

    @Test
    fun `should parse media type with parameters`() {
        val (type, subtype) = MediaType.parse("text/html; charset=utf-8")
        assertEquals("text", type)
        assertEquals("html", subtype)
    }

    @Test
    fun `should parse media type with quality factor`() {
        val (type, subtype) = MediaType.parse("text/html;q=0.9")
        assertEquals("text", type)
        assertEquals("html", subtype)
    }

    @Test
    fun `should handle case insensitive parsing`() {
        val (type, subtype) = MediaType.parse("TEXT/HTML")
        assertEquals("text", type)
        assertEquals("html", subtype)
    }

    @Test
    fun `should handle wildcard types`() {
        val (type1, subtype1) = MediaType.parse("*/*")
        assertEquals("*", type1)
        assertEquals("*", subtype1)

        val (type2, subtype2) = MediaType.parse("text/*")
        assertEquals("text", type2)
        assertEquals("*", subtype2)
    }

    @Test
    fun `should handle invalid format by returning wildcards`() {
        val (type, subtype) = MediaType.parse("invalid")
        assertEquals("*", type)
        assertEquals("*", subtype)
    }

    @Test
    fun `should handle empty string`() {
        val (type, subtype) = MediaType.parse("")
        assertEquals("*", type)
        assertEquals("*", subtype)
    }

    // ========================================================================
    // MediaType Creation Tests
    // ========================================================================

    @Test
    fun `should create MediaType from string`() {
        val mediaType = MediaType.from("text/html")
        assertEquals("text", mediaType.type)
        assertEquals("html", mediaType.subtype)
        assertEquals(1.0, mediaType.quality)
    }

    @Test
    fun `should extract quality factor`() {
        val mediaType = MediaType.from("text/html;q=0.8")
        assertEquals("text", mediaType.type)
        assertEquals("html", mediaType.subtype)
        assertEquals(0.8, mediaType.quality)
    }

    @Test
    fun `should handle quality factor with spaces`() {
        val mediaType = MediaType.from("text/html; q=0.5")
        assertEquals(0.5, mediaType.quality)
    }

    @Test
    fun `should default quality to 1_0 when not specified`() {
        val mediaType = MediaType.from("application/json")
        assertEquals(1.0, mediaType.quality)
    }

    @Test
    fun `should clamp quality factor to valid range`() {
        val mediaType1 = MediaType.from("text/html;q=1.5")
        assertEquals(1.0, mediaType1.quality)

        val mediaType2 = MediaType.from("text/html;q=-0.5")
        assertEquals(0.0, mediaType2.quality)
    }

    // ========================================================================
    // MediaType Matching Tests
    // ========================================================================

    @Test
    fun `should match exact media types`() {
        val mediaType = MediaType("text", "html")
        assertTrue(mediaType.matches("text/html"))
    }

    @Test
    fun `should match case insensitively`() {
        val mediaType = MediaType("text", "html")
        assertTrue(mediaType.matches("TEXT/HTML"))
        assertTrue(mediaType.matches("Text/Html"))
    }

    @Test
    fun `should match with wildcard subtype`() {
        val mediaType = MediaType("text", "*")
        assertTrue(mediaType.matches("text/html"))
        assertTrue(mediaType.matches("text/plain"))
        assertTrue(mediaType.matches("text/css"))
    }

    @Test
    fun `should match with wildcard type`() {
        val mediaType = MediaType("*", "*")
        assertTrue(mediaType.matches("text/html"))
        assertTrue(mediaType.matches("application/json"))
        assertTrue(mediaType.matches("image/png"))
    }

    @Test
    fun `should not match different types`() {
        val mediaType = MediaType("text", "html")
        assertFalse(mediaType.matches("application/json"))
        assertFalse(mediaType.matches("image/png"))
    }

    @Test
    fun `should not match different subtypes`() {
        val mediaType = MediaType("text", "html")
        assertFalse(mediaType.matches("text/plain"))
        assertFalse(mediaType.matches("text/css"))
    }

    @Test
    fun `should not match blank or empty candidates`() {
        val mediaType = MediaType("text", "html")
        assertFalse(mediaType.matches(""))
        assertFalse(mediaType.matches("   "))
    }

    // ========================================================================
    // MediaType Properties Tests
    // ========================================================================

    @Test
    fun `should construct full type string`() {
        val mediaType = MediaType("text", "html")
        assertEquals("text/html", mediaType.fullType)
    }

    @Test
    fun `should calculate specificity correctly`() {
        assertEquals(2, MediaType("text", "html").specificity)
        assertEquals(1, MediaType("text", "*").specificity)
        assertEquals(0, MediaType("*", "*").specificity)
    }
}

@DisplayName("ContentNegotiator Tests")
class ContentNegotiatorTest {

    // ========================================================================
    // Accept Header Parsing Tests
    // ========================================================================

    @Test
    fun `should parse simple Accept header`() {
        val result = ContentNegotiator.parseAcceptHeader("text/html")
        assertEquals(1, result.size)
        assertEquals("text", result[0].type)
        assertEquals("html", result[0].subtype)
    }

    @Test
    fun `should parse multiple media types`() {
        val result = ContentNegotiator.parseAcceptHeader("text/html, application/json")
        assertEquals(2, result.size)
    }

    @Test
    fun `should parse media types with quality factors`() {
        val result = ContentNegotiator.parseAcceptHeader("text/html;q=0.9, application/json;q=0.8")
        assertEquals(2, result.size)
        assertEquals(0.9, result[0].quality)
        assertEquals(0.8, result[1].quality)
    }

    @Test
    fun `should sort by quality factor descending`() {
        val result = ContentNegotiator.parseAcceptHeader("text/html;q=0.5, application/json;q=0.9")
        assertEquals("application", result[0].type)
        assertEquals("text", result[1].type)
    }

    @Test
    fun `should sort by specificity when quality is equal`() {
        val result = ContentNegotiator.parseAcceptHeader("*/*;q=0.8, text/*;q=0.8, text/html;q=0.8")
        assertEquals(2, result[0].specificity)
        assertEquals(1, result[1].specificity)
        assertEquals(0, result[2].specificity)
    }

    @Test
    fun `should filter out zero quality entries`() {
        val result = ContentNegotiator.parseAcceptHeader("text/html, application/json;q=0")
        assertEquals(1, result.size)
        assertEquals("text/html", result[0].fullType)
    }

    @Test
    fun `should return empty list for null header`() {
        val result = ContentNegotiator.parseAcceptHeader(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should return empty list for blank header`() {
        val result = ContentNegotiator.parseAcceptHeader("   ")
        assertTrue(result.isEmpty())
    }

    // ========================================================================
    // Content Negotiation Tests
    // ========================================================================

    @Test
    fun `should return first type when no Accept header`() {
        val result = ContentNegotiator.negotiate(null, listOf("text/html", "application/json"))
        assertEquals("text/html", result)
    }

    @Test
    fun `should return first type for blank Accept header`() {
        val result = ContentNegotiator.negotiate("", listOf("text/html", "application/json"))
        assertEquals("text/html", result)
    }

    @Test
    fun `should return null for empty types list`() {
        val result = ContentNegotiator.negotiate("text/html", emptyList())
        assertNull(result)
    }

    @Test
    fun `should negotiate exact match`() {
        val result = ContentNegotiator.negotiate(
            "application/json",
            listOf("text/html", "application/json")
        )
        assertEquals("application/json", result)
    }

    @Test
    fun `should negotiate with quality factors`() {
        val result = ContentNegotiator.negotiate(
            "text/html;q=0.5, application/json;q=0.9",
            listOf("text/html", "application/json")
        )
        assertEquals("application/json", result)
    }

    @Test
    fun `should negotiate with wildcards`() {
        val result = ContentNegotiator.negotiate(
            "text/*",
            listOf("application/json", "text/html")
        )
        assertEquals("text/html", result)
    }

    @Test
    fun `should negotiate with accept all wildcard`() {
        val result = ContentNegotiator.negotiate(
            "*/*",
            listOf("text/html", "application/json")
        )
        assertEquals("text/html", result)
    }

    @Test
    fun `should return null when no match found`() {
        val result = ContentNegotiator.negotiate(
            "application/xml",
            listOf("text/html", "application/json")
        )
        assertNull(result)
    }

    @Test
    fun `should normalize types during negotiation`() {
        val result = ContentNegotiator.negotiate(
            "application/json",
            listOf("html", "json")
        )
        assertEquals("json", result)
    }

    @Test
    fun `should handle complex Accept header`() {
        val result = ContentNegotiator.negotiate(
            "text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8",
            listOf("application/json", "text/html")
        )
        assertEquals("text/html", result)
    }

    @Test
    fun `should filter blank types from list`() {
        val result = ContentNegotiator.negotiate(
            "text/html",
            listOf("", "text/html", "   ")
        )
        assertEquals("text/html", result)
    }

    @Test
    fun `should return null when all types are blank`() {
        val result = ContentNegotiator.negotiate(
            "text/html",
            listOf("", "   ")
        )
        assertNull(result)
    }
}

@DisplayName("LanguageTag Tests")
class LanguageTagTest {

    // ========================================================================
    // LanguageTag Parsing Tests
    // ========================================================================

    @Test
    fun `should parse simple language tag`() {
        val (lang, region) = LanguageTag.parse("en")
        assertEquals("en", lang)
        assertNull(region)
    }

    @Test
    fun `should parse language tag with region`() {
        val (lang, region) = LanguageTag.parse("en-US")
        assertEquals("en", lang)
        assertEquals("US", region)
    }

    @Test
    fun `should parse language tag with quality factor`() {
        val (lang, region) = LanguageTag.parse("en-US;q=0.9")
        assertEquals("en", lang)
        assertEquals("US", region)
    }

    @Test
    fun `should handle wildcard language`() {
        val (lang, region) = LanguageTag.parse("*")
        assertEquals("*", lang)
        assertNull(region)
    }

    @Test
    fun `should limit region parsing to first hyphen`() {
        val (lang, region) = LanguageTag.parse("zh-Hans-CN")
        assertEquals("zh", lang)
        assertEquals("Hans-CN", region)
    }

    // ========================================================================
    // LanguageTag Creation Tests
    // ========================================================================

    @Test
    fun `should create LanguageTag from string`() {
        val tag = LanguageTag.from("en-US")
        assertEquals("en", tag.language)
        assertEquals("US", tag.region)
        assertEquals(1.0, tag.quality)
    }

    @Test
    fun `should extract quality factor from language tag`() {
        val tag = LanguageTag.from("en-US;q=0.8")
        assertEquals("en", tag.language)
        assertEquals("US", tag.region)
        assertEquals(0.8, tag.quality)
    }

    @Test
    fun `should default quality to 1_0`() {
        val tag = LanguageTag.from("fr")
        assertEquals(1.0, tag.quality)
    }

    // ========================================================================
    // LanguageTag Matching Tests
    // ========================================================================

    @Test
    fun `should match exact language tags`() {
        val tag = LanguageTag("en", "US")
        assertTrue(tag.matches("en-US"))
    }

    @Test
    fun `should match case insensitively`() {
        val tag = LanguageTag("en", "US")
        assertTrue(tag.matches("EN-us"))
        assertTrue(tag.matches("En-Us"))
    }

    @Test
    fun `should match language without region against language with region`() {
        val tag = LanguageTag("en", null)
        assertTrue(tag.matches("en"))
        assertTrue(tag.matches("en-US"))
        assertTrue(tag.matches("en-GB"))
    }

    @Test
    fun `should not match language with region against different region`() {
        val tag = LanguageTag("zh", "CN")
        assertTrue(tag.matches("zh-CN"))
        assertFalse(tag.matches("zh-TW"))
        assertFalse(tag.matches("zh-HK"))
    }

    @Test
    fun `should match wildcard against any language`() {
        val tag = LanguageTag("*")
        assertTrue(tag.matches("en"))
        assertTrue(tag.matches("fr"))
        assertTrue(tag.matches("zh-CN"))
    }

    @Test
    fun `should not match different languages`() {
        val tag = LanguageTag("en")
        assertFalse(tag.matches("fr"))
        assertFalse(tag.matches("de"))
    }

    @Test
    fun `should not match blank or empty candidates`() {
        val tag = LanguageTag("en")
        assertFalse(tag.matches(""))
        assertFalse(tag.matches("   "))
    }

    // ========================================================================
    // LanguageTag Properties Tests
    // ========================================================================

    @Test
    fun `should construct full tag without region`() {
        val tag = LanguageTag("en")
        assertEquals("en", tag.fullTag)
    }

    @Test
    fun `should construct full tag with region`() {
        val tag = LanguageTag("en", "US")
        assertEquals("en-US", tag.fullTag)
    }

    @Test
    fun `should calculate specificity correctly`() {
        assertEquals(2, LanguageTag("en", "US").specificity)
        assertEquals(1, LanguageTag("en").specificity)
        assertEquals(0, LanguageTag("*").specificity)
    }
}

@DisplayName("LanguageNegotiator Tests")
class LanguageNegotiatorTest {

    // ========================================================================
    // Accept-Language Header Parsing Tests
    // ========================================================================

    @Test
    fun `should parse simple Accept-Language header`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("en")
        assertEquals(1, result.size)
        assertEquals("en", result[0].language)
    }

    @Test
    fun `should parse multiple language tags`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("en-US, fr-FR")
        assertEquals(2, result.size)
    }

    @Test
    fun `should parse language tags with quality factors`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("en;q=0.9, fr;q=0.8")
        assertEquals(2, result.size)
        assertEquals(0.9, result[0].quality)
        assertEquals(0.8, result[1].quality)
    }

    @Test
    fun `should sort by quality factor descending`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("en;q=0.5, fr;q=0.9")
        assertEquals("fr", result[0].language)
        assertEquals("en", result[1].language)
    }

    @Test
    fun `should sort by specificity when quality is equal`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("*;q=0.8, en;q=0.8, en-US;q=0.8")
        assertEquals(2, result[0].specificity)
        assertEquals(1, result[1].specificity)
        assertEquals(0, result[2].specificity)
    }

    @Test
    fun `should filter out zero quality entries`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("en, fr;q=0")
        assertEquals(1, result.size)
        assertEquals("en", result[0].language)
    }

    @Test
    fun `should return empty list for null header`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should return empty list for blank header`() {
        val result = LanguageNegotiator.parseAcceptLanguageHeader("   ")
        assertTrue(result.isEmpty())
    }

    // ========================================================================
    // Language Negotiation Tests
    // ========================================================================

    @Test
    fun `should return first language when no Accept-Language header`() {
        val result = LanguageNegotiator.negotiate(null, listOf("en", "fr"))
        assertEquals("en", result)
    }

    @Test
    fun `should return first language for blank Accept-Language header`() {
        val result = LanguageNegotiator.negotiate("", listOf("en", "fr"))
        assertEquals("en", result)
    }

    @Test
    fun `should return null for empty languages list`() {
        val result = LanguageNegotiator.negotiate("en", emptyList())
        assertNull(result)
    }

    @Test
    fun `should negotiate exact match`() {
        val result = LanguageNegotiator.negotiate(
            "fr",
            listOf("en", "fr")
        )
        assertEquals("fr", result)
    }

    @Test
    fun `should negotiate with quality factors`() {
        val result = LanguageNegotiator.negotiate(
            "en;q=0.5, fr;q=0.9",
            listOf("en", "fr")
        )
        assertEquals("fr", result)
    }

    @Test
    fun `should negotiate language with region`() {
        val result = LanguageNegotiator.negotiate(
            "en-US",
            listOf("en-GB", "en-US")
        )
        assertEquals("en-US", result)
    }

    @Test
    fun `should match broad language preference to specific language`() {
        val result = LanguageNegotiator.negotiate(
            "en",
            listOf("fr", "en-US")
        )
        assertEquals("en-US", result)
    }

    @Test
    fun `should negotiate with wildcard`() {
        val result = LanguageNegotiator.negotiate(
            "*",
            listOf("en", "fr")
        )
        assertEquals("en", result)
    }

    @Test
    fun `should return null when no match found`() {
        val result = LanguageNegotiator.negotiate(
            "de",
            listOf("en", "fr")
        )
        assertNull(result)
    }

    @Test
    fun `should handle complex Accept-Language header`() {
        val result = LanguageNegotiator.negotiate(
            "fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5",
            listOf("en-US", "de-DE")
        )
        assertEquals("en-US", result)
    }

    @Test
    fun `should filter blank languages from list`() {
        val result = LanguageNegotiator.negotiate(
            "en",
            listOf("", "en", "   ")
        )
        assertEquals("en", result)
    }

    @Test
    fun `should return null when all languages are blank`() {
        val result = LanguageNegotiator.negotiate(
            "en",
            listOf("", "   ")
        )
        assertNull(result)
    }

    @Test
    fun `should respect region specificity in negotiation`() {
        val result = LanguageNegotiator.negotiate(
            "zh-CN;q=0.9, zh;q=0.8",
            listOf("zh-TW", "zh-CN")
        )
        assertEquals("zh-CN", result)
    }
}