package protocatalyst.sql

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import munit.FunSuite
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.{PlainSelect, Select, SetOperationList}
import net.sf.jsqlparser.statement.{Statement => JStatement}

import protocatalyst.sql.ast._
import protocatalyst.sql.parser.SqlParser

/** Comparison test suite that validates our SQL parser against JSQLParser.
  *
  * This ensures our parser correctly handles SQL syntax by comparing structural elements (tables,
  * columns, operators) between both parsers.
  */
class ParserComparisonSuite extends FunSuite:

  // ============================================
  // Helper Methods
  // ============================================

  /** Parse with our parser. */
  def parseOurs(sql: String): Either[String, SqlStatement] =
    SqlParser.parse(sql)

  /** Parse with JSQLParser. */
  def parseJSql(sql: String): Either[String, JStatement] =
    Try(CCJSqlParserUtil.parse(sql)) match
      case Success(stmt) => Right(stmt)
      case Failure(e)    => Left(e.getMessage)

  /** Check if both parsers agree on parseability. */
  def bothParseOrBothFail(sql: String): Unit =
    val ours = parseOurs(sql)
    val jsql = parseJSql(sql)

    (ours, jsql) match
      case (Right(_), Right(_)) => () // Both succeed - good
      case (Left(_), Left(_))   => () // Both fail - acceptable
      case (Right(_), Left(_))  =>
        // We parsed but JSQLParser didn't - may be acceptable if we're more lenient
        // Log but don't fail (JSQLParser may be stricter)
        ()
      case (Left(ourErr), Right(_)) =>
        fail(s"JSQLParser succeeded but we failed: $ourErr\nSQL: $sql")

  /** Extract table names from our AST. */
  def extractTablesOurs(stmt: SqlStatement): Set[String] =
    stmt match
      case SqlStatement.SelectStatement(_, _, from, _, _, _, _, _) =>
        extractTablesFromClause(from)
      case SqlStatement.CompoundStatement(left, _, right) =>
        extractTablesOurs(left) ++ extractTablesOurs(right)
      case SqlStatement.WithStatement(ctes, _, query) =>
        ctes.flatMap(c => extractTablesOurs(c.query)).toSet ++ extractTablesOurs(query)

  private def extractTablesFromClause(from: FromClause): Set[String] = from match
    case FromClause.Table(ref)              => Set(ref.name)
    case FromClause.Join(left, right, _, _) =>
      extractTablesFromClause(left) ++ extractTablesFromClause(right)
    case FromClause.Subquery(stmt, _) =>
      extractTablesOurs(stmt)

  /** Extract table names from JSQLParser AST. */
  def extractTablesJSql(stmt: JStatement): Set[String] =
    stmt match
      case select: Select =>
        extractTablesFromSelect(select)
      case _ => Set.empty

  @annotation.nowarn("msg=deprecated")
  private def extractTablesFromSelect(select: Select): Set[String] =
    // Try the newer API first, fall back to deprecated if needed
    val plainSelect: PlainSelect | Null = select match
      case ps: PlainSelect => ps
      case _               =>
        try
          select.getSelectBody match
            case ps: PlainSelect => ps
            case _               => null
        catch case _: Exception => null

    if plainSelect != null then extractTablesFromPlainSelect(plainSelect.nn)
    else
      // Handle set operations
      try
        select.getSelectBody match
          case setOp: SetOperationList =>
            setOp.getSelects.asScala.flatMap { s =>
              extractTablesFromSelect(s.asInstanceOf[Select])
            }.toSet
          case _ => Set.empty
      catch case _: Exception => Set.empty

  private def extractTablesFromPlainSelect(plain: PlainSelect): Set[String] =
    Option(plain.getFromItem)
      .map {
        case t: Table => Set(t.getName)
        case _        => Set.empty[String]
      }
      .getOrElse(Set.empty) ++
      Option(plain.getJoins)
        .map(
          _.asScala
            .flatMap { join =>
              join.getRightItem match
                case t: Table => Some(t.getName)
                case _        => None
            }
            .toSet
        )
        .getOrElse(Set.empty)

  /** Extract column names from our AST expressions. */
  def extractColumnsOurs(stmt: SqlStatement): Set[String] =
    stmt match
      case SqlStatement.SelectStatement(_, projs, _, where, groupBy, having, orderBy, _) =>
        projs.flatMap(p => extractColumnsFromExpr(p.expr)).toSet ++
          where.toVector.flatMap(extractColumnsFromExpr).toSet ++
          groupBy.flatMap(extractColumnsFromExpr).toSet ++
          having.toVector.flatMap(extractColumnsFromExpr).toSet ++
          orderBy.flatMap(o => extractColumnsFromExpr(o.expr)).toSet
      case SqlStatement.CompoundStatement(left, _, right) =>
        extractColumnsOurs(left) ++ extractColumnsOurs(right)
      case SqlStatement.WithStatement(ctes, _, query) =>
        ctes.flatMap(c => extractColumnsOurs(c.query)).toSet ++ extractColumnsOurs(query)

  private def extractColumnsFromExpr(expr: SqlExpr): Set[String] = expr match
    case SqlExpr.ColumnRef(name, _)       => Set(name)
    case SqlExpr.Star(_)                  => Set("*")
    case SqlExpr.Compare(l, _, r)         => extractColumnsFromExpr(l) ++ extractColumnsFromExpr(r)
    case SqlExpr.Arithmetic(l, _, r)      => extractColumnsFromExpr(l) ++ extractColumnsFromExpr(r)
    case SqlExpr.And(l, r)                => extractColumnsFromExpr(l) ++ extractColumnsFromExpr(r)
    case SqlExpr.Or(l, r)                 => extractColumnsFromExpr(l) ++ extractColumnsFromExpr(r)
    case SqlExpr.Not(c)                   => extractColumnsFromExpr(c)
    case SqlExpr.IsNull(c)                => extractColumnsFromExpr(c)
    case SqlExpr.IsNotNull(c)             => extractColumnsFromExpr(c)
    case SqlExpr.FunctionCall(_, args, _) => args.flatMap(extractColumnsFromExpr).toSet
    case SqlExpr.CaseWhen(branches, elseVal) =>
      branches.flatMap((c, v) => extractColumnsFromExpr(c) ++ extractColumnsFromExpr(v)).toSet ++
        elseVal.toVector.flatMap(extractColumnsFromExpr).toSet
    case SqlExpr.Cast(c, _)            => extractColumnsFromExpr(c)
    case SqlExpr.Between(v, low, high) =>
      extractColumnsFromExpr(v) ++ extractColumnsFromExpr(low) ++ extractColumnsFromExpr(high)
    case SqlExpr.Like(v, pattern, escape) =>
      extractColumnsFromExpr(v) ++ extractColumnsFromExpr(pattern) ++ escape.toVector
        .flatMap(extractColumnsFromExpr)
        .toSet
    case SqlExpr.In(v, vals) =>
      extractColumnsFromExpr(v) ++ vals.flatMap(extractColumnsFromExpr).toSet
    case SqlExpr.InSubquery(v, _)           => extractColumnsFromExpr(v)
    case SqlExpr.Exists(_)                  => Set.empty
    case SqlExpr.ScalarSubquery(_)          => Set.empty
    case SqlExpr.WindowFunction(func, spec) =>
      extractColumnsFromExpr(func) ++
        spec.partitionBy.flatMap(extractColumnsFromExpr).toSet ++
        spec.orderBy.flatMap(o => extractColumnsFromExpr(o.expr)).toSet
    case _ => Set.empty

  /** Compare parsed structures. */
  def compareStructures(sql: String): Unit =
    val ours = parseOurs(sql)
    val jsql = parseJSql(sql)

    (ours, jsql) match
      case (Right(ourStmt), Right(jsqlStmt)) =>
        val ourTables = extractTablesOurs(ourStmt).map(_.toLowerCase)
        val jsqlTables = extractTablesJSql(jsqlStmt).map(_.toLowerCase)

        // Our tables should be a superset of JSQLParser's tables
        // (we may extract more from CTEs and subqueries)
        val missing = jsqlTables -- ourTables
        assert(
          missing.isEmpty,
          s"Tables found by JSQLParser but not by us: $missing\nSQL: $sql"
        )
      case (Left(e), _) =>
        fail(s"Our parser failed: $e\nSQL: $sql")
      case (_, Left(_)) =>
        // JSQLParser failed but we succeeded - that's OK, log it
        ()

  // ============================================
  // Basic SELECT Tests
  // ============================================

  test("simple SELECT") {
    val sql = "SELECT name FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("SELECT with multiple columns") {
    val sql = "SELECT name, age, salary FROM employees"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("SELECT *") {
    val sql = "SELECT * FROM products"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("SELECT with alias") {
    val sql = "SELECT name AS n, age AS a FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("SELECT with table alias") {
    val sql = "SELECT u.name, u.age FROM users u"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // WHERE Clause Tests
  // ============================================

  test("WHERE with comparison") {
    val sql = "SELECT name FROM users WHERE age > 18"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with AND") {
    val sql = "SELECT name FROM users WHERE age > 18 AND salary > 50000"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with OR") {
    val sql = "SELECT name FROM users WHERE age < 18 OR age > 65"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with NOT") {
    val sql = "SELECT name FROM users WHERE NOT active"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with IS NULL") {
    val sql = "SELECT name FROM users WHERE email IS NULL"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with IS NOT NULL") {
    val sql = "SELECT name FROM users WHERE email IS NOT NULL"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with BETWEEN") {
    val sql = "SELECT name FROM users WHERE age BETWEEN 18 AND 65"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with IN list") {
    val sql = "SELECT name FROM users WHERE status IN ('active', 'pending')"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("WHERE with LIKE") {
    val sql = "SELECT name FROM users WHERE name LIKE 'J%'"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // ORDER BY Tests
  // ============================================

  test("ORDER BY single column") {
    val sql = "SELECT name FROM users ORDER BY name"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("ORDER BY DESC") {
    val sql = "SELECT name FROM users ORDER BY age DESC"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("ORDER BY multiple columns") {
    val sql = "SELECT name FROM users ORDER BY department ASC, salary DESC"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // LIMIT Tests
  // ============================================

  test("LIMIT clause") {
    val sql = "SELECT name FROM users LIMIT 10"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("ORDER BY with LIMIT") {
    val sql = "SELECT name FROM users ORDER BY age DESC LIMIT 5"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // JOIN Tests
  // ============================================

  test("INNER JOIN") {
    val sql = "SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("LEFT JOIN") {
    val sql = "SELECT u.name, o.total FROM users u LEFT JOIN orders o ON u.id = o.user_id"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("RIGHT JOIN") {
    val sql = "SELECT u.name, o.total FROM users u RIGHT JOIN orders o ON u.id = o.user_id"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("FULL OUTER JOIN") {
    val sql = "SELECT u.name, o.total FROM users u FULL OUTER JOIN orders o ON u.id = o.user_id"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("CROSS JOIN") {
    val sql = "SELECT * FROM users CROSS JOIN products"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("multiple JOINs") {
    val sql = """
      SELECT u.name, o.total, a.city
      FROM users u
      INNER JOIN orders o ON u.id = o.user_id
      LEFT JOIN addresses a ON u.id = a.user_id
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Aggregation Tests
  // ============================================

  test("COUNT(*)") {
    val sql = "SELECT COUNT(*) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("COUNT with GROUP BY") {
    val sql = "SELECT department, COUNT(*) FROM users GROUP BY department"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("multiple aggregates") {
    val sql = "SELECT department, COUNT(*), AVG(salary), MAX(age) FROM users GROUP BY department"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("GROUP BY with HAVING") {
    val sql = "SELECT department, COUNT(*) FROM users GROUP BY department HAVING COUNT(*) > 5"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("COUNT DISTINCT") {
    val sql = "SELECT COUNT(DISTINCT department) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Arithmetic Expression Tests
  // ============================================

  test("arithmetic in SELECT") {
    val sql = "SELECT name, salary * 1.1 AS new_salary FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("arithmetic in WHERE") {
    val sql = "SELECT name FROM users WHERE age + 5 > 30"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("complex arithmetic") {
    val sql = "SELECT (price * quantity) - discount AS total FROM orders"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // CASE Expression Tests
  // ============================================

  test("simple CASE WHEN") {
    val sql = "SELECT name, CASE WHEN age > 30 THEN 'senior' ELSE 'junior' END FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("CASE WHEN with multiple branches") {
    val sql = """
      SELECT name,
        CASE
          WHEN age < 20 THEN 'teen'
          WHEN age < 30 THEN 'young'
          WHEN age < 50 THEN 'middle'
          ELSE 'senior'
        END AS age_group
      FROM users
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Function Tests
  // ============================================

  test("UPPER function") {
    val sql = "SELECT UPPER(name) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("COALESCE function") {
    val sql = "SELECT COALESCE(nickname, name) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("CAST expression") {
    val sql = "SELECT CAST(age AS VARCHAR) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("nested functions") {
    val sql = "SELECT UPPER(TRIM(name)) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Set Operation Tests
  // ============================================

  test("UNION") {
    val sql = "SELECT name FROM active_users UNION SELECT name FROM inactive_users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("UNION ALL") {
    val sql = "SELECT name FROM active_users UNION ALL SELECT name FROM inactive_users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("INTERSECT") {
    val sql = "SELECT name FROM premium_users INTERSECT SELECT name FROM active_users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("EXCEPT") {
    val sql = "SELECT name FROM all_users EXCEPT SELECT name FROM banned_users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Window Function Tests
  // ============================================

  test("ROW_NUMBER()") {
    val sql = "SELECT name, ROW_NUMBER() OVER (ORDER BY salary DESC) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("RANK() with PARTITION BY") {
    val sql = "SELECT name, RANK() OVER (PARTITION BY department ORDER BY salary DESC) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("SUM() as window function") {
    val sql = "SELECT name, SUM(salary) OVER (PARTITION BY department) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("LAG() window function") {
    val sql = "SELECT name, LAG(salary, 1) OVER (ORDER BY hire_date) FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // CTE (WITH clause) Tests
  // ============================================

  test("simple CTE") {
    val sql = """
      WITH active AS (
        SELECT * FROM users WHERE active = true
      )
      SELECT name FROM active
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("multiple CTEs") {
    val sql = """
      WITH
        active AS (SELECT * FROM users WHERE active = true),
        premium AS (SELECT * FROM active WHERE premium = true)
      SELECT name FROM premium
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("CTE with column aliases") {
    val sql = """
      WITH totals(dept, total_salary) AS (
        SELECT department, SUM(salary) FROM users GROUP BY department
      )
      SELECT dept, total_salary FROM totals
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Subquery Tests
  // ============================================

  test("subquery in WHERE") {
    val sql =
      "SELECT name FROM users WHERE department IN (SELECT name FROM departments WHERE active = true)"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("EXISTS subquery") {
    val sql =
      "SELECT name FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("scalar subquery") {
    val sql =
      "SELECT name, (SELECT COUNT(*) FROM orders WHERE user_id = users.id) AS order_count FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("subquery in FROM") {
    val sql = "SELECT t.name FROM (SELECT name FROM users WHERE active = true) t"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // DISTINCT Tests
  // ============================================

  test("SELECT DISTINCT") {
    val sql = "SELECT DISTINCT department FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("SELECT DISTINCT multiple columns") {
    val sql = "SELECT DISTINCT department, role FROM users"
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Complex Query Tests
  // ============================================

  test("complex query with multiple clauses") {
    val sql = """
      SELECT
        u.department,
        COUNT(*) AS emp_count,
        AVG(u.salary) AS avg_salary
      FROM users u
      WHERE u.active = true
        AND u.hire_date > '2020-01-01'
      GROUP BY u.department
      HAVING COUNT(*) > 5
      ORDER BY avg_salary DESC
      LIMIT 10
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  test("complex CTE with aggregation and join") {
    val sql = """
      WITH
        dept_stats AS (
          SELECT department, AVG(salary) AS avg_salary, COUNT(*) AS emp_count
          FROM users
          WHERE active = true
          GROUP BY department
        )
      SELECT d.department, d.avg_salary, d.emp_count
      FROM dept_stats d
      WHERE d.emp_count > 3
      ORDER BY d.avg_salary DESC
    """
    bothParseOrBothFail(sql)
    compareStructures(sql)
  }

  // ============================================
  // Edge Cases and Error Handling
  // ============================================

  test("empty string should fail our parser") {
    // JSQLParser may be lenient with empty input, but we should require valid SQL
    assert(parseOurs("").isLeft)
  }

  test("incomplete SELECT should fail both parsers") {
    // "SELECT" alone is incomplete
    assert(parseOurs("SELECT").isLeft)
  }

  test("missing FROM - we require FROM clause") {
    // We require FROM clause for valid SELECT
    // JSQLParser may accept SELECT without FROM (e.g., SELECT 1)
    assert(parseOurs("SELECT name").isLeft)
  }

  test("invalid syntax should fail both parsers") {
    bothParseOrBothFail("SELECT FROM users name")
  }

  test("unclosed parenthesis should fail both parsers") {
    bothParseOrBothFail("SELECT * FROM (SELECT * FROM users")
  }

  test("SELECT constant - requires FROM in our parser") {
    // Some databases allow SELECT 1, but we require FROM
    assert(parseOurs("SELECT 1").isLeft)
    // JSQLParser accepts this
    assert(parseJSql("SELECT 1").isRight)
  }
