package protocatalyst.dsl

import scala.language.dynamics
import scala.quoted._

import protocatalyst.encoder._

/** Dynamic field selector that provides typed column access.
  *
  * Uses Scala's Dynamic trait with a `transparent inline` macro to intercept field access and
  * return correctly typed Column references. The macro inspects the case class type parameter `A`
  * at compile time to determine the actual field type.
  *
  * Usage:
  * {{{
  * case class User(name: String, age: Int) derives ProtoEncoder
  * val users = Table[User]("users")
  *
  * // _.name returns Column[User, String] (not Column[User, Any])
  * users.filter(_.age > 18)
  * users.select(_.name)
  * }}}
  */
final class FieldSelector[A] private[dsl] (
    private[dsl] val encoder: ProtoEncoder[A],
    private val qualifier: Option[String] = None
) extends Dynamic:

  /** Dynamic field access - delegates to the static inline macro helper to produce a correctly
    * typed Column[A, T].
    */
  transparent inline def selectDynamic(inline fieldName: String): Any =
    fieldSelectorField[A](this, fieldName)

object FieldSelector:
  def apply[A](using enc: ProtoEncoder[A]): FieldSelector[A] =
    new FieldSelector[A](enc)

  /** Non-inline helper that produces a Column[A, T]. Preserves the FieldSelector reference in the
    * AST so that QuoteMacro can extract the lambda parameter name for join conditions.
    */
  def typedColumn[A, T](fs: FieldSelector[A], fieldName: String)(using
      enc: ProtoEncoder[T]
  ): Column[A, T] =
    Column.apply[A, T](fieldName)(using enc)

/** Top-level inline macro helper for FieldSelector.selectDynamic. Must be at the top level (static
  * scope) because Scala 3 does not allow macro splices in non-static inline methods.
  */
private[dsl] transparent inline def fieldSelectorField[A](
    self: FieldSelector[A],
    inline fieldName: String
): Any =
  ${ FieldSelectorMacros.selectDynamicImpl[A]('self, 'fieldName) }

private[dsl] object FieldSelectorMacros:

  /** Macro implementation: inspects the case class A to find the field type and produces a
    * FieldSelector.typedColumn[A, T](self, fieldName) call with the correct type parameter T.
    */
  def selectDynamicImpl[A: Type](
      selfExpr: Expr[FieldSelector[A]],
      fieldNameExpr: Expr[String]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*

    val fieldName = fieldNameExpr.valueOrAbort
    val tpeA = TypeRepr.of[A].dealias

    val sym = tpeA.classSymbol.getOrElse(
      report.errorAndAbort(s"Type ${tpeA.show} has no class symbol")
    )
    if !sym.flags.is(Flags.Case) then report.errorAndAbort(s"Type ${tpeA.show} is not a case class")

    val caseFields = sym.caseFields
    val fieldSymbol = caseFields
      .find(_.name == fieldName)
      .getOrElse(
        report.errorAndAbort(
          s"Field '$fieldName' not found in ${tpeA.show}. " +
            s"Available fields: ${caseFields.map(_.name).mkString(", ")}"
        )
      )

    val fieldTypeRepr = tpeA.memberType(fieldSymbol)

    fieldTypeRepr.asType match
      case '[fieldType] =>
        Expr.summon[ProtoEncoder[fieldType]] match
          case Some(encExpr) =>
            '{
              FieldSelector.typedColumn[A, fieldType]($selfExpr, ${ Expr(fieldName) })(using
                $encExpr
              )
            }
          case None =>
            report.errorAndAbort(
              s"No ProtoEncoder found for field type ${fieldTypeRepr.show} " +
                s"(field '$fieldName' in ${tpeA.show})"
            )
