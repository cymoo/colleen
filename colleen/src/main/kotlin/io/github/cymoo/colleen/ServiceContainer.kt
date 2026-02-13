package io.github.cymoo.colleen

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

enum class Lifetime {
    Singleton,
    Transient
}

/**
 * Service container implementation.
 * Supports registering services as singletons or factories.
 */
class ServiceContainer {
    @PublishedApi
    internal val instances = ConcurrentHashMap<KClass<*>, Any>()

    @PublishedApi
    internal val factories = ConcurrentHashMap<KClass<*>, () -> Any>()

    /**
     * Register a service instance.
     */
    inline fun <reified T : Any> registerInstance(instance: T): ServiceContainer {
        instances[T::class] = instance
        return this
    }

    /**
     * Register a transient service instance (creates a new instance on each retrieval).
     */
    inline fun <reified T : Any> registerTransient(noinline factory: () -> T): ServiceContainer {
        factories[T::class] = factory
        return this
    }

    /**
     * Register a lazily initialized service instance.
     */
    inline fun <reified T : Any> registerSingleton(noinline factory: () -> T): ServiceContainer {
        factories[T::class] = {
            instances.getOrPut(T::class) { factory() }
        }
        return this
    }

    /**
     * Bind an interface to its implementation type.
     */
    inline fun <reified TInterface : Any, reified TImpl : TInterface> bind(): ServiceContainer {
        factories[TInterface::class] = { get<TImpl>() }
        return this
    }

    /**
     * Retrieve a service instance.
     * @throws NoSuchElementException if the service is not registered.
     */
    inline fun <reified T : Any> get(): T {
        val kClass = T::class
        return get(kClass)
    }

    fun <T : Any> get(kClass: KClass<T>): T {
        // Check for an existing instance
        @Suppress("UNCHECKED_CAST")
        instances[kClass]?.let { return it as T }

        // Check for a registered factory
        @Suppress("UNCHECKED_CAST")
        factories[kClass]?.let { return it() as T }

        throw NoSuchElementException("Service ${kClass.simpleName} is not registered")
    }

    /**
     * Try to retrieve a service; return null if not registered.
     */
    inline fun <reified T : Any> getOrNull(): T? = runCatching { get<T>() }.getOrNull()

    fun <T : Any> getOrNull(kClass: KClass<T>): T? = runCatching { get(kClass) }.getOrNull()

    /**
     * Retrieve a service or return the provided default value.
     */
    inline fun <reified T : Any> getOrDefault(defaultValue: T): T = getOrNull() ?: defaultValue

    /**
     * Retrieve a service or create one using the provided fallback factory.
     */
    inline fun <reified T : Any> getOrElse(factory: () -> T): T = getOrNull() ?: factory()

    /**
     * Check whether the service is registered.
     */
    inline fun <reified T : Any> has(): Boolean {
        val kClass = T::class
        return instances.containsKey(kClass) || factories.containsKey(kClass)
    }

    /**
     * Remove a service.
     * @return true if the service was removed.
     */
    inline fun <reified T : Any> remove(): Boolean {
        val kClass = T::class
        return (instances.remove(kClass) != null) or (factories.remove(kClass) != null)
    }

    /**
     * Clear all registered services.
     */
    fun clear() {
        instances.clear()
        factories.clear()
    }

    /**
     * Get all registered service types.
     */
    val registeredServices: Set<KClass<*>>
        get() = instances.keys + factories.keys

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    fun <T : Any> registerInstance(clazz: Class<T>, instance: T): ServiceContainer {
        instances[clazz.kotlin] = instance
        return this
    }

    fun <T : Any> registerTransient(clazz: Class<T>, factory: () -> T): ServiceContainer {
        factories[clazz.kotlin] = factory
        return this
    }

    fun <T : Any> registerSingleton(clazz: Class<T>, factory: () -> T): ServiceContainer {
        factories[clazz.kotlin] = {
            instances.getOrPut(clazz.kotlin) { factory() }
        }
        return this
    }
}
