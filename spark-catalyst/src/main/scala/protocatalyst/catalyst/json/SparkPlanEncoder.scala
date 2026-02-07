package protocatalyst.catalyst.json

import io.circe.Json
import org.apache.spark.sql.catalyst.analysis.{
  UnresolvedAttribute,
  UnresolvedFunction,
  UnresolvedRelation
}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
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

      case l: GlobalLimit =>
        Json.obj(
          "$type" -> Json.fromString("Limit"),
          "limitExpr" -> encodeExpr(l.limitExpr),
          "child" -> encode(l.child)
        )

      case l: LocalLimit =>
        Json.obj(
          "$type" -> Json.fromString("Limit"),
          "limitExpr" -> encodeExpr(l.limitExpr),
          "child" -> encode(l.child)
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
          "allowMissingCol" -> Json.fromBoolean(u.allowMissingCol)
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
          "$type" -> Json.fromString("WithCTE"),
          "cteRelations" -> Json.arr(w.cteRelations.map { case (name, sub) =>
            Json.obj(
              "name" -> Json.fromString(name),
              "subquery" -> encode(sub.child)
            )
          }: _*),
          "child" -> encode(w.child)
        )

      case w: WithCTE =>
        Json.obj(
          "$type" -> Json.fromString("WithCTE"),
          "cteDefs" -> Json.arr(w.cteDefs.map(encode): _*),
          "child" -> encode(w.plan)
        )

      case c: CTERelationDef =>
        Json.obj(
          "$type" -> Json.fromString("CTERelationDef"),
          "child" -> encode(c.child)
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

      // Aggregate expressions
      case AggregateExpression(aggFunc, _, _, _, _) =>
        encodeAggFunc(aggFunc)

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
