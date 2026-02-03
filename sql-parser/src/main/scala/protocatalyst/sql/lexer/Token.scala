package protocatalyst.sql.lexer

/** SQL tokens for the lexer. */
enum Token:
  // Keywords
  case SELECT, FROM, WHERE, ORDER, BY, ASC, DESC, LIMIT, DISTINCT
  case AND, OR, NOT, IS, NULL, TRUE, FALSE, AS
  case BETWEEN, LIKE, IN, ESCAPE
  case JOIN, INNER, LEFT, RIGHT, FULL, OUTER, CROSS, ON
  case GROUP, HAVING
  // Advanced grouping keywords
  case CUBE, ROLLUP, SETS, GROUPING
  case CASE, WHEN, THEN, ELSE, END, CAST
  case EXISTS
  // Date/time keywords
  case EXTRACT, INTERVAL
  case UNION, INTERSECT, EXCEPT, ALL
  // CTE keywords
  case WITH, RECURSIVE
  // Window function keywords
  case OVER, PARTITION, ROWS, RANGE, UNBOUNDED, PRECEDING, FOLLOWING, CURRENT, ROW
  // PIVOT/UNPIVOT keywords
  case PIVOT, UNPIVOT, FOR
  // LATERAL keyword
  case LATERAL
  // LATERAL VIEW keywords
  case VIEW
  // VALUES keyword
  case VALUES

  // Literals
  case IntegerLiteral(value: Long)
  case DoubleLiteral(value: Double)
  case StringLiteral(value: String)
  case Identifier(name: String)

  // Comparison operators
  case Eq // =
  case NotEq // <> or !=
  case Lt // <
  case LtEq // <=
  case Gt // >
  case GtEq // >=

  // Arithmetic operators
  case Plus // +
  case Minus // -
  case Star // *
  case Slash // /

  // Punctuation
  case LParen // (
  case RParen // )
  case Comma // ,
  case Dot // .

  // Hint (optimizer hint from /*+ ... */ comments)
  case Hint(content: String)

  // Special
  case EOF

object Token:
  /** Keywords lookup (case-insensitive). */
  val keywords: Map[String, Token] = Map(
    "SELECT" -> Token.SELECT,
    "FROM" -> Token.FROM,
    "WHERE" -> Token.WHERE,
    "ORDER" -> Token.ORDER,
    "BY" -> Token.BY,
    "ASC" -> Token.ASC,
    "DESC" -> Token.DESC,
    "LIMIT" -> Token.LIMIT,
    "DISTINCT" -> Token.DISTINCT,
    "AND" -> Token.AND,
    "OR" -> Token.OR,
    "NOT" -> Token.NOT,
    "IS" -> Token.IS,
    "NULL" -> Token.NULL,
    "TRUE" -> Token.TRUE,
    "FALSE" -> Token.FALSE,
    "AS" -> Token.AS,
    "BETWEEN" -> Token.BETWEEN,
    "LIKE" -> Token.LIKE,
    "IN" -> Token.IN,
    "ESCAPE" -> Token.ESCAPE,
    "JOIN" -> Token.JOIN,
    "INNER" -> Token.INNER,
    "LEFT" -> Token.LEFT,
    "RIGHT" -> Token.RIGHT,
    "FULL" -> Token.FULL,
    "OUTER" -> Token.OUTER,
    "CROSS" -> Token.CROSS,
    "ON" -> Token.ON,
    "GROUP" -> Token.GROUP,
    "HAVING" -> Token.HAVING,
    "CUBE" -> Token.CUBE,
    "ROLLUP" -> Token.ROLLUP,
    "SETS" -> Token.SETS,
    "GROUPING" -> Token.GROUPING,
    "CASE" -> Token.CASE,
    "WHEN" -> Token.WHEN,
    "THEN" -> Token.THEN,
    "ELSE" -> Token.ELSE,
    "END" -> Token.END,
    "CAST" -> Token.CAST,
    "EXISTS" -> Token.EXISTS,
    "UNION" -> Token.UNION,
    "INTERSECT" -> Token.INTERSECT,
    "EXCEPT" -> Token.EXCEPT,
    "ALL" -> Token.ALL,
    "WITH" -> Token.WITH,
    "RECURSIVE" -> Token.RECURSIVE,
    "OVER" -> Token.OVER,
    "PARTITION" -> Token.PARTITION,
    "ROWS" -> Token.ROWS,
    "RANGE" -> Token.RANGE,
    "UNBOUNDED" -> Token.UNBOUNDED,
    "PRECEDING" -> Token.PRECEDING,
    "FOLLOWING" -> Token.FOLLOWING,
    "CURRENT" -> Token.CURRENT,
    "ROW" -> Token.ROW,
    "EXTRACT" -> Token.EXTRACT,
    "INTERVAL" -> Token.INTERVAL,
    "PIVOT" -> Token.PIVOT,
    "UNPIVOT" -> Token.UNPIVOT,
    "FOR" -> Token.FOR,
    "LATERAL" -> Token.LATERAL,
    "VIEW" -> Token.VIEW,
    "VALUES" -> Token.VALUES
  )
