package protocatalyst.expr

import protocatalyst.types.*
import java.io.Serializable

/** Compile-time expression representation. */
enum ProtoExpr extends Serializable:
  // Leaf nodes
  case Literal(value: LiteralValue)
  case ColumnRef(
      name: String,
      qualifier: Option[String],
      resolvedType: ProtoType,
      nullable: Boolean
  )
  case BoundRef(index: Int, dataType: ProtoType, nullable: Boolean)

  // Comparison
  case Eq(left: ProtoExpr, right: ProtoExpr)
  case NotEq(left: ProtoExpr, right: ProtoExpr)
  case Lt(left: ProtoExpr, right: ProtoExpr)
  case LtEq(left: ProtoExpr, right: ProtoExpr)
  case Gt(left: ProtoExpr, right: ProtoExpr)
  case GtEq(left: ProtoExpr, right: ProtoExpr)

  // Logical
  case And(children: Vector[ProtoExpr])
  case Or(children: Vector[ProtoExpr])
  case Not(child: ProtoExpr)

  // Null handling
  case IsNull(child: ProtoExpr)
  case IsNotNull(child: ProtoExpr)
  case Coalesce(children: Vector[ProtoExpr])

  // Arithmetic
  case Add(left: ProtoExpr, right: ProtoExpr)
  case Subtract(left: ProtoExpr, right: ProtoExpr)
  case Multiply(left: ProtoExpr, right: ProtoExpr)
  case Divide(left: ProtoExpr, right: ProtoExpr)

  // String
  case Concat(children: Vector[ProtoExpr])
  case Substring(str: ProtoExpr, pos: ProtoExpr, len: ProtoExpr)
  case Upper(child: ProtoExpr)
  case Lower(child: ProtoExpr)

  // Aggregates
  case Count(child: ProtoExpr, distinct: Boolean)
  case Sum(child: ProtoExpr)
  case Avg(child: ProtoExpr)
  case Min(child: ProtoExpr)
  case Max(child: ProtoExpr)

  // Control flow
  case CaseWhen(branches: Vector[(ProtoExpr, ProtoExpr)], elseValue: Option[ProtoExpr])
  case If(predicate: ProtoExpr, trueValue: ProtoExpr, falseValue: ProtoExpr)
  case In(value: ProtoExpr, list: Vector[ProtoExpr])

  // Cast and alias
  case Cast(child: ProtoExpr, targetType: ProtoType)
  case Alias(child: ProtoExpr, name: String)

  // Opaque function call (UDFs and unknown functions)
  case OpaqueCall(
      functionName: String,
      arguments: Vector[ProtoExpr],
      returnType: Option[ProtoType],
      deterministic: Boolean
  )

object ProtoExpr:
  // Convenience constructors for literals
  def lit(value: Boolean): ProtoExpr = Literal(LiteralValue.BooleanValue(value))
  def lit(value: Int): ProtoExpr = Literal(LiteralValue.IntValue(value))
  def lit(value: Long): ProtoExpr = Literal(LiteralValue.LongValue(value))
  def lit(value: Double): ProtoExpr = Literal(LiteralValue.DoubleValue(value))
  def lit(value: String): ProtoExpr = Literal(LiteralValue.StringValue(value))
  def litNull(dataType: ProtoType): ProtoExpr = Literal(LiteralValue.NullValue(dataType))
