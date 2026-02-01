package protocatalyst.sql.transform

/** Errors during SQL AST to Proto transformation. */
sealed trait TransformError:
  def message: String

object TransformError:
  case class UnknownColumn(name: String, qualifier: Option[String]) extends TransformError:
    override def message: String = qualifier match
      case Some(q) => s"Unknown column: $q.$name"
      case None    => s"Unknown column: $name"

  case class AmbiguousColumn(name: String, tables: Vector[String]) extends TransformError:
    override def message: String =
      s"Ambiguous column '$name' found in tables: ${tables.mkString(", ")}"

  case class TypeMismatch(expected: String, found: String, context: String) extends TransformError:
    override def message: String = s"Type mismatch in $context: expected $expected, found $found"

  case class UnsupportedFeature(feature: String) extends TransformError:
    override def message: String = s"Unsupported SQL feature: $feature"

  case class InvalidExpression(description: String) extends TransformError:
    override def message: String = s"Invalid expression: $description"
