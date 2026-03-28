package io.github.cymoo.colleen.server.undertow

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.util.http.UrlPath
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.form.FormData
import io.undertow.server.handlers.form.FormParserFactory
import io.undertow.server.handlers.form.MultiPartParserDefinition
import io.undertow.util.HeaderValues
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts Undertow's HttpServerExchange into Colleen's Request model.
 *
 * Responsibilities:
 * - Extract headers, query string, and metadata
 * - Provide raw input stream access
 * - Lazily parse multipart/form-data when requested
 */
internal object UndertowRequestAdapter {

    /**
     * Cached FormParserFactory instances keyed by relevant config values.
     *
     * Since ServerConfig is typically stable for the server lifetime,
     * this effectively caches a single factory instance and avoids
     * recreating it on every multipart request.
     */
    private data class FormParserCacheKey(val maxFileSize: Long, val fileSizeThreshold: Long)

    private val formParserFactoryCache = ConcurrentHashMap<FormParserCacheKey, FormParserFactory>()

    /**
     * Returns a cached FormParserFactory configured for multipart handling.
     *
     * Applies:
     * - Maximum file size limits
     * - In-memory / disk threshold
     * - UTF-8 as default charset
     */
    private fun getFormParserFactory(config: ServerConfig): FormParserFactory {
        val key = FormParserCacheKey(config.maxFileSize, config.fileSizeThreshold)
        return formParserFactoryCache.computeIfAbsent(key) {
            val multipart = MultiPartParserDefinition().apply {
                maxIndividualFileSize = config.maxFileSize
                setFileSizeThreshold(config.fileSizeThreshold)
            }

            FormParserFactory.Builder()
                .addParser(multipart)
                .withDefaultCharset(StandardCharsets.UTF_8.name())
                .build()
        }
    }

    /**
     * Converts an HttpServerExchange into a Colleen Request.
     *
     * Multipart bodies are parsed lazily via a supplier to avoid
     * prematurely consuming the request stream.
     */
    fun adapt(exchange: HttpServerExchange, config: ServerConfig): Request {
        val headers = extractHeaders(exchange)
        val contentType = headers["content-type"]

        val isMultipart = contentType?.startsWith("multipart/form-data") == true

        val request = Request(
            method = exchange.requestMethod.toString(),
            path = UrlPath.normalize(exchange.requestPath),
            queryString = exchange.queryString ?: "",
            headers = headers,
            stream = exchange.inputStream,
            serverInfo = Request.ServerInfo(
                remoteAddr = exchange.sourceAddress.address.hostAddress,
                remoteHost = exchange.sourceAddress.hostName
                    ?: exchange.sourceAddress.address.hostAddress,
                remotePort = exchange.sourceAddress.port,
                scheme = exchange.requestScheme,
                serverName = exchange.hostName ?: "localhost",
                serverPort = exchange.hostPort,
                isSecure = exchange.requestScheme == "https",
                protocol = exchange.protocol.toString()
            ),
            multipartSupplier = if (isMultipart) {
                { parseMultipart(exchange, config) }
            } else {
                { emptyList() }
            }
        )

        return request
    }

    /**
     * Parses multipart/form-data into Colleen Part abstractions.
     *
     * Notes:
     * - Temporary files are managed and deleted by Undertow
     * - The request body MUST NOT be consumed before parsing
     */
    private fun parseMultipart(exchange: HttpServerExchange, config: ServerConfig): List<Part> {
        val parser = getFormParserFactory(config).createParser(exchange) ?: error("Request is not multipart")
        val formData: FormData

        try {
            formData = parser.parseBlocking()
        } catch (err: IOException) {
            if (err.message?.contains("UT000036") == true) {
                throw IllegalStateException(
                    "Failed to parse multipart body. " +
                            "The request body may have been consumed earlier " +
                            "(e.g. reading raw InputStream before parsing multipart).",
                    err
                )
            }
            throw err
        }

        val parts = mutableListOf<Part>()

        for (fieldName in formData) {
            for (formValue in formData.get(fieldName)) {
                val part = if (formValue.isFileItem) {
                    // NOTE: undertow will delete temporary files after exchange completed
                    FilePart(
                        name = fieldName,
                        filename = formValue.fileName ?: "unknown",
                        contentType = formValue.headers.getFirst(io.undertow.util.Headers.CONTENT_TYPE),
                        size = formValue.fileItem.fileSize,
                        inputStream = formValue.fileItem.inputStream,
                    )
                } else {
                    FormField(fieldName, formValue.value)
                }
                parts.add(part)
            }
        }

        return parts
    }

    /**
     * Extracts all request headers from Undertow exchange.
     *
     * Header names are preserved as-is and may appear multiple times.
     */
    private fun extractHeaders(exchange: HttpServerExchange): Headers {
        val headers = Headers()
        for (headerValues: HeaderValues in exchange.requestHeaders) {
            val name = headerValues.headerName.toString()
            headerValues.forEach { headers.add(name, it) }
        }
        return headers
    }
}
