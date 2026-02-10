package protocatalyst.substrait

import io.substrait.`type`.Type
import io.substrait.expression.Expression

/** Registry for mapping ProtoCatalyst functions to Substrait functions.
  *
  * **Current Status**: The Substrait function extension system is complex and requires:
  *   1. Loading extension YAML files from Substrait repository
  *   2. Registering function anchors with the extension collector
  *   3. Resolving function variants based on argument types and signatures
  *   4. Managing function IDs in the plan
  *
  * This is a **stub implementation** that documents the required function mappings but throws
  * UnsupportedSubstraitFeatureException when called. Full implementation would require:
  *   - Integrating with Substrait's `SimpleExtension` and `ExtensionCollector`
  *   - Loading standard extension YAML files
  *   - Implementing function anchor lookup and variant resolution
  *
  * **Alternative**: The SQL transpiler backend ([DataFusionBackend](../../executor/src/main/scala/protocatalyst/executor/datafusion/DataFusionBackend.scala))
  * works today and doesn't require Substrait function mapping.
  */
class SubstraitFunctionRegistry:

  /** Create a scalar function call expression.
    *
    * @param name
    *   ProtoCatalyst function name (e.g., "add", "equal", "concat")
    * @param args
    *   Function arguments (already converted to Substrait expressions)
    * @param outputType
    *   Expected output type of the function
    * @return
    *   Substrait ScalarFunctionInvocation expression
    * @throws UnsupportedSubstraitFeatureException
    *   always (not yet implemented)
    */
  def createScalarFunction(
      name: String,
      args: Vector[Expression],
      outputType: Type
  ): Expression =
    // Map ProtoCatalyst function name to Substrait function name
    val substraitName = mapFunctionName(name)

    throw UnsupportedSubstraitFeatureException(
      s"Substrait function system not yet implemented. " +
        s"Function: '$name' (Substrait: '$substraitName'). " +
        s"This requires integrating with Substrait's extension system to load function definitions " +
        s"from YAML files and resolve function variants. " +
        s"For now, use the SQL transpiler backend (DataFusionBackend) which works today."
    )

  /** Map ProtoCatalyst function names to Substrait function names.
    *
    * Most names are identical, but some differ (e.g., "not_equal" vs "not_equals").
    */
  private def mapFunctionName(name: String): String = name match
    // Comparison operators (already standardized)
    case "equal"     => "equal"
    case "not_equal" => "not_equal"
    case "lt"        => "lt"
    case "lte"       => "lte"
    case "gt"        => "gt"
    case "gte"       => "gte"

    // Logical operators
    case "and" => "and"
    case "or"  => "or"
    case "not" => "not"

    // Null handling
    case "is_null"     => "is_null"
    case "is_not_null" => "is_not_null"
    case "coalesce"    => "coalesce"
    case "nullif"      => "nullif"

    // Arithmetic
    case "add"      => "add"
    case "subtract" => "subtract"
    case "multiply" => "multiply"
    case "divide"   => "divide"

    // Math functions
    case "abs"     => "abs"
    case "ceil"    => "ceil"
    case "floor"   => "floor"
    case "round"   => "round"
    case "trunc"   => "trunc"
    case "sqrt"    => "sqrt"
    case "cbrt"    => "cbrt"
    case "power"   => "power"
    case "modulus" => "modulus"
    case "sign"    => "sign"
    case "ln"      => "ln"
    case "log"     => "log"
    case "exp"     => "exp"

    // String functions
    case "concat"      => "concat"
    case "substring"   => "substring"
    case "upper"       => "upper"
    case "lower"       => "lower"
    case "trim"        => "trim"
    case "ltrim"       => "ltrim"
    case "rtrim"       => "rtrim"
    case "char_length" => "char_length"
    case "replace"     => "replace"
    case "strpos"      => "strpos"
    case "lpad"        => "lpad"
    case "rpad"        => "rpad"
    case "split"       => "split"
    case "reverse"     => "reverse"
    case "repeat"      => "repeat"

    // Control flow
    case "if" => "if"
    case "in" => "in"

    // Pattern matching
    case "like" => "like"

    // Date/time functions
    case "current_date"      => "current_date"
    case "current_timestamp" => "current_timestamp"
    case "add_days"          => "add_days"
    case "subtract_days"     => "subtract_days"
    case "date_diff"         => "date_diff"
    case "to_date"           => "to_date"
    case "to_timestamp"      => "to_timestamp"

    // Extract functions
    case n if n.startsWith("extract_")   => n
    case n if n.startsWith("date_trunc_") => n

    // Default: use the name as-is
    case other => other

end SubstraitFunctionRegistry

object SubstraitFunctionRegistry:
  /** Shared default registry instance. */
  lazy val default: SubstraitFunctionRegistry = new SubstraitFunctionRegistry()
