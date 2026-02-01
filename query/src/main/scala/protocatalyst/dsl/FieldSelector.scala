package protocatalyst.dsl

import protocatalyst.encoder.*
import protocatalyst.types.*
import scala.language.dynamics

/** Dynamic field selector that provides typed column access.
  *
  * Uses Scala's Dynamic trait to intercept field access and return typed Column references. The
  * macro ensures type safety at compile time.
  *
  * Usage:
  * {{{
  * case class User(name: String, age: Int) derives ProtoEncoder
  * val users = Table[User]("users")
  *
  * // These are equivalent:
  * users.filter(_.age > 18)
  * users.filter(users.col[Int]("age") > Expr.lit(18))
  * }}}
  */
final class FieldSelector[A] private[dsl] (
    private val encoder: ProtoEncoder[A],
    private val qualifier: Option[String] = None
) extends Dynamic:

  /** Dynamic field access - returns a Column for the field. Type safety is enforced by the macro in
    * TypedOps.
    */
  def selectDynamic(fieldName: String): Column[A, Any] =
    encoder.fields.find(_.name == fieldName) match
      case Some(field) =>
        Column[A, Any](
          fieldName,
          field.encoder.catalystType,
          field.nullable,
          field.encoder.asInstanceOf[ProtoEncoder[Any]]
        )
      case None =>
        throw new IllegalArgumentException(
          s"Field '$fieldName' not found. Available: ${encoder.fields.map(_.name).mkString(", ")}"
        )

object FieldSelector:
  def apply[A](using enc: ProtoEncoder[A]): FieldSelector[A] =
    new FieldSelector[A](enc)
