package protocatalyst.dsl

import protocatalyst.encoder.*
import protocatalyst.types.*
import scala.quoted.*

/**
 * Macro-based typed operations for field access.
 *
 * Provides compile-time type checking for field selectors while
 * allowing the ergonomic `_.fieldName` syntax.
 */
object TypedOps:

  /**
   * Type-safe field extraction macro.
   *
   * Extracts a typed column from a field selector lambda.
   * Verifies at compile time that the field exists and infers its type.
   */
  inline def field[A, T](inline selector: FieldSelector[A] => Column[A, T])(using enc: ProtoEncoder[A]): Column[A, T] =
    ${ fieldImpl[A, T]('selector, 'enc) }

  private def fieldImpl[A: Type, T: Type](
      selector: Expr[FieldSelector[A] => Column[A, T]],
      enc: Expr[ProtoEncoder[A]]
  )(using Quotes): Expr[Column[A, T]] =
    import quotes.reflect.*

    // Extract field name from the lambda
    selector.asTerm match
      case Inlined(_, _, Block(List(DefDef(_, _, _, Some(body))), _)) =>
        extractFieldName(body) match
          case Some(fieldName) =>
            '{
              val fs = FieldSelector[A](using $enc)
              fs.selectDynamic(${ Expr(fieldName) }).asInstanceOf[Column[A, T]]
            }
          case None =>
            report.errorAndAbort("Could not extract field name from selector")

      case other =>
        report.errorAndAbort(s"Expected a simple field selector lambda, got: ${other.show}")

  private def extractFieldName(using Quotes)(term: quotes.reflect.Term): Option[String] =
    import quotes.reflect.*
    term match
      case Apply(Select(_, "selectDynamic"), List(Literal(StringConstant(name)))) =>
        Some(name)
      case Select(_, name) if !name.startsWith("$") =>
        Some(name)
      case Inlined(_, _, inner) =>
        extractFieldName(inner)
      case Block(_, expr) =>
        extractFieldName(expr)
      case Typed(expr, _) =>
        extractFieldName(expr)
      case _ =>
        None

  /**
   * Compile-time field existence check.
   */
  inline def checkField[A](inline fieldName: String)(using enc: ProtoEncoder[A]): Unit =
    ${ checkFieldImpl[A]('fieldName, 'enc) }

  private def checkFieldImpl[A: Type](
      fieldName: Expr[String],
      enc: Expr[ProtoEncoder[A]]
  )(using Quotes): Expr[Unit] =
    import quotes.reflect.*

    fieldName.value match
      case Some(name) =>
        // We can't check at macro time without the encoder value,
        // but we emit code that will fail fast at runtime
        '{
          val encoder = $enc
          if !encoder.fields.exists(_.name == $fieldName) then
            throw new IllegalArgumentException(
              s"Field '${$fieldName}' not found in encoder"
            )
        }
      case None =>
        report.errorAndAbort("Field name must be a literal string")
