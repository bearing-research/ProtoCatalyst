package protocatalyst.plan

import protocatalyst.expr.*
import protocatalyst.schema.*
import protocatalyst.types.*
import java.io.Serializable

/** Minimal logical plan nodes for compiled queries. */
enum ProtoLogicalPlan extends Serializable:
  case RelationRef(
      name: String,
      alias: Option[String],
      schemaContract: SchemaContract
  )

  case Values(
      rows: Vector[Vector[ProtoExpr]],
      schema: ProtoSchema
  )

  case Project(
      projectList: Vector[ProtoExpr],
      child: ProtoLogicalPlan
  )

  case Filter(
      condition: ProtoExpr,
      child: ProtoLogicalPlan
  )

  case Aggregate(
      groupingExprs: Vector[ProtoExpr],
      aggregateExprs: Vector[ProtoExpr],
      child: ProtoLogicalPlan
  )

  case Sort(
      order: Vector[SortOrder],
      global: Boolean,
      child: ProtoLogicalPlan
  )

  case Limit(
      limit: Int,
      child: ProtoLogicalPlan
  )

  case Distinct(
      child: ProtoLogicalPlan
  )

  case SubqueryAlias(
      alias: String,
      child: ProtoLogicalPlan
  )

  case Join(
      left: ProtoLogicalPlan,
      right: ProtoLogicalPlan,
      joinType: JoinType,
      condition: Option[ProtoExpr]
  )

  case Union(
      children: Vector[ProtoLogicalPlan],
      byName: Boolean,
      allowMissingColumns: Boolean
  )

  case Intersect(
      left: ProtoLogicalPlan,
      right: ProtoLogicalPlan,
      isAll: Boolean
  )

  case Except(
      left: ProtoLogicalPlan,
      right: ProtoLogicalPlan,
      isAll: Boolean
  )

  case Window(
      windowExprs: Vector[ProtoExpr],
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[SortOrder],
      child: ProtoLogicalPlan
  )

enum JoinType extends Serializable:
  case Inner, LeftOuter, RightOuter, FullOuter, LeftSemi, LeftAnti, Cross

case class SortOrder(
    child: ProtoExpr,
    direction: SortDirection,
    nullOrdering: NullOrdering
) extends Serializable

enum SortDirection extends Serializable:
  case Ascending, Descending

enum NullOrdering extends Serializable:
  case NullsFirst, NullsLast
