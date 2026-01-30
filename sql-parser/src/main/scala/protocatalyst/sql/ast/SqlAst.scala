package protocatalyst.sql.ast

/** SQL statement AST. */
enum SqlStatement:
  case SelectStatement(
    distinct: Boolean,
    projections: Vector[Projection],
    from: FromClause,
    where: Option[SqlExpr],
    groupBy: Vector[SqlExpr],
    having: Option[SqlExpr],
    orderBy: Vector[OrderSpec],
    limit: Option[Long]
  )
  case CompoundStatement(
    left: SqlStatement,
    op: SetOperation,
    right: SqlStatement
  )
  case WithStatement(
    ctes: Vector[CteDefinition],
    recursive: Boolean,
    query: SqlStatement
  )

/** CTE (Common Table Expression) definition. */
case class CteDefinition(
    name: String,
    columnAliases: Option[Vector[String]],
    query: SqlStatement
)

/** Set operation types. */
enum SetOperation:
  case Union(all: Boolean)
  case Intersect(all: Boolean)
  case Except(all: Boolean)

/** A projection in the SELECT clause. */
case class Projection(expr: SqlExpr, alias: Option[String])

/** A table reference with optional alias. */
case class TableRef(name: String, alias: Option[String])

/** The FROM clause - can be a simple table, a join, or a subquery. */
enum FromClause:
  case Table(ref: TableRef)
  case Join(left: FromClause, right: FromClause, joinType: JoinType, condition: Option[SqlExpr])
  case Subquery(stmt: SqlStatement.SelectStatement, alias: String)

/** Join types. */
enum JoinType:
  case Inner
  case LeftOuter
  case RightOuter
  case FullOuter
  case Cross

/** ORDER BY specification. */
case class OrderSpec(expr: SqlExpr, ascending: Boolean)

/** Binary comparison operators. */
enum CompareOp:
  case Eq, NotEq, Lt, LtEq, Gt, GtEq

/** Binary arithmetic operators. */
enum ArithOp:
  case Add, Subtract, Multiply, Divide

/** SQL expression AST. */
enum SqlExpr:
  // Literals
  case IntLit(value: Long)
  case DoubleLit(value: Double)
  case StringLit(value: String)
  case BoolLit(value: Boolean)
  case NullLit

  // Column reference
  case ColumnRef(name: String, qualifier: Option[String])

  // Star projection (SELECT *)
  case Star(qualifier: Option[String])

  // Comparison
  case Compare(left: SqlExpr, op: CompareOp, right: SqlExpr)

  // Arithmetic
  case Arithmetic(left: SqlExpr, op: ArithOp, right: SqlExpr)

  // Boolean logic
  case And(left: SqlExpr, right: SqlExpr)
  case Or(left: SqlExpr, right: SqlExpr)
  case Not(child: SqlExpr)

  // Null checks
  case IsNull(child: SqlExpr)
  case IsNotNull(child: SqlExpr)

  // BETWEEN
  case Between(value: SqlExpr, low: SqlExpr, high: SqlExpr)
  case NotBetween(value: SqlExpr, low: SqlExpr, high: SqlExpr)

  // LIKE
  case Like(value: SqlExpr, pattern: SqlExpr, escape: Option[SqlExpr])
  case NotLike(value: SqlExpr, pattern: SqlExpr, escape: Option[SqlExpr])

  // IN
  case In(value: SqlExpr, list: Vector[SqlExpr])
  case NotIn(value: SqlExpr, list: Vector[SqlExpr])

  // Function call
  case FunctionCall(name: String, args: Vector[SqlExpr], distinct: Boolean)

  // Parenthesized expression (for grouping)
  case Paren(child: SqlExpr)

  // CASE WHEN
  case CaseWhen(branches: Vector[(SqlExpr, SqlExpr)], elseValue: Option[SqlExpr])

  // CAST
  case Cast(expr: SqlExpr, targetType: SqlType)

  // Subquery expressions
  case ScalarSubquery(stmt: SqlStatement.SelectStatement)
  case Exists(stmt: SqlStatement.SelectStatement)
  case NotExists(stmt: SqlStatement.SelectStatement)
  case InSubquery(value: SqlExpr, stmt: SqlStatement.SelectStatement)
  case NotInSubquery(value: SqlExpr, stmt: SqlStatement.SelectStatement)

  // Window function expression
  case WindowFunction(function: SqlExpr, windowSpec: WindowSpec)

/** Window specification for OVER clause. */
case class WindowSpec(
    partitionBy: Vector[SqlExpr],
    orderBy: Vector[OrderSpec],
    frame: Option[WindowFrame]
)

/** Window frame specification. */
case class WindowFrame(
    frameType: FrameType,
    start: FrameBound,
    end: FrameBound
)

/** Window frame types. */
enum FrameType:
  case Rows
  case Range

/** Window frame bound. */
enum FrameBound:
  case UnboundedPreceding
  case UnboundedFollowing
  case CurrentRow
  case Preceding(n: Long)
  case Following(n: Long)

/** SQL data types for CAST. */
enum SqlType:
  case IntType
  case LongType
  case DoubleType
  case StringType
  case BooleanType
  case DateType
  case TimestampType
