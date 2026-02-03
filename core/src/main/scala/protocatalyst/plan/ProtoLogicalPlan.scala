package protocatalyst.plan

import java.io.Serializable

import protocatalyst.expr._
import protocatalyst.schema._

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

  case With(
      cteRelations: Vector[(String, ProtoLogicalPlan)],
      child: ProtoLogicalPlan
  )

  /** PIVOT: transposes rows into columns based on pivot column values */
  case Pivot(
      /** Grouping columns (not explicitly aggregated or pivoted) */
      groupingExprs: Vector[ProtoExpr],
      /** The column to pivot on */
      pivotColumn: ProtoExpr,
      /** The values of pivot column to create new columns */
      pivotValues: Vector[ProtoExpr],
      /** Aggregate expressions to apply for each pivot value */
      aggregates: Vector[ProtoExpr],
      child: ProtoLogicalPlan
  )

  /** UNPIVOT: transposes columns into rows */
  case Unpivot(
      /** Column name for the unpivoted values */
      valueColumnName: String,
      /** Column name for the source column names */
      variableColumnName: String,
      /** Source columns to unpivot with optional aliases */
      columns: Vector[(ProtoExpr, Option[String])],
      /** Whether to include null values */
      includeNulls: Boolean,
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
