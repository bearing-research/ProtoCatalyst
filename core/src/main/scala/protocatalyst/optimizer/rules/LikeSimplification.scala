package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Converts LIKE patterns to simpler operations when possible.
  *
  * Examples:
  *   - `x LIKE 'abc'` → `x = 'abc'` (no wildcards)
  *   - `x LIKE 'abc%'` → `LOCATE('abc', x) = 1` (starts with)
  *   - `x LIKE '%abc'` → `LOCATE('abc', x) = LENGTH(x) - LENGTH('abc') + 1` (ends with)
  *   - `x LIKE '%abc%'` → `LOCATE('abc', x) > 0` (contains)
  *   - `x LIKE '%'` → `x IS NOT NULL` (matches everything except null)
  *   - `x LIKE ''` → `x = ''` (matches empty string)
  *
  * Based on Spark Catalyst's LikeSimplification rule (expressions.scala:500-550).
  *
  * Note: This implementation only handles patterns without escape characters. Patterns with escape
  * sequences or complex wildcards are left unchanged.
  */
object LikeSimplification extends Rule:
  override val ruleName: String = "LikeSimplification"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(simplifyLike)

  /** Simplify LIKE expressions where possible. */
  def simplifyLike(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Only handle LIKE with literal string pattern and no escape
      case ProtoExpr.Like(value, ProtoExpr.Literal(LiteralValue.StringValue(pattern)), None) =>
        simplifyPattern(value, pattern)

      // LIKE with empty/blank escape treated same as no escape for simple patterns
      case ProtoExpr.Like(
            value,
            ProtoExpr.Literal(LiteralValue.StringValue(pattern)),
            Some(ProtoExpr.Literal(LiteralValue.StringValue("")))
          ) =>
        simplifyPattern(value, pattern)
    }

  /** Simplify a LIKE pattern. */
  private def simplifyPattern(value: ProtoExpr, pattern: String): ProtoExpr =
    pattern match
      // Empty pattern - matches empty string
      case "" =>
        ProtoExpr.Eq(value, ProtoExpr.Literal(LiteralValue.StringValue("")))

      // Just '%' - matches anything (except null)
      case "%" =>
        ProtoExpr.IsNotNull(value)

      // Just multiple '%' - same as single '%'
      case p if p.forall(_ == '%') =>
        ProtoExpr.IsNotNull(value)

      // No wildcards at all - exact match
      case p if !p.contains('%') && !p.contains('_') =>
        ProtoExpr.Eq(value, ProtoExpr.Literal(LiteralValue.StringValue(p)))

      // Prefix match: 'abc%' (no other wildcards)
      case p if p.endsWith("%") && !p.dropRight(1).exists(isWildcard) =>
        val prefix = p.dropRight(1)
        // LOCATE(prefix, value) = 1
        ProtoExpr.Eq(
          ProtoExpr.StringLocate(
            ProtoExpr.Literal(LiteralValue.StringValue(prefix)),
            value,
            None
          ),
          ProtoExpr.Literal(LiteralValue.IntValue(1))
        )

      // Suffix match: '%abc' (no other wildcards)
      case p if p.startsWith("%") && !p.drop(1).exists(isWildcard) =>
        val suffix = p.drop(1)
        // LOCATE(suffix, value) = LENGTH(value) - LENGTH(suffix) + 1
        // Simplified: check that the suffix appears at the expected position
        // Using: LOCATE(suffix, value, LENGTH(value) - LENGTH(suffix) + 1) > 0
        // Or more directly: right portion equals suffix
        // For compile-time optimization, we check:
        // LOCATE(suffix, value) = LENGTH(value) - len(suffix) + 1
        val suffixLen = suffix.length
        ProtoExpr.Eq(
          ProtoExpr.StringLocate(
            ProtoExpr.Literal(LiteralValue.StringValue(suffix)),
            value,
            None
          ),
          ProtoExpr.Add(
            ProtoExpr.Subtract(
              ProtoExpr.Length(value),
              ProtoExpr.Literal(LiteralValue.IntValue(suffixLen))
            ),
            ProtoExpr.Literal(LiteralValue.IntValue(1))
          )
        )

      // Contains match: '%abc%' (no other wildcards in the middle)
      case p
          if p.startsWith("%") && p.endsWith("%") && p.length > 2 &&
            !p.drop(1).dropRight(1).exists(isWildcard) =>
        val substring = p.drop(1).dropRight(1)
        // LOCATE(substring, value) > 0
        ProtoExpr.Gt(
          ProtoExpr.StringLocate(
            ProtoExpr.Literal(LiteralValue.StringValue(substring)),
            value,
            None
          ),
          ProtoExpr.Literal(LiteralValue.IntValue(0))
        )

      // Complex pattern - keep as LIKE
      case _ =>
        ProtoExpr.Like(value, ProtoExpr.Literal(LiteralValue.StringValue(pattern)), None)

  /** Check if a character is a wildcard. */
  private def isWildcard(c: Char): Boolean = c == '%' || c == '_'
