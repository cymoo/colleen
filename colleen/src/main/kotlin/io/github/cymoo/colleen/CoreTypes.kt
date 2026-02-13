package io.github.cymoo.colleen

// ========================================================================
// Colleen Core Interfaces
// ========================================================================

/**
 * Route handler.
 *
 * Returning a value will be mapped to a Response by Colleen.
 */
@FunctionalInterface
fun interface Handler {
    operator fun invoke(ctx: Context): Any?
}

/**
 * Continuation callback used by middleware.
 *
 * Calling next() transfers control to the next middleware or handler.
 */
@FunctionalInterface
fun interface Next {
    operator fun invoke()
}

/**
 * Middleware function.
 *
 * Middleware may run logic before and/or after next() is invoked.
 */
@FunctionalInterface
fun interface Middleware {
    operator fun invoke(ctx: Context, next: Next)
}

/**
 * Error handler for a specific exception type.
 *
 * Used to handle uncaught exceptions during request processing.
 */
@FunctionalInterface
fun interface ErrorHandler<T> {
    operator fun invoke(throwable: T, ctx: Context)
}

// ========================================================================
// HTTP Method Annotations（Controller-style routing）
// ========================================================================

/** Marks a method as a GET route. */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Get(val value: String = "/")

/** Marks a method as a POST route. */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Post(val value: String = "/")

/** Marks a method as a PUT route. */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Put(val value: String = "/")

/** Marks a method as a DELETE route. */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Delete(val value: String = "/")

/** Marks a method as a PATCH route. */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Patch(val value: String = "/")

/**
 * Marks a class as a controller.
 *
 * The value represents the base path for all routes in this controller.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Controller(val value: String = "/")

/**
 * Attaches middleware to a handler method.
 *
 * Executed before the route handler.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Use

// ========================================================================
// Java Compatibility
// ========================================================================

/**
 * Java-friendly handler that does not return a value.
 *
 * Equivalent to a Kotlin Handler returning Unit.
 */
@FunctionalInterface
interface VoidHandler {
    fun invoke(ctx: Context)
}
