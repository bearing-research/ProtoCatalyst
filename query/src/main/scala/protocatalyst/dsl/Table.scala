package protocatalyst.dsl

import protocatalyst.encoder._
import protocatalyst.plan._
import protocatalyst.schema._

/** Entry point for building type-safe queries.
  *
  * Usage:
  * {{{
  * case class User(name: String, age: Int) derives ProtoEncoder
  *
  * val users = Table[User]("users")
  * val query = users
  *   .filter(users.col[Int]("age") > Expr.lit(18))
  *   .select(users.col[String]("name"), users.col[Int]("age"))
  * }}}
  */
final class Table[A] private (
    val tableName: String,
    val encoder: ProtoEncoder[A],
    private val schemaContract: SchemaContract
):
  private val baseQuery: Query[A] = new Query[A](
    ProtoLogicalPlan.RelationRef(tableName, None, schemaContract),
    encoder,
    Vector(schemaContract)
  )

  /** Get a typed column by name */
  def col[T](name: String)(using colEnc: ProtoEncoder[T]): Column[A, T] =
    encoder.fields.find(_.name == name) match
      case Some(field) =>
        Column[A, T](name, field.encoder.catalystType, field.nullable, colEnc)
      case None =>
        throw new IllegalArgumentException(
          s"Column '$name' not found in ${encoder.schema.fields.map(_.name).mkString(", ")}"
        )

  /** Get all columns */
  def columns: Vector[Column[A, ?]] =
    encoder.fields.map { field =>
      Column[A, Any](
        field.name,
        field.encoder.catalystType,
        field.nullable,
        field.encoder.asInstanceOf[ProtoEncoder[Any]]
      )
    }

  /** Get a field selector for lambda-style access */
  def row: FieldSelector[A] = FieldSelector[A](using encoder)

  // Delegate query operations to baseQuery

  /** Filter rows matching the predicate */
  def filter(predicate: Expr[Boolean]): Query[A] = baseQuery.filter(predicate)

  /** Filter with lambda-style field access: users.filter(_.age > 18) */
  def filter(f: FieldSelector[A] => Expr[Boolean]): Query[A] =
    baseQuery.filter(f(FieldSelector[A](using encoder)))

  /** Alias for filter */
  def where(predicate: Expr[Boolean]): Query[A] = baseQuery.where(predicate)

  /** Where with lambda-style field access */
  def where(f: FieldSelector[A] => Expr[Boolean]): Query[A] =
    baseQuery.filter(f(FieldSelector[A](using encoder)))

  /** Select with lambda-style field access: table.select(_.name) */
  def select[B](f: FieldSelector[A] => Expr[B])(using enc: ProtoEncoder[B]): Query[B] =
    baseQuery.select(f)

  /** Project to selected expressions */
  def select[B](exprs: Expr[B]*)(using enc: ProtoEncoder[B]): Query[B] = baseQuery.select(exprs*)

  /** Select with lambda-style tuple output - 2 columns: table.select(u => (u.name, u.age)) */
  def select[B1, B2](f: FieldSelector[A] => (Expr[B1], Expr[B2]))(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2]
  ): Query[(B1, B2)] = baseQuery.select(f)

  /** Select with lambda-style tuple output - 3 columns */
  def select[B1, B2, B3](f: FieldSelector[A] => (Expr[B1], Expr[B2], Expr[B3]))(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2],
      enc3: ProtoEncoder[B3]
  ): Query[(B1, B2, B3)] = baseQuery.select(f)

  /** Select with lambda-style tuple output - 4 columns */
  def select[B1, B2, B3, B4](
      f: FieldSelector[A] => (Expr[B1], Expr[B2], Expr[B3], Expr[B4])
  )(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2],
      enc3: ProtoEncoder[B3],
      enc4: ProtoEncoder[B4]
  ): Query[(B1, B2, B3, B4)] = baseQuery.select(f)

  /** Select with tuple output - 2 columns */
  def select[B1, B2](e1: Expr[B1], e2: Expr[B2])(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2]
  ): Query[(B1, B2)] = baseQuery.select(e1, e2)

  /** Select with tuple output - 3 columns */
  def select[B1, B2, B3](e1: Expr[B1], e2: Expr[B2], e3: Expr[B3])(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2],
      enc3: ProtoEncoder[B3]
  ): Query[(B1, B2, B3)] = baseQuery.select(e1, e2, e3)

  /** Select with tuple output - 4 columns */
  def select[B1, B2, B3, B4](e1: Expr[B1], e2: Expr[B2], e3: Expr[B3], e4: Expr[B4])(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2],
      enc3: ProtoEncoder[B3],
      enc4: ProtoEncoder[B4]
  ): Query[(B1, B2, B3, B4)] = baseQuery.select(e1, e2, e3, e4)

  /** Sort by expressions */
  def orderBy(orders: SortExpr*): Query[A] = baseQuery.orderBy(orders*)

  /** Limit results */
  def limit(n: Int): Query[A] = baseQuery.limit(n)

  /** Distinct rows */
  def distinct: Query[A] = baseQuery.distinct

  /** Alias this table */
  def as(alias: String): Query[A] = baseQuery.as(alias)

  /** Add optimizer hints */
  def hint(hints: PlanHint*): Query[A] = baseQuery.hint(hints*)

  /** Group by with lambda-style field access: table.groupBy(_.age) */
  def groupBy[K](f: FieldSelector[A] => Expr[K]): GroupedQuery[A, K] = baseQuery.groupBy(f)

  /** Group by expressions for aggregation */
  def groupBy[K](keys: Expr[K]*): GroupedQuery[A, K] = baseQuery.groupBy(keys*)

  /** Inner join with another query */
  def join[B](other: Query[B]): JoinBuilder[A, B] = baseQuery.join(other)

  /** Inner join with another table */
  def join[B](other: Table[B]): JoinBuilder[A, B] = baseQuery.join(other.toQuery)

  /** Left outer join */
  def leftJoin[B](other: Query[B]): JoinBuilder[A, B] = baseQuery.leftJoin(other)

  /** Left outer join with table */
  def leftJoin[B](other: Table[B]): JoinBuilder[A, B] = baseQuery.leftJoin(other.toQuery)

  /** Right outer join */
  def rightJoin[B](other: Query[B]): JoinBuilder[A, B] = baseQuery.rightJoin(other)

  /** Right outer join with table */
  def rightJoin[B](other: Table[B]): JoinBuilder[A, B] = baseQuery.rightJoin(other.toQuery)

  /** Cross join */
  def crossJoin[B](other: Query[B])(using encB: ProtoEncoder[B]): Query[(A, B)] =
    baseQuery.crossJoin(other)

  /** Cross join with table */
  def crossJoin[B](other: Table[B])(using encB: ProtoEncoder[B]): Query[(A, B)] =
    baseQuery.crossJoin(other.toQuery)

  /** Union with another query */
  def union(other: Query[A]): Query[A] = baseQuery.union(other)

  /** Intersect with another query */
  def intersect(other: Query[A]): Query[A] = baseQuery.intersect(other)

  /** Intersect all with another query */
  def intersectAll(other: Query[A]): Query[A] = baseQuery.intersectAll(other)

  /** Except (set difference) with another query */
  def except(other: Query[A]): Query[A] = baseQuery.except(other)

  /** Except all with another query */
  def exceptAll(other: Query[A]): Query[A] = baseQuery.exceptAll(other)

  /** Compile this query to an artifact */
  def compile: protocatalyst.query.CompiledQuery[A] = baseQuery.compile

  /** Convert to Query */
  def toQuery: Query[A] = baseQuery

object Table:
  /** Create a table reference with compile-time schema derivation.
    *
    * @param tableName
    *   The table name as it appears in the catalog
    */
  def apply[A](tableName: String)(using enc: ProtoEncoder[A]): Table[A] =
    val fields = enc.schema.fields
    val fingerprint = SchemaFingerprint.compute(fields)
    val fieldContracts = fields.map { f =>
      FieldContract(f.name, f.dataType, f.nullable)
    }
    val contract = SchemaContract(tableName, fieldContracts, fingerprint)
    new Table[A](tableName, enc, contract)

  /** Create a table with an explicit schema contract. Use this when you want to specify a
    * pre-computed schema fingerprint.
    */
  def withContract[A](tableName: String, contract: SchemaContract)(using
      enc: ProtoEncoder[A]
  ): Table[A] =
    new Table[A](tableName, enc, contract)
