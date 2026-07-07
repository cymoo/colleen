package io.github.cymoo.colleen.json

/**
 * Marks a [String] as an already-serialized JSON document that must be sent
 * verbatim, without re-encoding.
 *
 * Plain strings passed to `ctx.json(...)` (or returned from handlers) are
 * serialized as JSON string values — `ctx.json("hi")` produces `"hi"` with
 * quotes. Use [RawJson] to pass through pre-rendered JSON instead:
 *
 * ```kotlin
 * ctx.json(RawJson("""{"cached":true}"""))   // sent as-is
 * ctx.json("hello")                          // sent as "hello"
 * ```
 *
 * The wrapped content is NOT validated; the caller is responsible for it
 * being well-formed JSON.
 */
@JvmInline
value class RawJson(val json: String)
