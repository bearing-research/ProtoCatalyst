package protocatalyst.sql

import protocatalyst.sql.ast.*
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.{AstToProtoTransform, TransformError}
import protocatalyst.plan.*
import protocatalyst.expr.*
import protocatalyst.schema.*
import protocatalyst.types.*

/**
 * SQL verification utility for testing and debugging.
 *
 * Usage:
 *   sbt "sqlParser/Test/runMain protocatalyst.sql.SqlVerifier"
 *
 * Or run specific methods in a REPL/worksheet.
 */
object SqlVerifier:

  // ============================================
  // Test Schemas
  // ============================================

  val usersSchema: ProtoSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.LongType, nullable = false),
      ProtoStructField("name", ProtoType.StringType, nullable = false),
      ProtoStructField("age", ProtoType.IntType, nullable = false),
      ProtoStructField("salary", ProtoType.DoubleType, nullable = true),
      ProtoStructField("department", ProtoType.StringType, nullable = true),
      ProtoStructField("active", ProtoType.BooleanType, nullable = false),
      ProtoStructField("hire_date", ProtoType.DateType, nullable = true)
    )
  )

  val ordersSchema: ProtoSchema = ProtoSchema(
    Vector(
      ProtoStructField("order_id", ProtoType.LongType, nullable = false),
      ProtoStructField("user_id", ProtoType.LongType, nullable = false),
      ProtoStructField("total", ProtoType.DoubleType, nullable = false),
      ProtoStructField("created_at", ProtoType.TimestampType, nullable = false)
    )
  )

  // ============================================
  // Verification Methods
  // ============================================

  /** Parse SQL and return the AST, or error message. */
  def parse(sql: String): Either[String, SqlStatement] =
    SqlParser.parse(sql)

  /** Parse and transform SQL to ProtoLogicalPlan. */
  def transform(sql: String, schema: ProtoSchema = usersSchema, tableName: String = "users"): Either[String, ProtoLogicalPlan] =
    parse(sql).flatMap { stmt =>
      AstToProtoTransform.transformStmt(stmt, schema, tableName) match
        case Right(plan) => Right(plan)
        case Left(err) => Left(s"Transform error: ${err.message}")
    }

  /** Pretty print an AST statement. */
  def prettyPrintAst(stmt: SqlStatement, indent: Int = 0): String =
    val pad = "  " * indent
    stmt match
      case SqlStatement.SelectStatement(distinct, projections, from, where, groupBy, having, orderBy, limit) =>
        val sb = new StringBuilder()
        sb.append(s"${pad}SelectStatement:\n")
        sb.append(s"${pad}  distinct: $distinct\n")
        sb.append(s"${pad}  projections: ${projections.map(p => prettyPrintProjection(p)).mkString(", ")}\n")
        sb.append(s"${pad}  from: ${prettyPrintFrom(from)}\n")
        sb.append(s"${pad}  where: ${where.map(prettyPrintExpr).getOrElse("None")}\n")
        if groupBy.nonEmpty then sb.append(s"${pad}  groupBy: ${groupBy.map(prettyPrintExpr).mkString(", ")}\n")
        if having.isDefined then sb.append(s"${pad}  having: ${having.map(prettyPrintExpr).getOrElse("None")}\n")
        if orderBy.nonEmpty then sb.append(s"${pad}  orderBy: ${orderBy.map(o => s"${prettyPrintExpr(o.expr)} ${if o.ascending then "ASC" else "DESC"}").mkString(", ")}\n")
        if limit.isDefined then sb.append(s"${pad}  limit: ${limit.get}\n")
        sb.toString()

      case SqlStatement.CompoundStatement(left, op, right) =>
        s"${pad}CompoundStatement($op):\n${prettyPrintAst(left, indent + 1)}${prettyPrintAst(right, indent + 1)}"

      case SqlStatement.WithStatement(ctes, recursive, query) =>
        val ctesStr = ctes.map(c => s"${c.name}${c.columnAliases.map(a => s"(${a.mkString(", ")})").getOrElse("")}").mkString(", ")
        s"${pad}WithStatement(recursive=$recursive, ctes=[$ctesStr]):\n${prettyPrintAst(query, indent + 1)}"

  private def prettyPrintProjection(p: Projection): String =
    val expr = prettyPrintExpr(p.expr)
    p.alias match
      case Some(a) => s"$expr AS $a"
      case None => expr

  private def prettyPrintFrom(from: FromClause): String = from match
    case FromClause.Table(ref) => ref.alias.map(a => s"${ref.name} AS $a").getOrElse(ref.name)
    case FromClause.Join(left, right, joinType, cond) =>
      s"${prettyPrintFrom(left)} $joinType JOIN ${prettyPrintFrom(right)}${cond.map(c => s" ON ${prettyPrintExpr(c)}").getOrElse("")}"
    case FromClause.Subquery(_, alias) => s"(subquery) AS $alias"

  private def prettyPrintExpr(expr: SqlExpr): String = expr match
    case SqlExpr.ColumnRef(name, qual) => qual.map(q => s"$q.$name").getOrElse(name)
    case SqlExpr.IntLit(v) => v.toString
    case SqlExpr.DoubleLit(v) => v.toString
    case SqlExpr.StringLit(v) => s"'$v'"
    case SqlExpr.BoolLit(v) => v.toString
    case SqlExpr.NullLit => "NULL"
    case SqlExpr.Star(qual) => qual.map(q => s"$q.*").getOrElse("*")
    case SqlExpr.Compare(l, op, r) => s"${prettyPrintExpr(l)} $op ${prettyPrintExpr(r)}"
    case SqlExpr.Arithmetic(l, op, r) => s"(${prettyPrintExpr(l)} $op ${prettyPrintExpr(r)})"
    case SqlExpr.And(l, r) => s"(${prettyPrintExpr(l)} AND ${prettyPrintExpr(r)})"
    case SqlExpr.Or(l, r) => s"(${prettyPrintExpr(l)} OR ${prettyPrintExpr(r)})"
    case SqlExpr.Not(c) => s"NOT ${prettyPrintExpr(c)}"
    case SqlExpr.IsNull(c) => s"${prettyPrintExpr(c)} IS NULL"
    case SqlExpr.IsNotNull(c) => s"${prettyPrintExpr(c)} IS NOT NULL"
    case SqlExpr.FunctionCall(name, args, distinct) =>
      val d = if distinct then "DISTINCT " else ""
      s"$name($d${args.map(prettyPrintExpr).mkString(", ")})"
    case SqlExpr.WindowFunction(func, spec) =>
      val partBy = if spec.partitionBy.nonEmpty then s"PARTITION BY ${spec.partitionBy.map(prettyPrintExpr).mkString(", ")}" else ""
      val ordBy = if spec.orderBy.nonEmpty then s"ORDER BY ${spec.orderBy.map(o => prettyPrintExpr(o.expr)).mkString(", ")}" else ""
      s"${prettyPrintExpr(func)} OVER ($partBy $ordBy)"
    case _ => expr.toString

  /** Pretty print a ProtoLogicalPlan. */
  def prettyPrintPlan(plan: ProtoLogicalPlan, indent: Int = 0): String =
    val pad = "  " * indent
    plan match
      case ProtoLogicalPlan.RelationRef(name, alias, _) =>
        s"${pad}RelationRef($name${alias.map(a => s" AS $a").getOrElse("")})\n"

      case ProtoLogicalPlan.Project(exprs, child) =>
        s"${pad}Project(${exprs.map(prettyPrintProtoExpr).mkString(", ")})\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Filter(cond, child) =>
        s"${pad}Filter(${prettyPrintProtoExpr(cond)})\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Aggregate(grouping, aggs, child) =>
        s"${pad}Aggregate(groupBy=[${grouping.map(prettyPrintProtoExpr).mkString(", ")}], aggs=[${aggs.map(prettyPrintProtoExpr).mkString(", ")}])\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Sort(order, global, child) =>
        val orders = order.map(o => s"${prettyPrintProtoExpr(o.child)} ${o.direction}").mkString(", ")
        s"${pad}Sort([$orders], global=$global)\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Limit(n, child) =>
        s"${pad}Limit($n)\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Distinct(child) =>
        s"${pad}Distinct\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Join(left, right, joinType, cond) =>
        s"${pad}Join($joinType${cond.map(c => s", ${prettyPrintProtoExpr(c)}").getOrElse("")})\n${prettyPrintPlan(left, indent + 1)}${prettyPrintPlan(right, indent + 1)}"

      case ProtoLogicalPlan.Union(children, byName, _) =>
        s"${pad}Union(byName=$byName)\n${children.map(prettyPrintPlan(_, indent + 1)).mkString}"

      case ProtoLogicalPlan.Intersect(left, right, isAll) =>
        s"${pad}Intersect(all=$isAll)\n${prettyPrintPlan(left, indent + 1)}${prettyPrintPlan(right, indent + 1)}"

      case ProtoLogicalPlan.Except(left, right, isAll) =>
        s"${pad}Except(all=$isAll)\n${prettyPrintPlan(left, indent + 1)}${prettyPrintPlan(right, indent + 1)}"

      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        s"${pad}SubqueryAlias($alias)\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.With(ctes, child) =>
        val ctesStr = ctes.map((name, _) => name).mkString(", ")
        s"${pad}With([$ctesStr])\n${ctes.map((n, p) => s"${pad}  CTE($n):\n${prettyPrintPlan(p, indent + 2)}").mkString}${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Window(exprs, partition, order, child) =>
        s"${pad}Window(exprs=[${exprs.size}], partition=[${partition.size}], order=[${order.size}])\n${prettyPrintPlan(child, indent + 1)}"

      case ProtoLogicalPlan.Values(rows, _) =>
        s"${pad}Values(${rows.size} rows)\n"

  private def prettyPrintProtoExpr(expr: ProtoExpr): String = expr match
    case ProtoExpr.Literal(value) => value.toString
    case ProtoExpr.ColumnRef(name, qual, _, _) => qual.map(q => s"$q.$name").getOrElse(name)
    case ProtoExpr.BoundRef(ord, dt, _) => s"#$ord:$dt"
    case ProtoExpr.Eq(l, r) => s"(${prettyPrintProtoExpr(l)} = ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.NotEq(l, r) => s"(${prettyPrintProtoExpr(l)} != ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.Lt(l, r) => s"(${prettyPrintProtoExpr(l)} < ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.LtEq(l, r) => s"(${prettyPrintProtoExpr(l)} <= ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.Gt(l, r) => s"(${prettyPrintProtoExpr(l)} > ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.GtEq(l, r) => s"(${prettyPrintProtoExpr(l)} >= ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.And(children) => children.map(prettyPrintProtoExpr).mkString("(", " AND ", ")")
    case ProtoExpr.Or(children) => children.map(prettyPrintProtoExpr).mkString("(", " OR ", ")")
    case ProtoExpr.Not(c) => s"NOT ${prettyPrintProtoExpr(c)}"
    case ProtoExpr.IsNull(c) => s"${prettyPrintProtoExpr(c)} IS NULL"
    case ProtoExpr.IsNotNull(c) => s"${prettyPrintProtoExpr(c)} IS NOT NULL"
    case ProtoExpr.Add(l, r) => s"(${prettyPrintProtoExpr(l)} + ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.Subtract(l, r) => s"(${prettyPrintProtoExpr(l)} - ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.Multiply(l, r) => s"(${prettyPrintProtoExpr(l)} * ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.Divide(l, r) => s"(${prettyPrintProtoExpr(l)} / ${prettyPrintProtoExpr(r)})"
    case ProtoExpr.Count(_, distinct) => if distinct then "COUNT(DISTINCT ...)" else "COUNT(...)"
    case ProtoExpr.Sum(_) => "SUM(...)"
    case ProtoExpr.Avg(_) => "AVG(...)"
    case ProtoExpr.Min(_) => "MIN(...)"
    case ProtoExpr.Max(_) => "MAX(...)"
    case ProtoExpr.Alias(c, name) => s"${prettyPrintProtoExpr(c)} AS $name"
    case ProtoExpr.RowNumber() => "ROW_NUMBER()"
    case ProtoExpr.Rank() => "RANK()"
    case ProtoExpr.DenseRank() => "DENSE_RANK()"
    case ProtoExpr.WindowExpr(func, _, _, _) => s"WindowExpr(${prettyPrintProtoExpr(func)})"
    case _ => expr.getClass.getSimpleName

  /** Full verification: parse, transform, and print both representations. */
  def verify(sql: String, schema: ProtoSchema = usersSchema, tableName: String = "users"): Unit =
    println("=" * 60)
    println("SQL:")
    println(sql.trim)
    println("-" * 60)

    parse(sql) match
      case Left(err) =>
        println(s"PARSE ERROR: $err")
      case Right(ast) =>
        println("AST:")
        println(prettyPrintAst(ast))
        println("-" * 60)

        AstToProtoTransform.transformStmt(ast, schema, tableName) match
          case Left(err) =>
            println(s"TRANSFORM ERROR: ${err.message}")
          case Right(plan) =>
            println("ProtoLogicalPlan:")
            println(prettyPrintPlan(plan))

    println("=" * 60)
    println()

  // ============================================
  // Demo / Test Runner
  // ============================================

  def main(args: Array[String]): Unit =
    println("SQL Parser Verification Tool")
    println("============================")
    println()

    // Basic queries
    verify("SELECT name, age FROM users")
    verify("SELECT * FROM users WHERE age > 18")
    verify("SELECT name FROM users WHERE age > 18 AND salary > 50000")
    verify("SELECT name FROM users ORDER BY age DESC LIMIT 10")

    // Aggregations
    verify("SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM users GROUP BY department")
    verify("SELECT department, COUNT(*) FROM users GROUP BY department HAVING COUNT(*) > 5")

    // JOINs
    verify("SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id", usersSchema, "users")

    // Window functions
    verify("SELECT name, ROW_NUMBER() OVER (ORDER BY salary DESC) AS rank FROM users")
    verify("SELECT name, SUM(salary) OVER (PARTITION BY department ORDER BY hire_date) FROM users")

    // Set operations
    verify("SELECT name FROM users WHERE active = true UNION SELECT name FROM users WHERE salary > 100000")

    // CTEs
    verify("""
      WITH active_users AS (
        SELECT * FROM users WHERE active = true
      )
      SELECT name, salary FROM active_users WHERE age > 25
    """)

    // Complex query
    verify("""
      WITH
        dept_stats AS (
          SELECT department, AVG(salary) AS avg_salary, COUNT(*) AS cnt
          FROM users
          WHERE active = true
          GROUP BY department
          HAVING COUNT(*) > 3
        ),
        high_earners AS (
          SELECT * FROM users WHERE salary > 100000
        )
      SELECT d.department, d.avg_salary, d.cnt
      FROM dept_stats d
      ORDER BY d.avg_salary DESC
      LIMIT 5
    """)

    // Error cases
    println("Testing error cases:")
    verify("SELECT unknown_column FROM users")  // Unknown column
    verify("SELECT * FROM")  // Syntax error
    verify("WITH RECURSIVE cte AS (SELECT * FROM users) SELECT * FROM cte")  // Recursive CTE not supported
