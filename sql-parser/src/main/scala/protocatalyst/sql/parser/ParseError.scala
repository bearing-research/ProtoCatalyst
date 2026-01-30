package protocatalyst.sql.parser

import protocatalyst.sql.lexer.Token

/** SQL parse errors. */
sealed trait ParseError:
  def message: String
  def position: Option[ParsePosition]

  def format: String = position match
    case Some(pos) => s"Parse error at ${pos.line}:${pos.col}: $message"
    case None => s"Parse error: $message"

object ParseError:
  /** Unexpected token error. */
  case class UnexpectedToken(
    found: Token,
    expected: String,
    override val position: Option[ParsePosition]
  ) extends ParseError:
    override def message: String = s"Unexpected token $found, expected $expected"

  /** Unexpected end of input. */
  case class UnexpectedEOF(
    expected: String,
    override val position: Option[ParsePosition]
  ) extends ParseError:
    override def message: String = s"Unexpected end of input, expected $expected"

  /** Generic syntax error. */
  case class SyntaxError(
    override val message: String,
    override val position: Option[ParsePosition]
  ) extends ParseError

/** Position in the source. */
case class ParsePosition(line: Int, col: Int)
