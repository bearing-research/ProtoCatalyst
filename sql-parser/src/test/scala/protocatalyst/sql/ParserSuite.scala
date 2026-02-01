package protocatalyst.sql

import protocatalyst.sql.ast.*
import protocatalyst.sql.parser.SqlParser

class ParserSuite extends munit.FunSuite:

  // Helper to extract SelectStatement from SqlStatement
  def asSelect(stmt: SqlStatement): SqlStatement.SelectStatement = stmt match
    case s: SqlStatement.SelectStatement => s
    case _ => fail("Expected SelectStatement").asInstanceOf[SqlStatement.SelectStatement]

  // Helper to extract table name from FromClause
  def extractTableName(from: FromClause): String = from match
    case FromClause.Table(ref)          => ref.name
    case FromClause.Join(left, _, _, _) => extractTableName(left)
    case FromClause.Subquery(_, alias)  => alias

  def extractTableAlias(from: FromClause): Option[String] = from match
    case FromClause.Table(ref)          => ref.alias
    case FromClause.Join(left, _, _, _) => extractTableAlias(left)
    case FromClause.Subquery(_, alias)  => Some(alias)

  test("parses simple SELECT"):
    val result = SqlParser.parse("SELECT name FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 1)
    assertEquals(extractTableName(stmt.from), "users")
    assertEquals(stmt.where, None)
    assertEquals(stmt.orderBy, Vector.empty)
    assertEquals(stmt.limit, None)

  test("parses SELECT with multiple columns"):
    val result = SqlParser.parse("SELECT name, age, salary FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 3)

  test("parses SELECT *"):
    val result = SqlParser.parse("SELECT * FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 1)
    stmt.projections.head.expr match
      case SqlExpr.Star(None) => () // ok
      case _                  => fail("Expected Star")

  test("parses SELECT DISTINCT"):
    val result = SqlParser.parse("SELECT DISTINCT name FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.distinct, true)

  test("parses WHERE with comparison"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age > 18")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(
            SqlExpr.Compare(
              SqlExpr.ColumnRef("age", None),
              CompareOp.Gt,
              SqlExpr.IntLit(18)
            )
          ) =>
        () // ok
      case _ => fail(s"Unexpected where: ${stmt.where}")

  test("parses WHERE with AND"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age > 18 AND salary > 50000")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.And(_, _)) => () // ok
      case _                       => fail(s"Expected AND expression")

  test("parses WHERE with OR"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age < 18 OR age > 65")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Or(_, _)) => () // ok
      case _                      => fail(s"Expected OR expression")

  test("parses WHERE with NOT"):
    val result = SqlParser.parse("SELECT name FROM users WHERE NOT active")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Not(_)) => () // ok
      case _                    => fail(s"Expected NOT expression")

  test("parses WHERE with IS NULL"):
    val result = SqlParser.parse("SELECT name FROM users WHERE email IS NULL")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.IsNull(_)) => () // ok
      case _                       => fail(s"Expected IS NULL expression")

  test("parses WHERE with IS NOT NULL"):
    val result = SqlParser.parse("SELECT name FROM users WHERE email IS NOT NULL")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.IsNotNull(_)) => () // ok
      case _                          => fail(s"Expected IS NOT NULL expression")

  test("parses ORDER BY"):
    val result = SqlParser.parse("SELECT name FROM users ORDER BY age")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.orderBy.size, 1)
    assertEquals(stmt.orderBy.head.ascending, true)

  test("parses ORDER BY DESC"):
    val result = SqlParser.parse("SELECT name FROM users ORDER BY age DESC")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.orderBy.size, 1)
    assertEquals(stmt.orderBy.head.ascending, false)

  test("parses ORDER BY multiple columns"):
    val result = SqlParser.parse("SELECT name FROM users ORDER BY age DESC, name ASC")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.orderBy.size, 2)
    assertEquals(stmt.orderBy(0).ascending, false)
    assertEquals(stmt.orderBy(1).ascending, true)

  test("parses LIMIT"):
    val result = SqlParser.parse("SELECT name FROM users LIMIT 100")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.limit, Some(100L))

  test("parses complete query"):
    val result = SqlParser.parse("""
      SELECT name, salary
      FROM users
      WHERE age > 18 AND salary > 50000
      ORDER BY salary DESC
      LIMIT 10
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 2)
    assertEquals(extractTableName(stmt.from), "users")
    assert(stmt.where.isDefined)
    assertEquals(stmt.orderBy.size, 1)
    assertEquals(stmt.limit, Some(10L))

  test("parses aliased columns"):
    val result = SqlParser.parse("SELECT name AS n, age AS a FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections(0).alias, Some("n"))
    assertEquals(stmt.projections(1).alias, Some("a"))

  test("parses table alias"):
    val result = SqlParser.parse("SELECT u.name FROM users u")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(extractTableAlias(stmt.from), Some("u"))

  test("parses qualified column reference"):
    val result = SqlParser.parse("SELECT u.name FROM users u")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.ColumnRef("name", Some("u")) => () // ok
      case _                                    => fail("Expected qualified column ref")

  test("parses parenthesized expression"):
    val result = SqlParser.parse("SELECT name FROM users WHERE (age > 18)")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Paren(_)) => () // ok
      case _                      => fail("Expected parenthesized expression")

  test("parses string literal in WHERE"):
    val result = SqlParser.parse("SELECT name FROM users WHERE name = 'Alice'")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Compare(_, CompareOp.Eq, SqlExpr.StringLit("Alice"))) => () // ok
      case _ => fail("Expected string comparison")

  test("parses boolean literals"):
    val result = SqlParser.parse("SELECT name FROM users WHERE active = TRUE")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Compare(_, CompareOp.Eq, SqlExpr.BoolLit(true))) => () // ok
      case _ => fail("Expected boolean comparison")

  test("parses arithmetic expressions"):
    val result = SqlParser.parse("SELECT salary + 1000 FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.Arithmetic(_, ArithOp.Add, _) => () // ok
      case _                                     => fail("Expected arithmetic expression")

  test("fails on missing FROM"):
    val result = SqlParser.parse("SELECT name users")
    assert(result.isLeft)

  test("fails on incomplete query"):
    val result = SqlParser.parse("SELECT name FROM")
    assert(result.isLeft)

  test("fails on invalid syntax"):
    val result = SqlParser.parse("SELECT FROM WHERE")
    assert(result.isLeft)

  // Phase 2 tests

  test("parses BETWEEN"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age BETWEEN 18 AND 65")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Between(_, _, _)) => () // ok
      case _                              => fail("Expected BETWEEN expression")

  test("parses NOT BETWEEN"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age NOT BETWEEN 18 AND 65")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.NotBetween(_, _, _)) => () // ok
      case _                                 => fail("Expected NOT BETWEEN expression")

  test("parses LIKE"):
    val result = SqlParser.parse("SELECT name FROM users WHERE name LIKE 'A%'")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Like(_, SqlExpr.StringLit("A%"), None)) => () // ok
      case _                                                    => fail("Expected LIKE expression")

  test("parses NOT LIKE"):
    val result = SqlParser.parse("SELECT name FROM users WHERE name NOT LIKE '%test%'")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.NotLike(_, _, _)) => () // ok
      case _                              => fail("Expected NOT LIKE expression")

  test("parses LIKE with ESCAPE"):
    val result = SqlParser.parse("SELECT name FROM users WHERE name LIKE 'A!%' ESCAPE '!'")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Like(_, _, Some(SqlExpr.StringLit("!")))) => () // ok
      case other => fail(s"Expected LIKE with ESCAPE expression, got $other")

  test("parses IN"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age IN (18, 21, 25)")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.In(_, list)) =>
        assertEquals(list.size, 3)
      case _ => fail("Expected IN expression")

  test("parses NOT IN"):
    val result = SqlParser.parse("SELECT name FROM users WHERE age NOT IN (1, 2, 3)")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.NotIn(_, _)) => () // ok
      case _                         => fail("Expected NOT IN expression")

  test("parses function call"):
    val result = SqlParser.parse("SELECT UPPER(name) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.FunctionCall("UPPER", args, false) =>
        assertEquals(args.size, 1)
      case _ => fail("Expected function call")

  test("parses function with multiple arguments"):
    val result = SqlParser.parse("SELECT SUBSTRING(name, 1, 5) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.FunctionCall("SUBSTRING", args, false) =>
        assertEquals(args.size, 3)
      case _ => fail("Expected function call with 3 args")

  test("parses COUNT with DISTINCT"):
    val result = SqlParser.parse("SELECT COUNT(DISTINCT name) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.FunctionCall("COUNT", _, true) => () // ok
      case _                                      => fail("Expected COUNT DISTINCT")

  test("parses nested function calls"):
    val result = SqlParser.parse("SELECT UPPER(LOWER(name)) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.FunctionCall("UPPER", Vector(SqlExpr.FunctionCall("LOWER", _, _)), _) => () // ok
      case _ => fail("Expected nested function calls")

  test("parses function call with no arguments"):
    val result = SqlParser.parse("SELECT NOW() FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.FunctionCall("NOW", Vector(), false) => () // ok
      case _ => fail("Expected function call with no args")

  // Phase 3 - JOIN tests

  test("parses INNER JOIN"):
    val result =
      SqlParser.parse("SELECT name FROM users INNER JOIN orders ON users.id = orders.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.Inner, Some(_)) => () // ok
      case _ => fail(s"Expected INNER JOIN, got ${stmt.from}")

  test("parses plain JOIN (defaults to INNER)"):
    val result = SqlParser.parse("SELECT name FROM users JOIN orders ON users.id = orders.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.Inner, Some(_)) => () // ok
      case _ => fail(s"Expected JOIN to default to INNER, got ${stmt.from}")

  test("parses LEFT JOIN"):
    val result =
      SqlParser.parse("SELECT name FROM users LEFT JOIN orders ON users.id = orders.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.LeftOuter, Some(_)) => () // ok
      case _ => fail(s"Expected LEFT JOIN, got ${stmt.from}")

  test("parses LEFT OUTER JOIN"):
    val result =
      SqlParser.parse("SELECT name FROM users LEFT OUTER JOIN orders ON users.id = orders.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.LeftOuter, Some(_)) => () // ok
      case _ => fail(s"Expected LEFT OUTER JOIN, got ${stmt.from}")

  test("parses RIGHT JOIN"):
    val result =
      SqlParser.parse("SELECT name FROM users RIGHT JOIN orders ON users.id = orders.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.RightOuter, Some(_)) => () // ok
      case _ => fail(s"Expected RIGHT JOIN, got ${stmt.from}")

  test("parses FULL OUTER JOIN"):
    val result =
      SqlParser.parse("SELECT name FROM users FULL OUTER JOIN orders ON users.id = orders.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.FullOuter, Some(_)) => () // ok
      case _ => fail(s"Expected FULL OUTER JOIN, got ${stmt.from}")

  test("parses CROSS JOIN"):
    val result = SqlParser.parse("SELECT name FROM users CROSS JOIN orders")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, _, JoinType.Cross, None) => () // ok
      case _ => fail(s"Expected CROSS JOIN without condition, got ${stmt.from}")

  test("parses multiple JOINs"):
    val result = SqlParser.parse("""
      SELECT u.name, o.total, p.name
      FROM users u
      INNER JOIN orders o ON u.id = o.user_id
      INNER JOIN products p ON o.product_id = p.id
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    // Should be Join(Join(Table, Table), Table)
    stmt.from match
      case FromClause.Join(FromClause.Join(_, _, _, _), _, _, _) => () // ok
      case _ => fail(s"Expected nested JOINs, got ${stmt.from}")

  test("parses JOIN with table aliases"):
    val result = SqlParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(
            FromClause.Table(TableRef("users", Some("u"))),
            FromClause.Table(TableRef("orders", Some("o"))),
            JoinType.Inner,
            Some(_)
          ) =>
        () // ok
      case _ => fail(s"Expected JOIN with aliases, got ${stmt.from}")

  // Phase 4 - GROUP BY and HAVING tests

  test("parses simple GROUP BY"):
    val result = SqlParser.parse("SELECT name, COUNT(*) FROM users GROUP BY name")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.groupBy.size, 1)
    stmt.groupBy.head match
      case SqlExpr.ColumnRef("name", None) => () // ok
      case _                               => fail("Expected column ref in GROUP BY")

  test("parses GROUP BY with multiple columns"):
    val result = SqlParser.parse("SELECT name, age, COUNT(*) FROM users GROUP BY name, age")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.groupBy.size, 2)

  test("parses GROUP BY with HAVING"):
    val result =
      SqlParser.parse("SELECT name, COUNT(*) FROM users GROUP BY name HAVING COUNT(*) > 5")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.groupBy.size, 1)
    assert(stmt.having.isDefined)
    stmt.having match
      case Some(
            SqlExpr.Compare(SqlExpr.FunctionCall("COUNT", _, _), CompareOp.Gt, SqlExpr.IntLit(5))
          ) =>
        () // ok
      case _ => fail(s"Expected HAVING with COUNT comparison, got ${stmt.having}")

  test("parses GROUP BY with aggregate functions"):
    val result = SqlParser.parse(
      "SELECT name, SUM(salary), AVG(age), MIN(age), MAX(age) FROM users GROUP BY name"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 5)
    assertEquals(stmt.groupBy.size, 1)

  test("parses complete GROUP BY query"):
    val result = SqlParser.parse("""
      SELECT name, COUNT(*) AS cnt
      FROM users
      WHERE age > 18
      GROUP BY name
      HAVING COUNT(*) > 5
      ORDER BY cnt DESC
      LIMIT 10
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 2)
    assertEquals(stmt.projections(1).alias, Some("cnt"))
    assert(stmt.where.isDefined)
    assertEquals(stmt.groupBy.size, 1)
    assert(stmt.having.isDefined)
    assertEquals(stmt.orderBy.size, 1)
    assertEquals(stmt.limit, Some(10L))

  // Phase 5 - CASE WHEN and CAST tests

  test("parses simple CASE WHEN"):
    val result =
      SqlParser.parse("SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.CaseWhen(branches, Some(SqlExpr.StringLit("minor"))) =>
        assertEquals(branches.size, 1)
      case _ => fail("Expected CASE WHEN expression")

  test("parses CASE WHEN with multiple branches"):
    val result = SqlParser.parse("""
      SELECT CASE
        WHEN age < 13 THEN 'child'
        WHEN age < 20 THEN 'teen'
        WHEN age < 65 THEN 'adult'
        ELSE 'senior'
      END FROM users
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.CaseWhen(branches, Some(_)) =>
        assertEquals(branches.size, 3)
      case _ => fail("Expected CASE WHEN with 3 branches")

  test("parses CASE WHEN without ELSE"):
    val result = SqlParser.parse("SELECT CASE WHEN active THEN 'yes' END FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.CaseWhen(branches, None) =>
        assertEquals(branches.size, 1)
      case _ => fail("Expected CASE WHEN without ELSE")

  test("parses CAST to INT"):
    val result = SqlParser.parse("SELECT CAST(age AS INT) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.Cast(SqlExpr.ColumnRef("age", None), SqlType.IntType) => () // ok
      case _ => fail("Expected CAST to INT")

  test("parses CAST to VARCHAR"):
    val result = SqlParser.parse("SELECT CAST(age AS VARCHAR) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.Cast(_, SqlType.StringType) => () // ok
      case _                                   => fail("Expected CAST to STRING")

  test("parses CAST to DOUBLE"):
    val result = SqlParser.parse("SELECT CAST(salary AS DOUBLE) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.Cast(_, SqlType.DoubleType) => () // ok
      case _                                   => fail("Expected CAST to DOUBLE")

  test("parses CAST to TIMESTAMP"):
    val result = SqlParser.parse("SELECT CAST(created AS TIMESTAMP) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.Cast(_, SqlType.TimestampType) => () // ok
      case _                                      => fail("Expected CAST to TIMESTAMP")

  test("parses nested CASE and CAST"):
    val result = SqlParser.parse("""
      SELECT CASE
        WHEN CAST(age AS INT) > 18 THEN 'adult'
        ELSE 'minor'
      END FROM users
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.CaseWhen(branches, _) =>
        branches.head._1 match
          case SqlExpr.Compare(SqlExpr.Cast(_, _), _, _) => () // ok
          case _ => fail("Expected CAST inside CASE WHEN condition")
      case _ => fail("Expected CASE WHEN")

  // Phase 6 - Subquery tests

  test("parses scalar subquery"):
    val result = SqlParser.parse("SELECT name, (SELECT MAX(age) FROM users) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 2)
    stmt.projections(1).expr match
      case SqlExpr.ScalarSubquery(_) => () // ok
      case _                         => fail("Expected scalar subquery")

  test("parses EXISTS subquery"):
    val result = SqlParser.parse(
      "SELECT name FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.Exists(_)) => () // ok
      case _                       => fail("Expected EXISTS expression")

  test("parses NOT EXISTS subquery"):
    val result = SqlParser.parse(
      "SELECT name FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.NotExists(_)) => () // ok
      case _                          => fail("Expected NOT EXISTS expression")

  test("parses IN subquery"):
    val result = SqlParser.parse("SELECT name FROM users WHERE id IN (SELECT user_id FROM orders)")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.InSubquery(_, _)) => () // ok
      case _                              => fail("Expected IN subquery expression")

  test("parses NOT IN subquery"):
    val result =
      SqlParser.parse("SELECT name FROM users WHERE id NOT IN (SELECT user_id FROM blacklist)")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.where match
      case Some(SqlExpr.NotInSubquery(_, _)) => () // ok
      case _                                 => fail("Expected NOT IN subquery expression")

  test("parses FROM subquery (derived table)"):
    val result = SqlParser.parse("SELECT sub.name FROM (SELECT name FROM users) AS sub")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Subquery(innerStmt, alias) =>
        assertEquals(alias, "sub")
        assertEquals(innerStmt.projections.size, 1)
      case _ => fail("Expected subquery in FROM clause")

  test("parses JOIN with subquery"):
    val result = SqlParser.parse("""
      SELECT u.name, o.total
      FROM users u
      JOIN (SELECT user_id, SUM(amount) AS total FROM orders GROUP BY user_id) AS o
      ON u.id = o.user_id
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.from match
      case FromClause.Join(_, FromClause.Subquery(_, alias), _, _) =>
        assertEquals(alias, "o")
      case _ => fail("Expected JOIN with subquery")

  // Phase 7 - Set Operations tests (UNION, INTERSECT, EXCEPT)

  test("parses UNION"):
    val result = SqlParser.parse("SELECT name FROM users UNION SELECT name FROM orders")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Union(false), _) => () // ok
      case _ => fail("Expected UNION compound statement")

  test("parses UNION ALL"):
    val result = SqlParser.parse("SELECT name FROM users UNION ALL SELECT name FROM orders")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Union(true), _) => () // ok
      case _ => fail("Expected UNION ALL compound statement")

  test("parses INTERSECT"):
    val result = SqlParser.parse("SELECT name FROM users INTERSECT SELECT name FROM orders")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Intersect(false), _) => () // ok
      case _ => fail("Expected INTERSECT compound statement")

  test("parses INTERSECT ALL"):
    val result = SqlParser.parse("SELECT name FROM users INTERSECT ALL SELECT name FROM orders")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Intersect(true), _) => () // ok
      case _ => fail("Expected INTERSECT ALL compound statement")

  test("parses EXCEPT"):
    val result = SqlParser.parse("SELECT name FROM users EXCEPT SELECT name FROM orders")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Except(false), _) => () // ok
      case _ => fail("Expected EXCEPT compound statement")

  test("parses EXCEPT ALL"):
    val result = SqlParser.parse("SELECT name FROM users EXCEPT ALL SELECT name FROM orders")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Except(true), _) => () // ok
      case _ => fail("Expected EXCEPT ALL compound statement")

  test("parses chained UNION"):
    val result = SqlParser.parse("SELECT a FROM t1 UNION SELECT b FROM t2 UNION SELECT c FROM t3")

    assert(result.isRight)
    // Chained unions are left-associative: (t1 UNION t2) UNION t3
    result.toOption.get match
      case SqlStatement.CompoundStatement(
            SqlStatement.CompoundStatement(_, SetOperation.Union(_), _),
            SetOperation.Union(_),
            _
          ) =>
        () // ok
      case _ => fail("Expected nested UNION compound statements")

  test("parses mixed set operations"):
    val result =
      SqlParser.parse("SELECT a FROM t1 UNION SELECT b FROM t2 INTERSECT SELECT c FROM t3")

    assert(result.isRight)
    // No precedence differentiation - left-associative: (t1 UNION t2) INTERSECT t3
    result.toOption.get match
      case SqlStatement.CompoundStatement(
            SqlStatement.CompoundStatement(_, SetOperation.Union(_), _),
            SetOperation.Intersect(_),
            _
          ) =>
        () // ok
      case _ => fail("Expected mixed set operations")

  test("parses set operation with ORDER BY"):
    // ORDER BY after UNION applies to the whole result in standard SQL
    // Our parser parses ORDER BY with the final SELECT for simplicity
    val result =
      SqlParser.parse("SELECT name FROM users UNION SELECT name FROM orders ORDER BY name")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Union(_), _) =>
        () // UNION parsed correctly
      case SqlStatement.SelectStatement(_, _, _, _, orderBy, _, _, _) if orderBy.nonEmpty =>
        () // Parser may attach ORDER BY to result
      case other => fail(s"Expected UNION compound statement, got $other")

  test("parses set operation with LIMIT"):
    // LIMIT after UNION applies to the whole result in standard SQL
    val result = SqlParser.parse("SELECT name FROM users UNION SELECT name FROM orders LIMIT 10")

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(_, SetOperation.Union(_), _) =>
        () // UNION parsed correctly
      case SqlStatement.SelectStatement(_, _, _, _, _, Some(_), _, _) =>
        () // Parser may attach LIMIT to result
      case other => fail(s"Expected UNION compound statement, got $other")

  test("parses set operation with WHERE clauses"):
    val result = SqlParser.parse(
      "SELECT name FROM users WHERE age > 18 UNION SELECT name FROM orders WHERE total > 100"
    )

    assert(result.isRight)
    result.toOption.get match
      case SqlStatement.CompoundStatement(left, SetOperation.Union(_), right) =>
        (left, right) match
          case (
                SqlStatement.SelectStatement(_, _, _, Some(_), _, _, _, _),
                SqlStatement.SelectStatement(_, _, _, Some(_), _, _, _, _)
              ) =>
            () // ok - both have WHERE clauses
          case _ => fail("Expected both sides to have WHERE clauses")
      case _ => fail("Expected UNION compound statement")

  // Phase 8 - Window Functions tests

  test("parses ROW_NUMBER window function"):
    val result = SqlParser.parse("SELECT name, ROW_NUMBER() OVER (ORDER BY age) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 2)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("ROW_NUMBER", Vector(), false), spec) =>
        assertEquals(spec.partitionBy, Vector.empty)
        assertEquals(spec.orderBy.size, 1)
      case _ => fail("Expected ROW_NUMBER window function")

  test("parses RANK window function"):
    val result = SqlParser.parse("SELECT name, RANK() OVER (ORDER BY salary DESC) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("RANK", Vector(), false), spec) =>
        assertEquals(spec.orderBy.size, 1)
        assertEquals(spec.orderBy.head.ascending, false)
      case _ => fail("Expected RANK window function")

  test("parses DENSE_RANK window function"):
    val result = SqlParser.parse(
      "SELECT name, DENSE_RANK() OVER (PARTITION BY department ORDER BY salary) FROM users"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("DENSE_RANK", Vector(), false), spec) =>
        assertEquals(spec.partitionBy.size, 1)
        assertEquals(spec.orderBy.size, 1)
      case _ => fail("Expected DENSE_RANK window function with PARTITION BY")

  test("parses SUM window function"):
    val result =
      SqlParser.parse("SELECT name, SUM(salary) OVER (PARTITION BY department) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("SUM", Vector(_), false), spec) =>
        assertEquals(spec.partitionBy.size, 1)
        assertEquals(spec.orderBy, Vector.empty)
      case _ => fail("Expected SUM window function")

  test("parses LEAD window function"):
    val result =
      SqlParser.parse("SELECT name, LEAD(salary, 1) OVER (ORDER BY hire_date) FROM users")

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("LEAD", args, false), _) =>
        assertEquals(args.size, 2)
      case _ => fail("Expected LEAD window function")

  test("parses LAG window function"):
    val result = SqlParser.parse(
      "SELECT name, LAG(salary) OVER (PARTITION BY department ORDER BY hire_date) FROM users"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("LAG", args, false), spec) =>
        assertEquals(args.size, 1)
        assertEquals(spec.partitionBy.size, 1)
        assertEquals(spec.orderBy.size, 1)
      case _ => fail("Expected LAG window function")

  test("parses window function with ROWS frame"):
    val result = SqlParser.parse(
      "SELECT SUM(salary) OVER (ORDER BY hire_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM users"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.WindowFunction(_, spec) =>
        spec.frame match
          case Some(
                WindowFrame(FrameType.Rows, FrameBound.UnboundedPreceding, FrameBound.CurrentRow)
              ) =>
            () // ok
          case _ => fail(s"Expected ROWS frame, got ${spec.frame}")
      case _ => fail("Expected window function")

  test("parses window function with RANGE frame"):
    val result = SqlParser.parse(
      "SELECT AVG(salary) OVER (ORDER BY age RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM users"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.WindowFunction(_, spec) =>
        spec.frame match
          case Some(
                WindowFrame(
                  FrameType.Range,
                  FrameBound.UnboundedPreceding,
                  FrameBound.UnboundedFollowing
                )
              ) =>
            () // ok
          case _ => fail(s"Expected RANGE frame, got ${spec.frame}")
      case _ => fail("Expected window function")

  test("parses window function with numeric frame bounds"):
    val result = SqlParser.parse(
      "SELECT SUM(amount) OVER (ORDER BY date ROWS BETWEEN 3 PRECEDING AND 1 FOLLOWING) FROM orders"
    )

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    stmt.projections.head.expr match
      case SqlExpr.WindowFunction(_, spec) =>
        spec.frame match
          case Some(
                WindowFrame(FrameType.Rows, FrameBound.Preceding(3), FrameBound.Following(1))
              ) =>
            () // ok
          case _ => fail(s"Expected numeric frame bounds, got ${spec.frame}")
      case _ => fail("Expected window function")

  test("parses multiple window functions"):
    val result = SqlParser.parse("""
      SELECT
        name,
        ROW_NUMBER() OVER (ORDER BY hire_date) AS row_num,
        RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS salary_rank
      FROM users
    """)

    assert(result.isRight)
    val stmt = asSelect(result.toOption.get)
    assertEquals(stmt.projections.size, 3)
    stmt.projections(1).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("ROW_NUMBER", _, _), _) => () // ok
      case _ => fail("Expected first window function to be ROW_NUMBER")
    stmt.projections(2).expr match
      case SqlExpr.WindowFunction(SqlExpr.FunctionCall("RANK", _, _), _) => () // ok
      case _ => fail("Expected second window function to be RANK")

  // ============================================
  // CTE (Common Table Expression) Tests
  // ============================================

  // Helper to extract WithStatement
  def asWithStmt(stmt: SqlStatement): SqlStatement.WithStatement = stmt match
    case w: SqlStatement.WithStatement => w
    case _ => fail("Expected WithStatement").asInstanceOf[SqlStatement.WithStatement]

  test("parses simple CTE"):
    val result = SqlParser.parse("""
      WITH active_users AS (
        SELECT * FROM users WHERE active = true
      )
      SELECT * FROM active_users
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 1)
    assertEquals(stmt.ctes.head.name, "active_users")
    assertEquals(stmt.ctes.head.columnAliases, None)
    assertEquals(stmt.recursive, false)

  test("parses CTE with column aliases"):
    val result = SqlParser.parse("""
      WITH user_summary (user_name, total_orders) AS (
        SELECT name, COUNT(*) FROM users GROUP BY name
      )
      SELECT * FROM user_summary
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 1)
    assertEquals(stmt.ctes.head.name, "user_summary")
    assertEquals(stmt.ctes.head.columnAliases, Some(Vector("user_name", "total_orders")))

  test("parses multiple CTEs"):
    val result = SqlParser.parse("""
      WITH
        active_users AS (
          SELECT * FROM users WHERE active = true
        ),
        premium_users AS (
          SELECT * FROM users WHERE premium = true
        )
      SELECT * FROM active_users JOIN premium_users ON active_users.id = premium_users.id
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 2)
    assertEquals(stmt.ctes(0).name, "active_users")
    assertEquals(stmt.ctes(1).name, "premium_users")

  test("parses RECURSIVE CTE"):
    val result = SqlParser.parse("""
      WITH RECURSIVE cte AS (
        SELECT * FROM users WHERE id = 1
      )
      SELECT * FROM cte
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.recursive, true)
    assertEquals(stmt.ctes.size, 1)
    assertEquals(stmt.ctes.head.name, "cte")

  test("parses CTE with complex inner query"):
    val result = SqlParser.parse("""
      WITH department_stats AS (
        SELECT department, AVG(salary) AS avg_salary, COUNT(*) AS employee_count
        FROM users
        WHERE active = true
        GROUP BY department
        HAVING COUNT(*) > 5
      )
      SELECT * FROM department_stats WHERE avg_salary > 50000
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 1)
    // Verify the inner query has GROUP BY and HAVING
    stmt.ctes.head.query match
      case s: SqlStatement.SelectStatement =>
        assert(s.groupBy.nonEmpty)
        assert(s.having.isDefined)
      case _ => fail("Expected SelectStatement in CTE")

  test("parses CTE referencing another CTE"):
    val result = SqlParser.parse("""
      WITH
        first_cte AS (
          SELECT id, name FROM users
        ),
        second_cte AS (
          SELECT * FROM first_cte WHERE id > 10
        )
      SELECT * FROM second_cte
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 2)
    assertEquals(stmt.ctes(0).name, "first_cte")
    assertEquals(stmt.ctes(1).name, "second_cte")
    // Verify second CTE references first_cte
    stmt.ctes(1).query match
      case s: SqlStatement.SelectStatement =>
        assertEquals(extractTableName(s.from), "first_cte")
      case _ => fail("Expected SelectStatement in CTE")

  test("parses CTE with main query using ORDER BY and LIMIT"):
    val result = SqlParser.parse("""
      WITH top_earners AS (
        SELECT * FROM users WHERE salary > 100000
      )
      SELECT name, salary FROM top_earners ORDER BY salary DESC LIMIT 10
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    // Verify main query has ORDER BY and LIMIT
    stmt.query match
      case s: SqlStatement.SelectStatement =>
        assert(s.orderBy.nonEmpty)
        assertEquals(s.limit, Some(10L))
      case _ => fail("Expected SelectStatement as main query")

  test("parses CTE with UNION in main query"):
    val result = SqlParser.parse("""
      WITH active AS (
        SELECT * FROM users WHERE active = true
      )
      SELECT name FROM active WHERE age > 30
      UNION
      SELECT name FROM active WHERE salary > 50000
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 1)
    // Main query should be a CompoundStatement
    stmt.query match
      case SqlStatement.CompoundStatement(_, SetOperation.Union(_), _) => () // ok
      case _ => fail("Expected CompoundStatement with UNION")

  test("parses CTE with JOIN in inner query"):
    val result = SqlParser.parse("""
      WITH user_orders AS (
        SELECT u.name, o.total
        FROM users u
        JOIN orders o ON u.id = o.user_id
      )
      SELECT * FROM user_orders
    """)

    assert(result.isRight)
    val stmt = asWithStmt(result.toOption.get)
    assertEquals(stmt.ctes.size, 1)
    // Verify inner query has JOIN
    stmt.ctes.head.query match
      case s: SqlStatement.SelectStatement =>
        s.from match
          case FromClause.Join(_, _, _, _) => () // ok
          case _                           => fail("Expected JOIN in CTE")
      case _ => fail("Expected SelectStatement in CTE")
