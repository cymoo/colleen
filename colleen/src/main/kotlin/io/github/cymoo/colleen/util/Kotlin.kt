package io.github.cymoo.colleen.util

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

fun isKotlinInstance(obj: Any): Boolean {
    if (obj is Class<*> || obj is KClass<*>) {
        throw IllegalArgumentException("Expected an object, but got a class: $obj")
    }
    val clazz = obj::class.java
    return clazz.getAnnotation(Metadata::class.java) != null
}

/**
 * Loom-friendly [Lazy] implementation
 */
internal class ReentrantLazy<T>(private var initializer: (() -> T)?) : Lazy<T> {

    private companion object {
        private object Uninitialized
    }

    @Volatile
    private var _value: Any? = Uninitialized

    private val lock = ReentrantLock()

    override val value: T
        get() {
            // Fast path: already initialized
            val v = _value
            if (v !== Uninitialized) {
                @Suppress("UNCHECKED_CAST")
                return v as T
            }

            return lock.withLock {
                // Double-check after acquiring lock
                val v2 = _value
                if (v2 !== Uninitialized) {
                    @Suppress("UNCHECKED_CAST")
                    return@withLock v2 as T
                }

                // Initialize
                val newValue = initializer!!.invoke()
                _value = newValue
                initializer = null

                newValue
            }
        }

    override fun isInitialized(): Boolean =
        _value !== Uninitialized
}


/**
 * Loom-friendly [lazy] implementation
 *
 * By default, [lazy] uses [SynchronizedLazyImpl] which is not Loom-friendly.
 * We instead use LazyThreadSafetyMode = NONE by default, and use [ReentrantLazy] when
 * [LazyThreadSafetyMode.SYNCHRONIZED] is requested.
 */
internal fun <T> lazyLoom(
    threadSafetyMode: LazyThreadSafetyMode = LazyThreadSafetyMode.NONE,
    initializer: () -> T
): Lazy<T> = when (threadSafetyMode) {
    LazyThreadSafetyMode.SYNCHRONIZED -> ReentrantLazy(initializer)
    else -> lazy(threadSafetyMode, initializer)
}
