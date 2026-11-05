package dev.app8.engine.core.vars

/** Errors during variable operations (Swift `VariableError`). Messages match iOS `errorDescription`. */
public sealed class VariableException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public class UndefinedVariable(public val name: String) : VariableException("Undefined variable: '$name'")

    public class TypeMismatch(
        public val variable: String,
        public val expected: VariableType,
        public val actual: String,
    ) : VariableException("Type mismatch for variable '$variable': expected $expected, got $actual")

    public class CannotModifyComputedVariable(public val name: String) :
        VariableException("Cannot modify computed variable: '$name'")

    public class CircularDependency(public val path: List<String>) :
        VariableException("Circular dependency detected: ${path.joinToString(" -> ")}")

    public class InvalidExpression(public val expression: String, public val underlying: Throwable) :
        VariableException("Invalid expression '$expression': ${underlying.message}", underlying)

    public class ComputationError(public val expression: String, public val underlying: Throwable) :
        VariableException("Computation error for '$expression': ${underlying.message}", underlying)
}

/** Errors during datasource loading (Swift `DatasourceError`). */
public sealed class DatasourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public class NotFound(public val screenId: String, public val datasourceId: String) :
        DatasourceException("Datasource '$datasourceId' not found for screen '$screenId'")

    public class InvalidFormat(public val datasourceId: String, public val underlying: Throwable) :
        DatasourceException("Invalid format for datasource '$datasourceId': ${underlying.message}", underlying)

    public class TypeMismatch(
        public val datasourceId: String,
        public val expected: VariableType,
        public val actual: String,
    ) : DatasourceException("Type mismatch for datasource '$datasourceId': expected $expected, got $actual")

    public class NotImplemented : DatasourceException("Datasource loading not implemented in data source")
}

/** Errors during JSON Schema validation (Swift `SchemaValidationError`). */
public sealed class SchemaValidationException(message: String) : Exception(message) {
    public class InvalidSchema(public val reason: String) : SchemaValidationException("Invalid schema: $reason")

    public class TypeMismatch(public val path: String, public val expected: String, public val actual: String) :
        SchemaValidationException("Type mismatch at '$path': expected $expected, got $actual")

    public class MissingRequired(public val path: String, public val property: String) :
        SchemaValidationException("Missing required property '$property' at '$path'")

    public class InvalidValue(public val path: String, public val reason: String) :
        SchemaValidationException("Invalid value at '$path': $reason")
}

/** Optional diagnostics sink (iOS `A8Log`). Stores and handlers log nothing when unset. */
public interface VariableLogger {
    public fun debug(message: String)
    public fun warning(message: String)
    public fun error(message: String)
}
