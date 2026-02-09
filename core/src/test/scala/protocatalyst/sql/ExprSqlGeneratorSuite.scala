package protocatalyst.sql

import scala.collection.immutable

import protocatalyst.expr._
import protocatalyst.plan.{NullOrdering, SortDirection, SortOrder}
import protocatalyst.types.{LiteralValue, ProtoType}

class ExprSqlGeneratorSuite extends munit.FunSuite:
  import ExprSqlGenerator.generate

  // ========== Literals ==========
  test("Literal NULL"):
    val expr = ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.IntegerType))
    assertEquals(generate(expr), "NULL")

  test("Literal Boolean TRUE"):
    val expr = ProtoExpr.Literal(LiteralValue.BooleanValue(true))
    assertEquals(generate(expr), "TRUE")

  test("Literal Boolean FALSE"):
    val expr = ProtoExpr.Literal(LiteralValue.BooleanValue(false))
    assertEquals(generate(expr), "FALSE")

  test("Literal Integer"):
    val expr = ProtoExpr.lit(42)
    assertEquals(generate(expr), "42")

  test("Literal Long"):
    val expr = ProtoExpr.lit(9876543210L)
    assertEquals(generate(expr), "9876543210")

  test("Literal Float"):
    val expr = ProtoExpr.Literal(LiteralValue.FloatValue(3.14f))
    assertEquals(generate(expr), "3.14")

  test("Literal Double"):
    val expr = ProtoExpr.lit(2.718)
    assertEquals(generate(expr), "2.718")

  test("Literal Float NaN"):
    val expr = ProtoExpr.Literal(LiteralValue.FloatValue(Float.NaN))
    assertEquals(generate(expr), "'NaN'::REAL")

  test("Literal Double Infinity"):
    val expr = ProtoExpr.Literal(LiteralValue.DoubleValue(Double.PositiveInfinity))
    assertEquals(generate(expr), "'Infinity'::DOUBLE")

  test("Literal String"):
    val expr = ProtoExpr.lit("hello")
    assertEquals(generate(expr), "'hello'")

  test("Literal String with single quotes"):
    val expr = ProtoExpr.lit("it's")
    assertEquals(generate(expr), "'it''s'")

  test("Literal Binary"):
    val bytes = immutable.ArraySeq.unsafeWrapArray(Array[Byte](0x68, 0x65, 0x6c, 0x6c, 0x6f))
    val expr = ProtoExpr.Literal(LiteralValue.BinaryValue(bytes))
    assertEquals(generate(expr), "X'68656c6c6f'")

  // ========== Column References ==========
  test("ColumnRef without qualifier"):
    val expr = ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    assertEquals(generate(expr), "name")

  test("ColumnRef with qualifier"):
    val expr = ProtoExpr.ColumnRef("age", Some("users"), ProtoType.IntegerType, nullable = false)
    assertEquals(generate(expr), "users.age")

  test("BoundRef"):
    val expr = ProtoExpr.BoundRef(0, ProtoType.StringType, nullable = true)
    // BoundRef generates positional reference syntax
    assert(generate(expr).nonEmpty)

  // ========== Comparison Operators ==========
  test("Eq operator"):
    val expr = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
      ProtoExpr.lit(25)
    )
    assertEquals(generate(expr), "age = 25")

  test("NotEq operator"):
    val expr = ProtoExpr.NotEq(
      ProtoExpr.ColumnRef("status", None, ProtoType.StringType, nullable = true),
      ProtoExpr.lit("inactive")
    )
    assertEquals(generate(expr), "status <> 'inactive'")

  test("Lt operator"):
    val expr = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
      ProtoExpr.lit(30)
    )
    assertEquals(generate(expr), "age < 30")

  test("LtEq operator"):
    val expr = ProtoExpr.LtEq(
      ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
      ProtoExpr.lit(30)
    )
    assertEquals(generate(expr), "age <= 30")

  test("Gt operator"):
    val expr = ProtoExpr.Gt(
      ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(50000.0)
    )
    assertEquals(generate(expr), "salary > 50000.0")

  test("GtEq operator"):
    val expr = ProtoExpr.GtEq(
      ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(50000.0)
    )
    assertEquals(generate(expr), "salary >= 50000.0")

  // ========== Logical Operators ==========
  test("And operator"):
    val expr = ProtoExpr.And(
      Vector(
        ProtoExpr.Gt(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
          ProtoExpr.lit(25)
        ),
        ProtoExpr.Lt(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
          ProtoExpr.lit(50)
        )
      )
    )
    assertEquals(generate(expr), "(age > 25) AND (age < 50)")

  test("Or operator"):
    val expr = ProtoExpr.Or(
      Vector(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("status", None, ProtoType.StringType, nullable = true),
          ProtoExpr.lit("active")
        ),
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("status", None, ProtoType.StringType, nullable = true),
          ProtoExpr.lit("pending")
        )
      )
    )
    assertEquals(generate(expr), "(status = 'active') OR (status = 'pending')")

  test("Not operator"):
    val expr = ProtoExpr.Not(
      ProtoExpr.IsNull(ProtoExpr.ColumnRef("email", None, ProtoType.StringType, nullable = true))
    )
    assertEquals(generate(expr), "NOT (email IS NULL)")

  // ========== Arithmetic Operators ==========
  test("Add operator"):
    val expr = ProtoExpr.Add(
      ProtoExpr.ColumnRef("price", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(10.0)
    )
    assertEquals(generate(expr), "price + 10.0")

  test("Subtract operator"):
    val expr = ProtoExpr.Subtract(
      ProtoExpr.ColumnRef("balance", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(5.0)
    )
    assertEquals(generate(expr), "balance - 5.0")

  test("Multiply operator"):
    val expr = ProtoExpr.Multiply(
      ProtoExpr.ColumnRef("quantity", None, ProtoType.IntegerType, nullable = false),
      ProtoExpr.lit(2)
    )
    assertEquals(generate(expr), "quantity * 2")

  test("Divide operator"):
    val expr = ProtoExpr.Divide(
      ProtoExpr.ColumnRef("total", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(3.0)
    )
    assertEquals(generate(expr), "total / 3.0")

  test("Pmod operator"):
    val expr = ProtoExpr.Pmod(
      ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, nullable = false),
      ProtoExpr.lit(10)
    )
    assertEquals(generate(expr), "PMOD(id, 10)")

  // ========== Null Checks ==========
  test("IsNull"):
    val expr = ProtoExpr.IsNull(
      ProtoExpr.ColumnRef("email", None, ProtoType.StringType, nullable = true)
    )
    assertEquals(generate(expr), "email IS NULL")

  test("IsNotNull"):
    val expr = ProtoExpr.IsNotNull(
      ProtoExpr.ColumnRef("email", None, ProtoType.StringType, nullable = true)
    )
    assertEquals(generate(expr), "email IS NOT NULL")

  test("Coalesce"):
    val expr = ProtoExpr.Coalesce(
      Vector(
        ProtoExpr.ColumnRef("email", None, ProtoType.StringType, nullable = true),
        ProtoExpr.lit("no-email@example.com")
      )
    )
    assertEquals(generate(expr), "COALESCE(email, 'no-email@example.com')")

  test("NullIf"):
    val expr = ProtoExpr.NullIf(
      ProtoExpr.ColumnRef("status", None, ProtoType.StringType, nullable = true),
      ProtoExpr.lit("")
    )
    assertEquals(generate(expr), "NULLIF(status, '')")

  // ========== String Functions ==========
  test("Like"):
    val expr = ProtoExpr.Like(
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
      ProtoExpr.lit("John%"),
      None
    )
    assertEquals(generate(expr), "name LIKE 'John%'")

  test("Concat"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.ColumnRef("first_name", None, ProtoType.StringType, nullable = true),
        ProtoExpr.lit(" "),
        ProtoExpr.ColumnRef("last_name", None, ProtoType.StringType, nullable = true)
      )
    )
    assertEquals(generate(expr), "CONCAT(first_name, ' ', last_name)")

  test("Upper"):
    val expr = ProtoExpr.Upper(
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    )
    assertEquals(generate(expr), "UPPER(name)")

  test("Lower"):
    val expr = ProtoExpr.Lower(
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    )
    assertEquals(generate(expr), "LOWER(name)")

  test("Substring"):
    val expr = ProtoExpr.Substring(
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
      ProtoExpr.lit(1),
      ProtoExpr.lit(5)
    )
    assertEquals(generate(expr), "SUBSTRING(name, 1, 5)")

  test("Trim"):
    val expr = ProtoExpr.Trim(
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
      None,
      TrimType.Both
    )
    assertEquals(generate(expr), "TRIM(name)")

  test("Replace"):
    val expr = ProtoExpr.Replace(
      ProtoExpr.ColumnRef("text", None, ProtoType.StringType, nullable = true),
      ProtoExpr.lit("old"),
      ProtoExpr.lit("new")
    )
    assertEquals(generate(expr), "REPLACE(text, 'old', 'new')")

  test("Length"):
    val expr = ProtoExpr.Length(
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    )
    assertEquals(generate(expr), "LENGTH(name)")

  // ========== Math Functions ==========
  test("Abs"):
    val expr = ProtoExpr.Abs(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "ABS(value)")

  test("Ceil"):
    val expr = ProtoExpr.Ceil(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "CEIL(value)")

  test("Floor"):
    val expr = ProtoExpr.Floor(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "FLOOR(value)")

  test("Round"):
    val expr = ProtoExpr.Round(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(2)
    )
    assertEquals(generate(expr), "ROUND(value, 2)")

  test("Sqrt"):
    val expr = ProtoExpr.Sqrt(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "SQRT(value)")

  test("Pow"):
    val expr = ProtoExpr.Pow(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false),
      ProtoExpr.lit(2.0)
    )
    assertEquals(generate(expr), "POWER(value, 2.0)")

  test("Exp"):
    val expr = ProtoExpr.Exp(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "EXP(value)")

  test("Log with base"):
    val expr = ProtoExpr.Log(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false),
      Some(ProtoExpr.lit(2.0))
    )
    assertEquals(generate(expr), "LOG(2.0, value)")

  test("Log without base (natural log)"):
    val expr = ProtoExpr.Log(
      ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false),
      None
    )
    assertEquals(generate(expr), "LN(value)")

  // ========== Aggregates ==========
  test("Count(*)"):
    val expr = ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), distinct = false)
    val sql = generate(expr)
    assert(sql.contains("COUNT"))

  test("Count DISTINCT"):
    val expr = ProtoExpr.Count(
      ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, nullable = false),
      distinct = true
    )
    assertEquals(generate(expr), "COUNT(DISTINCT user_id)")

  test("Sum"):
    val expr = ProtoExpr.Sum(
      ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "SUM(amount)")

  test("Avg"):
    val expr = ProtoExpr.Avg(
      ProtoExpr.ColumnRef("score", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "AVG(score)")

  test("Min"):
    val expr = ProtoExpr.Min(
      ProtoExpr.ColumnRef("price", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "MIN(price)")

  test("Max"):
    val expr = ProtoExpr.Max(
      ProtoExpr.ColumnRef("price", None, ProtoType.DoubleType, nullable = false)
    )
    assertEquals(generate(expr), "MAX(price)")

  // ========== Date/Time Functions ==========
  test("CurrentDate"):
    assertEquals(generate(ProtoExpr.CurrentDate()), "CURRENT_DATE")

  test("CurrentTimestamp"):
    assertEquals(generate(ProtoExpr.CurrentTimestamp()), "CURRENT_TIMESTAMP")

  test("DateAdd"):
    val expr = ProtoExpr.DateAdd(
      ProtoExpr.ColumnRef("start_date", None, ProtoType.DateType, nullable = false),
      ProtoExpr.lit(7)
    )
    assertEquals(generate(expr), "DATE_ADD(start_date, 7)")

  test("DateDiff"):
    val expr = ProtoExpr.DateDiff(
      ProtoExpr.ColumnRef("end_date", None, ProtoType.DateType, nullable = false),
      ProtoExpr.ColumnRef("start_date", None, ProtoType.DateType, nullable = false)
    )
    assertEquals(generate(expr), "DATEDIFF(end_date, start_date)")

  test("Extract Year"):
    val expr = ProtoExpr.Extract(
      DateTimeField.Year,
      ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false)
    )
    assertEquals(generate(expr), "EXTRACT(YEAR FROM date)")

  test("Year"):
    val expr = ProtoExpr.Year(
      ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false)
    )
    assertEquals(generate(expr), "YEAR(date)")

  test("Month"):
    val expr = ProtoExpr.Month(
      ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false)
    )
    assertEquals(generate(expr), "MONTH(date)")

  test("Day"):
    val expr = ProtoExpr.DayOfMonth(
      ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false)
    )
    assertEquals(generate(expr), "DAY(date)")

  // ========== Cast ==========
  test("Cast to INTEGER"):
    val expr = ProtoExpr.Cast(
      ProtoExpr.ColumnRef("value", None, ProtoType.StringType, nullable = true),
      ProtoType.IntegerType
    )
    assertEquals(generate(expr), "CAST(value AS INTEGER)")

  test("Cast to VARCHAR"):
    val expr = ProtoExpr.Cast(
      ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, nullable = false),
      ProtoType.StringType
    )
    assertEquals(generate(expr), "CAST(id AS VARCHAR)")

  // ========== Alias ==========
  test("Alias"):
    val expr = ProtoExpr.Alias(
      ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, nullable = false),
      "id"
    )
    assertEquals(generate(expr), "user_id AS id")

  // ========== CaseWhen ==========
  test("CaseWhen without else"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (
          ProtoExpr.Gt(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
            ProtoExpr.lit(18)
          ),
          ProtoExpr.lit("adult")
        )
      ),
      None
    )
    assertEquals(generate(expr), "CASE WHEN age > 18 THEN 'adult' END")

  test("CaseWhen with else"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (
          ProtoExpr.Gt(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
            ProtoExpr.lit(18)
          ),
          ProtoExpr.lit("adult")
        ),
        (
          ProtoExpr.Gt(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
            ProtoExpr.lit(12)
          ),
          ProtoExpr.lit("teen")
        )
      ),
      Some(ProtoExpr.lit("child"))
    )
    assertEquals(
      generate(expr),
      "CASE WHEN age > 18 THEN 'adult' WHEN age > 12 THEN 'teen' ELSE 'child' END"
    )

  test("If"):
    val expr = ProtoExpr.If(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(18)
      ),
      ProtoExpr.lit("adult"),
      ProtoExpr.lit("minor")
    )
    assertEquals(generate(expr), "CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END")

  // ========== In ==========
  test("In"):
    val expr = ProtoExpr.In(
      ProtoExpr.ColumnRef("status", None, ProtoType.StringType, nullable = true),
      Vector(ProtoExpr.lit("active"), ProtoExpr.lit("pending"), ProtoExpr.lit("approved"))
    )
    assertEquals(generate(expr), "status IN ('active', 'pending', 'approved')")

  // ========== Window Functions ==========
  test("RowNumber"):
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.RowNumber(),
      Vector(ProtoExpr.ColumnRef("department", None, ProtoType.StringType, nullable = false)),
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      None
    )
    assertEquals(
      generate(expr),
      "ROW_NUMBER() OVER ( PARTITION BY department ORDER BY salary DESC NULLS LAST)"
    )

  test("Rank"):
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.Rank(),
      Vector(ProtoExpr.ColumnRef("category", None, ProtoType.StringType, nullable = false)),
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("score", None, ProtoType.IntegerType, nullable = false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      None
    )
    assertEquals(
      generate(expr),
      "RANK() OVER ( PARTITION BY category ORDER BY score DESC NULLS LAST)"
    )

  test("DenseRank"):
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.DenseRank(),
      Vector(ProtoExpr.ColumnRef("category", None, ProtoType.StringType, nullable = false)),
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("score", None, ProtoType.IntegerType, nullable = false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      None
    )
    assertEquals(
      generate(expr),
      "DENSE_RANK() OVER ( PARTITION BY category ORDER BY score DESC NULLS LAST)"
    )

  test("Lead"):
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.Lead(
        ProtoExpr.ColumnRef("value", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(1),
        None
      ),
      Vector.empty,
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false),
          SortDirection.Ascending,
          NullOrdering.NullsLast
        )
      ),
      None
    )
    assertEquals(generate(expr), "LEAD(value, 1) OVER ( ORDER BY date ASC NULLS LAST)")

  test("Lag"):
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.Lag(
        ProtoExpr.ColumnRef("value", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(1),
        None
      ),
      Vector.empty,
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false),
          SortDirection.Ascending,
          NullOrdering.NullsLast
        )
      ),
      None
    )
    assertEquals(generate(expr), "LAG(value, 1) OVER ( ORDER BY date ASC NULLS LAST)")

  // ========== Generators ==========
  test("Explode"):
    val expr = ProtoExpr.Explode(
      ProtoExpr.ColumnRef(
        "tags",
        None,
        ProtoType.ArrayType(ProtoType.StringType, true),
        nullable = false
      )
    )
    // Explode may or may not generate SQL depending on implementation
    val sql = generate(expr)
    assert(sql.nonEmpty)

end ExprSqlGeneratorSuite
