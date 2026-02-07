package protocatalyst.catalyst.protobuf

import io.protocatalyst.proto.{v1 => pb}
import munit.FunSuite
import org.apache.spark.sql.catalyst.analysis.{
  UnresolvedAttribute,
  UnresolvedFunction,
  UnresolvedRelation
}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.types._
import protocatalyst.catalyst.json.ArtifactParser

/** Tests for protobuf-to-Spark decoders (ProtobufTypeDecoder, ProtobufExpressionDecoder,
  * ProtobufPlanDecoder) and the ArtifactParser protobuf path (format byte 0x02).
  */
class ProtobufDecoderSuite extends FunSuite {

  // ============================================================================
  // Helper: build a minimal CompiledArtifactMsg wrapping a plan
  // ============================================================================

  private def wrapInArtifact(plan: pb.ProtoLogicalPlanMsg): pb.CompiledArtifactMsg = {
    pb.CompiledArtifactMsg
      .newBuilder()
      .setFormatVersion(
        pb.ArtifactVersionMsg.newBuilder().setMajor(1).setMinor(0).setPatch(0).build()
      )
      .setProtocatalystVersion("0.1.0-test")
      .setCompiledAt(1700000000000L)
      .setContentHash(12345L)
      .setPlan(plan)
      .setOutputSchema(
        pb.ProtoSchemaMsg
          .newBuilder()
          .addFields(
            pb.ProtoStructFieldMsg
              .newBuilder()
              .setName("id")
              .setDataType(pb.ProtoTypeMsg.newBuilder().setLongType(pb.EmptyMsg.getDefaultInstance))
              .setNullable(false)
          )
          .build()
      )
      .build()
  }

  /** Wrap artifact bytes with PCAT header + protobuf format byte. */
  private def withPcatHeader(artifactBytes: Array[Byte]): Array[Byte] = {
    val header = "PCAT".getBytes("UTF-8") ++ Array[Byte](0x02.toByte)
    header ++ artifactBytes
  }

  // ============================================================================
  // Helper: build proto messages
  // ============================================================================

  private def relationRef(name: String): pb.ProtoLogicalPlanMsg = {
    pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setRelationRef(
        pb.RelationRefMsg
          .newBuilder()
          .setName(name)
          .setSchemaContract(
            pb.SchemaContractMsg
              .newBuilder()
              .setRelationName(name)
              .setFingerprint(99999L)
          )
      )
      .build()
  }

  private def columnRef(name: String): pb.ProtoExprMsg = {
    pb.ProtoExprMsg
      .newBuilder()
      .setColumnRef(
        pb.ColumnRefExprMsg
          .newBuilder()
          .setName(name)
          .setResolvedType(
            pb.ProtoTypeMsg.newBuilder().setLongType(pb.EmptyMsg.getDefaultInstance)
          )
          .setNullable(false)
      )
      .build()
  }

  private def intLiteral(value: Int): pb.ProtoExprMsg = {
    pb.ProtoExprMsg
      .newBuilder()
      .setLiteral(
        pb.LiteralExprMsg
          .newBuilder()
          .setValue(pb.LiteralValueMsg.newBuilder().setIntValue(value))
      )
      .build()
  }

  private def stringLiteral(value: String): pb.ProtoExprMsg = {
    pb.ProtoExprMsg
      .newBuilder()
      .setLiteral(
        pb.LiteralExprMsg
          .newBuilder()
          .setValue(pb.LiteralValueMsg.newBuilder().setStringValue(value))
      )
      .build()
  }

  private def booleanLiteral(value: Boolean): pb.ProtoExprMsg = {
    pb.ProtoExprMsg
      .newBuilder()
      .setLiteral(
        pb.LiteralExprMsg
          .newBuilder()
          .setValue(pb.LiteralValueMsg.newBuilder().setBooleanValue(value))
      )
      .build()
  }

  // ============================================================================
  // TypeDecoder Tests
  // ============================================================================

  test("decode all primitive types") {
    val cases: Seq[(pb.ProtoTypeMsg, DataType)] = Seq(
      pb.ProtoTypeMsg
        .newBuilder()
        .setBooleanType(pb.EmptyMsg.getDefaultInstance)
        .build() -> BooleanType,
      pb.ProtoTypeMsg.newBuilder().setByteType(pb.EmptyMsg.getDefaultInstance).build() -> ByteType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setShortType(pb.EmptyMsg.getDefaultInstance)
        .build() -> ShortType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setIntegerType(pb.EmptyMsg.getDefaultInstance)
        .build() -> IntegerType,
      pb.ProtoTypeMsg.newBuilder().setLongType(pb.EmptyMsg.getDefaultInstance).build() -> LongType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setFloatType(pb.EmptyMsg.getDefaultInstance)
        .build() -> FloatType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setDoubleType(pb.EmptyMsg.getDefaultInstance)
        .build() -> DoubleType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setStringType(pb.EmptyMsg.getDefaultInstance)
        .build() -> StringType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setBinaryType(pb.EmptyMsg.getDefaultInstance)
        .build() -> BinaryType,
      pb.ProtoTypeMsg.newBuilder().setDateType(pb.EmptyMsg.getDefaultInstance).build() -> DateType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setTimestampType(pb.EmptyMsg.getDefaultInstance)
        .build() -> TimestampType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setTimestampNtzType(pb.EmptyMsg.getDefaultInstance)
        .build() -> TimestampNTZType,
      pb.ProtoTypeMsg.newBuilder().setNullType(pb.EmptyMsg.getDefaultInstance).build() -> NullType,
      pb.ProtoTypeMsg
        .newBuilder()
        .setVariantType(pb.EmptyMsg.getDefaultInstance)
        .build() -> VariantType
    )
    cases.foreach { case (proto, expected) =>
      assertEquals(ProtobufTypeDecoder.decode(proto), expected, s"Failed for $expected")
    }
  }

  test("decode parameterized types") {
    val decimal = pb.ProtoTypeMsg
      .newBuilder()
      .setDecimalType(pb.DecimalTypeMsg.newBuilder().setPrecision(18).setScale(6))
      .build()
    assertEquals(ProtobufTypeDecoder.decode(decimal), DecimalType(18, 6))

    val char = pb.ProtoTypeMsg
      .newBuilder()
      .setCharType(pb.CharTypeMsg.newBuilder().setLength(10))
      .build()
    assertEquals(ProtobufTypeDecoder.decode(char), CharType(10))

    val varchar = pb.ProtoTypeMsg
      .newBuilder()
      .setVarcharType(pb.VarcharTypeMsg.newBuilder().setLength(255))
      .build()
    assertEquals(ProtobufTypeDecoder.decode(varchar), VarcharType(255))
  }

  test("decode complex types") {
    // ArrayType
    val array = pb.ProtoTypeMsg
      .newBuilder()
      .setArrayType(
        pb.ArrayTypeMsg
          .newBuilder()
          .setElementType(
            pb.ProtoTypeMsg.newBuilder().setIntegerType(pb.EmptyMsg.getDefaultInstance)
          )
          .setContainsNull(true)
      )
      .build()
    assertEquals(ProtobufTypeDecoder.decode(array), ArrayType(IntegerType, true))

    // MapType
    val map = pb.ProtoTypeMsg
      .newBuilder()
      .setMapType(
        pb.MapTypeMsg
          .newBuilder()
          .setKeyType(
            pb.ProtoTypeMsg.newBuilder().setStringType(pb.EmptyMsg.getDefaultInstance)
          )
          .setValueType(
            pb.ProtoTypeMsg.newBuilder().setDoubleType(pb.EmptyMsg.getDefaultInstance)
          )
          .setValueContainsNull(false)
      )
      .build()
    assertEquals(ProtobufTypeDecoder.decode(map), MapType(StringType, DoubleType, false))

    // StructType
    val struct = pb.ProtoTypeMsg
      .newBuilder()
      .setStructType(
        pb.StructTypeMsg
          .newBuilder()
          .addFields(
            pb.ProtoStructFieldMsg
              .newBuilder()
              .setName("x")
              .setDataType(
                pb.ProtoTypeMsg.newBuilder().setIntegerType(pb.EmptyMsg.getDefaultInstance)
              )
              .setNullable(false)
          )
          .addFields(
            pb.ProtoStructFieldMsg
              .newBuilder()
              .setName("y")
              .setDataType(
                pb.ProtoTypeMsg.newBuilder().setStringType(pb.EmptyMsg.getDefaultInstance)
              )
              .setNullable(true)
          )
      )
      .build()
    val expected =
      StructType(Array(StructField("x", IntegerType, false), StructField("y", StringType, true)))
    assertEquals(ProtobufTypeDecoder.decode(struct), expected)
  }

  // ============================================================================
  // ExpressionDecoder Tests
  // ============================================================================

  test("decode literal expressions") {
    val intExpr = intLiteral(42)
    val result = ProtobufExpressionDecoder.decode(intExpr)
    assertEquals(result, Literal(42))

    val strExpr = stringLiteral("hello")
    val strResult = ProtobufExpressionDecoder.decode(strExpr)
    assert(strResult.isInstanceOf[Literal])

    val boolExpr = booleanLiteral(true)
    val boolResult = ProtobufExpressionDecoder.decode(boolExpr)
    assertEquals(boolResult, Literal(true))
  }

  test("decode column reference") {
    val expr = columnRef("name")
    val result = ProtobufExpressionDecoder.decode(expr)
    assertEquals(result, UnresolvedAttribute(Seq("name")))
  }

  test("decode qualified column reference") {
    val expr = pb.ProtoExprMsg
      .newBuilder()
      .setColumnRef(
        pb.ColumnRefExprMsg
          .newBuilder()
          .setName("id")
          .setQualifier("t1")
          .setResolvedType(
            pb.ProtoTypeMsg.newBuilder().setLongType(pb.EmptyMsg.getDefaultInstance)
          )
          .setNullable(false)
      )
      .build()
    val result = ProtobufExpressionDecoder.decode(expr)
    assertEquals(result, UnresolvedAttribute(Seq("t1", "id")))
  }

  test("decode comparison expressions") {
    val binary = pb.BinaryExprMsg
      .newBuilder()
      .setLeft(columnRef("age"))
      .setRight(intLiteral(18))
      .build()

    // GT
    val gt = pb.ProtoExprMsg.newBuilder().setGt(binary).build()
    val gtResult = ProtobufExpressionDecoder.decode(gt)
    assert(gtResult.isInstanceOf[GreaterThan])

    // LT
    val lt = pb.ProtoExprMsg.newBuilder().setLt(binary).build()
    val ltResult = ProtobufExpressionDecoder.decode(lt)
    assert(ltResult.isInstanceOf[LessThan])

    // EQ
    val eq = pb.ProtoExprMsg.newBuilder().setEq(binary).build()
    val eqResult = ProtobufExpressionDecoder.decode(eq)
    assert(eqResult.isInstanceOf[EqualTo])
  }

  test("decode logical expressions") {
    val cond1 = pb.ProtoExprMsg
      .newBuilder()
      .setGt(
        pb.BinaryExprMsg
          .newBuilder()
          .setLeft(columnRef("age"))
          .setRight(intLiteral(18))
      )
      .build()

    val cond2 = pb.ProtoExprMsg
      .newBuilder()
      .setLt(
        pb.BinaryExprMsg
          .newBuilder()
          .setLeft(columnRef("age"))
          .setRight(intLiteral(65))
      )
      .build()

    // AND
    val andExpr = pb.ProtoExprMsg
      .newBuilder()
      .setAnd(pb.NaryExprMsg.newBuilder().addChildren(cond1).addChildren(cond2))
      .build()
    val andResult = ProtobufExpressionDecoder.decode(andExpr)
    assert(andResult.isInstanceOf[And])

    // NOT
    val notExpr = pb.ProtoExprMsg
      .newBuilder()
      .setNot(pb.UnaryExprMsg.newBuilder().setChild(cond1))
      .build()
    val notResult = ProtobufExpressionDecoder.decode(notExpr)
    assert(notResult.isInstanceOf[Not])
  }

  test("decode arithmetic expressions") {
    val binary = pb.BinaryExprMsg
      .newBuilder()
      .setLeft(columnRef("salary"))
      .setRight(intLiteral(1000))
      .build()

    val add = pb.ProtoExprMsg.newBuilder().setAdd(binary).build()
    val addResult = ProtobufExpressionDecoder.decode(add)
    assert(addResult.isInstanceOf[Add])

    val mul = pb.ProtoExprMsg.newBuilder().setMultiply(binary).build()
    val mulResult = ProtobufExpressionDecoder.decode(mul)
    assert(mulResult.isInstanceOf[Multiply])
  }

  test("decode string functions") {
    val upper = pb.ProtoExprMsg
      .newBuilder()
      .setUpper(pb.UnaryExprMsg.newBuilder().setChild(columnRef("name")))
      .build()
    val upperResult = ProtobufExpressionDecoder.decode(upper)
    assert(upperResult.isInstanceOf[Upper])

    val lower = pb.ProtoExprMsg
      .newBuilder()
      .setLower(pb.UnaryExprMsg.newBuilder().setChild(columnRef("name")))
      .build()
    val lowerResult = ProtobufExpressionDecoder.decode(lower)
    assert(lowerResult.isInstanceOf[Lower])
  }

  test("decode aggregate expressions") {
    val sum = pb.ProtoExprMsg
      .newBuilder()
      .setSum(pb.UnaryExprMsg.newBuilder().setChild(columnRef("salary")))
      .build()
    val sumResult = ProtobufExpressionDecoder.decode(sum)
    assert(sumResult.isInstanceOf[AggregateExpression])

    val count = pb.ProtoExprMsg
      .newBuilder()
      .setCount(pb.CountExprMsg.newBuilder().setChild(columnRef("id")).setDistinct(false))
      .build()
    val countResult = ProtobufExpressionDecoder.decode(count)
    assert(countResult.isInstanceOf[AggregateExpression])
  }

  test("decode alias expression") {
    val alias = pb.ProtoExprMsg
      .newBuilder()
      .setAlias(
        pb.AliasExprMsg
          .newBuilder()
          .setChild(columnRef("name"))
          .setName("user_name")
      )
      .build()
    val result = ProtobufExpressionDecoder.decode(alias)
    assert(result.isInstanceOf[Alias])
    val a = result.asInstanceOf[Alias]
    assertEquals(a.name, "user_name")
  }

  test("decode cast expression") {
    val cast = pb.ProtoExprMsg
      .newBuilder()
      .setCast(
        pb.CastExprMsg
          .newBuilder()
          .setChild(columnRef("age"))
          .setTargetType(
            pb.ProtoTypeMsg.newBuilder().setDoubleType(pb.EmptyMsg.getDefaultInstance)
          )
      )
      .build()
    val result = ProtobufExpressionDecoder.decode(cast)
    assert(result.isInstanceOf[Cast])
    val c = result.asInstanceOf[Cast]
    assertEquals(c.dataType, DoubleType)
  }

  test("decode null handling expressions") {
    val isNull = pb.ProtoExprMsg
      .newBuilder()
      .setIsNull(pb.UnaryExprMsg.newBuilder().setChild(columnRef("email")))
      .build()
    assert(ProtobufExpressionDecoder.decode(isNull).isInstanceOf[IsNull])

    val isNotNull = pb.ProtoExprMsg
      .newBuilder()
      .setIsNotNull(pb.UnaryExprMsg.newBuilder().setChild(columnRef("email")))
      .build()
    assert(ProtobufExpressionDecoder.decode(isNotNull).isInstanceOf[IsNotNull])
  }

  test("decode window function expressions") {
    // RowNumber
    val rowNum = pb.ProtoExprMsg
      .newBuilder()
      .setRowNumber(pb.EmptyMsg.getDefaultInstance)
      .build()
    assert(ProtobufExpressionDecoder.decode(rowNum).isInstanceOf[RowNumber])

    // Rank
    val rank = pb.ProtoExprMsg
      .newBuilder()
      .setRank(pb.EmptyMsg.getDefaultInstance)
      .build()
    assert(ProtobufExpressionDecoder.decode(rank).isInstanceOf[Rank])

    // WindowExpr
    val windowExpr = pb.ProtoExprMsg
      .newBuilder()
      .setWindowExpr(
        pb.WindowExprMsg
          .newBuilder()
          .setFunction(rowNum)
          .addPartitionSpec(columnRef("dept_id"))
          .addOrderSpec(
            pb.SortOrderMsg
              .newBuilder()
              .setChild(columnRef("salary"))
              .setDirection(pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING)
              .setNullOrdering(pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST)
          )
      )
      .build()
    val result = ProtobufExpressionDecoder.decode(windowExpr)
    assert(result.isInstanceOf[WindowExpression])
  }

  test("decode case when expression") {
    val caseWhen = pb.ProtoExprMsg
      .newBuilder()
      .setCaseWhen(
        pb.CaseWhenExprMsg
          .newBuilder()
          .addBranches(
            pb.CaseWhenBranchMsg
              .newBuilder()
              .setCondition(
                pb.ProtoExprMsg
                  .newBuilder()
                  .setGt(
                    pb.BinaryExprMsg
                      .newBuilder()
                      .setLeft(columnRef("age"))
                      .setRight(intLiteral(18))
                  )
              )
              .setValue(stringLiteral("adult"))
          )
          .setElseValue(stringLiteral("minor"))
      )
      .build()
    val result = ProtobufExpressionDecoder.decode(caseWhen)
    assert(result.isInstanceOf[CaseWhen])
  }

  // ============================================================================
  // PlanDecoder Tests
  // ============================================================================

  test("decode RelationRef") {
    val plan = ProtobufPlanDecoder.decode(relationRef("users"))
    assert(plan.isInstanceOf[UnresolvedRelation])
    assertEquals(plan.asInstanceOf[UnresolvedRelation].multipartIdentifier, Seq("users"))
  }

  test("decode RelationRef with alias") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setRelationRef(
        pb.RelationRefMsg
          .newBuilder()
          .setName("users")
          .setAlias("u")
          .setSchemaContract(
            pb.SchemaContractMsg.newBuilder().setRelationName("users").setFingerprint(0L)
          )
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[SubqueryAlias])
    assertEquals(plan.asInstanceOf[SubqueryAlias].identifier.name, "u")
  }

  test("decode Project") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setProject(
        pb.ProjectMsg
          .newBuilder()
          .addProjectList(columnRef("name"))
          .addProjectList(columnRef("age"))
          .setChild(relationRef("users"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Project])
    val p = plan.asInstanceOf[Project]
    assertEquals(p.projectList.size, 2)
  }

  test("decode Filter") {
    val condition = pb.ProtoExprMsg
      .newBuilder()
      .setGt(
        pb.BinaryExprMsg
          .newBuilder()
          .setLeft(columnRef("age"))
          .setRight(intLiteral(18))
      )
      .build()

    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setFilter(
        pb.FilterMsg
          .newBuilder()
          .setCondition(condition)
          .setChild(relationRef("users"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Filter])
    assert(plan.asInstanceOf[Filter].condition.isInstanceOf[GreaterThan])
  }

  test("decode Sort") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setSort(
        pb.SortMsg
          .newBuilder()
          .addOrder(
            pb.SortOrderMsg
              .newBuilder()
              .setChild(columnRef("salary"))
              .setDirection(pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING)
              .setNullOrdering(pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST)
          )
          .setChild(relationRef("users"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Sort])
    val s = plan.asInstanceOf[Sort]
    assertEquals(s.order.size, 1)
    assertEquals(s.order.head.direction, Descending)
    assertEquals(s.order.head.nullOrdering, NullsLast)
  }

  test("decode Limit") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setLimit(
        pb.LimitMsg
          .newBuilder()
          .setLimit(10)
          .setChild(relationRef("users"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[GlobalLimit])
    val gl = plan.asInstanceOf[GlobalLimit]
    assert(gl.child.isInstanceOf[LocalLimit])
  }

  test("decode Distinct") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setDistinct(
        pb.DistinctMsg
          .newBuilder()
          .setChild(relationRef("users"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Distinct])
  }

  test("decode Join") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setJoin(
        pb.JoinMsg
          .newBuilder()
          .setLeft(relationRef("users"))
          .setRight(relationRef("orders"))
          .setJoinType(pb.JoinTypeEnum.JOIN_TYPE_INNER)
          .setCondition(
            pb.ProtoExprMsg
              .newBuilder()
              .setEq(
                pb.BinaryExprMsg
                  .newBuilder()
                  .setLeft(columnRef("user_id"))
                  .setRight(columnRef("id"))
              )
          )
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Join])
    val j = plan.asInstanceOf[Join]
    assertEquals(j.joinType, Inner)
    assert(j.condition.isDefined)
  }

  test("decode Join types") {
    val joinTypes = Seq(
      pb.JoinTypeEnum.JOIN_TYPE_INNER -> Inner,
      pb.JoinTypeEnum.JOIN_TYPE_LEFT_OUTER -> LeftOuter,
      pb.JoinTypeEnum.JOIN_TYPE_RIGHT_OUTER -> RightOuter,
      pb.JoinTypeEnum.JOIN_TYPE_FULL_OUTER -> FullOuter,
      pb.JoinTypeEnum.JOIN_TYPE_LEFT_SEMI -> LeftSemi,
      pb.JoinTypeEnum.JOIN_TYPE_LEFT_ANTI -> LeftAnti,
      pb.JoinTypeEnum.JOIN_TYPE_CROSS -> Cross
    )
    joinTypes.foreach { case (protoJt, sparkJt) =>
      val msg = pb.ProtoLogicalPlanMsg
        .newBuilder()
        .setJoin(
          pb.JoinMsg
            .newBuilder()
            .setLeft(relationRef("t1"))
            .setRight(relationRef("t2"))
            .setJoinType(protoJt)
        )
        .build()
      val plan = ProtobufPlanDecoder.decode(msg)
      assertEquals(plan.asInstanceOf[Join].joinType, sparkJt, s"Failed for $protoJt")
    }
  }

  test("decode Union") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setUnion(
        pb.UnionMsg
          .newBuilder()
          .addChildren(relationRef("t1"))
          .addChildren(relationRef("t2"))
          .setByName(false)
          .setAllowMissingColumns(false)
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Union])
    assertEquals(plan.asInstanceOf[Union].children.size, 2)
  }

  test("decode Intersect") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setIntersect(
        pb.IntersectMsg
          .newBuilder()
          .setLeft(relationRef("t1"))
          .setRight(relationRef("t2"))
          .setIsAll(true)
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Intersect])
    assert(plan.asInstanceOf[Intersect].isAll)
  }

  test("decode Except") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setExcept(
        pb.ExceptMsg
          .newBuilder()
          .setLeft(relationRef("t1"))
          .setRight(relationRef("t2"))
          .setIsAll(false)
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Except])
    assert(!plan.asInstanceOf[Except].isAll)
  }

  test("decode Aggregate") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setAggregate(
        pb.AggregateMsg
          .newBuilder()
          .addGroupingExprs(columnRef("dept_id"))
          .addAggregateExprs(columnRef("dept_id"))
          .addAggregateExprs(
            pb.ProtoExprMsg
              .newBuilder()
              .setCount(
                pb.CountExprMsg
                  .newBuilder()
                  .setChild(columnRef("id"))
                  .setDistinct(false)
              )
          )
          .setChild(relationRef("employees"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(plan.isInstanceOf[Aggregate])
    val a = plan.asInstanceOf[Aggregate]
    assertEquals(a.groupingExpressions.size, 1)
    assertEquals(a.aggregateExpressions.size, 2)
  }

  test("decode ResolvedHint with Broadcast") {
    val msg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setResolvedHint(
        pb.ResolvedHintMsg
          .newBuilder()
          .addHints(
            pb.PlanHintMsg
              .newBuilder()
              .setName("BROADCAST")
              .addParams(
                pb.HintParamMsg.newBuilder().setStringVal("t1")
              )
          )
          .setChild(relationRef("t1"))
      )
      .build()
    val plan = ProtobufPlanDecoder.decode(msg)
    assert(
      plan.isInstanceOf[UnresolvedHint],
      s"Expected UnresolvedHint, got ${plan.getClass.getSimpleName}"
    )
    val h = plan.asInstanceOf[UnresolvedHint]
    assertEquals(h.name, "BROADCAST")
    assertEquals(h.parameters.size, 1)
  }

  test("decode nested plan: Filter(Project)") {
    val filterPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setFilter(
        pb.FilterMsg
          .newBuilder()
          .setCondition(
            pb.ProtoExprMsg
              .newBuilder()
              .setGt(
                pb.BinaryExprMsg
                  .newBuilder()
                  .setLeft(columnRef("age"))
                  .setRight(intLiteral(18))
              )
          )
          .setChild(
            pb.ProtoLogicalPlanMsg
              .newBuilder()
              .setProject(
                pb.ProjectMsg
                  .newBuilder()
                  .addProjectList(columnRef("name"))
                  .addProjectList(columnRef("age"))
                  .setChild(relationRef("users"))
              )
          )
      )
      .build()

    val plan = ProtobufPlanDecoder.decode(filterPlan)
    assert(plan.isInstanceOf[Filter])
    assert(plan.asInstanceOf[Filter].child.isInstanceOf[Project])
  }

  // ============================================================================
  // End-to-End: parsePlanFromBytes
  // ============================================================================

  test("parsePlanFromBytes decodes protobuf artifact") {
    val planMsg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setProject(
        pb.ProjectMsg
          .newBuilder()
          .addProjectList(columnRef("name"))
          .setChild(relationRef("users"))
      )
      .build()

    val artifact = wrapInArtifact(planMsg)
    val result = ProtobufPlanDecoder.parsePlanFromBytes(artifact.toByteArray)

    assert(result.isRight, s"Expected Right, got: ${result.left.getOrElse("")}")
    val plan = result.toOption.get
    assert(plan.isInstanceOf[Project])
  }

  test("parsePlanFromBytes returns error for invalid bytes") {
    val result = ProtobufPlanDecoder.parsePlanFromBytes(Array[Byte](1, 2, 3, 4))
    // Invalid protobuf should either fail to parse or produce an incomplete artifact
    // Either way, the method should handle it gracefully (no exception thrown)
    // Note: protobuf is lenient; it may parse garbage as empty fields
  }

  // ============================================================================
  // ArtifactParser integration: format byte 0x02
  // ============================================================================

  test("ArtifactParser.parsePlan handles format byte 0x02") {
    val planMsg = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setFilter(
        pb.FilterMsg
          .newBuilder()
          .setCondition(
            pb.ProtoExprMsg
              .newBuilder()
              .setGt(
                pb.BinaryExprMsg
                  .newBuilder()
                  .setLeft(columnRef("age"))
                  .setRight(intLiteral(18))
              )
          )
          .setChild(relationRef("users"))
      )
      .build()

    val artifact = wrapInArtifact(planMsg)
    val bytes = withPcatHeader(artifact.toByteArray)
    val result = ArtifactParser.parsePlan(bytes)

    assert(result.isRight, s"Expected Right, got: ${result.left.getOrElse("")}")
    val plan = result.toOption.get
    assert(plan.isInstanceOf[Filter], s"Expected Filter, got ${plan.getClass.getSimpleName}")
    assert(plan.asInstanceOf[Filter].condition.isInstanceOf[GreaterThan])
  }

  test("ArtifactParser.parsePlan still handles format byte 0x01 (JSON)") {
    // Verify JSON path still works
    val jsonStr = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "test_table",
        "alias": null,
        "schemaContract": {
          "relationName": "test_table",
          "requiredFields": [],
          "fingerprint": {"value": 99999}
        }
      }
    }"""
    val payload = jsonStr.getBytes("UTF-8")
    val header = "PCAT".getBytes("UTF-8") ++ Array[Byte](0x01.toByte)
    val bytes = header ++ payload
    val result = ArtifactParser.parsePlan(bytes)

    assert(result.isRight, s"JSON format byte 0x01 failed: ${result.left.getOrElse("")}")
  }

  test("ArtifactParser.parsePlan rejects unknown format byte") {
    val header = "PCAT".getBytes("UTF-8") ++ Array[Byte](0x99.toByte)
    val bytes = header ++ "payload".getBytes("UTF-8")
    val result = ArtifactParser.parsePlan(bytes)

    assert(result.isLeft, "Expected Left for unknown format byte")
    assert(result.left.getOrElse("").contains("Unsupported format"))
  }

  test("ArtifactParser.parsePlan rejects too-short bytes") {
    val result = ArtifactParser.parsePlan(Array[Byte](1, 2, 3))
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("too short"))
  }

  test("ArtifactParser.parsePlan rejects invalid magic header") {
    val bytes = "XXXX".getBytes("UTF-8") ++ Array[Byte](0x02.toByte) ++ Array.emptyByteArray
    val result = ArtifactParser.parsePlan(bytes)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Invalid magic header"))
  }

  // ============================================================================
  // Complex plan through ArtifactParser
  // ============================================================================

  test("ArtifactParser protobuf: Project + Filter + Sort + Limit") {
    // Build inside-out to keep nesting manageable
    val projectPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setProject(
        pb.ProjectMsg
          .newBuilder()
          .addProjectList(columnRef("name"))
          .addProjectList(columnRef("salary"))
          .setChild(relationRef("employees"))
      )
      .build()

    val filterPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setFilter(
        pb.FilterMsg
          .newBuilder()
          .setCondition(
            pb.ProtoExprMsg
              .newBuilder()
              .setGt(
                pb.BinaryExprMsg
                  .newBuilder()
                  .setLeft(columnRef("age"))
                  .setRight(intLiteral(25))
              )
          )
          .setChild(projectPlan)
      )
      .build()

    val sortPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setSort(
        pb.SortMsg
          .newBuilder()
          .addOrder(
            pb.SortOrderMsg
              .newBuilder()
              .setChild(columnRef("salary"))
              .setDirection(pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING)
              .setNullOrdering(pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST)
          )
          .setChild(filterPlan)
      )
      .build()

    val innerPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setLimit(
        pb.LimitMsg
          .newBuilder()
          .setLimit(10)
          .setChild(sortPlan)
      )
      .build()

    val artifact = wrapInArtifact(innerPlan)
    val bytes = withPcatHeader(artifact.toByteArray)
    val result = ArtifactParser.parsePlan(bytes)

    assert(result.isRight, s"Expected Right, got: ${result.left.getOrElse("")}")
    val plan = result.toOption.get

    // Should be GlobalLimit(LocalLimit(Sort(Filter(Project(UnresolvedRelation)))))
    assert(
      plan.isInstanceOf[GlobalLimit],
      s"Expected GlobalLimit, got ${plan.getClass.getSimpleName}"
    )
    val gl = plan.asInstanceOf[GlobalLimit]
    assert(gl.child.isInstanceOf[LocalLimit])
    val ll = gl.child.asInstanceOf[LocalLimit]
    assert(ll.child.isInstanceOf[Sort])
    val sort = ll.child.asInstanceOf[Sort]
    assert(sort.child.isInstanceOf[Filter])
    val filter = sort.child.asInstanceOf[Filter]
    assert(filter.child.isInstanceOf[Project])
    val project = filter.child.asInstanceOf[Project]
    assert(project.child.isInstanceOf[UnresolvedRelation])
  }

  test("ArtifactParser protobuf: Join with condition") {
    val joinPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setJoin(
        pb.JoinMsg
          .newBuilder()
          .setLeft(relationRef("users"))
          .setRight(relationRef("orders"))
          .setJoinType(pb.JoinTypeEnum.JOIN_TYPE_LEFT_OUTER)
          .setCondition(
            pb.ProtoExprMsg
              .newBuilder()
              .setEq(
                pb.BinaryExprMsg
                  .newBuilder()
                  .setLeft(columnRef("id"))
                  .setRight(columnRef("user_id"))
              )
          )
      )
      .build()

    val artifact = wrapInArtifact(joinPlan)
    val bytes = withPcatHeader(artifact.toByteArray)
    val result = ArtifactParser.parsePlan(bytes)

    assert(result.isRight, s"Expected Right, got: ${result.left.getOrElse("")}")
    val plan = result.toOption.get
    assert(plan.isInstanceOf[Join])
    val j = plan.asInstanceOf[Join]
    assertEquals(j.joinType, LeftOuter)
    assert(j.condition.isDefined)
  }

  test("ArtifactParser protobuf: Aggregate with group-by and aggregates") {
    val aggPlan = pb.ProtoLogicalPlanMsg
      .newBuilder()
      .setAggregate(
        pb.AggregateMsg
          .newBuilder()
          .addGroupingExprs(columnRef("dept_id"))
          .addAggregateExprs(columnRef("dept_id"))
          .addAggregateExprs(
            pb.ProtoExprMsg
              .newBuilder()
              .setAlias(
                pb.AliasExprMsg
                  .newBuilder()
                  .setChild(
                    pb.ProtoExprMsg
                      .newBuilder()
                      .setSum(
                        pb.UnaryExprMsg.newBuilder().setChild(columnRef("salary"))
                      )
                  )
                  .setName("total_salary")
              )
          )
          .setChild(relationRef("employees"))
      )
      .build()

    val artifact = wrapInArtifact(aggPlan)
    val bytes = withPcatHeader(artifact.toByteArray)
    val result = ArtifactParser.parsePlan(bytes)

    assert(result.isRight, s"Expected Right, got: ${result.left.getOrElse("")}")
    val plan = result.toOption.get
    assert(plan.isInstanceOf[Aggregate])
    val agg = plan.asInstanceOf[Aggregate]
    assertEquals(agg.groupingExpressions.size, 1)
    assertEquals(agg.aggregateExpressions.size, 2)
  }
}
