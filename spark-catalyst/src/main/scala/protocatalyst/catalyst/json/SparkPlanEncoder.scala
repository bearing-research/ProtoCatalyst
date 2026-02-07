package protocatalyst.catalyst.json

import io.circe.Json
import org.apache.spark.sql.catalyst.analysis.{
  UnresolvedAlias,
  UnresolvedAttribute,
  UnresolvedFunction,
  UnresolvedGenerator,
  UnresolvedRelation,
  UnresolvedStar
}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.{
  AggregateExpression,
  AggregateFunction,
  Average,
  Count,
  First,
  Last,
  Max,
  Min,
  Sum
}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/** Encodes Spark LogicalPlan to JSON in the same format as ProtoCatalyst.
  *
  * This allows direct JSON comparison without needing a decoder, removing the decoder from the
  * verification path.
  */
object SparkPlanEncoder {

  def encode(plan: LogicalPlan): Json = {
    plan match {
      case p: Project =>
        Json.obj(
          "$type" -> Json.fromString("Project"),
          "projectList" -> Json.arr(p.projectList.map(encodeExpr): _*),
          "child" -> encode(p.child)
        )

      case f: Filter =>
        Json.obj(
          "$type" -> Json.fromString("Filter"),
          "condition" -> encodeExpr(f.condition),
          "child" -> encode(f.child)
        )

      case a: Aggregate =>
        Json.obj(
          "$type" -> Json.fromString("Aggregate"),
          "groupingExprs" -> Json.arr(a.groupingExpressions.map(encodeExpr): _*),
          "aggregateExprs" -> Json.arr(a.aggregateExpressions.map(encodeExpr): _*),
          "child" -> encode(a.child)
        )

      case s: Sort =>
        Json.obj(
          "$type" -> Json.fromString("Sort"),
          "order" -> Json.arr(s.order.map(encodeSortOrder): _*),
          "global" -> Json.fromBoolean(true),
          "child" -> encode(s.child)
        )

      // Spark wraps LIMIT as GlobalLimit(LocalLimit(child)) — unwrap to single Limit
      case GlobalLimit(Literal(n: Int, _), LocalLimit(_, child)) =>
        Json.obj(
          "$type" -> Json.fromString("Limit"),
          "limit" -> Json.fromInt(n),
          "child" -> encode(child)
        )

      case GlobalLimit(Literal(n: Int, _), child) =>
        Json.obj(
          "$type" -> Json.fromString("Limit"),
          "limit" -> Json.fromInt(n),
          "child" -> encode(child)
        )

      case LocalLimit(Literal(n: Int, _), child) =>
        Json.obj(
          "$type" -> Json.fromString("Limit"),
          "limit" -> Json.fromInt(n),
          "child" -> encode(child)
        )

      case d: Distinct =>
        Json.obj(
          "$type" -> Json.fromString("Distinct"),
          "child" -> encode(d.child)
        )

      case u: Union =>
        Json.obj(
          "$type" -> Json.fromString("Union"),
          "children" -> Json.arr(u.children.map(encode): _*),
          "byName" -> Json.fromBoolean(u.byName),
          "allowMissingColumns" -> Json.fromBoolean(u.allowMissingCol)
        )

      case i: Intersect =>
        Json.obj(
          "$type" -> Json.fromString("Intersect"),
          "left" -> encode(i.left),
          "right" -> encode(i.right),
          "isAll" -> Json.fromBoolean(i.isAll)
        )

      case e: Except =>
        Json.obj(
          "$type" -> Json.fromString("Except"),
          "left" -> encode(e.left),
          "right" -> encode(e.right),
          "isAll" -> Json.fromBoolean(e.isAll)
        )

      case j: Join =>
        Json.obj(
          "$type" -> Json.fromString("Join"),
          "left" -> encode(j.left),
          "right" -> encode(j.right),
          "joinType" -> Json.fromString(j.joinType.toString),
          "condition" -> j.condition.map(encodeExpr).getOrElse(Json.Null)
        )

      case s: SubqueryAlias =>
        Json.obj(
          "$type" -> Json.fromString("SubqueryAlias"),
          "alias" -> Json.fromString(s.identifier.name),
          "child" -> encode(s.child)
        )

      case r: UnresolvedRelation =>
        Json.obj(
          "$type" -> Json.fromString("RelationRef"),
          "name" -> Json.fromString(r.multipartIdentifier.mkString(".")),
          "alias" -> Json.Null
        )

      case w: UnresolvedWith =>
        Json.obj(
          "$type" -> Json.fromString("With"),
          "cteRelations" -> Json.arr(w.cteRelations.map { case (name, sub) =>
            Json.arr(Json.fromString(name), encode(sub.child))
          }: _*),
          "recursive" -> Json.fromBoolean(false),
          "child" -> encode(w.child)
        )

      case w: WithCTE =>
        // Extract name from SubqueryAlias inside CTERelationDef
        val cteRelations = w.cteDefs.map { cteDef =>
          cteDef.child match {
            case SubqueryAlias(identifier, child) =>
              Json.arr(Json.fromString(identifier.name), encode(child))
            case other =>
              Json.arr(Json.fromString("cte"), encode(other))
          }
        }
        Json.obj(
          "$type" -> Json.fromString("With"),
          "cteRelations" -> Json.arr(cteRelations: _*),
          "recursive" -> Json.fromBoolean(false),
          "child" -> encode(w.plan)
        )

      case p: Pivot =>
        Json.obj(
          "$type" -> Json.fromString("Pivot"),
          "groupingExprs" -> Json.arr(
            p.groupByExprsOpt.getOrElse(Seq.empty).map(encodeExpr): _*
          ),
          "pivotColumn" -> encodeExpr(p.pivotColumn),
          "pivotValues" -> Json.arr(p.pivotValues.map(encodeExpr): _*),
          "aggregates" -> Json.arr(p.aggregates.map(encodeExpr): _*),
          "child" -> encode(p.child)
        )

      case u: Unpivot =>
        Json.obj(
          "$type" -> Json.fromString("Unpivot"),
          "valueColumnName" -> Json.fromString(u.valueColumnNames.headOption.getOrElse("value")),
          "variableColumnName" -> Json.fromString(u.variableColumnName),
          "columns" -> Json.arr(
            u.values
              .getOrElse(Seq.empty)
              .zip(u.aliases.getOrElse(Seq.empty))
              .map { case (exprs, alias) =>
                Json.arr(
                  encodeExpr(exprs.head),
                  alias.map(Json.fromString).getOrElse(Json.Null)
                )
              }: _*
          ),
          "child" -> encode(u.child)
        )

      case g: Generate =>
        Json.obj(
          "$type" -> Json.fromString("Generate"),
          "generator" -> encodeExpr(g.generator),
          "generatorOutput" -> Json.arr(
            g.generatorOutput.map(a => Json.fromString(a.name)): _*
          ),
          "outer" -> Json.fromBoolean(g.outer),
          "child" -> encode(g.child)
        )

      case w: Window =>
        Json.obj(
          "$type" -> Json.fromString("Window"),
          "windowExpressions" -> Json.arr(w.windowExpressions.map(encodeExpr): _*),
          "partitionSpec" -> Json.arr(w.partitionSpec.map(encodeExpr): _*),
          "orderSpec" -> Json.arr(w.orderSpec.map(encodeSortOrder): _*),
          "child" -> encode(w.child)
        )

      case lj: LateralJoin =>
        Json.obj(
          "$type" -> Json.fromString("LateralJoin"),
          "left" -> encode(lj.left),
          "lateral" -> encode(lj.right.plan),
          "condition" -> lj.condition.map(encodeExpr).getOrElse(Json.Null)
        )

      case h: UnresolvedHint =>
        Json.obj(
          "$type" -> Json.fromString("ResolvedHint"),
          "hints" -> Json.arr(encodeSparkHint(h.name, h.parameters)),
          "child" -> encode(h.child)
        )

      // UnresolvedHaving — Spark's unresolved HAVING clause, encode as Filter
      case uh if uh.getClass.getSimpleName == "UnresolvedHaving" =>
        val condition = uh.expressions.head
        val child = uh.children.head
        Json.obj(
          "$type" -> Json.fromString("Filter"),
          "condition" -> encodeExpr(condition),
          "child" -> encode(child)
        )

      case other =>
        Json.obj(
          "$type" -> Json.fromString(other.getClass.getSimpleName),
          "_unsupported" -> Json.fromString(other.toString.take(200))
        )
    }
  }

  def encodeExpr(expr: Expression): Json = {
    expr match {
      case l: Literal =>
        Json.obj(
          "$type" -> Json.fromString("Literal"),
          "value" -> encodeLiteralValue(l.value, l.dataType)
        )

      case a: UnresolvedAttribute =>
        Json.obj(
          "$type" -> Json.fromString("ColumnRef"),
          "name" -> Json.fromString(a.nameParts.last),
          "qualifier" -> (if (a.nameParts.size > 1) Json.fromString(a.nameParts.init.mkString("."))
                          else Json.Null)
        )

      case a: AttributeReference =>
        Json.obj(
          "$type" -> Json.fromString("ColumnRef"),
          "name" -> Json.fromString(a.name),
          "qualifier" -> a.qualifier.headOption.map(Json.fromString).getOrElse(Json.Null)
        )

      case alias: Alias =>
        Json.obj(
          "$type" -> Json.fromString("Alias"),
          "child" -> encodeExpr(alias.child),
          "name" -> Json.fromString(alias.name)
        )

      // UnresolvedAlias — Spark wraps SELECT expressions, encode as Alias to match decoder output
      case ua: UnresolvedAlias =>
        Json.obj(
          "$type" -> Json.fromString("Alias"),
          "child" -> encodeExpr(ua.child),
          "name" -> Json.fromString(ua.child.sql)
        )

      // UnresolvedStar — SELECT * or t.*
      case star: UnresolvedStar =>
        val target = star.target
        Json.obj(
          "$type" -> Json.fromString("ColumnRef"),
          "name" -> Json.fromString("*"),
          "qualifier" -> target
            .map(parts => Json.fromString(parts.mkString(".")))
            .getOrElse(Json.Null)
        )

      case EqualTo(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("Eq"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case Not(EqualTo(left, right)) =>
        Json.obj(
          "$type" -> Json.fromString("NotEq"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case LessThan(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("Lt"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case LessThanOrEqual(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("LtEq"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case GreaterThan(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("Gt"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case GreaterThanOrEqual(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("GtEq"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case And(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("And"),
          "children" -> Json.arr(encodeExpr(left), encodeExpr(right))
        )

      case Or(left, right) =>
        Json.obj(
          "$type" -> Json.fromString("Or"),
          "children" -> Json.arr(encodeExpr(left), encodeExpr(right))
        )

      case Not(child) =>
        Json.obj(
          "$type" -> Json.fromString("Not"),
          "child" -> encodeExpr(child)
        )

      case IsNull(child) =>
        Json.obj(
          "$type" -> Json.fromString("IsNull"),
          "child" -> encodeExpr(child)
        )

      case IsNotNull(child) =>
        Json.obj(
          "$type" -> Json.fromString("IsNotNull"),
          "child" -> encodeExpr(child)
        )

      case In(value, list) =>
        Json.obj(
          "$type" -> Json.fromString("In"),
          "value" -> encodeExpr(value),
          "list" -> Json.arr(list.map(encodeExpr): _*)
        )

      case Add(left, right, _) =>
        Json.obj(
          "$type" -> Json.fromString("Add"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case Subtract(left, right, _) =>
        Json.obj(
          "$type" -> Json.fromString("Subtract"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case Multiply(left, right, _) =>
        Json.obj(
          "$type" -> Json.fromString("Multiply"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      case Divide(left, right, _) =>
        Json.obj(
          "$type" -> Json.fromString("Divide"),
          "left" -> encodeExpr(left),
          "right" -> encodeExpr(right)
        )

      // === Math functions ===
      case Abs(child, _) =>
        encodeUnary("Abs", child)

      case Ceil(child) =>
        encodeUnary("Ceil", child)

      case Floor(child) =>
        encodeUnary("Floor", child)

      case Round(child, scale, _) =>
        Json.obj(
          "$type" -> Json.fromString("Round"),
          "child" -> encodeExpr(child),
          "scale" -> encodeExpr(scale)
        )

      case Sqrt(child) =>
        encodeUnary("Sqrt", child)

      case Cbrt(child) =>
        encodeUnary("Cbrt", child)

      case Pow(left, right) =>
        encodeBinary("Pow", left, right)

      case Pmod(left, right, _) =>
        encodeBinary("Pmod", left, right)

      case Signum(child) =>
        encodeUnary("Sign", child)

      case Logarithm(base, child) =>
        Json.obj(
          "$type" -> Json.fromString("Log"),
          "child" -> encodeExpr(child),
          "base" -> encodeExpr(base)
        )

      case Log(child) =>
        encodeUnary("Log", child)

      case Exp(child) =>
        encodeUnary("Exp", child)

      case c: Cast =>
        Json.obj(
          "$type" -> Json.fromString("Cast"),
          "child" -> encodeExpr(c.child),
          "targetType" -> encodeDataType(c.dataType)
        )

      case CaseWhen(branches, elseValue) =>
        Json.obj(
          "$type" -> Json.fromString("CaseWhen"),
          "branches" -> Json.arr(branches.map { case (when, then_) =>
            Json.arr(encodeExpr(when), encodeExpr(then_))
          }: _*),
          "elseValue" -> elseValue.map(encodeExpr).getOrElse(Json.Null)
        )

      case Coalesce(children) =>
        Json.obj(
          "$type" -> Json.fromString("Coalesce"),
          "children" -> Json.arr(children.map(encodeExpr): _*)
        )

      case Upper(child) =>
        Json.obj(
          "$type" -> Json.fromString("Upper"),
          "child" -> encodeExpr(child)
        )

      case Lower(child) =>
        Json.obj(
          "$type" -> Json.fromString("Lower"),
          "child" -> encodeExpr(child)
        )

      case Concat(children) =>
        Json.obj(
          "$type" -> Json.fromString("Concat"),
          "children" -> Json.arr(children.map(encodeExpr): _*)
        )

      case Substring(str, pos, len) =>
        Json.obj(
          "$type" -> Json.fromString("Substring"),
          "str" -> encodeExpr(str),
          "pos" -> encodeExpr(pos),
          "len" -> encodeExpr(len)
        )

      // === String functions ===
      case StringTrim(srcStr, trimStr) =>
        Json.obj(
          "$type" -> Json.fromString("Trim"),
          "child" -> encodeExpr(srcStr),
          "trimStr" -> trimStr.map(encodeExpr).getOrElse(Json.Null),
          "trimType" -> Json.fromString("Both")
        )

      case StringTrimLeft(srcStr, trimStr) =>
        Json.obj(
          "$type" -> Json.fromString("Trim"),
          "child" -> encodeExpr(srcStr),
          "trimStr" -> trimStr.map(encodeExpr).getOrElse(Json.Null),
          "trimType" -> Json.fromString("Leading")
        )

      case StringTrimRight(srcStr, trimStr) =>
        Json.obj(
          "$type" -> Json.fromString("Trim"),
          "child" -> encodeExpr(srcStr),
          "trimStr" -> trimStr.map(encodeExpr).getOrElse(Json.Null),
          "trimType" -> Json.fromString("Trailing")
        )

      case Length(child) =>
        encodeUnary("Length", child)

      case StringReplace(srcExpr, searchExpr, replaceExpr) =>
        Json.obj(
          "$type" -> Json.fromString("Replace"),
          "str" -> encodeExpr(srcExpr),
          "search" -> encodeExpr(searchExpr),
          "replace" -> encodeExpr(replaceExpr)
        )

      case StringLocate(substr, str, start) =>
        Json.obj(
          "$type" -> Json.fromString("StringLocate"),
          "substr" -> encodeExpr(substr),
          "str" -> encodeExpr(str),
          "start" -> encodeExpr(start)
        )

      case StringLPad(str, len, pad) =>
        Json.obj(
          "$type" -> Json.fromString("Lpad"),
          "str" -> encodeExpr(str),
          "len" -> encodeExpr(len),
          "pad" -> encodeExpr(pad)
        )

      case StringRPad(str, len, pad) =>
        Json.obj(
          "$type" -> Json.fromString("Rpad"),
          "str" -> encodeExpr(str),
          "len" -> encodeExpr(len),
          "pad" -> encodeExpr(pad)
        )

      case StringSplitSQL(str, delimiter) =>
        Json.obj(
          "$type" -> Json.fromString("StringSplit"),
          "str" -> encodeExpr(str),
          "delimiter" -> encodeExpr(delimiter)
        )

      case Reverse(child) =>
        encodeUnary("Reverse", child)

      case StringRepeat(str, times) =>
        Json.obj(
          "$type" -> Json.fromString("StringRepeat"),
          "str" -> encodeExpr(str),
          "times" -> encodeExpr(times)
        )

      // === Pattern matching ===
      case Like(left, right, _) =>
        Json.obj(
          "$type" -> Json.fromString("Like"),
          "value" -> encodeExpr(left),
          "pattern" -> encodeExpr(right)
        )

      // === Control flow ===
      case If(predicate, trueValue, falseValue) =>
        Json.obj(
          "$type" -> Json.fromString("If"),
          "predicate" -> encodeExpr(predicate),
          "trueValue" -> encodeExpr(trueValue),
          "falseValue" -> encodeExpr(falseValue)
        )

      // === BoundReference ===
      case BoundReference(ordinal, dataType, nullable) =>
        Json.obj(
          "$type" -> Json.fromString("BoundRef"),
          "index" -> Json.fromInt(ordinal),
          "dataType" -> encodeDataType(dataType),
          "nullable" -> Json.fromBoolean(nullable)
        )

      // === Date/Time functions ===
      case _: CurrentDate =>
        Json.obj("$type" -> Json.fromString("CurrentDate"))

      case _: CurrentTimestamp =>
        Json.obj("$type" -> Json.fromString("CurrentTimestamp"))

      case DateAdd(startDate, days) =>
        Json.obj(
          "$type" -> Json.fromString("DateAdd"),
          "start" -> encodeExpr(startDate),
          "days" -> encodeExpr(days)
        )

      case DateSub(startDate, days) =>
        Json.obj(
          "$type" -> Json.fromString("DateSub"),
          "start" -> encodeExpr(startDate),
          "days" -> encodeExpr(days)
        )

      case DateDiff(endDate, startDate) =>
        Json.obj(
          "$type" -> Json.fromString("DateDiff"),
          "end" -> encodeExpr(endDate),
          "start" -> encodeExpr(startDate)
        )

      case TruncTimestamp(format, timestamp, _) =>
        Json.obj(
          "$type" -> Json.fromString("DateTrunc"),
          "field" -> encodeExpr(format),
          "timestamp" -> encodeExpr(timestamp)
        )

      case Year(child) =>
        encodeUnary("Year", child)

      case Month(child) =>
        encodeUnary("Month", child)

      case DayOfMonth(child) =>
        encodeUnary("DayOfMonth", child)

      case Hour(child, _) =>
        encodeUnary("Hour", child)

      case Minute(child, _) =>
        encodeUnary("Minute", child)

      case Second(child, _) =>
        encodeUnary("Second", child)

      // === Subquery expressions ===
      case s: ScalarSubquery =>
        Json.obj(
          "$type" -> Json.fromString("ScalarSubquery"),
          "plan" -> encode(s.plan)
        )

      case e: Exists =>
        Json.obj(
          "$type" -> Json.fromString("Exists"),
          "plan" -> encode(e.plan)
        )

      case InSubquery(values, ListQuery(plan, _, _, _, _, _)) =>
        Json.obj(
          "$type" -> Json.fromString("InSubquery"),
          "value" -> encodeExpr(values.head),
          "plan" -> encode(plan)
        )

      // === Window functions ===
      case WindowExpression(func, WindowSpecDefinition(partitionSpec, orderSpec, frame)) =>
        Json.obj(
          "$type" -> Json.fromString("WindowExpr"),
          "function" -> encodeExpr(func),
          "partitionSpec" -> Json.arr(partitionSpec.map(encodeExpr): _*),
          "orderSpec" -> Json.arr(orderSpec.map(encodeSortOrder): _*),
          "frameSpec" -> encodeWindowFrame(frame)
        )

      case _: RowNumber =>
        Json.obj("$type" -> Json.fromString("RowNumber"))

      case _: Rank =>
        Json.obj("$type" -> Json.fromString("Rank"))

      case _: DenseRank =>
        Json.obj("$type" -> Json.fromString("DenseRank"))

      case NTile(buckets) =>
        Json.obj(
          "$type" -> Json.fromString("Ntile"),
          "n" -> encodeExpr(buckets)
        )

      case Lead(input, offset, default, ignoreNulls) =>
        Json.obj(
          "$type" -> Json.fromString("Lead"),
          "input" -> encodeExpr(input),
          "offset" -> encodeExpr(offset),
          "default" -> encodeExpr(default)
        )

      case Lag(input, inputOffset, default, ignoreNulls) =>
        Json.obj(
          "$type" -> Json.fromString("Lag"),
          "input" -> encodeExpr(input),
          "offset" -> encodeExpr(inputOffset),
          "default" -> encodeExpr(default)
        )

      case NthValue(input, offset, _) =>
        Json.obj(
          "$type" -> Json.fromString("NthValue"),
          "input" -> encodeExpr(input),
          "n" -> encodeExpr(offset)
        )

      // === Generator expressions ===
      case Explode(child) =>
        encodeUnary("Explode", child)

      case PosExplode(child) =>
        encodeUnary("PosExplode", child)

      case i: org.apache.spark.sql.catalyst.expressions.Inline =>
        encodeUnary("Inline", i.child)

      // === Grouping ===
      case Grouping(child) =>
        Json.obj(
          "$type" -> Json.fromString("Grouping"),
          "columns" -> Json.arr(encodeExpr(child))
        )

      case GroupingID(groupByExprs) =>
        Json.obj(
          "$type" -> Json.fromString("Grouping"),
          "columns" -> Json.arr(groupByExprs.map(encodeExpr): _*)
        )

      // Aggregate expressions
      case AggregateExpression(aggFunc, _, _, _, _) =>
        encodeAggFunc(aggFunc)

      // UnresolvedGenerator — map known generators to their Proto types
      case ug: UnresolvedGenerator =>
        ug.name.funcName.toLowerCase match {
          case "explode"    => encodeUnary("Explode", ug.children.head)
          case "posexplode" => encodeUnary("PosExplode", ug.children.head)
          case "inline"     => encodeUnary("Inline", ug.children.head)
          case _            =>
            Json.obj(
              "$type" -> Json.fromString("OpaqueCall"),
              "functionName" -> Json.fromString(ug.name.funcName),
              "arguments" -> Json.arr(ug.children.map(encodeExpr): _*)
            )
        }

      // Unresolved function - encode as OpaqueCall
      case f: UnresolvedFunction =>
        Json.obj(
          "$type" -> Json.fromString("OpaqueCall"),
          "functionName" -> Json.fromString(f.nameParts.mkString(".")),
          "arguments" -> Json.arr(f.arguments.map(encodeExpr): _*)
        )

      case other =>
        Json.obj(
          "$type" -> Json.fromString(other.getClass.getSimpleName),
          "_unsupported" -> Json.fromString(other.toString.take(200))
        )
    }
  }

  private def encodeAggFunc(aggFunc: AggregateFunction): Json = {
    aggFunc match {
      case Count(children) =>
        Json.obj(
          "$type" -> Json.fromString("Count"),
          "child" -> (if (children.size == 1) encodeExpr(children.head) else Json.Null),
          "distinct" -> Json.fromBoolean(false)
        )

      case s: Sum =>
        Json.obj(
          "$type" -> Json.fromString("Sum"),
          "child" -> encodeExpr(s.child)
        )

      case a: Average =>
        Json.obj(
          "$type" -> Json.fromString("Avg"),
          "child" -> encodeExpr(a.child)
        )

      case Min(child) =>
        Json.obj(
          "$type" -> Json.fromString("Min"),
          "child" -> encodeExpr(child)
        )

      case Max(child) =>
        Json.obj(
          "$type" -> Json.fromString("Max"),
          "child" -> encodeExpr(child)
        )

      case f: First =>
        Json.obj(
          "$type" -> Json.fromString("FirstValue"),
          "input" -> encodeExpr(f.child),
          "ignoreNulls" -> Json.fromBoolean(f.ignoreNulls)
        )

      case l: Last =>
        Json.obj(
          "$type" -> Json.fromString("LastValue"),
          "input" -> encodeExpr(l.child),
          "ignoreNulls" -> Json.fromBoolean(l.ignoreNulls)
        )

      case other =>
        Json.obj(
          "$type" -> Json.fromString(other.getClass.getSimpleName),
          "_unsupported" -> Json.fromString(other.toString.take(200))
        )
    }
  }

  private def encodeSortOrder(so: SortOrder): Json = {
    Json.obj(
      "child" -> encodeExpr(so.child),
      "direction" -> Json.fromString(if (so.direction == Ascending) "Ascending" else "Descending"),
      "nullOrdering" -> Json.fromString(
        if (so.nullOrdering == NullsFirst) "NullsFirst" else "NullsLast"
      )
    )
  }

  private def encodeUnary(typeName: String, child: Expression): Json =
    Json.obj(
      "$type" -> Json.fromString(typeName),
      "child" -> encodeExpr(child)
    )

  private def encodeBinary(typeName: String, left: Expression, right: Expression): Json =
    Json.obj(
      "$type" -> Json.fromString(typeName),
      "left" -> encodeExpr(left),
      "right" -> encodeExpr(right)
    )

  /** Encode a Spark hint to opaque PlanHint JSON format. Format: {"name": "HINT_NAME", "params":
    * [{"$type": "...HintParam.StringVal/IntVal", "value": ...}]} Normalizes Spark hint name aliases
    * (BROADCASTJOIN → BROADCAST, etc.)
    */
  private def encodeSparkHint(name: String, params: Seq[Expression]): Json = {
    // Normalize Spark hint name aliases to canonical names
    val canonicalName = name.toUpperCase match {
      case "BROADCASTJOIN" | "MAPJOIN" => "BROADCAST"
      case "MERGE" | "MERGEJOIN"       => "SHUFFLE_MERGE"
      case other                       => other
    }

    val hintParams: Seq[Json] = params.map {
      case UnresolvedAttribute(nameParts) =>
        Json.obj(
          "$type" -> Json.fromString("protocatalyst.plan.HintParam.StringVal"),
          "value" -> Json.fromString(nameParts.mkString("."))
        )
      case Literal(n: Int, IntegerType) =>
        Json.obj(
          "$type" -> Json.fromString("protocatalyst.plan.HintParam.IntVal"),
          "value" -> Json.fromInt(n)
        )
      case other =>
        Json.obj(
          "$type" -> Json.fromString("protocatalyst.plan.HintParam.StringVal"),
          "value" -> Json.fromString(other.sql)
        )
    }

    Json.obj(
      "name" -> Json.fromString(canonicalName),
      "params" -> Json.arr(hintParams: _*)
    )
  }

  private def encodeWindowFrame(frame: WindowFrame): Json = frame match {
    case SpecifiedWindowFrame(frameType, lower, upper) =>
      Json.obj(
        "frameType" -> Json.fromString(frameType match {
          case RowFrame   => "Rows"
          case RangeFrame => "Range"
        }),
        "lower" -> encodeFrameBound(lower),
        "upper" -> encodeFrameBound(upper)
      )
    case _ => Json.Null
  }

  private def encodeFrameBound(bound: Expression): Json = bound match {
    case UnboundedPreceding =>
      Json.obj("$type" -> Json.fromString("UnboundedPreceding"))
    case UnboundedFollowing =>
      Json.obj("$type" -> Json.fromString("UnboundedFollowing"))
    case CurrentRow =>
      Json.obj("$type" -> Json.fromString("CurrentRow"))
    case UnaryMinus(Literal(n: Int, IntegerType), _) =>
      Json.obj("$type" -> Json.fromString("Preceding"), "n" -> Json.fromInt(n))
    case Literal(n: Int, IntegerType) =>
      Json.obj("$type" -> Json.fromString("Following"), "n" -> Json.fromInt(n))
    case other =>
      Json.obj("$type" -> Json.fromString("Unknown"), "value" -> Json.fromString(other.toString))
  }

  private def encodeLiteralValue(value: Any, dataType: DataType): Json = {
    (value, dataType) match {
      case (null, dt) =>
        Json.obj(
          "$type" -> Json.fromString("NullValue"),
          "dataType" -> encodeDataType(dt)
        )
      case (b: Boolean, _) =>
        Json.obj("$type" -> Json.fromString("BooleanValue"), "value" -> Json.fromBoolean(b))
      case (b: Byte, _) =>
        Json.obj("$type" -> Json.fromString("ByteValue"), "value" -> Json.fromInt(b.toInt))
      case (s: Short, _) =>
        Json.obj("$type" -> Json.fromString("ShortValue"), "value" -> Json.fromInt(s.toInt))
      case (i: Int, _) =>
        Json.obj("$type" -> Json.fromString("IntValue"), "value" -> Json.fromInt(i))
      case (l: Long, _) =>
        Json.obj("$type" -> Json.fromString("LongValue"), "value" -> Json.fromLong(l))
      case (f: Float, _) =>
        Json.obj("$type" -> Json.fromString("FloatValue"), "value" -> Json.fromFloatOrString(f))
      case (d: Double, _) =>
        Json.obj("$type" -> Json.fromString("DoubleValue"), "value" -> Json.fromDoubleOrString(d))
      case (s: UTF8String, _) =>
        Json.obj("$type" -> Json.fromString("StringValue"), "value" -> Json.fromString(s.toString))
      case (s: String, _) =>
        Json.obj("$type" -> Json.fromString("StringValue"), "value" -> Json.fromString(s))
      case (d: Decimal, _) =>
        Json.obj("$type" -> Json.fromString("DecimalValue"), "value" -> Json.fromString(d.toString))
      case (bytes: Array[Byte], _) =>
        Json.obj(
          "$type" -> Json.fromString("BinaryValue"),
          "value" -> Json.fromString(java.util.Base64.getEncoder.encodeToString(bytes))
        )
      case (other, _) =>
        Json.obj("$type" -> Json.fromString("Unknown"), "value" -> Json.fromString(other.toString))
    }
  }

  private def encodeDataType(dt: DataType): Json = {
    dt match {
      case BooleanType    => Json.fromString("BooleanType")
      case ByteType       => Json.fromString("ByteType")
      case ShortType      => Json.fromString("ShortType")
      case IntegerType    => Json.fromString("IntegerType")
      case LongType       => Json.fromString("LongType")
      case FloatType      => Json.fromString("FloatType")
      case DoubleType     => Json.fromString("DoubleType")
      case StringType     => Json.fromString("StringType")
      case BinaryType     => Json.fromString("BinaryType")
      case DateType       => Json.fromString("DateType")
      case TimestampType  => Json.fromString("TimestampType")
      case d: DecimalType =>
        Json.obj(
          "$type" -> Json.fromString("DecimalType"),
          "precision" -> Json.fromInt(d.precision),
          "scale" -> Json.fromInt(d.scale)
        )
      case ArrayType(elem, containsNull) =>
        Json.obj(
          "$type" -> Json.fromString("ArrayType"),
          "elementType" -> encodeDataType(elem),
          "containsNull" -> Json.fromBoolean(containsNull)
        )
      case MapType(key, value, valueContainsNull) =>
        Json.obj(
          "$type" -> Json.fromString("MapType"),
          "keyType" -> encodeDataType(key),
          "valueType" -> encodeDataType(value),
          "valueContainsNull" -> Json.fromBoolean(valueContainsNull)
        )
      case StructType(fields) =>
        Json.obj(
          "$type" -> Json.fromString("StructType"),
          "fields" -> Json.arr(fields.map { f =>
            Json.obj(
              "name" -> Json.fromString(f.name),
              "dataType" -> encodeDataType(f.dataType),
              "nullable" -> Json.fromBoolean(f.nullable)
            )
          }: _*)
        )
      case other =>
        Json.fromString(other.typeName)
    }
  }

  /** Pretty print JSON for comparison */
  def toPrettyJson(plan: LogicalPlan): String = {
    encode(plan).spaces2
  }
}
