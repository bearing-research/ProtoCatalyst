package protocatalyst.sql.parser

import protocatalyst.sql.lexer.{Lexer, LexerError, Token}
import protocatalyst.sql.ast.*

/** Recursive descent SQL parser. */
class SqlParser(tokens: Vector[Token]):
  private var pos: Int = 0

  /** Parse a SQL statement (SELECT, compound with set operations, or WITH clause). */
  def parse(): Either[ParseError, SqlStatement] =
    for
      stmt <- parseStatement()
      _ <- expectEOF()
    yield stmt

  /** Parse the top-level statement: WITH clause or query. */
  private def parseStatement(): Either[ParseError, SqlStatement] =
    if check(Token.WITH) then
      parseWithStatement()
    else
      parseQuery()

  /** Parse WITH clause: WITH [RECURSIVE] cte1, cte2, ... query */
  private def parseWithStatement(): Either[ParseError, SqlStatement] =
    advance() // consume WITH
    val recursive = if check(Token.RECURSIVE) then
      advance()
      true
    else
      false

    for
      ctes <- parseCteDefinitions()
      query <- parseQuery()
    yield SqlStatement.WithStatement(ctes, recursive, query)

  /** Parse comma-separated CTE definitions. */
  private def parseCteDefinitions(): Either[ParseError, Vector[CteDefinition]] =
    parseCteDefinition().flatMap { first =>
      parseRestOfCteDefinitions(Vector(first))
    }

  private def parseRestOfCteDefinitions(acc: Vector[CteDefinition]): Either[ParseError, Vector[CteDefinition]] =
    if check(Token.Comma) then
      advance()
      parseCteDefinition().flatMap { cte =>
        parseRestOfCteDefinitions(acc :+ cte)
      }
    else
      Right(acc)

  /** Parse a single CTE: name [(columns)] AS (query) */
  private def parseCteDefinition(): Either[ParseError, CteDefinition] =
    current match
      case Token.Identifier(name) =>
        advance()
        for
          columnAliases <- parseOptionalCteColumns()
          _ <- expect(Token.AS, "AS")
          _ <- expect(Token.LParen, "(")
          query <- parseQuery()
          _ <- expect(Token.RParen, ")")
        yield CteDefinition(name, columnAliases, query)
      case t =>
        Left(ParseError.UnexpectedToken(t, "CTE name", currentPosition))

  /** Parse optional column list for CTE: (col1, col2, ...) */
  private def parseOptionalCteColumns(): Either[ParseError, Option[Vector[String]]] =
    if check(Token.LParen) then
      advance()
      parseIdentifierList().flatMap { cols =>
        expect(Token.RParen, ")").map(_ => Some(cols))
      }
    else
      Right(None)

  /** Parse comma-separated identifier list. */
  private def parseIdentifierList(): Either[ParseError, Vector[String]] =
    current match
      case Token.Identifier(name) =>
        advance()
        parseRestOfIdentifierList(Vector(name))
      case t =>
        Left(ParseError.UnexpectedToken(t, "identifier", currentPosition))

  private def parseRestOfIdentifierList(acc: Vector[String]): Either[ParseError, Vector[String]] =
    if check(Token.Comma) then
      advance()
      current match
        case Token.Identifier(name) =>
          advance()
          parseRestOfIdentifierList(acc :+ name)
        case t =>
          Left(ParseError.UnexpectedToken(t, "identifier", currentPosition))
    else
      Right(acc)

  /** Parse a query which may include set operations. */
  private def parseQuery(): Either[ParseError, SqlStatement] =
    parseSingleSelect().flatMap { left =>
      parseSetOperations(left)
    }

  /** Parse set operations (UNION, INTERSECT, EXCEPT) following a statement. */
  private def parseSetOperations(left: SqlStatement): Either[ParseError, SqlStatement] =
    parseOptionalSetOp() match
      case Some(op) =>
        for
          right <- parseSingleSelect()
          combined = SqlStatement.CompoundStatement(left, op, right)
          result <- parseSetOperations(combined)
        yield result
      case None =>
        Right(left)

  /** Check for and parse a set operation keyword. */
  private def parseOptionalSetOp(): Option[SetOperation] =
    if check(Token.UNION) then
      advance()
      val all = if check(Token.ALL) then { advance(); true } else false
      Some(SetOperation.Union(all))
    else if check(Token.INTERSECT) then
      advance()
      val all = if check(Token.ALL) then { advance(); true } else false
      Some(SetOperation.Intersect(all))
    else if check(Token.EXCEPT) then
      advance()
      val all = if check(Token.ALL) then { advance(); true } else false
      Some(SetOperation.Except(all))
    else
      None

  /** Parse a single SELECT statement (without set operations). */
  private def parseSingleSelect(): Either[ParseError, SqlStatement.SelectStatement] =
    for
      _ <- expect(Token.SELECT, "SELECT")
      distinct <- parseDistinct()
      projections <- parseProjections()
      _ <- expect(Token.FROM, "FROM")
      from <- parseFromClause()
      where <- parseOptionalWhere()
      groupBy <- parseOptionalGroupBy()
      having <- parseOptionalHaving()
      orderBy <- parseOptionalOrderBy()
      limit <- parseOptionalLimit()
    yield SqlStatement.SelectStatement(distinct, projections, from, where, groupBy, having, orderBy, limit)

  private def parseDistinct(): Either[ParseError, Boolean] =
    if check(Token.DISTINCT) then
      advance()
      Right(true)
    else
      Right(false)

  private def parseProjections(): Either[ParseError, Vector[Projection]] =
    val projections = Vector.newBuilder[Projection]

    parseProjection() match
      case Right(p) => projections += p
      case Left(e) => return Left(e)

    while check(Token.Comma) do
      advance()
      parseProjection() match
        case Right(p) => projections += p
        case Left(e) => return Left(e)

    Right(projections.result())

  private def parseProjection(): Either[ParseError, Projection] =
    // Check for star
    if check(Token.Star) then
      advance()
      return Right(Projection(SqlExpr.Star(None), None))

    for
      expr <- parseExpr()
      alias <- parseOptionalAlias()
    yield Projection(expr, alias)

  private def parseOptionalAlias(): Either[ParseError, Option[String]] =
    if check(Token.AS) then
      advance()
      current match
        case Token.Identifier(name) =>
          advance()
          Right(Some(name))
        case t =>
          Left(ParseError.UnexpectedToken(t, "identifier", currentPosition))
    else if current.isInstanceOf[Token.Identifier] then
      val Token.Identifier(name) = current: @unchecked
      // Only treat as alias if it's not a keyword-like position
      if !isKeyword(name) then
        advance()
        Right(Some(name))
      else
        Right(None)
    else
      Right(None)

  private def isKeyword(name: String): Boolean =
    val upper = name.toUpperCase
    upper == "FROM" || upper == "WHERE" || upper == "ORDER" ||
    upper == "LIMIT" || upper == "AND" || upper == "OR"

  private def parseFromClause(): Either[ParseError, FromClause] =
    parseFromItem().flatMap { firstItem =>
      parseJoinClauses(firstItem)
    }

  /** Parse a single FROM item: table or subquery. */
  private def parseFromItem(): Either[ParseError, FromClause] =
    if check(Token.LParen) then
      advance()
      if check(Token.SELECT) then
        // Subquery in FROM clause
        parseSelectStatement().flatMap { stmt =>
          expect(Token.RParen, ")").flatMap { _ =>
            // Subquery must have an alias
            parseRequiredAlias().map { alias =>
              FromClause.Subquery(stmt, alias)
            }
          }
        }
      else
        Left(ParseError.SyntaxError("Expected SELECT in subquery", currentPosition))
    else
      parseTableRef().map(FromClause.Table(_))

  private def parseRequiredAlias(): Either[ParseError, String] =
    if check(Token.AS) then
      advance()
    current match
      case Token.Identifier(name) if !isKeyword(name) && !isJoinKeyword(name) =>
        advance()
        Right(name)
      case _ =>
        Left(ParseError.SyntaxError("Subquery in FROM clause requires an alias", currentPosition))

  private def parseTableRef(): Either[ParseError, TableRef] =
    current match
      case Token.Identifier(table) =>
        advance()
        val alias = if check(Token.AS) then
          advance()
          current match
            case Token.Identifier(a) =>
              advance()
              Some(a)
            case _ => None
        else if current.isInstanceOf[Token.Identifier] then
          val Token.Identifier(a) = current: @unchecked
          if !isKeyword(a) && !isJoinKeyword(a) then
            advance()
            Some(a)
          else
            None
        else
          None
        Right(TableRef(table, alias))
      case t =>
        Left(ParseError.UnexpectedToken(t, "table name", currentPosition))

  private def isJoinKeyword(name: String): Boolean =
    val upper = name.toUpperCase
    upper == "JOIN" || upper == "INNER" || upper == "LEFT" ||
    upper == "RIGHT" || upper == "FULL" || upper == "CROSS" || upper == "ON"

  private def parseJoinClauses(left: FromClause): Either[ParseError, FromClause] =
    parseOptionalJoinType() match
      case Some(joinType) =>
        for
          rightItem <- parseFromItem()
          condition <- parseJoinCondition(joinType)
          joined = FromClause.Join(left, rightItem, joinType, condition)
          result <- parseJoinClauses(joined)
        yield result
      case None =>
        Right(left)

  private def parseOptionalJoinType(): Option[JoinType] =
    if check(Token.INNER) then
      advance()
      expect(Token.JOIN, "JOIN")
      Some(JoinType.Inner)
    else if check(Token.LEFT) then
      advance()
      if check(Token.OUTER) then advance()
      expect(Token.JOIN, "JOIN")
      Some(JoinType.LeftOuter)
    else if check(Token.RIGHT) then
      advance()
      if check(Token.OUTER) then advance()
      expect(Token.JOIN, "JOIN")
      Some(JoinType.RightOuter)
    else if check(Token.FULL) then
      advance()
      if check(Token.OUTER) then advance()
      expect(Token.JOIN, "JOIN")
      Some(JoinType.FullOuter)
    else if check(Token.CROSS) then
      advance()
      expect(Token.JOIN, "JOIN")
      Some(JoinType.Cross)
    else if check(Token.JOIN) then
      advance()
      Some(JoinType.Inner) // Plain JOIN is INNER JOIN
    else
      None

  private def parseJoinCondition(joinType: JoinType): Either[ParseError, Option[SqlExpr]] =
    joinType match
      case JoinType.Cross =>
        // CROSS JOIN doesn't have an ON clause
        Right(None)
      case _ =>
        if check(Token.ON) then
          advance()
          parseExpr().map(Some(_))
        else
          // ON clause is optional for non-CROSS joins (becomes a cross join in practice)
          Right(None)

  private def parseOptionalWhere(): Either[ParseError, Option[SqlExpr]] =
    if check(Token.WHERE) then
      advance()
      parseExpr().map(Some(_))
    else
      Right(None)

  private def parseOptionalGroupBy(): Either[ParseError, Vector[SqlExpr]] =
    if check(Token.GROUP) then
      advance()
      expect(Token.BY, "BY").flatMap(_ => parseGroupByExprs())
    else
      Right(Vector.empty)

  private def parseGroupByExprs(): Either[ParseError, Vector[SqlExpr]] =
    val exprs = Vector.newBuilder[SqlExpr]

    parsePrimary() match
      case Right(e) => exprs += e
      case Left(err) => return Left(err)

    while check(Token.Comma) do
      advance()
      parsePrimary() match
        case Right(e) => exprs += e
        case Left(err) => return Left(err)

    Right(exprs.result())

  private def parseOptionalHaving(): Either[ParseError, Option[SqlExpr]] =
    if check(Token.HAVING) then
      advance()
      parseExpr().map(Some(_))
    else
      Right(None)

  private def parseOptionalOrderBy(): Either[ParseError, Vector[OrderSpec]] =
    if check(Token.ORDER) then
      advance()
      expect(Token.BY, "BY").flatMap(_ => parseOrderSpecs())
    else
      Right(Vector.empty)

  private def parseOrderSpecs(): Either[ParseError, Vector[OrderSpec]] =
    val specs = Vector.newBuilder[OrderSpec]

    parseOrderSpec() match
      case Right(s) => specs += s
      case Left(e) => return Left(e)

    while check(Token.Comma) do
      advance()
      parseOrderSpec() match
        case Right(s) => specs += s
        case Left(e) => return Left(e)

    Right(specs.result())

  private def parseOrderSpec(): Either[ParseError, OrderSpec] =
    for
      expr <- parsePrimary() // Only simple expressions in ORDER BY
      asc = if check(Token.DESC) then
        advance()
        false
      else
        if check(Token.ASC) then advance()
        true
    yield OrderSpec(expr, asc)

  private def parseOptionalLimit(): Either[ParseError, Option[Long]] =
    if check(Token.LIMIT) then
      advance()
      current match
        case Token.IntegerLiteral(n) =>
          advance()
          Right(Some(n))
        case t =>
          Left(ParseError.UnexpectedToken(t, "integer", currentPosition))
    else
      Right(None)

  // Expression parsing with precedence

  private def parseExpr(): Either[ParseError, SqlExpr] = parseOrExpr()

  private def parseOrExpr(): Either[ParseError, SqlExpr] =
    var left = parseAndExpr() match
      case Right(e) => e
      case Left(err) => return Left(err)

    while check(Token.OR) do
      advance()
      parseAndExpr() match
        case Right(right) => left = SqlExpr.Or(left, right)
        case Left(err) => return Left(err)

    Right(left)

  private def parseAndExpr(): Either[ParseError, SqlExpr] =
    var left = parseNotExpr() match
      case Right(e) => e
      case Left(err) => return Left(err)

    while check(Token.AND) do
      advance()
      parseNotExpr() match
        case Right(right) => left = SqlExpr.And(left, right)
        case Left(err) => return Left(err)

    Right(left)

  private def parseNotExpr(): Either[ParseError, SqlExpr] =
    if check(Token.NOT) then
      advance()
      if check(Token.EXISTS) then
        // NOT EXISTS (subquery)
        parseExists().map {
          case SqlExpr.Exists(stmt) => SqlExpr.NotExists(stmt)
          case other => SqlExpr.Not(other)
        }
      else
        parseNotExpr().map(SqlExpr.Not(_))
    else
      parseComparison()

  private def parseComparison(): Either[ParseError, SqlExpr] =
    val left = parseAdditive() match
      case Right(e) => e
      case Left(err) => return Left(err)

    // IS [NOT] NULL
    if check(Token.IS) then
      advance()
      val notNull = if check(Token.NOT) then
        advance()
        true
      else
        false
      expect(Token.NULL, "NULL").map { _ =>
        if notNull then SqlExpr.IsNotNull(left)
        else SqlExpr.IsNull(left)
      }
    // [NOT] BETWEEN low AND high
    else if check(Token.BETWEEN) then
      advance()
      for
        low <- parseAdditive()
        _ <- expect(Token.AND, "AND")
        high <- parseAdditive()
      yield SqlExpr.Between(left, low, high)
    else if check(Token.NOT) then
      advance()
      if check(Token.BETWEEN) then
        advance()
        for
          low <- parseAdditive()
          _ <- expect(Token.AND, "AND")
          high <- parseAdditive()
        yield SqlExpr.NotBetween(left, low, high)
      else if check(Token.LIKE) then
        advance()
        for
          pattern <- parseAdditive()
          escape <- parseOptionalEscape()
        yield SqlExpr.NotLike(left, pattern, escape)
      else if check(Token.IN) then
        advance()
        parseInListOrSubquery(left, negated = true)
      else
        Left(ParseError.SyntaxError("Expected BETWEEN, LIKE, or IN after NOT", currentPosition))
    // [NOT] LIKE pattern [ESCAPE char]
    else if check(Token.LIKE) then
      advance()
      for
        pattern <- parseAdditive()
        escape <- parseOptionalEscape()
      yield SqlExpr.Like(left, pattern, escape)
    // [NOT] IN (list or subquery)
    else if check(Token.IN) then
      advance()
      parseInListOrSubquery(left, negated = false)
    // Comparison operators
    else if isCompareOp(current) then
      val op = parseCompareOp()
      advance()
      parseAdditive().map(right => SqlExpr.Compare(left, op, right))
    else
      Right(left)

  private def parseOptionalEscape(): Either[ParseError, Option[SqlExpr]] =
    if check(Token.ESCAPE) then
      advance()
      parseAdditive().map(Some(_))
    else
      Right(None)

  /** Parse IN (list) or IN (subquery). */
  private def parseInListOrSubquery(left: SqlExpr, negated: Boolean): Either[ParseError, SqlExpr] =
    expect(Token.LParen, "(").flatMap { _ =>
      if check(Token.SELECT) then
        // IN (SELECT ...)
        parseSelectStatement().flatMap { stmt =>
          expect(Token.RParen, ")").map { _ =>
            if negated then SqlExpr.NotInSubquery(left, stmt)
            else SqlExpr.InSubquery(left, stmt)
          }
        }
      else
        // IN (value, value, ...)
        parseExprList().flatMap { items =>
          expect(Token.RParen, ")").map { _ =>
            if negated then SqlExpr.NotIn(left, items)
            else SqlExpr.In(left, items)
          }
        }
    }

  private def parseInList(): Either[ParseError, Vector[SqlExpr]] =
    expect(Token.LParen, "(").flatMap { _ =>
      parseExprList().flatMap { items =>
        expect(Token.RParen, ")").map(_ => items)
      }
    }

  private def parseExprList(): Either[ParseError, Vector[SqlExpr]] =
    parseExpr().flatMap { first =>
      parseRestOfExprList(Vector(first))
    }

  private def parseRestOfExprList(acc: Vector[SqlExpr]): Either[ParseError, Vector[SqlExpr]] =
    if check(Token.Comma) then
      advance()
      parseExpr().flatMap { e =>
        parseRestOfExprList(acc :+ e)
      }
    else
      Right(acc)

  private def isCompareOp(token: Token): Boolean = token match
    case Token.Eq | Token.NotEq | Token.Lt | Token.LtEq | Token.Gt | Token.GtEq => true
    case _ => false

  private def parseCompareOp(): CompareOp = current match
    case Token.Eq => CompareOp.Eq
    case Token.NotEq => CompareOp.NotEq
    case Token.Lt => CompareOp.Lt
    case Token.LtEq => CompareOp.LtEq
    case Token.Gt => CompareOp.Gt
    case Token.GtEq => CompareOp.GtEq
    case _ => throw new IllegalStateException("Not a compare op")

  private def parseAdditive(): Either[ParseError, SqlExpr] =
    var left = parseMultiplicative() match
      case Right(e) => e
      case Left(err) => return Left(err)

    while check(Token.Plus) || check(Token.Minus) do
      val op = if check(Token.Plus) then ArithOp.Add else ArithOp.Subtract
      advance()
      parseMultiplicative() match
        case Right(right) => left = SqlExpr.Arithmetic(left, op, right)
        case Left(err) => return Left(err)

    Right(left)

  private def parseMultiplicative(): Either[ParseError, SqlExpr] =
    var left = parsePrimary() match
      case Right(e) => e
      case Left(err) => return Left(err)

    while check(Token.Star) || check(Token.Slash) do
      val op = if check(Token.Star) then ArithOp.Multiply else ArithOp.Divide
      advance()
      parsePrimary() match
        case Right(right) => left = SqlExpr.Arithmetic(left, op, right)
        case Left(err) => return Left(err)

    Right(left)

  private def parsePrimary(): Either[ParseError, SqlExpr] =
    current match
      case Token.IntegerLiteral(n) =>
        advance()
        Right(SqlExpr.IntLit(n))

      case Token.DoubleLiteral(d) =>
        advance()
        Right(SqlExpr.DoubleLit(d))

      case Token.StringLiteral(s) =>
        advance()
        Right(SqlExpr.StringLit(s))

      case Token.TRUE =>
        advance()
        Right(SqlExpr.BoolLit(true))

      case Token.FALSE =>
        advance()
        Right(SqlExpr.BoolLit(false))

      case Token.NULL =>
        advance()
        Right(SqlExpr.NullLit)

      case Token.Star =>
        advance()
        Right(SqlExpr.Star(None))

      case Token.Identifier(name) =>
        advance()
        if check(Token.LParen) then
          // Function call
          advance()
          val distinct = if check(Token.DISTINCT) then
            advance()
            true
          else
            false

          val funcResult = if check(Token.RParen) then
            // No arguments
            advance()
            Right(SqlExpr.FunctionCall(name.toUpperCase, Vector.empty, distinct))
          else
            parseExprList().flatMap { args =>
              expect(Token.RParen, ")").map(_ => SqlExpr.FunctionCall(name.toUpperCase, args, distinct))
            }

          // Check for OVER clause (window function)
          funcResult.flatMap { func =>
            if check(Token.OVER) then
              parseWindowSpec().map(spec => SqlExpr.WindowFunction(func, spec))
            else
              Right(func)
          }
        else if check(Token.Dot) then
          advance()
          current match
            case Token.Identifier(field) =>
              advance()
              Right(SqlExpr.ColumnRef(field, Some(name)))
            case Token.Star =>
              advance()
              Right(SqlExpr.Star(Some(name)))
            case t =>
              Left(ParseError.UnexpectedToken(t, "identifier or *", currentPosition))
        else
          Right(SqlExpr.ColumnRef(name, None))

      case Token.LParen =>
        advance()
        // Check if this is a subquery (SELECT ...) or a parenthesized expression
        if check(Token.SELECT) then
          parseSelectStatement().flatMap { subStmt =>
            expect(Token.RParen, ")").map(_ => SqlExpr.ScalarSubquery(subStmt))
          }
        else
          val expr = parseExpr()
          expr.flatMap { e =>
            expect(Token.RParen, ")").map(_ => SqlExpr.Paren(e))
          }

      case Token.Minus =>
        advance()
        parsePrimary().map {
          case SqlExpr.IntLit(n) => SqlExpr.IntLit(-n)
          case SqlExpr.DoubleLit(d) => SqlExpr.DoubleLit(-d)
          case other => SqlExpr.Arithmetic(SqlExpr.IntLit(0), ArithOp.Subtract, other)
        }

      case Token.CASE =>
        parseCaseWhen()

      case Token.CAST =>
        parseCast()

      case Token.EXISTS =>
        parseExists()

      case t =>
        Left(ParseError.UnexpectedToken(t, "expression", currentPosition))

  /** Parse CASE WHEN expr THEN result [WHEN ...] [ELSE result] END */
  private def parseCaseWhen(): Either[ParseError, SqlExpr] =
    advance() // consume CASE
    val branches = Vector.newBuilder[(SqlExpr, SqlExpr)]

    // Parse WHEN branches
    while check(Token.WHEN) do
      advance() // consume WHEN
      parseExpr() match
        case Left(err) => return Left(err)
        case Right(condition) =>
          expect(Token.THEN, "THEN") match
            case Left(err) => return Left(err)
            case Right(_) =>
              parseExpr() match
                case Left(err) => return Left(err)
                case Right(result) =>
                  branches += ((condition, result))

    if branches.result().isEmpty then
      return Left(ParseError.SyntaxError("CASE must have at least one WHEN branch", currentPosition))

    // Parse optional ELSE
    val elseValue = if check(Token.ELSE) then
      advance()
      parseExpr() match
        case Left(err) => return Left(err)
        case Right(e) => Some(e)
    else
      None

    // Expect END
    expect(Token.END, "END").map { _ =>
      SqlExpr.CaseWhen(branches.result(), elseValue)
    }

  /** Parse CAST(expr AS type) */
  private def parseCast(): Either[ParseError, SqlExpr] =
    advance() // consume CAST
    for
      _ <- expect(Token.LParen, "(")
      expr <- parseExpr()
      _ <- expect(Token.AS, "AS")
      targetType <- parseType()
      _ <- expect(Token.RParen, ")")
    yield SqlExpr.Cast(expr, targetType)

  /** Parse EXISTS (subquery) */
  private def parseExists(): Either[ParseError, SqlExpr] =
    advance() // consume EXISTS
    for
      _ <- expect(Token.LParen, "(")
      stmt <- parseSelectStatement()
      _ <- expect(Token.RParen, ")")
    yield SqlExpr.Exists(stmt)

  /** Parse a SELECT statement (used for subqueries). */
  private def parseSelectStatement(): Either[ParseError, SqlStatement.SelectStatement] =
    for
      _ <- expect(Token.SELECT, "SELECT")
      distinct <- parseDistinct()
      projections <- parseProjections()
      _ <- expect(Token.FROM, "FROM")
      from <- parseFromClause()
      where <- parseOptionalWhere()
      groupBy <- parseOptionalGroupBy()
      having <- parseOptionalHaving()
      orderBy <- parseOptionalOrderBy()
      limit <- parseOptionalLimit()
    yield SqlStatement.SelectStatement(distinct, projections, from, where, groupBy, having, orderBy, limit)

  /** Parse a SQL type name. */
  private def parseType(): Either[ParseError, SqlType] =
    current match
      case Token.Identifier(typeName) =>
        advance()
        typeName.toUpperCase match
          case "INT" | "INTEGER" => Right(SqlType.IntType)
          case "BIGINT" | "LONG" => Right(SqlType.LongType)
          case "DOUBLE" | "FLOAT" | "REAL" => Right(SqlType.DoubleType)
          case "STRING" | "VARCHAR" | "TEXT" | "CHAR" =>
            // Skip optional length specification like VARCHAR(255)
            if check(Token.LParen) then
              advance()
              if current.isInstanceOf[Token.IntegerLiteral] then advance()
              expect(Token.RParen, ")")
            Right(SqlType.StringType)
          case "BOOLEAN" | "BOOL" => Right(SqlType.BooleanType)
          case "DATE" => Right(SqlType.DateType)
          case "TIMESTAMP" | "DATETIME" => Right(SqlType.TimestampType)
          case other => Left(ParseError.SyntaxError(s"Unknown type: $other", currentPosition))
      case t =>
        Left(ParseError.UnexpectedToken(t, "type name", currentPosition))

  /** Parse window specification: OVER (PARTITION BY ... ORDER BY ... [frame]) */
  private def parseWindowSpec(): Either[ParseError, WindowSpec] =
    advance() // consume OVER
    for
      _ <- expect(Token.LParen, "(")
      partitionBy <- parseOptionalPartitionBy()
      orderBy <- parseOptionalWindowOrderBy()
      frame <- parseOptionalFrame()
      _ <- expect(Token.RParen, ")")
    yield WindowSpec(partitionBy, orderBy, frame)

  private def parseOptionalPartitionBy(): Either[ParseError, Vector[SqlExpr]] =
    if check(Token.PARTITION) then
      advance() // consume PARTITION
      expect(Token.BY, "BY").flatMap { _ =>
        parseExprList()
      }
    else
      Right(Vector.empty)

  private def parseOptionalWindowOrderBy(): Either[ParseError, Vector[OrderSpec]] =
    if check(Token.ORDER) then
      advance() // consume ORDER
      expect(Token.BY, "BY").flatMap { _ =>
        parseWindowOrderSpecs()
      }
    else
      Right(Vector.empty)

  private def parseWindowOrderSpecs(): Either[ParseError, Vector[OrderSpec]] =
    parseWindowOrderSpec().flatMap { first =>
      parseRestOfWindowOrderSpecs(Vector(first))
    }

  private def parseRestOfWindowOrderSpecs(acc: Vector[OrderSpec]): Either[ParseError, Vector[OrderSpec]] =
    if check(Token.Comma) then
      advance()
      parseWindowOrderSpec().flatMap { spec =>
        parseRestOfWindowOrderSpecs(acc :+ spec)
      }
    else
      Right(acc)

  private def parseWindowOrderSpec(): Either[ParseError, OrderSpec] =
    parseAdditive().map { expr =>
      val ascending = if check(Token.DESC) then
        advance()
        false
      else if check(Token.ASC) then
        advance()
        true
      else
        true // default ascending
      OrderSpec(expr, ascending)
    }

  private def parseOptionalFrame(): Either[ParseError, Option[WindowFrame]] =
    if check(Token.ROWS) || check(Token.RANGE) then
      val frameType = if check(Token.ROWS) then FrameType.Rows else FrameType.Range
      advance()

      if check(Token.BETWEEN) then
        // BETWEEN start AND end
        advance()
        for
          start <- parseFrameBound()
          _ <- expect(Token.AND, "AND")
          end <- parseFrameBound()
        yield Some(WindowFrame(frameType, start, end))
      else
        // Single bound (start only, end defaults to CURRENT ROW)
        parseFrameBound().map { start =>
          Some(WindowFrame(frameType, start, FrameBound.CurrentRow))
        }
    else
      Right(None)

  private def parseFrameBound(): Either[ParseError, FrameBound] =
    if check(Token.UNBOUNDED) then
      advance()
      if check(Token.PRECEDING) then
        advance()
        Right(FrameBound.UnboundedPreceding)
      else if check(Token.FOLLOWING) then
        advance()
        Right(FrameBound.UnboundedFollowing)
      else
        Left(ParseError.SyntaxError("Expected PRECEDING or FOLLOWING after UNBOUNDED", currentPosition))
    else if check(Token.CURRENT) then
      advance()
      expect(Token.ROW, "ROW").map(_ => FrameBound.CurrentRow)
    else
      // n PRECEDING or n FOLLOWING
      current match
        case Token.IntegerLiteral(n) =>
          advance()
          if check(Token.PRECEDING) then
            advance()
            Right(FrameBound.Preceding(n))
          else if check(Token.FOLLOWING) then
            advance()
            Right(FrameBound.Following(n))
          else
            Left(ParseError.SyntaxError("Expected PRECEDING or FOLLOWING after number", currentPosition))
        case _ =>
          Left(ParseError.SyntaxError("Expected frame bound (UNBOUNDED PRECEDING/FOLLOWING, CURRENT ROW, or n PRECEDING/FOLLOWING)", currentPosition))

  // Utility methods

  private def current: Token =
    if pos < tokens.length then tokens(pos)
    else Token.EOF

  private def advance(): Unit =
    if pos < tokens.length then pos += 1

  private def check(token: Token): Boolean = current == token

  private def expect(token: Token, expected: String): Either[ParseError, Unit] =
    if check(token) then
      advance()
      Right(())
    else
      Left(ParseError.UnexpectedToken(current, expected, currentPosition))

  private def expectEOF(): Either[ParseError, Unit] =
    if current == Token.EOF then Right(())
    else Left(ParseError.UnexpectedToken(current, "end of query", currentPosition))

  private def currentPosition: Option[ParsePosition] = None // Could track from lexer

object SqlParser:
  /** Parse a SQL string. */
  def parse(sql: String): Either[String, SqlStatement] =
    val lexer = Lexer(sql)
    lexer.tokenize() match
      case Left(err) => Left(err.toString)
      case Right(tokens) =>
        val parser = SqlParser(tokens)
        parser.parse() match
          case Left(err) => Left(err.format)
          case Right(stmt) => Right(stmt)
