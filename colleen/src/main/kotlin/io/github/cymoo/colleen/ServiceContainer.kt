package io.github.cymoo.colleen

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

// ============================================================================
// Lifetime
// ============================================================================

enum class Lifetime {
    Singleton,
    Transient
}

// ============================================================================
// ServiceKey
// ============================================================================

/**
 * Composite key used to uniquely identify a service registration.
 *
 * A service is uniquely identified by its [type] combined with an optional
 * [qualifier]. This allows multiple instances of the same type to coexist
 * in the container under different qualifiers.
 *
 * Qualifiers can be any object — typically a Kotlin `object` singleton
 * (preferred for type safety and IDE refactoring support) or a plain [String]
 * (convenient for simple cases). Both work because [ServiceKey] relies on
 * standard `equals`/`hashCode` semantics.
 *
 * Examples:
 * ```kotlin
 * // No qualifier (default)
 * ServiceKey(Database::class)
 *
 * // String qualifier
 * ServiceKey(DataSource::class, "primary")
 *
 * // Object qualifier (recommended — compile-time safe, IDE refactor-friendly)
 * object Primary
 * ServiceKey(DataSource::class, Primary)
 * ```
 */
data class ServiceKey(
    val type: KClass<*>,
    val qualifier: Any? = null,
)

// ============================================================================
// ServiceContainer
// ============================================================================

/**
 * A lightweight, thread-safe dependency injection container.
 *
 * Supports:
 * - **Singleton** services: created once and reused across all resolutions.
 * - **Transient** services: a fresh instance is created on every resolution.
 * - **Qualifier-based** multi-registration: multiple instances of the same
 *   type can be registered and retrieved using a qualifier (string or object).
 * - **Interface binding**: bind an interface to a concrete implementation.
 * - **Bulk retrieval**: retrieve all registered instances of a given type.
 *
 * All mutating operations are thread-safe via [ConcurrentHashMap].
 *
 * ### Basic usage
 * ```kotlin
 * val container = ServiceContainer()
 *
 * container.registerInstance(MyService())
 * container.registerSingleton { Database.connect(config) }
 * container.registerTransient { HttpClient() }
 *
 * val svc = container.get<MyService>()
 * ```
 *
 * ### Qualifier usage
 * ```kotlin
 * object Primary
 * object Replica
 *
 * container.registerSingleton(Primary) { HikariDataSource(primaryConfig) }
 * container.registerSingleton(Replica) { HikariDataSource(replicaConfig) }
 *
 * val primary = container.get<DataSource>(Primary)
 * val replica  = container.get<DataSource>(Replica)
 * ```
 */
class ServiceContainer {

    @PublishedApi
    internal val instances = ConcurrentHashMap<ServiceKey, Any>()

    @PublishedApi
    internal val factories = ConcurrentHashMap<ServiceKey, () -> Any>()

    // ========================================================================
    // Registration
    // ========================================================================

    /**
     * Registers a pre-built service instance.
     *
     * The instance is stored directly and returned as-is on every retrieval.
     *
     * @param instance  the service instance to register.
     * @param qualifier optional qualifier to distinguish instances of the same type.
     */
    inline fun <reified T : Any> registerInstance(
        instance: T,
        qualifier: Any? = null,
    ): ServiceContainer {
        instances[ServiceKey(T::class, qualifier)] = instance
        return this
    }

    /**
     * Registers a lazily initialized singleton service.
     *
     * The [factory] is invoked at most once; the resulting instance is cached
     * and returned for all subsequent resolutions of the same key.
     *
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @param factory   the factory function used to create the service instance.
     */
    inline fun <reified T : Any> registerSingleton(
        qualifier: Any? = null,
        noinline factory: () -> T,
    ): ServiceContainer {
        val key = ServiceKey(T::class, qualifier)
        factories[key] = { instances.computeIfAbsent(key) { factory() } }
        return this
    }

    /**
     * Registers a transient service.
     *
     * The [factory] is invoked on every retrieval, producing a new instance
     * each time.
     *
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @param factory   the factory function used to create each service instance.
     */
    inline fun <reified T : Any> registerTransient(
        qualifier: Any? = null,
        noinline factory: () -> T,
    ): ServiceContainer {
        factories[ServiceKey(T::class, qualifier)] = factory
        return this
    }

    /**
     * Binds an interface [TInterface] to a concrete implementation [TImpl].
     *
     * Resolving [TInterface] will delegate to the registered [TImpl] service.
     *
     * @param qualifier optional qualifier applied to the interface binding.
     */
    inline fun <reified TInterface : Any, reified TImpl : TInterface> bind(
        qualifier: Any? = null,
    ): ServiceContainer {
        factories[ServiceKey(TInterface::class, qualifier)] = { get<TImpl>() }
        return this
    }

    // ========================================================================
    // Retrieval — reified entry points (delegate to KClass overloads below)
    //
    // Keeping reified and non-inline (KClass) variants separate follows a
    // consistent pattern: reified functions are thin wrappers that capture the
    // type at the call site and forward to non-inline functions that contain
    // the actual logic. This avoids duplicating implementation while still
    // giving callers the ergonomic reified API.
    // ========================================================================

    /**
     * Retrieves a registered service instance.
     *
     * @param qualifier optional qualifier identifying the specific registration.
     * @throws NoSuchElementException if no matching service is registered.
     * @see getOrNull
     */
    inline fun <reified T : Any> get(qualifier: Any? = null): T =
        get(T::class, qualifier)

    /**
     * Retrieves a registered service instance, or `null` if not registered.
     *
     * @param qualifier optional qualifier identifying the specific registration.
     * @see get
     */
    inline fun <reified T : Any> getOrNull(qualifier: Any? = null): T? =
        getOrNull(T::class, qualifier)

    /**
     * Retrieves a registered service instance, or returns [defaultValue] if
     * not registered.
     *
     * @param defaultValue the value to return when no service is found.
     * @param qualifier    optional qualifier identifying the specific registration.
     */
    inline fun <reified T : Any> getOrDefault(defaultValue: T, qualifier: Any? = null): T =
        getOrNull<T>(qualifier) ?: defaultValue

    /**
     * Retrieves a registered service instance, or creates one using [factory]
     * if not registered.
     *
     * @param qualifier optional qualifier identifying the specific registration.
     * @param factory   the fallback factory invoked when no service is found.
     */
    inline fun <reified T : Any> getOrElse(qualifier: Any? = null, factory: () -> T): T =
        getOrNull<T>(qualifier) ?: factory()

    /**
     * Retrieves all registered instances of type [T], regardless of qualifier.
     *
     * Both cached instances and unresolved factories are included; singleton
     * factories are resolved (and cached) eagerly during this call.
     *
     * Useful for plugin-style registrations where multiple implementations of
     * the same interface coexist under different qualifiers.
     *
     * ```kotlin
     * container.registerSingleton<EventHandler>(qualifier = "audit")   { AuditHandler() }
     * container.registerSingleton<EventHandler>(qualifier = "metrics") { MetricsHandler() }
     *
     * val handlers = container.getAll<EventHandler>()
     * handlers.forEach { it.handle(event) }
     * ```
     */
    inline fun <reified T : Any> getAll(): List<T> =
        getAll(T::class)

    // ========================================================================
    // Retrieval — non-inline core (shared by reified overloads and Java callers)
    // ========================================================================

    /**
     * Retrieves a registered service instance by [kClass].
     *
     * Resolution order:
     * 1. Cached instances.
     * 2. Registered factories.
     *
     * @param kClass    the service class.
     * @param qualifier optional qualifier identifying the specific registration.
     * @throws NoSuchElementException if no matching service is registered.
     */
    fun <T : Any> get(kClass: KClass<T>, qualifier: Any? = null): T =
        getOrNull(kClass, qualifier)
            ?: throw NoSuchElementException(
                "Service ${kClass.simpleName}(qualifier=$qualifier) is not registered"
            )

    /**
     * Retrieves a registered service instance by [kClass], or `null` if not
     * registered.
     *
     * Resolution order:
     * 1. Cached instances.
     * 2. Registered factories.
     *
     * This is the single place that contains the actual lookup logic; all other
     * retrieval methods ultimately delegate here.
     *
     * @param kClass    the service class.
     * @param qualifier optional qualifier identifying the specific registration.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrNull(kClass: KClass<T>, qualifier: Any? = null): T? {
        val key = ServiceKey(kClass, qualifier)
        instances[key]?.let { return it as T }
        factories[key]?.let { return it() as T }
        return null
    }

    /**
     * Retrieves all registered instances of [kClass], regardless of qualifier.
     *
     * @param kClass the service class.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getAll(kClass: KClass<T>): List<T> {
        val cachedKeys = instances.keys.filterTo(mutableSetOf()) { it.type == kClass }
        val cached = cachedKeys.mapNotNull { instances[it] as? T }
        val fromFactories = factories
            .filterKeys { it.type == kClass && it !in cachedKeys }
            .values
            .map { it() as T }  // let exceptions propagate naturally
        return cached + fromFactories
    }

    // ========================================================================
    // Introspection
    // ========================================================================

    /**
     * Returns `true` if a service of type [T] with the given [qualifier] is
     * registered.
     *
     * @param qualifier optional qualifier identifying the specific registration.
     */
    inline fun <reified T : Any> has(qualifier: Any? = null): Boolean {
        val key = ServiceKey(T::class, qualifier)
        return instances.containsKey(key) || factories.containsKey(key)
    }

    /**
     * Removes the service of type [T] with the given [qualifier].
     *
     * @param qualifier optional qualifier identifying the specific registration.
     * @return `true` if at least one registration (instance or factory) was removed.
     */
    inline fun <reified T : Any> remove(qualifier: Any? = null): Boolean {
        val key = ServiceKey(T::class, qualifier)
        return (instances.remove(key) != null) or (factories.remove(key) != null)
    }

    /**
     * Removes all registered services.
     */
    fun clear() {
        instances.clear()
        factories.clear()
    }

    /**
     * Returns the set of all registered [ServiceKey]s.
     */
    val registeredServices: Set<ServiceKey>
        get() = instances.keys + factories.keys

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    fun <T : Any> registerInstance(
        clazz: Class<T>,
        instance: T,
        qualifier: Any? = null,
    ): ServiceContainer {
        instances[ServiceKey(clazz.kotlin, qualifier)] = instance
        return this
    }

    fun <T : Any> registerSingleton(
        clazz: Class<T>,
        qualifier: Any? = null,
        factory: () -> T,
    ): ServiceContainer {
        val key = ServiceKey(clazz.kotlin, qualifier)
        factories[key] = { instances.computeIfAbsent(key) { factory() } }
        return this
    }

    fun <T : Any> registerTransient(
        clazz: Class<T>,
        qualifier: Any? = null,
        factory: () -> T,
    ): ServiceContainer {
        factories[ServiceKey(clazz.kotlin, qualifier)] = factory
        return this
    }
}