# SQL Parser Implementation

This document tracks the implementation progress of the compile-time SQL parser for ProtoCatalyst.

## Overview

The SQL parser transforms SQL strings into `ProtoLogicalPlan` and `ProtoExpr` structures at compile time using Scala 3 macros. This enables type-safe, serializable query representations that can be validated against schemas before runtime.

## Architecture

```
sql-parser/
├── src/main/scala/protocatalyst/sql/
│   ├── lexer/
│   │   ├── Token.scala           # Token definitions
│   │   └── Lexer.scala           # Tokenization
│   ├── parser/
│   │   ├── SqlParser.scala       # Recursive descent parser
│   │   └── ParseError.scala      # Error types
│   ├── ast/
│   │   └── SqlAst.scala          # Intermediate SQL AST
│   ├── transform/
│   │   ├── AstToProtoTransform.scala  # AST -> ProtoLogicalPlan
│   │   └── TransformError.scala       # Transform errors
│   └── SqlMacro.scala            # Macro entry point
└── src/test/scala/protocatalyst/sql/
    ├── LexerSuite.scala
    ├── ParserSuite.scala
    ├── TransformSuite.scala
    └── SqlVerifier.scala
```

## Implementation Phases

### Phase 1: Core Parser (Complete)

Basic SQL parsing infrastructure:
- Lexer for SQL tokens (keywords, operators, literals, identifiers)
- Recursive descent parser
- SELECT with column list
- FROM single table with optional alias
- WHERE with comparisons
- ORDER BY ASC/DESC
- LIMIT

```sql
SELECT name, age FROM users WHERE age > 18 ORDER BY name LIMIT 100
```

### Phase 2: Enhanced Expressions (Complete)

- Arithmetic expressions (+, -, *, /)
- Table aliases
- Qualified column references (t.column)
- BETWEEN / NOT BETWEEN
- LIKE / NOT LIKE with ESCAPE
- IN / NOT IN with value lists
- IS NULL / IS NOT NULL
- CASE WHEN expressions
- CAST expressions
- String functions: UPPER, LOWER, CONCAT, SUBSTRING, COALESCE

```sql
SELECT
  UPPER(name) AS upper_name,
  CASE WHEN age > 30 THEN 'senior' ELSE 'junior' END AS category,
  salary * 1.1 AS adjusted_salary
FROM users u
WHERE u.age BETWEEN 18 AND 65
  AND u.name LIKE 'J%'
```

### Phase 3: JOINs (Complete)

- INNER JOIN
- LEFT [OUTER] JOIN
- RIGHT [OUTER] JOIN
- FULL [OUTER] JOIN
- CROSS JOIN
- Multiple JOINs
- Self-joins

```sql
SELECT u.name, o.total
FROM users u
INNER JOIN orders o ON u.id = o.user_id
LEFT JOIN addresses a ON u.id = a.user_id
```

### Phase 4: Aggregations (Complete)

- GROUP BY
- HAVING
- COUNT, SUM, AVG, MIN, MAX
- COUNT(DISTINCT column)
- Aggregate with expressions

```sql
SELECT department, COUNT(*) AS emp_count, AVG(salary) AS avg_salary
FROM users
GROUP BY department
HAVING COUNT(*) > 5
```

### Phase 5: Subqueries (Complete)

- Scalar subqueries in SELECT
- Subqueries in FROM clause
- EXISTS / NOT EXISTS
- IN / NOT IN with subquery
- Correlated subqueries

```sql
SELECT name, (SELECT COUNT(*) FROM orders WHERE user_id = u.id) AS order_count
FROM users u
WHERE EXISTS (SELECT 1 FROM orders WHERE user_id = u.id)
```

### Phase 6: SELECT DISTINCT (Complete)

- DISTINCT keyword support
- DISTINCT with expressions

```sql
SELECT DISTINCT department FROM users
SELECT DISTINCT department, role FROM users
```

### Phase 7: Set Operations (Complete)

- UNION / UNION ALL
- INTERSECT / INTERSECT ALL
- EXCEPT / EXCEPT ALL
- Chained set operations

```sql
SELECT name FROM active_users
UNION
SELECT name FROM premium_users
EXCEPT
SELECT name FROM banned_users
```

### Phase 8: Window Functions (Complete)

- ROW_NUMBER(), RANK(), DENSE_RANK()
- NTILE(n)
- LEAD(col, offset, default), LAG(col, offset, default)
- FIRST_VALUE(col), LAST_VALUE(col), NTH_VALUE(col, n)
- Aggregate functions as window functions (SUM, AVG, COUNT, MIN, MAX)
- PARTITION BY clause
- ORDER BY within window
- Frame specifications:
  - ROWS / RANGE
  - UNBOUNDED PRECEDING / FOLLOWING
  - CURRENT ROW
  - n PRECEDING / n FOLLOWING

```sql
SELECT
  name,
  salary,
  ROW_NUMBER() OVER (ORDER BY hire_date) AS row_num,
  RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS dept_rank,
  SUM(salary) OVER (PARTITION BY department) AS dept_total,
  LAG(salary) OVER (ORDER BY hire_date) AS prev_salary,
  AVG(salary) OVER (ORDER BY hire_date ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS moving_avg
FROM users
```

### Phase 9: Common Table Expressions (Complete)

- WITH clause
- Single and multiple CTEs
- CTE with column aliases
- CTE referencing other CTEs
- WITH RECURSIVE (parsed but not yet supported in transform)

```sql
WITH
  active_users AS (
    SELECT * FROM users WHERE active = true
  ),
  premium_active AS (
    SELECT * FROM active_users WHERE premium = true
  )
SELECT * FROM premium_active WHERE age > 25
```

### Phase 10: String & Math Functions (Complete)

Built-in function support aligned with Spark SQL's function library:

**String Functions:**
- TRIM, LTRIM, RTRIM (leading/trailing whitespace removal)
- LENGTH, CHAR_LENGTH, CHARACTER_LENGTH
- REPLACE(str, search, replacement)
- LOCATE/POSITION (substring position)
- LPAD, RPAD (padding)
- SPLIT (string to array)
- REVERSE
- REPEAT

**Math Functions:**
- ABS, CEIL/CEILING, FLOOR
- ROUND(n, scale), TRUNCATE(n, scale)
- SQRT, CBRT
- POW/POWER
- MOD (positive modulo, matching Spark's pmod)
- SIGN
- LOG (with optional base), LN
- EXP

**Null/Conditional Functions:**
- NULLIF(a, b) - returns null if equal
- NVL/IFNULL - aliases for COALESCE
- IF(condition, then, else)

```sql
SELECT
  TRIM(name) AS clean_name,
  LENGTH(description) AS desc_len,
  REPLACE(title, 'old', 'new') AS new_title,
  ROUND(price * 1.1, 2) AS adjusted_price,
  ABS(balance) AS abs_balance,
  NULLIF(category, 'Unknown') AS category,
  IF(active, 'Yes', 'No') AS status
FROM products
WHERE LENGTH(name) > 5
  AND MOD(id, 2) = 0
```

### Phase 11: Date/Time Functions (Complete)

Date and time function support aligned with Spark SQL:

**Current Date/Time:**
- CURRENT_DATE(), CURRENT_TIMESTAMP(), NOW()

**Date Arithmetic:**
- DATE_ADD(date, days), DATEADD(date, days)
- DATE_SUB(date, days), DATESUB(date, days)
- DATE_DIFF(end, start), DATEDIFF(end, start)

**Field Extraction:**
- EXTRACT(field FROM expr) - supports YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, QUARTER, WEEK, DAYOFWEEK, DAYOFYEAR, MILLISECOND, MICROSECOND
- YEAR(date), MONTH(date), DAY(date), DAYOFMONTH(date)
- HOUR(timestamp), MINUTE(timestamp), SECOND(timestamp)

**Truncation:**
- DATE_TRUNC(field, timestamp)

**Parsing:**
- TO_DATE(str), TO_DATE(str, format)
- TO_TIMESTAMP(str), TO_TIMESTAMP(str, format)

```sql
SELECT
  CURRENT_DATE() AS today,
  EXTRACT(YEAR FROM hire_date) AS hire_year,
  DATE_ADD(hire_date, 30) AS probation_end,
  DATE_DIFF(CURRENT_DATE(), hire_date) AS days_employed,
  YEAR(birth_date) AS birth_year,
  DATE_TRUNC('MONTH', created_at) AS month_start,
  TO_DATE('2024-01-15') AS parsed_date
FROM users
```

### Phase 12: Advanced Grouping (Complete)

Advanced GROUP BY functionality for multi-dimensional aggregation:

**Grouping Sets:**
- GROUPING SETS - explicit list of grouping combinations
- CUBE - all combinations of grouping columns (2^n subsets)
- ROLLUP - hierarchical aggregation (n+1 subsets)
- WITH CUBE / WITH ROLLUP - alternate syntax after GROUP BY columns
- GROUPING(column) function - identifies super-aggregate rows (returns 1 if null due to grouping)

```sql
-- GROUPING SETS: explicit grouping combinations
SELECT department, region, SUM(sales) AS total
FROM sales
GROUP BY GROUPING SETS (
  (department, region),
  (department),
  (region),
  ()
)

-- CUBE: all 2^n combinations (department, region), (department), (region), ()
SELECT department, region, SUM(sales)
FROM sales
GROUP BY CUBE(department, region)

-- ROLLUP: hierarchical (year, quarter, month), (year, quarter), (year), ()
SELECT year, quarter, month, SUM(sales)
FROM sales
GROUP BY ROLLUP(year, quarter, month)

-- WITH CUBE/ROLLUP syntax
SELECT name, age, COUNT(*)
FROM users
GROUP BY name, age WITH CUBE

-- GROUPING function: detect super-aggregate rows
SELECT name, GROUPING(name) AS is_total, COUNT(*)
FROM users
GROUP BY CUBE(name)
```

## Future Phases

### Phase 13: PIVOT / UNPIVOT

Data reshaping operations:

```sql
-- PIVOT: rows to columns
SELECT * FROM sales
PIVOT (
  SUM(amount) AS total
  FOR quarter IN ('Q1', 'Q2', 'Q3', 'Q4')
)

-- UNPIVOT: columns to rows
SELECT * FROM quarterly_sales
UNPIVOT (
  amount FOR quarter IN (q1, q2, q3, q4)
)
```

### Phase 14: LATERAL Subquery

Correlated subqueries in FROM clause:

```sql
SELECT u.*, recent_orders.*
FROM users u,
LATERAL (
  SELECT * FROM orders
  WHERE user_id = u.id
  ORDER BY created_at DESC
  LIMIT 5
) recent_orders
```

### Phase 15: LATERAL VIEW

Generator functions with EXPLODE:

```sql
SELECT name, col
FROM users
LATERAL VIEW EXPLODE(tags) t AS col

-- With OUTER to preserve rows with empty arrays
SELECT name, col
FROM users
LATERAL VIEW OUTER EXPLODE(tags) t AS col
```

### Phase 16: VALUES Clause

Inline table values:

```sql
SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(id, name)
```

### Phase 17: Query Hints

Optimizer hints for join strategies and partitioning:

```sql
SELECT /*+ BROADCAST(t1) */ *
FROM t1 INNER JOIN t2 ON t1.key = t2.key

SELECT /*+ REPARTITION(3, col) */ *
FROM large_table
```

### Phase 18: Recursive CTEs

Full support for recursive common table expressions:

```sql
WITH RECURSIVE hierarchy AS (
  SELECT id, name, manager_id, 1 AS level
  FROM employees
  WHERE manager_id IS NULL
  UNION ALL
  SELECT e.id, e.name, e.manager_id, h.level + 1
  FROM employees e
  JOIN hierarchy h ON e.manager_id = h.id
)
SELECT * FROM hierarchy
```

## Usage

### Basic Usage

```scala
import protocatalyst.query.CompiledQuery
import protocatalyst.encoder.ProtoEncoder

case class User(name: String, age: Int, salary: Double) derives ProtoEncoder

// Compile-time SQL parsing
val query = CompiledQuery.sql[User]("""
  SELECT name, salary
  FROM users
  WHERE age > 18
  ORDER BY salary DESC
  LIMIT 100
""")

// Returns Either[String, CompiledArtifact]
val result = CompiledQuery.sqlEither[User]("""
  SELECT name FROM users WHERE unknown_column > 0
""")
// Left("Schema validation failed: Unknown column 'unknown_column'...")
```

### Error Handling

The parser provides compile-time error messages for:
- Syntax errors (invalid SQL)
- Unknown columns (not in schema)
- Type mismatches
- Unsupported features

## Test Coverage

Current test statistics (278 total tests in sql-parser module):
- Lexer tests: 18 tests
- Parser tests: 117 tests (including CTEs, window functions, set operations, date/time functions, advanced grouping)
- Transform tests: 79 tests
- **Parser Comparison tests: 64 tests** - validates against JSQLParser reference implementation
- Integration tests in query module: 50+ tests

### JSQLParser Comparison Testing

The `ParserComparisonSuite` validates our parser against [JSQLParser](https://github.com/JSQLParser/JSqlParser), a well-established Java SQL parser library. This ensures:

- Our parser accepts valid SQL that JSQLParser accepts
- Structural elements (tables, columns) are parsed correctly
- Edge cases and error handling are reasonable

Run comparison tests:
```bash
sbt "sqlParser/testOnly protocatalyst.sql.ParserComparisonSuite"
```

## IR Representation

SQL is transformed to:
- `ProtoLogicalPlan`: Query structure (Project, Filter, Join, Aggregate, etc.)
- `ProtoExpr`: Expressions (literals, columns, comparisons, functions, etc.)

These can be serialized and deserialized for cross-session/cross-process query execution.
