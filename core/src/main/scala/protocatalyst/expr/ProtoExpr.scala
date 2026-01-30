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

  // Pattern matching
  case Like(value: ProtoExpr, pattern: ProtoExpr, escape: Option[ProtoExpr])

  // Cast and alias
  case Cast(child: ProtoExpr, targetType: ProtoType)
  case Alias(child: ProtoExpr, name: String)

  // Subquery expressions
  case ScalarSubquery(plan: protocatalyst.plan.ProtoLogicalPlan)
  case Exists(plan: protocatalyst.plan.ProtoLogicalPlan)
  case InSubquery(value: ProtoExpr, plan: protocatalyst.plan.ProtoLogicalPlan)

  // Window functions
  case RowNumber()
  case Rank()
  case DenseRank()
  case Ntile(n: ProtoExpr)
  case Lead(input: ProtoExpr, offset: ProtoExpr, default: Option[ProtoExpr])
  case Lag(input: ProtoExpr, offset: ProtoExpr, default: Option[ProtoExpr])
  case FirstValue(input: ProtoExpr, ignoreNulls: Boolean)
  case LastValue(input: ProtoExpr, ignoreNulls: Boolean)
  case NthValue(input: ProtoExpr, n: ProtoExpr)

  // Window specification wrapper - wraps an expression with its window spec
  case WindowExpr(
      function: ProtoExpr,
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[protocatalyst.plan.SortOrder],
      frameSpec: Option[WindowFrame]
  )

  // Opaque function call (UDFs and unknown functions)
  case OpaqueCall(
      functionName: String,
      arguments: Vector[ProtoExpr],
      returnType: Option[ProtoType],
      deterministic: Boolean
  )

/** Window frame specification. */
case class WindowFrame(
    frameType: FrameType,
    lower: FrameBound,
    upper: FrameBound
) extends Serializable

enum FrameType extends Serializable:
  case Rows, Range

enum FrameBound extends Serializable:
  case UnboundedPreceding
  case UnboundedFollowing
  case CurrentRow
  case Preceding(n: Long)
  case Following(n: Long)

object ProtoExpr:
  // Convenience constructors for literals
  def lit(value: Boolean): ProtoExpr = Literal(LiteralValue.BooleanValue(value))
  def lit(value: Int): ProtoExpr = Literal(LiteralValue.IntValue(value))
  def lit(value: Long): ProtoExpr = Literal(LiteralValue.LongValue(value))
  def lit(value: Double): ProtoExpr = Literal(LiteralValue.DoubleValue(value))
  def lit(value: String): ProtoExpr = Literal(LiteralValue.StringValue(value))
  def litNull(dataType: ProtoType): ProtoExpr = Literal(LiteralValue.NullValue(dataType))
