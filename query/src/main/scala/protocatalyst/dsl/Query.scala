package protocatalyst.dsl

import protocatalyst.artifact._
import protocatalyst.encoder._
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan._
import protocatalyst.query._
import protocatalyst.schema._

/** Type-safe query builder.
  *
  * @tparam A
  *   The row type of this query's output
  */
class Query[A] private[dsl] (
    private[dsl] val plan: ProtoLogicalPlan,
    private[dsl] val outputEncoder: ProtoEncoder[A],
    private[dsl] val schemaContracts: Vector[SchemaContract]
):

  /** Get a field selector for lambda-style access */
  def row: FieldSelector[A] = FieldSelector[A](using outputEncoder)

  /** Filter rows matching the predicate */
  def filter(predicate: Expr[Boolean]): Query[A] =
    new Query(
      ProtoLogicalPlan.Filter(predicate.toProtoExpr, plan),
      outputEncoder,
      schemaContracts
    )

  /** Filter with lambda-style field access: query.filter(_.age > 18) */
  def filter(f: FieldSelector[A] => Expr[Boolean]): Query[A] =
    filter(f(FieldSelector[A](using outputEncoder)))

  /** Alias for filter */
  def where(predicate: Expr[Boolean]): Query[A] = filter(predicate)

  /** Where with lambda-style field access */
  def where(f: FieldSelector[A] => Expr[Boolean]): Query[A] =
    filter(f(FieldSelector[A](using outputEncoder)))

  /** Select with lambda-style field access: query.select(_.name) */
  def select[B](f: FieldSelector[A] => Expr[B])(using enc: ProtoEncoder[B]): Query[B] =
    select(f(FieldSelector[A](using outputEncoder)))

  /** Project to selected expressions */
  def select[B](exprs: Expr[B]*)(using enc: ProtoEncoder[B]): Query[B] =
    new Query(
      ProtoLogicalPlan.Project(exprs.map(_.toProtoExpr).toVector, plan),
      enc,
      schemaContracts
    )

  /** Select with tuple output - 2 columns */
  def select[B1, B2](
      e1: Expr[B1],
      e2: Expr[B2]
  )(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2]
  ): Query[(B1, B2)] =
    given ProtoEncoder[(B1, B2)] = ProtoEncoder.tuple2Encoder[B1, B2]
    new Query(
      ProtoLogicalPlan.Project(Vector(e1.toProtoExpr, e2.toProtoExpr), plan),
      summon[ProtoEncoder[(B1, B2)]],
      schemaContracts
    )

  /** Select with tuple output - 3 columns */
  def select[B1, B2, B3](
      e1: Expr[B1],
      e2: Expr[B2],
      e3: Expr[B3]
  )(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2],
      enc3: ProtoEncoder[B3]
  ): Query[(B1, B2, B3)] =
    given ProtoEncoder[(B1, B2, B3)] = ProtoEncoder.tuple3Encoder[B1, B2, B3]
    new Query(
      ProtoLogicalPlan.Project(Vector(e1.toProtoExpr, e2.toProtoExpr, e3.toProtoExpr), plan),
      summon[ProtoEncoder[(B1, B2, B3)]],
      schemaContracts
    )

  /** Select with tuple output - 4 columns */
  def select[B1, B2, B3, B4](
      e1: Expr[B1],
      e2: Expr[B2],
      e3: Expr[B3],
      e4: Expr[B4]
  )(using
      enc1: ProtoEncoder[B1],
      enc2: ProtoEncoder[B2],
      enc3: ProtoEncoder[B3],
      enc4: ProtoEncoder[B4]
  ): Query[(B1, B2, B3, B4)] =
    given ProtoEncoder[(B1, B2, B3, B4)] = ProtoEncoder.tuple4Encoder[B1, B2, B3, B4]
    new Query(
      ProtoLogicalPlan.Project(
        Vector(e1.toProtoExpr, e2.toProtoExpr, e3.toProtoExpr, e4.toProtoExpr),
        plan
      ),
      summon[ProtoEncoder[(B1, B2, B3, B4)]],
      schemaContracts
    )

  /** Sort by expressions */
  def orderBy(orders: SortExpr*): Query[A] =
    new Query(
      ProtoLogicalPlan.Sort(
        orders.map(_.toSortOrder).toVector,
        global = true,
        plan
      ),
      outputEncoder,
      schemaContracts
    )

  /** Limit results */
  def limit(n: Int): Query[A] =
    new Query(
      ProtoLogicalPlan.Limit(n, plan),
      outputEncoder,
      schemaContracts
    )

  /** Distinct rows */
  def distinct: Query[A] =
    new Query(
      ProtoLogicalPlan.Distinct(plan),
      outputEncoder,
      schemaContracts
    )

  /** Alias this query for use in joins */
  def as(alias: String): Query[A] =
    new Query(
      ProtoLogicalPlan.SubqueryAlias(alias, plan),
      outputEncoder,
      schemaContracts
    )

  /** Group by with lambda-style field access: query.groupBy(_.age) */
  def groupBy[K](f: FieldSelector[A] => Expr[K]): GroupedQuery[A, K] =
    groupBy(f(FieldSelector[A](using outputEncoder)))

  /** Group by expressions for aggregation */
  def groupBy[K](keys: Expr[K]*): GroupedQuery[A, K] =
    new GroupedQuery(plan, outputEncoder, schemaContracts, keys.map(_.toProtoExpr).toVector)

  /** Inner join with another query */
  def join[B](other: Query[B]): JoinBuilder[A, B] =
    new JoinBuilder(this, other, JoinType.Inner)

  /** Left outer join */
  def leftJoin[B](other: Query[B]): JoinBuilder[A, B] =
    new JoinBuilder(this, other, JoinType.LeftOuter)

  /** Right outer join */
  def rightJoin[B](other: Query[B]): JoinBuilder[A, B] =
    new JoinBuilder(this, other, JoinType.RightOuter)

  /** Cross join */
  def crossJoin[B](other: Query[B])(using encB: ProtoEncoder[B]): Query[(A, B)] =
    given ProtoEncoder[(A, B)] = ProtoEncoder.tuple2Encoder(using outputEncoder, encB)
    new Query(
      ProtoLogicalPlan.Join(plan, other.plan, JoinType.Cross, None),
      summon[ProtoEncoder[(A, B)]],
      schemaContracts ++ other.schemaContracts
    )

  /** Union with another query (must have same schema) */
  def union(other: Query[A]): Query[A] =
    new Query(
      ProtoLogicalPlan.Union(Vector(plan, other.plan), byName = false, allowMissingColumns = false),
      outputEncoder,
      schemaContracts ++ other.schemaContracts
    )

  /** Intersect with another query (must have same schema) */
  def intersect(other: Query[A]): Query[A] =
    new Query(
      ProtoLogicalPlan.Intersect(plan, other.plan, isAll = false),
      outputEncoder,
      schemaContracts ++ other.schemaContracts
    )

  /** Intersect all with another query (keeps duplicates) */
  def intersectAll(other: Query[A]): Query[A] =
    new Query(
      ProtoLogicalPlan.Intersect(plan, other.plan, isAll = true),
      outputEncoder,
      schemaContracts ++ other.schemaContracts
    )

  /** Except (set difference) with another query */
  def except(other: Query[A]): Query[A] =
    new Query(
      ProtoLogicalPlan.Except(plan, other.plan, isAll = false),
      outputEncoder,
      schemaContracts ++ other.schemaContracts
    )

  /** Except all with another query (keeps duplicates) */
  def exceptAll(other: Query[A]): Query[A] =
    new Query(
      ProtoLogicalPlan.Except(plan, other.plan, isAll = true),
      outputEncoder,
      schemaContracts ++ other.schemaContracts
    )

  /** Compile this query to an artifact */
  def compile: CompiledQuery[A] =
    val canonicalPlan = Canonicalizer.canonicalize(plan)
    val outputSchema = ProtoSchema(outputEncoder.schema.fields)
    val contentHash = computeContentHash(canonicalPlan, schemaContracts)

    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0-SNAPSHOT",
      compiledAt = System.currentTimeMillis(),
      contentHash = contentHash,
      schemaContracts = schemaContracts,
      plan = canonicalPlan,
      outputSchema = outputSchema,
      sourceInfo = None
    )

    CompiledQuery.fromArtifact(artifact, outputEncoder)

  private def computeContentHash(plan: ProtoLogicalPlan, contracts: Vector[SchemaContract]): Long =
    val planHash = plan.hashCode().toLong
    val contractHash = contracts.map(_.fingerprint.toLong).sum
    planHash * 31 + contractHash

/** Grouped query for aggregation.
  */
final class GroupedQuery[A, K] private[dsl] (
    private val plan: ProtoLogicalPlan,
    private val inputEncoder: ProtoEncoder[A],
    private val schemaContracts: Vector[SchemaContract],
    private val groupingExprs: Vector[ProtoExpr]
):
  /** Aggregate with expressions */
  def agg[B](aggExprs: Expr[B]*)(using enc: ProtoEncoder[B]): Query[B] =
    new Query(
      ProtoLogicalPlan.Aggregate(
        groupingExprs,
        aggExprs.map(_.toProtoExpr).toVector,
        plan
      ),
      enc,
      schemaContracts
    )

/** Join builder for specifying join conditions.
  */
final class JoinBuilder[A, B] private[dsl] (
    private[dsl] val left: Query[A],
    private[dsl] val right: Query[B],
    private[dsl] val joinType: JoinType
):
  /** Specify join condition with explicit expression */
  def on(
      condition: Expr[Boolean]
  )(using encA: ProtoEncoder[A], encB: ProtoEncoder[B]): Query[(A, B)] =
    given ProtoEncoder[(A, B)] = ProtoEncoder.tuple2Encoder(using encA, encB)
    new Query(
      ProtoLogicalPlan.Join(left.plan, right.plan, joinType, Some(condition.toProtoExpr)),
      summon[ProtoEncoder[(A, B)]],
      left.schemaContracts ++ right.schemaContracts
    )

  /** Specify join condition with lambda-style field access from both sides.
    *
    * Example:
    * {{{
    * users.join(depts.toQuery).on((u, d) => u.id === d.userId)
    * }}}
    */
  def on(
      f: (FieldSelector[A], FieldSelector[B]) => Expr[Boolean]
  )(using encA: ProtoEncoder[A], encB: ProtoEncoder[B]): Query[(A, B)] =
    val leftSelector = FieldSelector[A](using encA)
    val rightSelector = FieldSelector[B](using encB)
    val condition = f(leftSelector, rightSelector)
    on(condition)

/** Sort expression with direction.
  */
final class SortExpr private[dsl] (
    private val expr: ProtoExpr,
    private val direction: SortDirection,
    private val nullOrdering: NullOrdering
):
  def toSortOrder: SortOrder = SortOrder(expr, direction, nullOrdering)

extension [A](expr: Expr[A])
  def asc: SortExpr =
    new SortExpr(expr.toProtoExpr, SortDirection.Ascending, NullOrdering.NullsLast)
  def desc: SortExpr =
    new SortExpr(expr.toProtoExpr, SortDirection.Descending, NullOrdering.NullsFirst)
  def ascNullsFirst: SortExpr =
    new SortExpr(expr.toProtoExpr, SortDirection.Ascending, NullOrdering.NullsFirst)
  def descNullsLast: SortExpr =
    new SortExpr(expr.toProtoExpr, SortDirection.Descending, NullOrdering.NullsLast)
