package io.github.cymoo.colleen.openapi

import io.github.cymoo.colleen.Colleen

// ============================================================================
// Documentation UI HTML Generators
// ============================================================================

/**
 * Generates a self-contained ReDoc HTML page that renders an OpenAPI spec.
 *
 * [specPath] is the URL path to the OpenAPI JSON endpoint (e.g. `/openapi.json`).
 * [jsUrl] may be overridden to point to a self-hosted or alternate CDN bundle.
 *
 * To use ReDoc instead of the default Swagger UI, pass this function to [Colleen.openApi]:
 * ```kotlin
 * app.openApi(uiHtml = ::redocHtml)
 * ```
 */
fun redocHtml(
    specPath: String,
    jsUrl: String = "https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js",
): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>API Documentation</title>
        <style>body { margin: 0; }</style>
    </head>
    <body>
        <redoc spec-url='$specPath'></redoc>
        <script src="$jsUrl"></script>
    </body>
    </html>
""".trimIndent()

/**
 * Generates a self-contained Swagger UI HTML page that renders an OpenAPI spec.
 *
 * [specPath] is the URL path to the OpenAPI JSON endpoint (e.g. `/openapi.json`).
 * [jsUrl] and [cssUrl] may be overridden to point to self-hosted or alternate CDN bundles.
 *
 * This is the **default** UI used by [Colleen.openApi].
 */
fun swaggerUiHtml(
    specPath: String,
    jsUrl: String = "https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js",
    cssUrl: String = "https://unpkg.com/swagger-ui-dist@5/swagger-ui.css",
): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Swagger UI</title>
        <link rel="stylesheet" href="$cssUrl">
        <style>body { margin: 0; }</style>
    </head>
    <body>
        <div id="swagger-ui"></div>
        <script src="$jsUrl"></script>
        <script>
            SwaggerUIBundle({ url: '$specPath', dom_id: '#swagger-ui' })
        </script>
    </body>
    </html>
""".trimIndent()
