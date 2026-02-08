package protocatalyst.catalyst.protobuf

import scala.collection.JavaConverters._

import io.protocatalyst.proto.{v1 => pb}
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.analysis.{
  UnresolvedAttribute,
  UnresolvedFunction,
  UnresolvedGenerator,
  UnresolvedInlineTable,
  UnresolvedRelation
}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._

/** Decodes protobuf ProtoLogicalPlanMsg to Spark LogicalPlan.
  *
  * Mirrors the JSON PlanDecoder but reads from protobuf messages using getPlanCase() pattern
  * matching instead of circe JSON parsing.
  */
object ProtobufPlanDecoder {

  /** Parse a plan from protobuf artifact bytes (without PCAT header). */
  def parsePlanFromBytes(payload: Array[Byte]): Either[String, LogicalPlan] = {
    try {
      val artifact = pb.CompiledArtifactMsg.parseFrom(payload)
      if (!artifact.hasPlan) {
        scala.Left("Protobuf artifact missing 'plan' field")
      } else {
        scala.Right(decode(artifact.getPlan))
      }
    } catch {
      case e: Exception =>
        scala.Left(s"Protobuf plan decode error: ${e.getMessage}")
    }
  }

  def decode(msg: pb.ProtoLogicalPlanMsg): LogicalPlan = {
    import pb.ProtoLogicalPlanMsg.PlanCase._
    msg.getPlanCase match {
      // === Leaf nodes ===
      case RELATION_REF =>
        val r = msg.getRelationRef
        val base = UnresolvedRelation(Seq(r.getName))
        if (r.hasAlias) SubqueryAlias(r.getAlias, base) else base

      case VALUES =>
        val v = msg.getValues
        val schema = v.getSchema
        val columnNames = (0 until schema.getFieldsCount).map { i =>
          schema.getFields(i).getName
        }
        val rows = v.getRowsList.asScala.map { row =>
          ProtobufExpressionDecoder.decodeExprs(row.getValuesList)
        }.toSeq
        UnresolvedInlineTable(columnNames, rows)

      // === Unary nodes ===
      case PROJECT =>
        val p = msg.getProject
        val projectList = ProtobufExpressionDecoder.decodeExprs(p.getProjectListList)
        val child = decode(p.getChild)
        Project(projectList.map(asNamedExpression), child)

      case FILTER =>
        val f = msg.getFilter
        val condition = ProtobufExpressionDecoder.decode(f.getCondition)
        val child = decode(f.getChild)
        Filter(condition, child)

      case AGGREGATE =>
        val a = msg.getAggregate
        val grouping = ProtobufExpressionDecoder.decodeExprs(a.getGroupingExprsList)
        val aggregate = ProtobufExpressionDecoder.decodeExprs(a.getAggregateExprsList)
        val child = decode(a.getChild)
        Aggregate(grouping, aggregate.map(asNamedExpression), child)

      case SORT =>
        val s = msg.getSort
        val order = decodeSortOrders(s.getOrderList)
        val child = decode(s.getChild)
        Sort(order, global = true, child)

      case LIMIT =>
        val l = msg.getLimit
        val limit = l.getLimit
        val child = decode(l.getChild)
        GlobalLimit(Literal(limit), LocalLimit(Literal(limit), child))

      case DISTINCT =>
        val d = msg.getDistinct
        val child = decode(d.getChild)
        Distinct(child)

      case SUBQUERY_ALIAS =>
        val sa = msg.getSubqueryAlias
        val alias = sa.getAlias
        val child = decode(sa.getChild)
        SubqueryAlias(alias, child)

      // === Binary nodes ===
      case JOIN =>
        val j = msg.getJoin
        val left = decode(j.getLeft)
        val right = decode(j.getRight)
        val joinType = decodeJoinType(j.getJoinType)
        val condition =
          if (j.hasCondition) Some(ProtobufExpressionDecoder.decode(j.getCondition)) else None
        Join(left, right, joinType, condition, JoinHint.NONE)

      case UNION =>
        val u = msg.getUnion
        val children = u.getChildrenList.asScala.map(decode).toSeq
        Union(children, u.getByName, u.getAllowMissingColumns)

      case INTERSECT =>
        val i = msg.getIntersect
        val left = decode(i.getLeft)
        val right = decode(i.getRight)
        Intersect(left, right, i.getIsAll)

      case EXCEPT =>
        val e = msg.getExcept
        val left = decode(e.getLeft)
        val right = decode(e.getRight)
        Except(left, right, e.getIsAll)

      // === Window ===
      case WINDOW =>
        val w = msg.getWindow
        val windowExprs = ProtobufExpressionDecoder.decodeExprs(w.getWindowExprsList)
        val partitionSpec = ProtobufExpressionDecoder.decodeExprs(w.getPartitionSpecList)
        val orderSpec = decodeSortOrders(w.getOrderSpecList)
        val child = decode(w.getChild)
        Window(windowExprs.map(asNamedExpression), partitionSpec, orderSpec, child)

      // === CTE ===
      case WITH_PLAN =>
        val w = msg.getWithPlan
        val child = decode(w.getChild)
        val cteRelations = w.getCteRelationsList.asScala.map { cte =>
          val ctePlan = decode(cte.getPlan)
          (cte.getName, ctePlan)
        }.toSeq
        val cteDefs = cteRelations.map { case (name, plan) =>
          CTERelationDef(SubqueryAlias(name, plan))
        }
        WithCTE(child, cteDefs)

      // === Pivot/Unpivot ===
      case PIVOT =>
        val p = msg.getPivot
        val grouping = ProtobufExpressionDecoder.decodeExprs(p.getGroupingExprsList)
        val pivotCol = ProtobufExpressionDecoder.decode(p.getPivotColumn)
        val pivotVals = ProtobufExpressionDecoder.decodeExprs(p.getPivotValuesList)
        val aggs = ProtobufExpressionDecoder.decodeExprs(p.getAggregatesList)
        val child = decode(p.getChild)
        val groupOpt = if (grouping.isEmpty) None else Some(grouping.map(asNamedExpression))
        Pivot(groupOpt, pivotCol, pivotVals, aggs, child)

      case UNPIVOT =>
        val u = msg.getUnpivot
        val valueColName = u.getValueColumnName
        val varColName = u.getVariableColumnName
        val columns = u.getColumnsList.asScala.toSeq
        val colExprs = columns.map { col =>
          asNamedExpression(ProtobufExpressionDecoder.decode(col.getExpr))
        }
        val aliases = columns.map { col =>
          if (col.hasAlias) Some(col.getAlias) else None
        }
        val values = Some(colExprs.map(e => Seq(e)))
        Unpivot(
          None,
          values,
          Some(aliases),
          varColName,
          Seq(valueColName),
          child = decode(u.getChild)
        )

      // === Lateral ===
      case LATERAL_JOIN =>
        val lj = msg.getLateralJoin
        val left = decode(lj.getLeft)
        val lateral = decode(lj.getLateral)
        val condition =
          if (lj.hasCondition) Some(ProtobufExpressionDecoder.decode(lj.getCondition)) else None
        LateralJoin(left, LateralSubquery(lateral), Inner, condition)

      // === Generator (LATERAL VIEW) ===
      case GENERATE =>
        val g = msg.getGenerate
        val generatorExpr = ProtobufExpressionDecoder.decode(g.getGenerator)
        val generator = toGenerator(generatorExpr)
        val genOutput = (0 until g.getGeneratorOutputCount).map { i =>
          UnresolvedAttribute(Seq(g.getGeneratorOutput(i)))
        }
        val outer = g.getOuter
        val child = decode(g.getChild)
        Generate(generator, Seq.empty, outer, None, genOutput, child)

      // === Hints ===
      case RESOLVED_HINT =>
        val h = msg.getResolvedHint
        val child = decode(h.getChild)
        h.getHintsList.asScala.foldLeft(child) { (plan, hint) =>
          val name = hint.getName
          val params: Seq[Expression] = hint.getParamsList.asScala.map { param =>
            import pb.HintParamMsg.ParamCase._
            param.getParamCase match {
              case STRING_VAL    => UnresolvedAttribute(Seq(param.getStringVal))
              case INT_VAL       => Literal(param.getIntVal)
              case PARAM_NOT_SET => Literal(null)
            }
          }.toSeq
          UnresolvedHint(name, params, plan)
        }

      case PLAN_NOT_SET =>
        throw new IllegalArgumentException("ProtoLogicalPlanMsg plan not set")
    }
  }

  private def asNamedExpression(expr: Expression): NamedExpression = expr match {
    case ne: NamedExpression => ne
    case other               => Alias(other, other.sql)()
  }

  private def decodeJoinType(jt: pb.JoinTypeEnum): JoinType = jt match {
    case pb.JoinTypeEnum.JOIN_TYPE_INNER       => Inner
    case pb.JoinTypeEnum.JOIN_TYPE_LEFT_OUTER  => LeftOuter
    case pb.JoinTypeEnum.JOIN_TYPE_RIGHT_OUTER => RightOuter
    case pb.JoinTypeEnum.JOIN_TYPE_FULL_OUTER  => FullOuter
    case pb.JoinTypeEnum.JOIN_TYPE_LEFT_SEMI   => LeftSemi
    case pb.JoinTypeEnum.JOIN_TYPE_LEFT_ANTI   => LeftAnti
    case pb.JoinTypeEnum.JOIN_TYPE_CROSS       => Cross
    case _                                     => Inner
  }

  private def decodeSortOrders(msgs: java.util.List[pb.SortOrderMsg]): Seq[SortOrder] = {
    msgs.asScala.map(decodeSortOrder).toSeq
  }

  private def decodeSortOrder(msg: pb.SortOrderMsg): SortOrder = {
    val child = ProtobufExpressionDecoder.decode(msg.getChild)
    val direction = msg.getDirection match {
      case pb.SortDirectionEnum.SORT_DIRECTION_ASCENDING  => Ascending
      case pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING => Descending
      case _                                              => Ascending
    }
    val nullOrdering = msg.getNullOrdering match {
      case pb.NullOrderingEnum.NULL_ORDERING_NULLS_FIRST => NullsFirst
      case pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST  => NullsLast
      case _                                             => NullsFirst
    }
    SortOrder(child, direction, nullOrdering, Seq.empty)
  }

  /** Convert a decoded expression to a Spark Generator type. */
  private def toGenerator(expr: Expression): Generator = expr match {
    case g: Generator           => g
    case uf: UnresolvedFunction =>
      UnresolvedGenerator(FunctionIdentifier(uf.nameParts.mkString(".")), uf.arguments)
    case other =>
      UnresolvedGenerator(FunctionIdentifier(other.sql), Seq(other))
  }
}
