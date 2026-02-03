package protocatalyst.mock

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

/** Bidirectional converter between ProtoLogicalPlan and MockLogicalPlan.
  *
  * This enables testing the conversion layer before real Spark Scala 3 is available. When Spark
  * Scala 3 becomes available, this can be adapted to convert to real Spark LogicalPlan types.
  */
object PlanConverter:

  // ============================================
  // ProtoLogicalPlan → MockLogicalPlan
  // ============================================

  def toMock(plan: ProtoLogicalPlan): MockLogicalPlan =
    import ProtoLogicalPlan.*
    import MockLogicalPlan as MLP

    plan match
      case RelationRef(name, alias, schemaContract) =>
        val output = schemaContract.requiredFields.map { field =>
          MockExpression.AttributeReference(
            field.name,
            TypeConverter.toMock(field.expectedType),
            field.expectedNullable,
            alias.map(Seq(_)).getOrElse(Seq.empty)
          )
        }
        val base = MLP.LogicalRelation(name, output)
        alias.map(a => MLP.SubqueryAlias(a, base)).getOrElse(base)

      case Values(rows, schema) =>
        val output = schema.fields.map { field =>
          MockExpression.AttributeReference(
            field.name,
            TypeConverter.toMock(field.dataType),
            field.nullable
          )
        }
        val data = rows.map { row =>
          row.map(expr => evaluateLiteral(expr))
        }
        MLP.LocalRelation(output, data)

      case Project(projectList, child) =>
        MLP.Project(projectList.map(ExpressionConverter.toMock), toMock(child))

      case Filter(condition, child) =>
        MLP.Filter(ExpressionConverter.toMock(condition), toMock(child))

      case Aggregate(groupingExprs, aggregateExprs, child) =>
        MLP.Aggregate(
          groupingExprs.map(ExpressionConverter.toMock),
          aggregateExprs.map(ExpressionConverter.toMock),
          toMock(child)
        )

      case Sort(order, global, child) =>
        MLP.Sort(
          order.map(convertSortOrder),
          global,
          toMock(child)
        )

      case Limit(limit, child) =>
        MLP.GlobalLimit(
          MockExpression.Literal(limit, MockDataType.IntegerType),
          MLP.LocalLimit(
            MockExpression.Literal(limit, MockDataType.IntegerType),
            toMock(child)
          )
        )

      case Distinct(child) =>
        MLP.Distinct(toMock(child))

      case SubqueryAlias(alias, child) =>
        MLP.SubqueryAlias(alias, toMock(child))

      case Join(left, right, joinType, condition) =>
        MLP.Join(
          toMock(left),
          toMock(right),
          convertJoinType(joinType),
          condition.map(ExpressionConverter.toMock)
        )

      case Union(children, byName, allowMissingColumns) =>
        MLP.Union(children.map(toMock), byName, allowMissingColumns)

      case Intersect(left, right, isAll) =>
        MLP.Intersect(toMock(left), toMock(right), isAll)

      case Except(left, right, isAll) =>
        MLP.Except(toMock(left), toMock(right), isAll)

      case Window(windowExprs, partitionSpec, orderSpec, child) =>
        MLP.Window(
          windowExprs.map(ExpressionConverter.toMock),
          partitionSpec.map(ExpressionConverter.toMock),
          orderSpec.map(convertSortOrder),
          toMock(child)
        )

      case With(cteRelations, child) =>
        MLP.WithCTE(
          cteRelations.map((name, plan) => (name, toMock(plan))),
          toMock(child)
        )

      case Pivot(_, _, _, _, _) =>
        throw UnsupportedOperationException("PIVOT is not yet supported in mock runtime")

      case Unpivot(_, _, _, _, _) =>
        throw UnsupportedOperationException("UNPIVOT is not yet supported in mock runtime")

      case LateralJoin(_, _, _) =>
        throw UnsupportedOperationException("LATERAL JOIN is not yet supported in mock runtime")

      case Generate(_, _, _, _) =>
        throw UnsupportedOperationException("LATERAL VIEW / Generate is not yet supported in mock runtime")

  private def convertSortOrder(so: SortOrder): MockExpression.SortOrder =
    import MockExpression.{SortDirection as MSD, NullOrdering as MNO}
    MockExpression.SortOrder(
      ExpressionConverter.toMock(so.child),
      so.direction match
        case SortDirection.Ascending  => MSD.Ascending
        case SortDirection.Descending => MSD.Descending,
      so.nullOrdering match
        case NullOrdering.NullsFirst => MNO.NullsFirst
        case NullOrdering.NullsLast  => MNO.NullsLast
    )

  private def convertJoinType(jt: JoinType): MockLogicalPlan.JoinType =
    import MockLogicalPlan.JoinType as MJT
    jt match
      case JoinType.Inner      => MJT.Inner
      case JoinType.LeftOuter  => MJT.LeftOuter
      case JoinType.RightOuter => MJT.RightOuter
      case JoinType.FullOuter  => MJT.FullOuter
      case JoinType.LeftSemi   => MJT.LeftSemi
      case JoinType.LeftAnti   => MJT.LeftAnti
      case JoinType.Cross      => MJT.Cross

  private def evaluateLiteral(expr: ProtoExpr): Any =
    expr match
      case ProtoExpr.Literal(lit) =>
        import LiteralValue.*
        lit match
          case BooleanValue(v)                             => v
          case ByteValue(v)                                => v
          case ShortValue(v)                               => v
          case IntValue(v)                                 => v
          case LongValue(v)                                => v
          case FloatValue(v)                               => v
          case DoubleValue(v)                              => v
          case StringValue(v)                              => v
          case BinaryValue(v)                              => v
          case DecimalValue(v)                             => v
          case DateValue(v)                                => v
          case TimestampValue(v)                           => v
          case TimeValue(v)                                => v
          case CalendarIntervalValue(months, days, micros) => (months, days, micros)
          case NullValue(_)                                => null
      case _ => throw IllegalArgumentException(s"Expected literal in VALUES, got: $expr")

  // ============================================
  // MockLogicalPlan → ProtoLogicalPlan
  // ============================================

  def fromMock(plan: MockLogicalPlan): ProtoLogicalPlan =
    import MockLogicalPlan as MLP
    import ProtoLogicalPlan as PLP

    plan match
      case MLP.UnresolvedRelation(tableName) =>
        val name = tableName.mkString(".")
        PLP.RelationRef(
          name,
          None,
          SchemaContract(name, Vector.empty, SchemaFingerprint.fromLong(0L))
        )

      case MLP.LogicalRelation(tableName, output) =>
        val fields = output.zipWithIndex.map { case (attr, idx) =>
          FieldContract(attr.name, TypeConverter.fromMock(attr.dataType), attr.nullable, idx)
        }.toVector
        PLP.RelationRef(
          tableName,
          None,
          SchemaContract(tableName, fields, SchemaFingerprint.fromLong(0L))
        )

      case MLP.LocalRelation(output, data) =>
        val schema = ProtoSchema(output.map { attr =>
          ProtoStructField(attr.name, TypeConverter.fromMock(attr.dataType), attr.nullable)
        }.toVector)
        val rows = data.map { row =>
          row.map(v => valueToLiteral(v, ProtoType.StringType)).toVector
        }.toVector
        PLP.Values(rows, schema)

      case MLP.Project(projectList, child) =>
        PLP.Project(projectList.map(ExpressionConverter.fromMock).toVector, fromMock(child))

      case MLP.Filter(condition, child) =>
        PLP.Filter(ExpressionConverter.fromMock(condition), fromMock(child))

      case MLP.Aggregate(groupingExprs, aggregateExprs, child) =>
        PLP.Aggregate(
          groupingExprs.map(ExpressionConverter.fromMock).toVector,
          aggregateExprs.map(ExpressionConverter.fromMock).toVector,
          fromMock(child)
        )

      case MLP.Sort(order, global, child) =>
        PLP.Sort(
          order.map(fromMockSortOrder).toVector,
          global,
          fromMock(child)
        )

      case MLP.GlobalLimit(limitExpr, child) =>
        child match
          case MLP.LocalLimit(_, innerChild) =>
            val limit = extractLiteralInt(limitExpr)
            PLP.Limit(limit, fromMock(innerChild))
          case _ =>
            val limit = extractLiteralInt(limitExpr)
            PLP.Limit(limit, fromMock(child))

      case MLP.LocalLimit(limitExpr, child) =>
        val limit = extractLiteralInt(limitExpr)
        PLP.Limit(limit, fromMock(child))

      case MLP.Distinct(child) =>
        PLP.Distinct(fromMock(child))

      case MLP.SubqueryAlias(alias, child) =>
        PLP.SubqueryAlias(alias, fromMock(child))

      case MLP.Join(left, right, joinType, condition) =>
        PLP.Join(
          fromMock(left),
          fromMock(right),
          fromMockJoinType(joinType),
          condition.map(ExpressionConverter.fromMock)
        )

      case MLP.Union(children, byName, allowMissingColumns) =>
        PLP.Union(children.map(fromMock).toVector, byName, allowMissingColumns)

      case MLP.Intersect(left, right, isAll) =>
        PLP.Intersect(fromMock(left), fromMock(right), isAll)

      case MLP.Except(left, right, isAll) =>
        PLP.Except(fromMock(left), fromMock(right), isAll)

      case MLP.Window(windowExprs, partitionSpec, orderSpec, child) =>
        PLP.Window(
          windowExprs.map(ExpressionConverter.fromMock).toVector,
          partitionSpec.map(ExpressionConverter.fromMock).toVector,
          orderSpec.map(fromMockSortOrder).toVector,
          fromMock(child)
        )

      case MLP.WithCTE(cteRelations, child) =>
        PLP.With(
          cteRelations.map((name, plan) => (name, fromMock(plan))).toVector,
          fromMock(child)
        )

      case MLP.CTERelationRef(cteId, name, output) =>
        val fields = output.zipWithIndex.map { case (attr, idx) =>
          FieldContract(attr.name, TypeConverter.fromMock(attr.dataType), attr.nullable, idx)
        }.toVector
        PLP.RelationRef(name, None, SchemaContract(name, fields, SchemaFingerprint.fromLong(0L)))

  private def fromMockSortOrder(so: MockExpression.SortOrder): SortOrder =
    import MockExpression.{SortDirection as MSD, NullOrdering as MNO}
    SortOrder(
      ExpressionConverter.fromMock(so.child),
      so.direction match
        case MSD.Ascending  => SortDirection.Ascending
        case MSD.Descending => SortDirection.Descending,
      so.nullOrdering match
        case MNO.NullsFirst => NullOrdering.NullsFirst
        case MNO.NullsLast  => NullOrdering.NullsLast
    )

  private def fromMockJoinType(jt: MockLogicalPlan.JoinType): JoinType =
    import MockLogicalPlan.JoinType as MJT
    jt match
      case MJT.Inner      => JoinType.Inner
      case MJT.LeftOuter  => JoinType.LeftOuter
      case MJT.RightOuter => JoinType.RightOuter
      case MJT.FullOuter  => JoinType.FullOuter
      case MJT.LeftSemi   => JoinType.LeftSemi
      case MJT.LeftAnti   => JoinType.LeftAnti
      case MJT.Cross      => JoinType.Cross

  private def extractLiteralInt(expr: MockExpression): Int =
    expr match
      case MockExpression.Literal(v: Int, _)  => v
      case MockExpression.Literal(v: Long, _) => v.toInt
      case _ => throw IllegalArgumentException(s"Expected integer literal, got: $expr")

  private def valueToLiteral(value: Any, hint: ProtoType): ProtoExpr =
    if value == null then ProtoExpr.litNull(hint)
    else
      value match
        case b: Boolean => ProtoExpr.lit(b)
        case i: Int     => ProtoExpr.lit(i)
        case l: Long    => ProtoExpr.lit(l)
        case d: Double  => ProtoExpr.lit(d)
        case s: String  => ProtoExpr.lit(s)
        case _          => ProtoExpr.lit(value.toString)
