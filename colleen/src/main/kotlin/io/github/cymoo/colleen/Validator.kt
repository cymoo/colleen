package io.github.cymoo.colleen

import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.function.Consumer

// ========================================================================
// Validation Result
// ========================================================================

sealed class ValidationResult {
    data class Success(val message: String = "Validation passed") : ValidationResult()
    data class Failure(val errorMap: Map<String, List<String>>) : ValidationResult()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    fun errors(): Map<String, List<String>> = when (this) {
        is Success -> emptyMap()
        is Failure -> errorMap
    }
}

// ========================================================================
// Base Validator
// ========================================================================

sealed class Validator<T, Self : Validator<T, Self>>(protected val value: T?) {
    protected val errors = mutableListOf<String>()
    protected var lastError: Boolean = false

    protected fun addError(message: String) {
        errors.add(message)
        lastError = true
    }

    /**
     * Core validation helper: executes validation block only if value is non-null.
     * Automatically resets lastError before validation.
     *
     * @param block validation logic to execute on non-null value
     */
    protected inline fun validateIfPresent(block: (T) -> Unit) {
        lastError = false
        if (value != null) {
            block(value)
        }
    }

    /**
     * Marks this field as required (non-null).
     * If value is null, adds an error.
     *
     * @param message custom error message (default: "is required")
     */
    protected fun requireNonNull(message: String = "is required") {
        lastError = false
        if (value == null) {
            addError(message)
        }
    }

    /**
     * Overrides the last error message.
     * Only takes effect if the previous validation failed.
     *
     * @param message the new error message
     */
    @Suppress("UNCHECKED_CAST")
    fun message(message: String): Self {
        if (lastError && errors.isNotEmpty()) {
            errors[errors.size - 1] = message
        }
        return this as Self
    }

    /**
     * Overrides the last error message using a function.
     * Only takes effect if the previous validation failed.
     * Note: fn receives nullable value for safety.
     *
     * @param fn function to generate error message from current value
     */
    @Suppress("UNCHECKED_CAST")
    fun message(fn: (T?) -> String): Self {
        if (lastError && errors.isNotEmpty()) {
            errors[errors.size - 1] = fn(value)
        }
        return this as Self
    }

    internal fun getErrors(): List<String> = errors
}

// ========================================================================
// String Validator
// ========================================================================

class StringValidator(value: String?) : Validator<String, StringValidator>(value) {

    companion object {
        // Cached regex patterns for performance
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }

    /**
     * Marks this field as required (non-null).
     */
    fun required(message: String = "is required"): StringValidator {
        requireNonNull(message)
        return this
    }

    fun notBlank(): StringValidator {
        validateIfPresent {
            if (it.isBlank()) {
                addError("cannot be blank")
            }
        }
        return this
    }

    fun minSize(min: Int): StringValidator {
        validateIfPresent {
            if (it.length < min) {
                addError("minimum length is $min")
            }
        }
        return this
    }

    fun maxSize(max: Int): StringValidator {
        validateIfPresent {
            if (it.length > max) {
                addError("maximum length is $max")
            }
        }
        return this
    }

    fun size(exact: Int): StringValidator {
        validateIfPresent {
            if (it.length != exact) {
                addError("length must be exactly $exact")
            }
        }
        return this
    }

    fun matches(regex: Regex): StringValidator {
        validateIfPresent {
            if (!regex.matches(it)) {
                addError("format is invalid")
            }
        }
        return this
    }

    fun email(): StringValidator {
        validateIfPresent {
            if (!EMAIL_REGEX.matches(it)) {
                addError("must be a valid email address")
            }
        }
        return this
    }

    fun url(): StringValidator {
        validateIfPresent {
            try {
                val uri = URI(it)
                // Validate scheme exists and is http or https for web contexts
                if (uri.scheme == null || uri.scheme !in listOf("http", "https")) {
                    addError("must be a valid URL")
                    return@validateIfPresent
                }
                uri.toURL()
            } catch (_: Exception) {
                addError("must be a valid URL")
            }
        }
        return this
    }

    fun date(): StringValidator {
        validateIfPresent {
            try {
                LocalDate.parse(it)
            } catch (_: Exception) {
                addError("must be a valid date (yyyy-MM-dd)")
            }
        }
        return this
    }

    fun datetime(): StringValidator {
        validateIfPresent {
            try {
                LocalDateTime.parse(it)
            } catch (_: Exception) {
                addError("must be a valid datetime (yyyy-MM-ddTHH:mm:ss)")
            }
        }
        return this
    }

    fun notIn(vararg forbidden: String): StringValidator {
        validateIfPresent {
            if (it in forbidden) {
                addError("cannot be one of: ${forbidden.joinToString(", ")}")
            }
        }
        return this
    }

    fun alphanumeric(): StringValidator {
        validateIfPresent {
            if (!it.all { c -> c.isLetterOrDigit() || c == '_' }) {
                addError("must contain only letters and numbers")
            }
        }
        return this
    }

    fun numeric(): StringValidator {
        validateIfPresent {
            if (!it.all { c -> c.isDigit() }) {
                addError("must contain only digits")
            }
        }
        return this
    }

    fun `in`(vararg allowed: String): StringValidator {
        validateIfPresent {
            if (it !in allowed) {
                addError("must be one of: ${allowed.joinToString(", ")}")
            }
        }
        return this
    }

    fun custom(predicate: (String) -> Boolean, message: String): StringValidator {
        validateIfPresent {
            if (!predicate(it)) {
                addError(message)
            }
        }
        return this
    }
}

// ========================================================================
// Number Validator
// ========================================================================

class NumberValidator<T : Number>(value: T?) : Validator<T, NumberValidator<T>>(value) {

    /**
     * Marks this field as required (non-null).
     */
    fun required(message: String = "is required"): NumberValidator<T> {
        requireNonNull(message)
        return this
    }

    fun between(min: Number, max: Number): NumberValidator<T> {
        validateIfPresent {
            val d = it.toDouble()
            if (d < min.toDouble() || d > max.toDouble()) {
                addError("must be between $min and $max")
            }
        }
        return this
    }

    fun min(min: Number): NumberValidator<T> {
        validateIfPresent {
            if (it.toDouble() < min.toDouble()) {
                addError("minimum value is $min")
            }
        }
        return this
    }

    fun max(max: Number): NumberValidator<T> {
        validateIfPresent {
            if (it.toDouble() > max.toDouble()) {
                addError("maximum value is $max")
            }
        }
        return this
    }

    fun positive(): NumberValidator<T> {
        validateIfPresent {
            if (it.toDouble() <= 0) {
                addError("must be positive")
            }
        }
        return this
    }

    fun negative(): NumberValidator<T> {
        validateIfPresent {
            if (it.toDouble() >= 0) {
                addError("must be negative")
            }
        }
        return this
    }

    fun `in`(vararg allowed: Number): NumberValidator<T> {
        validateIfPresent {
            val d = it.toDouble()
            if (allowed.none { a -> a.toDouble() == d }) {
                addError("must be one of: ${allowed.joinToString(", ")}")
            }
        }
        return this
    }

    fun notIn(vararg forbidden: Number): NumberValidator<T> {
        validateIfPresent {
            val d = it.toDouble()
            if (forbidden.any { f -> f.toDouble() == d }) {
                addError("cannot be one of: ${forbidden.joinToString(", ")}")
            }
        }
        return this
    }

    fun custom(predicate: (T) -> Boolean, message: String): NumberValidator<T> {
        validateIfPresent {
            if (!predicate(it)) {
                addError(message)
            }
        }
        return this
    }
}

// ========================================================================
// Collection Validator
// ========================================================================

class CollectionValidator<T>(value: Collection<T>?) : Validator<Collection<T>, CollectionValidator<T>>(value) {

    /**
     * Marks this field as required (non-null).
     */
    fun required(message: String = "is required"): CollectionValidator<T> {
        requireNonNull(message)
        return this
    }

    fun notEmpty(): CollectionValidator<T> {
        validateIfPresent {
            if (it.isEmpty()) {
                addError("cannot be empty")
            }
        }
        return this
    }

    fun minSize(min: Int): CollectionValidator<T> {
        validateIfPresent {
            if (it.size < min) {
                addError("minimum size is $min")
            }
        }
        return this
    }

    fun maxSize(max: Int): CollectionValidator<T> {
        validateIfPresent {
            if (it.size > max) {
                addError("maximum size is $max")
            }
        }
        return this
    }

    fun noDuplicates(): CollectionValidator<T> {
        validateIfPresent {
            if (it.size != it.toSet().size) {
                addError("cannot have duplicate elements")
            }
        }
        return this
    }

    fun `in`(vararg allowed: T): CollectionValidator<T> {
        validateIfPresent {
            val notAllowed = it.filter { item -> item !in allowed }
            if (notAllowed.isNotEmpty()) {
                addError("contains invalid values")
            }
        }
        return this
    }

    fun notIn(vararg forbidden: T): CollectionValidator<T> {
        validateIfPresent {
            val notAllowed = it.filter { item -> item in forbidden }
            if (notAllowed.isNotEmpty()) {
                addError("contains forbidden values")
            }
        }
        return this
    }

    fun custom(predicate: (Collection<T>) -> Boolean, message: String): CollectionValidator<T> {
        validateIfPresent {
            if (!predicate(it)) {
                addError(message)
            }
        }
        return this
    }
}

// ========================================================================
// Datetime Validator
// ========================================================================

class DateTimeValidator(value: LocalDateTime?) : Validator<LocalDateTime, DateTimeValidator>(value) {

    /**
     * Marks this field as required (non-null).
     */
    fun required(message: String = "is required"): DateTimeValidator {
        requireNonNull(message)
        return this
    }

    fun isBefore(other: LocalDateTime): DateTimeValidator {
        validateIfPresent {
            if (!it.isBefore(other)) {
                addError("must be before $other")
            }
        }
        return this
    }

    fun isAfter(other: LocalDateTime): DateTimeValidator {
        validateIfPresent {
            if (!it.isAfter(other)) {
                addError("must be after $other")
            }
        }
        return this
    }

    fun between(start: LocalDateTime, end: LocalDateTime): DateTimeValidator {
        validateIfPresent {
            if (it.isBefore(start) || it.isAfter(end)) {
                addError("must be between $start and $end")
            }
        }
        return this
    }

    fun inFuture(): DateTimeValidator {
        validateIfPresent {
            if (!it.isAfter(LocalDateTime.now())) {
                addError("must be in the future")
            }
        }
        return this
    }

    fun inPast(): DateTimeValidator {
        validateIfPresent {
            if (!it.isBefore(LocalDateTime.now())) {
                addError("must be in the past")
            }
        }
        return this
    }

    fun custom(predicate: (LocalDateTime) -> Boolean, message: String): DateTimeValidator {
        validateIfPresent {
            if (!predicate(it)) {
                addError(message)
            }
        }
        return this
    }
}

// ========================================================================
// Validation Context
// ========================================================================

class ValidationContext(private val groupName: String = "") {
    private val errors = mutableMapOf<String, MutableList<String>>()
    private val validators = mutableListOf<Pair<String, Validator<*, *>>>()

    /**
     * Validates a string field.
     * Accepts nullable values - use .required() to enforce non-null.
     *
     * @param fieldName the field identifier
     * @param value the value to validate (nullable)
     * @return StringValidator for chaining validation rules
     */
    fun field(fieldName: String, value: String?): StringValidator {
        val validator = StringValidator(value)
        validators.add(fieldName to validator)
        return validator
    }

    /**
     * Validates a numeric field.
     * Accepts nullable values - use .required() to enforce non-null.
     *
     * @param fieldName the field identifier
     * @param value the value to validate (nullable)
     * @return NumberValidator for chaining validation rules
     */
    fun field(fieldName: String, value: Number?): NumberValidator<out Number> {
        val validator = NumberValidator(value)
        validators.add(fieldName to validator)
        return validator
    }

    /**
     * Validates a collection field.
     * Accepts nullable values - use .required() to enforce non-null.
     *
     * @param fieldName the field identifier
     * @param value the collection to validate (nullable)
     * @return CollectionValidator for chaining validation rules
     */
    fun <T> field(fieldName: String, value: Collection<T>?): CollectionValidator<T> {
        val validator = CollectionValidator(value)
        validators.add(fieldName to validator)
        return validator
    }

    /**
     * Validates a datetime field.
     * Accepts nullable values - use .required() to enforce non-null.
     *
     * @param fieldName the field identifier
     * @param value the datetime to validate (nullable)
     * @return DateTimeValidator for chaining validation rules
     */
    fun field(fieldName: String, value: LocalDateTime?): DateTimeValidator {
        val validator = DateTimeValidator(value)
        validators.add(fieldName to validator)
        return validator
    }

    /**
     * Creates a nested validation group.
     * Supports hierarchical field naming (e.g., "user.address.street").
     *
     * @param groupName the name of this group
     * @param block validation logic for this group
     */
    fun group(groupName: String, block: ValidationContext.() -> Unit) {
        val childContext = ValidationContext(
            if (this.groupName.isEmpty()) groupName else "${this.groupName}.$groupName"
        )
        childContext.block()
        childContext.collectErrors()
        errors.putAll(childContext.errors)
    }

    // Java Compatibility
    fun group(groupName: String, consumer: Consumer<ValidationContext>) {
        group(groupName) { consumer.accept(this) }
    }

    private fun collectErrors() {
        validators.forEach { (fieldName, validator) ->
            val fieldErrors = validator.getErrors()
            if (fieldErrors.isNotEmpty()) {
                val fullFieldName = if (groupName.isEmpty()) fieldName else "$groupName.$fieldName"
                errors.computeIfAbsent(fullFieldName) { mutableListOf() }.addAll(fieldErrors)
            }
        }
    }

    internal fun getErrors(): Map<String, List<String>> {
        collectErrors()
        return errors
    }

    fun getResult(): ValidationResult {
        val allErrors = getErrors()
        return if (allErrors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(allErrors)
        }
    }
}

// ========================================================================
// Top-level API
// ========================================================================

/**
 * Validates data and returns a ValidationResult.
 * Collects all errors without throwing exceptions.
 *
 * Example:
 * ```kotlin
 * val result = validate {
 *     field("username", user.username).required().minSize(3)
 *     field("bio", user.bio).maxSize(200) // optional
 * }
 * ```
 */
fun validate(block: ValidationContext.() -> Unit): ValidationResult {
    val context = ValidationContext()
    context.block()
    return context.getResult()
}

/**
 * Validates data and throws ValidationException if validation fails.
 *
 * Example:
 * ```kotlin
 * expect {
 *     field("age", user.age).between(18, 100)
 *     field("email", user.email).email()
 * }
 * ```
 *
 * @param block the validation block
 * @throws ValidationException if any validation fails
 */
fun expect(block: ValidationContext.() -> Unit) {
    val context = ValidationContext()
    context.block()
    val errors = context.getErrors()
    if (errors.isNotEmpty()) {
        throw ValidationException(errors)
    }
}

// ========================================================================
// Java Compatibility
// ========================================================================

/**
 * Validates data and returns a ValidationResult (Java-compatible).
 */
fun validate(consumer: Consumer<ValidationContext>): ValidationResult {
    return validate { consumer.accept(this) }
}

/**
 * Validates data and throws ValidationException if validation fails (Java-compatible).
 *
 * @param consumer the validation consumer
 * @throws ValidationException if any validation fails
 */
fun expect(consumer: Consumer<ValidationContext>) {
    expect { consumer.accept(this) }
}
