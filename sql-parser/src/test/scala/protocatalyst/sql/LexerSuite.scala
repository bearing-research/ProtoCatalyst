package protocatalyst.sql

import protocatalyst.sql.lexer._

class LexerSuite extends munit.FunSuite:

  test("tokenizes simple SELECT"):
    val lexer = Lexer("SELECT name FROM users")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.SELECT,
        Token.Identifier("name"),
        Token.FROM,
        Token.Identifier("users"),
        Token.EOF
      )
    )

  test("tokenizes SELECT with WHERE"):
    val lexer = Lexer("SELECT name FROM users WHERE age > 18")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.SELECT,
        Token.Identifier("name"),
        Token.FROM,
        Token.Identifier("users"),
        Token.WHERE,
        Token.Identifier("age"),
        Token.Gt,
        Token.IntegerLiteral(18),
        Token.EOF
      )
    )

  test("tokenizes comparison operators"):
    val lexer = Lexer("= <> != < <= > >=")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.Eq,
        Token.NotEq,
        Token.NotEq,
        Token.Lt,
        Token.LtEq,
        Token.Gt,
        Token.GtEq,
        Token.EOF
      )
    )

  test("tokenizes arithmetic operators"):
    val lexer = Lexer("+ - * /")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.Plus,
        Token.Minus,
        Token.Star,
        Token.Slash,
        Token.EOF
      )
    )

  test("tokenizes string literal"):
    val lexer = Lexer("'hello world'")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.StringLiteral("hello world"),
        Token.EOF
      )
    )

  test("tokenizes string literal with escapes"):
    val lexer = Lexer("'hello\\'s world'")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.StringLiteral("hello's world"),
        Token.EOF
      )
    )

  test("tokenizes integer literal"):
    val lexer = Lexer("42 -123")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.IntegerLiteral(42),
        Token.IntegerLiteral(-123),
        Token.EOF
      )
    )

  test("tokenizes double literal"):
    val lexer = Lexer("3.14 -2.5")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.DoubleLiteral(3.14),
        Token.DoubleLiteral(-2.5),
        Token.EOF
      )
    )

  test("tokenizes keywords case-insensitively"):
    val lexer = Lexer("select FROM Where AND or NOT")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.SELECT,
        Token.FROM,
        Token.WHERE,
        Token.AND,
        Token.OR,
        Token.NOT,
        Token.EOF
      )
    )

  test("tokenizes ORDER BY"):
    val lexer = Lexer("ORDER BY age DESC")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.ORDER,
        Token.BY,
        Token.Identifier("age"),
        Token.DESC,
        Token.EOF
      )
    )

  test("tokenizes LIMIT"):
    val lexer = Lexer("LIMIT 100")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.LIMIT,
        Token.IntegerLiteral(100),
        Token.EOF
      )
    )

  test("tokenizes qualified identifier"):
    val lexer = Lexer("users.name")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.Identifier("users"),
        Token.Dot,
        Token.Identifier("name"),
        Token.EOF
      )
    )

  test("tokenizes quoted identifier"):
    val lexer = Lexer("\"column name\"")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.Identifier("column name"),
        Token.EOF
      )
    )

  test("tokenizes parentheses and comma"):
    val lexer = Lexer("(a, b)")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.LParen,
        Token.Identifier("a"),
        Token.Comma,
        Token.Identifier("b"),
        Token.RParen,
        Token.EOF
      )
    )

  test("tokenizes IS NULL"):
    val lexer = Lexer("IS NULL")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.IS,
        Token.NULL,
        Token.EOF
      )
    )

  test("tokenizes TRUE and FALSE"):
    val lexer = Lexer("TRUE FALSE")
    val result = lexer.tokenize()

    assert(result.isRight)
    val tokens = result.toOption.get
    assertEquals(
      tokens,
      Vector(
        Token.TRUE,
        Token.FALSE,
        Token.EOF
      )
    )

  test("reports error on unterminated string"):
    val lexer = Lexer("'hello")
    val result = lexer.tokenize()

    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("Unterminated"))

  test("reports error on unexpected character"):
    val lexer = Lexer("SELECT @invalid")
    val result = lexer.tokenize()

    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("Unexpected"))
