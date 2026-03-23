@file:JvmName("lambda")

package io.github.cymoo.colleen

import java.io.Serializable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves a Java lambda into a [Handler].
 *
 * This method introspects the lambda using JVM SerializedLambda mechanics
 * to locate the actual implementation method and its captured instance
 * (if any).
 *
 * It supports:
 * - Static method references (no captured instance)
 * - Instance method references (with captured instance)
 *
 * @throws IllegalArgumentException if the lambda cannot be resolved
 *         or the captured instance type does not match the declaring class
 */
private fun resolveLambda(fn: Any): Handler {
    // Locate the actual implementation method behind the lambda
    val method = findImplMethod(fn) ?: throw IllegalArgumentException("Cannot find method for lambda")

    // Extract the captured instance (for non-static lambdas / method references)
    val instance = getCapturedInstance(fn)

    // Validate instance type to avoid illegal reflective invocation
    if (instance != null && !method.declaringClass.isInstance(instance)) {
        throw IllegalArgumentException(
            "Instance type mismatch: expected ${method.declaringClass.name}, " +
                    "got ${instance.javaClass.name}"
        )
    }

    // Create a Handler bound to either:
    // - a static method (instance = null)
    // - an instance method (instance != null)
    return if (Modifier.isStatic(method.modifiers)) {
        cx(method, null)
    } else {
        cx(method, instance)
    }
}

/**
 * Extracts the captured instance from a lambda, if present.
 *
 * For Kotlin lambdas or method references that capture an instance
 * (e.g. `controller::handle`), the compiler generates a synthetic
 * field named `arg$X` to hold the captured object.
 *
 * This method:
 * - Searches for the first synthetic field starting with "arg$"
 * - Makes it accessible
 * - Returns its value
 *
 * @return the captured instance, or null if the lambda is static
 */
private fun getCapturedInstance(fn: Any): Any? {
    return fn.javaClass.declaredFields
        // Why arg$: JVM / Kotlin compiler convention for captured variables in lambdas.
        .firstOrNull { it.name.startsWith("arg$") }
        ?.apply { isAccessible = true }
        ?.get(fn)
}

/**
 * Finds the actual implementation method of a lambda using SerializedLambda.
 *
 * JVM lambdas provide a hidden `writeReplace()` method that returns a
 * `java.lang.invoke.SerializedLambda`, which contains:
 * - The internal class name of the implementation
 * - The method name backing the lambda
 *
 * This method:
 * 1. Calls `writeReplace()` reflectively
 * 2. Extracts implementation class and method name
 * 3. Loads the target class
 * 4. Locates the declared method
 *
 * @return the resolved Method, or null if resolution fails
 */
private fun findImplMethod(fn: Any): Method? {
    try {
        // Obtain SerializedLambda via writeReplace()
        val writeReplace = fn.javaClass.getDeclaredMethod("writeReplace")
        writeReplace.isAccessible = true
        val serializedLambda = writeReplace.invoke(fn)

        // Extract implementation metadata
        val getImplClass = serializedLambda.javaClass.getDeclaredMethod("getImplClass")
        val getImplMethodName = serializedLambda.javaClass.getDeclaredMethod("getImplMethodName")

        val implClass = (getImplClass.invoke(serializedLambda) as String).replace('/', '.')
        val implMethodName = getImplMethodName.invoke(serializedLambda) as String

        // Load the implementation class and find the method
        val targetClass = Class.forName(implClass)
        return targetClass.declaredMethods
            .firstOrNull { it.name == implMethodName }
            ?.apply { isAccessible = true }

    } catch (e: Exception) {
        // Resolution failures are non-fatal and reported as null
        e.printStackTrace()
        return null
    }
}

// Fn0 ... Fn21 and Do0 ... Do21
fun interface Fn0<R> : Serializable {
    fun invoke(): R
}

fun interface Do0 : Serializable {
    fun invoke()
}

fun interface Fn1<P1, R> : Serializable {
    fun invoke(p1: P1): R
}

fun interface Do1<P1> : Serializable {
    fun invoke(p1: P1)
}

fun interface Fn2<P1, P2, R> : Serializable {
    fun invoke(p1: P1, p2: P2): R
}

fun interface Do2<P1, P2> : Serializable {
    fun invoke(p1: P1, p2: P2)
}

fun interface Fn3<P1, P2, P3, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3): R
}

fun interface Do3<P1, P2, P3> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3)
}

fun interface Fn4<P1, P2, P3, P4, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4): R
}

fun interface Do4<P1, P2, P3, P4> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4)
}

fun interface Fn5<P1, P2, P3, P4, P5, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5): R
}

fun interface Do5<P1, P2, P3, P4, P5> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5)
}

fun interface Fn6<P1, P2, P3, P4, P5, P6, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6): R
}

fun interface Do6<P1, P2, P3, P4, P5, P6> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6)
}

fun interface Fn7<P1, P2, P3, P4, P5, P6, P7, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7): R
}

fun interface Do7<P1, P2, P3, P4, P5, P6, P7> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7)
}

fun interface Fn8<P1, P2, P3, P4, P5, P6, P7, P8, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8): R
}

fun interface Do8<P1, P2, P3, P4, P5, P6, P7, P8> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8)
}

fun interface Fn9<P1, P2, P3, P4, P5, P6, P7, P8, P9, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9): R
}

fun interface Do9<P1, P2, P3, P4, P5, P6, P7, P8, P9> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9)
}

fun interface Fn10<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9, p10: P10): R
}

fun interface Do10<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9, p10: P10)
}

fun interface Fn11<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9, p10: P10, p11: P11): R
}

fun interface Do11<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9, p10: P10, p11: P11)
}

fun interface Fn12<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, R> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9, p10: P10, p11: P11, p12: P12): R
}

fun interface Do12<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12> : Serializable {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8, p9: P9, p10: P10, p11: P11, p12: P12)
}

fun interface Fn13<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, R> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13
    ): R
}

fun interface Do13<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13
    )
}

fun interface Fn14<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, R> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14
    ): R
}

fun interface Do14<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14
    )
}

fun interface Fn15<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, R> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15
    ): R
}

fun interface Do15<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15
    )
}

fun interface Fn16<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, R> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16
    ): R
}

fun interface Do16<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16
    )
}

fun interface Fn17<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, R> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17
    ): R
}

fun interface Do17<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17
    )
}

fun interface Fn18<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, R> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17, p18: P18
    ): R
}

fun interface Do18<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18> : Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17, p18: P18
    )
}

fun interface Fn19<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, R> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17, p18: P18, p19: P19
    ): R
}

fun interface Do19<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17, p18: P18, p19: P19
    )
}

fun interface Fn20<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, R> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17, p18: P18, p19: P19, p20: P20
    ): R
}

fun interface Do20<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15, p16: P16, p17: P17, p18: P18, p19: P19, p20: P20
    )
}

fun interface Fn21<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, R> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15,
        p16: P16, p17: P17, p18: P18, p19: P19, p20: P20, p21: P21
    ): R
}

fun interface Do21<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15,
        p16: P16, p17: P17, p18: P18, p19: P19, p20: P20, p21: P21
    )
}

fun interface Fn22<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22, R> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15,
        p16: P16, p17: P17, p18: P18, p19: P19, p20: P20, p21: P21, p22: P22
    ): R
}

fun interface Do22<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22> :
    Serializable {
    fun invoke(
        p1: P1, p2: P2, p3: P3, p4: P4, p5: P5,
        p6: P6, p7: P7, p8: P8, p9: P9, p10: P10,
        p11: P11, p12: P12, p13: P13, p14: P14, p15: P15,
        p16: P16, p17: P17, p18: P18, p19: P19, p20: P20, p21: P21, p22: P22
    )
}

// ch(...) overloads — exactly in the form you requested
fun <R> ch(fn: Fn0<R>) = resolveLambda(fn)
fun ch(fn: Do0) = resolveLambda(fn)

fun <P1, R> ch(fn: Fn1<P1, R>) = resolveLambda(fn)
fun <P1> ch(fn: Do1<P1>) = resolveLambda(fn)

fun <P1, P2, R> ch(fn: Fn2<P1, P2, R>) = resolveLambda(fn)
fun <P1, P2> ch(fn: Do2<P1, P2>) = resolveLambda(fn)

fun <P1, P2, P3, R> ch(fn: Fn3<P1, P2, P3, R>) = resolveLambda(fn)
fun <P1, P2, P3> ch(fn: Do3<P1, P2, P3>) = resolveLambda(fn)

fun <P1, P2, P3, P4, R> ch(fn: Fn4<P1, P2, P3, P4, R>) = resolveLambda(fn)
fun <P1, P2, P3, P4> ch(fn: Do4<P1, P2, P3, P4>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, R> ch(fn: Fn5<P1, P2, P3, P4, P5, R>) = resolveLambda(fn)
fun <P1, P2, P3, P4, P5> ch(fn: Do5<P1, P2, P3, P4, P5>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, R> ch(fn: Fn6<P1, P2, P3, P4, P5, P6, R>) = resolveLambda(fn)
fun <P1, P2, P3, P4, P5, P6> ch(fn: Do6<P1, P2, P3, P4, P5, P6>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, R> ch(fn: Fn7<P1, P2, P3, P4, P5, P6, P7, R>) = resolveLambda(fn)
fun <P1, P2, P3, P4, P5, P6, P7> ch(fn: Do7<P1, P2, P3, P4, P5, P6, P7>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, R> ch(fn: Fn8<P1, P2, P3, P4, P5, P6, P7, P8, R>) = resolveLambda(fn)
fun <P1, P2, P3, P4, P5, P6, P7, P8> ch(fn: Do8<P1, P2, P3, P4, P5, P6, P7, P8>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, R> ch(fn: Fn9<P1, P2, P3, P4, P5, P6, P7, P8, P9, R>) = resolveLambda(fn)
fun <P1, P2, P3, P4, P5, P6, P7, P8, P9> ch(fn: Do9<P1, P2, P3, P4, P5, P6, P7, P8, P9>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, R> ch(fn: Fn10<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10> ch(fn: Do10<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10>) = resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, R> ch(fn: Fn11<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11> ch(fn: Do11<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, R> ch(fn: Fn12<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12> ch(fn: Do12<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, R> ch(fn: Fn13<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13> ch(fn: Do13<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, R> ch(fn: Fn14<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14> ch(fn: Do14<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, R> ch(fn: Fn15<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15> ch(fn: Do15<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, R> ch(fn: Fn16<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16> ch(fn: Do16<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, R> ch(fn: Fn17<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17> ch(fn: Do17<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, R> ch(fn: Fn18<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18> ch(fn: Do18<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, R> ch(fn: Fn19<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19> ch(fn: Do19<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, R> ch(fn: Fn20<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20> ch(fn: Do20<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, R> ch(fn: Fn21<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21> ch(fn: Do21<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22, R> ch(fn: Fn22<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22, R>) =
    resolveLambda(fn)

fun <P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22> ch(fn: Do22<P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15, P16, P17, P18, P19, P20, P21, P22>) =
    resolveLambda(fn)
