package protocatalyst.sql.lexer

/** SQL lexer that tokenizes input strings. */
class Lexer(input: String):
  private var pos: Int = 0
  private var line: Int = 1
  private var col: Int = 1

  /** Current position in the input. */
  def position: Int = pos

  /** Current line number (1-indexed). */
  def currentLine: Int = line

  /** Current column number (1-indexed). */
  def currentCol: Int = col

  /** Tokenize the entire input. */
  def tokenize(): Either[LexerError, Vector[Token]] =
    val tokens = Vector.newBuilder[Token]
    var continue = true

    while continue do
      nextToken() match
        case Right(Token.EOF) =>
          tokens += Token.EOF
          continue = false
        case Right(token) =>
          tokens += token
        case Left(error) =>
          return Left(error)

    Right(tokens.result())

  /** Get the next token. */
  def nextToken(): Either[LexerError, Token] =
    skipWhitespace()

    if pos >= input.length then Right(Token.EOF)
    else
      val c = current
      c match
        case '(' => advance(); Right(Token.LParen)
        case ')' => advance(); Right(Token.RParen)
        case ',' => advance(); Right(Token.Comma)
        case '.' => advance(); Right(Token.Dot)
        case '+' => advance(); Right(Token.Plus)
        case '-' =>
          advance()
          if pos < input.length && current == '-' then
            // Line comment: skip to end of line
            skipLineComment()
            nextToken() // Skip and get next token
          else if pos < input.length && current.isDigit then readNumber(negative = true)
          else Right(Token.Minus)
        case '*' => advance(); Right(Token.Star)
        case '/' =>
          advance()
          if pos < input.length && current == '*' then
            // Block comment - check if it's a hint (/*+)
            advance()
            if pos < input.length && current == '+' then
              advance()
              readHint()
            else
              skipBlockComment()
              nextToken() // Skip and get next token
          else if pos < input.length && current == '-' then
            // Possibly -- line comment (rare but /- is not valid)
            Right(Token.Slash) // Just return slash, let parser handle invalid syntax
          else Right(Token.Slash)
        case '=' => advance(); Right(Token.Eq)
        case '<' =>
          advance()
          if pos < input.length && current == '=' then
            advance(); Right(Token.LtEq)
          else if pos < input.length && current == '>' then
            advance(); Right(Token.NotEq)
          else Right(Token.Lt)
        case '>' =>
          advance()
          if pos < input.length && current == '=' then
            advance(); Right(Token.GtEq)
          else Right(Token.Gt)
        case '!' =>
          advance()
          if pos < input.length && current == '=' then
            advance(); Right(Token.NotEq)
          else Left(LexerError(s"Expected '=' after '!'", line, col - 1))
        case '\''                        => readString()
        case '"'                         => readQuotedIdentifier()
        case c if c.isDigit              => readNumber(negative = false)
        case c if c.isLetter || c == '_' => readIdentifierOrKeyword()
        case c => Left(LexerError(s"Unexpected character: '$c'", line, col))

  private def current: Char = input.charAt(pos)

  private def advance(): Unit =
    if pos < input.length then
      if current == '\n' then
        line += 1
        col = 1
      else col += 1
      pos += 1

  private def skipWhitespace(): Unit =
    while pos < input.length && current.isWhitespace do advance()

  /** Skip a line comment (from -- to end of line). */
  private def skipLineComment(): Unit =
    while pos < input.length && current != '\n' do advance()
    if pos < input.length then advance() // Skip the newline

  /** Skip a block comment (from current position to closing star-slash). */
  private def skipBlockComment(): Unit =
    while pos < input.length do
      if current == '*' && pos + 1 < input.length && input.charAt(pos + 1) == '/' then
        advance() // skip *
        advance() // skip /
        return
      advance()

  /** Read a hint comment (after /*+, until */). */
  private def readHint(): Either[LexerError, Token] =
    val startLine = line
    val startCol = col
    val sb = new StringBuilder

    while pos < input.length do
      if current == '*' && pos + 1 < input.length && input.charAt(pos + 1) == '/' then
        advance() // skip *
        advance() // skip /
        return Right(Token.Hint(sb.toString.trim))
      sb.append(current)
      advance()

    Left(LexerError("Unterminated hint comment", startLine, startCol))

  private def readNumber(negative: Boolean): Either[LexerError, Token] =
    col
    val sb = new StringBuilder
    if negative then sb.append('-')

    while pos < input.length && current.isDigit do
      sb.append(current)
      advance()

    // Check for decimal point
    if pos < input.length && current == '.' then
      sb.append(current)
      advance()
      if pos >= input.length || !current.isDigit then
        return Left(LexerError("Expected digit after decimal point", line, col))
      while pos < input.length && current.isDigit do
        sb.append(current)
        advance()
      Right(Token.DoubleLiteral(sb.toString.toDouble))
    else Right(Token.IntegerLiteral(sb.toString.toLong))

  private def readString(): Either[LexerError, Token] =
    val startLine = line
    val startCol = col
    advance() // skip opening quote
    val sb = new StringBuilder

    while pos < input.length && current != '\'' do
      if current == '\\' then
        advance()
        if pos >= input.length then
          return Left(LexerError("Unterminated string", startLine, startCol))
        current match
          case 'n'  => sb.append('\n')
          case 't'  => sb.append('\t')
          case 'r'  => sb.append('\r')
          case '\'' => sb.append('\'')
          case '\\' => sb.append('\\')
          case c    => sb.append(c)
      else sb.append(current)
      advance()

    if pos >= input.length then Left(LexerError("Unterminated string", startLine, startCol))
    else
      advance() // skip closing quote
      Right(Token.StringLiteral(sb.toString))

  private def readQuotedIdentifier(): Either[LexerError, Token] =
    val startLine = line
    val startCol = col
    advance() // skip opening quote
    val sb = new StringBuilder

    while pos < input.length && current != '"' do
      sb.append(current)
      advance()

    if pos >= input.length then
      Left(LexerError("Unterminated quoted identifier", startLine, startCol))
    else
      advance() // skip closing quote
      Right(Token.Identifier(sb.toString))

  private def readIdentifierOrKeyword(): Either[LexerError, Token] =
    val sb = new StringBuilder

    while pos < input.length && (current.isLetterOrDigit || current == '_') do
      sb.append(current)
      advance()

    val word = sb.toString
    val upper = word.toUpperCase

    Token.keywords.get(upper) match
      case Some(keyword) => Right(keyword)
      case None          => Right(Token.Identifier(word))

/** Lexer error with position information. */
case class LexerError(message: String, line: Int, col: Int):
  override def toString: String = s"Lexer error at $line:$col: $message"
