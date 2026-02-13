package io.github.cymoo.colleen.util

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Type reference for capturing generic type information at runtime,
 * mainly for JSON deserialization.
 *
 * Usage examples:
 * ```java
 * // Anonymous class syntax
 * new TypeRef<List<User>>() {}
 * new TypeRef<Map<String, User>>() {}
 *
 * // Factory methods for common cases
 * TypeRef.listOf(User.class)
 * TypeRef.mapOf(String.class, User.class)
 * ```
 */
abstract class TypeRef<T> {

    open val type: Type by lazy {
        val superClass = javaClass.genericSuperclass
        require(superClass is ParameterizedType) {
            "TypeRef must be instantiated with type parameter, e.g., new TypeRef<List<String>>() {}"
        }
        superClass.actualTypeArguments[0]
    }

    companion object {

        @JvmStatic
        internal fun <T> of(type: Type): TypeRef<T> = object : TypeRef<T>() {
            override val type: Type = type
        }

        @JvmStatic
        fun <T> of(clazz: Class<T>): TypeRef<T> = of(clazz as Type)

        // Factory methods for common collection types

        @JvmStatic
        fun <T> listOf(elementClass: Class<T>): TypeRef<List<T>> =
            of(ParameterizedTypeImpl(List::class.java, elementClass))

        @JvmStatic
        fun <T> setOf(elementClass: Class<T>): TypeRef<Set<T>> =
            of(ParameterizedTypeImpl(Set::class.java, elementClass))

        @JvmStatic
        fun <K, V> mapOf(keyClass: Class<K>, valueClass: Class<V>): TypeRef<Map<K, V>> =
            of(ParameterizedTypeImpl(Map::class.java, keyClass, valueClass))
    }
}

/**
 * Implementation of ParameterizedType for runtime type construction.
 */
private class ParameterizedTypeImpl(
    private val rawType: Type,
    private vararg val typeArguments: Type
) : ParameterizedType {

    override fun getRawType(): Type = rawType

    override fun getActualTypeArguments(): Array<out Type> = typeArguments

    override fun getOwnerType(): Type? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParameterizedType) return false

        return rawType == other.rawType &&
                typeArguments.contentEquals(other.actualTypeArguments)
    }

    override fun hashCode(): Int {
        return rawType.hashCode() xor typeArguments.contentHashCode()
    }

    override fun toString(): String {
        val args = typeArguments.joinToString(", ") { it.typeName }
        return "${rawType.typeName}<$args>"
    }
}
