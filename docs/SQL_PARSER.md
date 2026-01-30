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
    └── SqlMacroSuite.scala
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

## Future Phases

### Phase 10: Additional Functions

- Math: ABS, ROUND, CEIL, FLOOR, MOD, POWER, SQRT
- String: TRIM, LTRIM, RTRIM, REPLACE, LENGTH, POSITION
- Null handling: NULLIF, NVL/IFNULL, NVL2
- Type conversion: TRY_CAST

```sql
SELECT
  TRIM(name),
  ROUND(salary, 2),
  NULLIF(department, 'Unknown'),
  LENGTH(description)
FROM users
```

### Phase 11: Date/Time Functions

- CURRENT_DATE, CURRENT_TIMESTAMP
- DATE_ADD, DATE_SUB, DATE_DIFF
- EXTRACT (YEAR, MONTH, DAY, etc.)
- DATE_TRUNC
- TO_DATE, TO_TIMESTAMP

```sql
SELECT
  EXTRACT(YEAR FROM hire_date) AS hire_year,
  DATE_ADD(hire_date, 30) AS probation_end,
  DATE_DIFF(CURRENT_DATE, hire_date) AS days_employed
FROM users
```

### Phase 12: Advanced Grouping

- GROUPING SETS
- CUBE
- ROLLUP
- GROUPING() function

```sql
SELECT department, region, SUM(sales)
FROM sales
GROUP BY GROUPING SETS (
  (department, region),
  (department),
  (region),
  ()
)
```

### Phase 13: QUALIFY Clause

Filter window function results:

```sql
SELECT * FROM users
QUALIFY ROW_NUMBER() OVER (PARTITION BY department ORDER BY salary DESC) = 1
```

### Phase 14: LATERAL Joins

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

### Phase 15: VALUES Clause

Inline table values:

```sql
SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(id, name)
```

### Phase 16: Recursive CTEs

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

Current test statistics (154 total tests in sql-parser module):
- Lexer tests: 27 tests
- Parser tests: 81 tests (including CTEs, window functions, set operations)
- Transform tests: 46 tests
- Integration tests in query module: 50+ tests

## IR Representation

SQL is transformed to:
- `ProtoLogicalPlan`: Query structure (Project, Filter, Join, Aggregate, etc.)
- `ProtoExpr`: Expressions (literals, columns, comparisons, functions, etc.)

These can be serialized and deserialized for cross-session/cross-process query execution.
