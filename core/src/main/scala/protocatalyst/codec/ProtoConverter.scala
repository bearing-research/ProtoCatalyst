package protocatalyst.codec

import com.google.protobuf.ByteString
import io.protocatalyst.proto.{v1 => pb}

import protocatalyst.artifact._
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

/** Bidirectional conversion between Scala IR types and generated Java protobuf types. */
object ProtoConverter:

  // ============================================================================
  // Top-level: CompiledArtifact
  // ============================================================================

  def toProto(artifact: CompiledArtifact): pb.CompiledArtifactMsg =
    val builder = pb.CompiledArtifactMsg.newBuilder()
    builder.setFormatVersion(toProtoVersion(artifact.formatVersion))
    builder.setProtocatalystVersion(artifact.protocatalystVersion)
    builder.setCompiledAt(artifact.compiledAt)
    builder.setContentHash(artifact.contentHash)
    artifact.schemaContracts.foreach(sc => builder.addSchemaContracts(toProtoSchemaContract(sc)))
    builder.setPlan(toProtoPlan(artifact.plan))
    builder.setOutputSchema(toProtoSchema(artifact.outputSchema))
    artifact.sourceInfo.foreach(si => builder.setSourceInfo(toProtoSourceInfo(si)))
    builder.build()

  def fromProto(msg: pb.CompiledArtifactMsg): CompiledArtifact =
    val contracts = (0 until msg.getSchemaContractsCount)
      .map(i => fromProtoSchemaContract(msg.getSchemaContracts(i)))
      .toVector
    CompiledArtifact(
      formatVersion = fromProtoVersion(msg.getFormatVersion),
      protocatalystVersion = msg.getProtocatalystVersion,
      compiledAt = msg.getCompiledAt,
      contentHash = msg.getContentHash,
      schemaContracts = contracts,
      plan = fromProtoPlan(msg.getPlan),
      outputSchema = fromProtoSchema(msg.getOutputSchema),
      sourceInfo = if msg.hasSourceInfo then Some(fromProtoSourceInfo(msg.getSourceInfo)) else None
    )

  // ============================================================================
  // ArtifactVersion / SourceInfo
  // ============================================================================

  private def toProtoVersion(v: ArtifactVersion): pb.ArtifactVersionMsg =
    pb.ArtifactVersionMsg.newBuilder().setMajor(v.major).setMinor(v.minor).setPatch(v.patch).build()

  private def fromProtoVersion(msg: pb.ArtifactVersionMsg): ArtifactVersion =
    ArtifactVersion(msg.getMajor, msg.getMinor, msg.getPatch)

  private def toProtoSourceInfo(si: SourceInfo): pb.SourceInfoMsg =
    val b = pb.SourceInfoMsg
      .newBuilder()
      .setSourceFile(si.sourceFile)
      .setLineNumber(si.lineNumber)
    si.originalSql.foreach(b.setOriginalSql)
    b.build()

  private def fromProtoSourceInfo(msg: pb.SourceInfoMsg): SourceInfo =
    SourceInfo(
      sourceFile = msg.getSourceFile,
      lineNumber = msg.getLineNumber,
      originalSql = if msg.hasOriginalSql then Some(msg.getOriginalSql) else None
    )

  // ============================================================================
  // Schema types
  // ============================================================================

  private def toProtoSchema(s: ProtoSchema): pb.ProtoSchemaMsg =
    val b = pb.ProtoSchemaMsg.newBuilder().setFingerprint(s.fingerprint.toLong)
    s.fields.foreach(f => b.addFields(toProtoStructField(f)))
    b.build()

  private def fromProtoSchema(msg: pb.ProtoSchemaMsg): ProtoSchema =
    val fields =
      (0 until msg.getFieldsCount).map(i => fromProtoStructField(msg.getFields(i))).toVector
    ProtoSchema(fields, SchemaFingerprint.fromLong(msg.getFingerprint))

  private def toProtoSchemaContract(sc: SchemaContract): pb.SchemaContractMsg =
    val b = pb.SchemaContractMsg
      .newBuilder()
      .setRelationName(sc.relationName)
      .setFingerprint(sc.fingerprint.toLong)
    sc.requiredFields.foreach(f => b.addRequiredFields(toProtoFieldContract(f)))
    b.build()

  private def fromProtoSchemaContract(msg: pb.SchemaContractMsg): SchemaContract =
    val fields = (0 until msg.getRequiredFieldsCount)
      .map(i => fromProtoFieldContract(msg.getRequiredFields(i)))
      .toVector
    SchemaContract(msg.getRelationName, fields, SchemaFingerprint.fromLong(msg.getFingerprint))

  private def toProtoFieldContract(fc: FieldContract): pb.FieldContractMsg =
    pb.FieldContractMsg
      .newBuilder()
      .setName(fc.name)
      .setExpectedType(toProtoType(fc.expectedType))
      .setExpectedNullable(fc.expectedNullable)
      .setPosition(fc.position)
      .build()

  private def fromProtoFieldContract(msg: pb.FieldContractMsg): FieldContract =
    FieldContract(
      msg.getName,
      fromProtoType(msg.getExpectedType),
      msg.getExpectedNullable,
      msg.getPosition
    )

  // ============================================================================
  // ProtoType
  // ============================================================================

  def toProtoType(t: ProtoType): pb.ProtoTypeMsg =
    val b = pb.ProtoTypeMsg.newBuilder()
    val empty = pb.EmptyMsg.newBuilder().build()
    t match
      case ProtoType.BooleanType      => b.setBooleanType(empty)
      case ProtoType.ByteType         => b.setByteType(empty)
      case ProtoType.ShortType        => b.setShortType(empty)
      case ProtoType.IntegerType      => b.setIntegerType(empty)
      case ProtoType.LongType         => b.setLongType(empty)
      case ProtoType.FloatType        => b.setFloatType(empty)
      case ProtoType.DoubleType       => b.setDoubleType(empty)
      case ProtoType.StringType       => b.setStringType(empty)
      case ProtoType.BinaryType       => b.setBinaryType(empty)
      case ProtoType.DateType         => b.setDateType(empty)
      case ProtoType.TimestampType    => b.setTimestampType(empty)
      case ProtoType.TimestampNTZType => b.setTimestampNtzType(empty)
      case ProtoType.TimeType(p)      =>
        b.setTimeType(pb.TimeTypeMsg.newBuilder().setPrecision(p).build())
      case ProtoType.DayTimeIntervalType   => b.setDayTimeIntervalType(empty)
      case ProtoType.YearMonthIntervalType => b.setYearMonthIntervalType(empty)
      case ProtoType.CalendarIntervalType  => b.setCalendarIntervalType(empty)
      case ProtoType.CharType(len)         =>
        b.setCharType(pb.CharTypeMsg.newBuilder().setLength(len).build())
      case ProtoType.VarcharType(len) =>
        b.setVarcharType(pb.VarcharTypeMsg.newBuilder().setLength(len).build())
      case ProtoType.DecimalType(p, s) =>
        b.setDecimalType(pb.DecimalTypeMsg.newBuilder().setPrecision(p).setScale(s).build())
      case ProtoType.ArrayType(el, cn) =>
        b.setArrayType(
          pb.ArrayTypeMsg.newBuilder().setElementType(toProtoType(el)).setContainsNull(cn).build()
        )
      case ProtoType.MapType(k, v, vcn) =>
        b.setMapType(
          pb.MapTypeMsg
            .newBuilder()
            .setKeyType(toProtoType(k))
            .setValueType(toProtoType(v))
            .setValueContainsNull(vcn)
            .build()
        )
      case ProtoType.StructType(fields) =>
        val st = pb.StructTypeMsg.newBuilder()
        fields.foreach(f => st.addFields(toProtoStructField(f)))
        b.setStructType(st.build())
      case ProtoType.UDTType(cls, sql) =>
        b.setUdtType(
          pb.UDTTypeMsg.newBuilder().setUdtClassName(cls).setSqlType(toProtoType(sql)).build()
        )
      case ProtoType.VariantType             => b.setVariantType(empty)
      case ProtoType.NullType                => b.setNullType(empty)
      case ProtoType.SumType(disc, variants) =>
        val st = pb.SumTypeMsg.newBuilder().setDiscriminatorField(disc)
        variants.foreach(v =>
          val vb = pb.SumVariantMsg.newBuilder().setName(v.name).setOrdinal(v.ordinal)
          v.dataType.foreach(dt => vb.setDataType(toProtoType(dt)))
          st.addVariants(vb.build())
        )
        b.setSumType(st.build())
      case ProtoType.UnresolvedType(hint) =>
        b.setUnresolvedType(pb.UnresolvedTypeMsg.newBuilder().setHint(hint).build())
    b.build()

  def fromProtoType(msg: pb.ProtoTypeMsg): ProtoType =
    import pb.ProtoTypeMsg.TypeCase._
    msg.getTypeCase match
      case BOOLEAN_TYPE             => ProtoType.BooleanType
      case BYTE_TYPE                => ProtoType.ByteType
      case SHORT_TYPE               => ProtoType.ShortType
      case INTEGER_TYPE             => ProtoType.IntegerType
      case LONG_TYPE                => ProtoType.LongType
      case FLOAT_TYPE               => ProtoType.FloatType
      case DOUBLE_TYPE              => ProtoType.DoubleType
      case STRING_TYPE              => ProtoType.StringType
      case BINARY_TYPE              => ProtoType.BinaryType
      case DATE_TYPE                => ProtoType.DateType
      case TIMESTAMP_TYPE           => ProtoType.TimestampType
      case TIMESTAMP_NTZ_TYPE       => ProtoType.TimestampNTZType
      case TIME_TYPE                => ProtoType.TimeType(msg.getTimeType.getPrecision)
      case DAY_TIME_INTERVAL_TYPE   => ProtoType.DayTimeIntervalType
      case YEAR_MONTH_INTERVAL_TYPE => ProtoType.YearMonthIntervalType
      case CALENDAR_INTERVAL_TYPE   => ProtoType.CalendarIntervalType
      case CHAR_TYPE                => ProtoType.CharType(msg.getCharType.getLength)
      case VARCHAR_TYPE             => ProtoType.VarcharType(msg.getVarcharType.getLength)
      case DECIMAL_TYPE             =>
        ProtoType.DecimalType(msg.getDecimalType.getPrecision, msg.getDecimalType.getScale)
      case ARRAY_TYPE =>
        val at = msg.getArrayType
        ProtoType.ArrayType(fromProtoType(at.getElementType), at.getContainsNull)
      case MAP_TYPE =>
        val mt = msg.getMapType
        ProtoType.MapType(
          fromProtoType(mt.getKeyType),
          fromProtoType(mt.getValueType),
          mt.getValueContainsNull
        )
      case STRUCT_TYPE =>
        val st = msg.getStructType
        val fields =
          (0 until st.getFieldsCount).map(i => fromProtoStructField(st.getFields(i))).toVector
        ProtoType.StructType(fields)
      case UDT_TYPE =>
        val udt = msg.getUdtType
        ProtoType.UDTType(udt.getUdtClassName, fromProtoType(udt.getSqlType))
      case VARIANT_TYPE => ProtoType.VariantType
      case NULL_TYPE    => ProtoType.NullType
      case SUM_TYPE     =>
        val st = msg.getSumType
        val variants = (0 until st.getVariantsCount).map { i =>
          val v = st.getVariants(i)
          SumVariant(
            v.getName,
            v.getOrdinal,
            if v.hasDataType then Some(fromProtoType(v.getDataType)) else None
          )
        }.toVector
        ProtoType.SumType(st.getDiscriminatorField, variants)
      case UNRESOLVED_TYPE => ProtoType.UnresolvedType(msg.getUnresolvedType.getHint)
      case TYPE_NOT_SET    => throw new IllegalArgumentException("ProtoTypeMsg type not set")

  private def toProtoStructField(f: ProtoStructField): pb.ProtoStructFieldMsg =
    val b = pb.ProtoStructFieldMsg
      .newBuilder()
      .setName(f.name)
      .setDataType(toProtoType(f.dataType))
      .setNullable(f.nullable)
    f.metadata.foreach((k, v) => b.putMetadata(k, v))
    b.build()

  private def fromProtoStructField(msg: pb.ProtoStructFieldMsg): ProtoStructField =
    import scala.jdk.CollectionConverters._
    ProtoStructField(
      msg.getName,
      fromProtoType(msg.getDataType),
      msg.getNullable,
      msg.getMetadataMap.asScala.toMap
    )

  // ============================================================================
  // LiteralValue
  // ============================================================================

  private def toProtoLiteral(lit: LiteralValue): pb.LiteralValueMsg =
    val b = pb.LiteralValueMsg.newBuilder()
    lit match
      case LiteralValue.BooleanValue(v)                 => b.setBooleanValue(v)
      case LiteralValue.ByteValue(v)                    => b.setByteValue(v.toInt)
      case LiteralValue.ShortValue(v)                   => b.setShortValue(v.toInt)
      case LiteralValue.IntValue(v)                     => b.setIntValue(v)
      case LiteralValue.LongValue(v)                    => b.setLongValue(v)
      case LiteralValue.FloatValue(v)                   => b.setFloatValue(v)
      case LiteralValue.DoubleValue(v)                  => b.setDoubleValue(v)
      case LiteralValue.StringValue(v)                  => b.setStringValue(v)
      case LiteralValue.BinaryValue(v)                  => b.setBinaryValue(ByteString.copyFrom(v))
      case LiteralValue.DecimalValue(v)                 => b.setDecimalValue(v.toString)
      case LiteralValue.DateValue(v)                    => b.setDateValue(v)
      case LiteralValue.TimestampValue(v)               => b.setTimestampValue(v)
      case LiteralValue.TimeValue(v)                    => b.setTimeValue(v)
      case LiteralValue.CalendarIntervalValue(m, d, us) =>
        b.setCalendarIntervalValue(
          pb.CalendarIntervalValueMsg
            .newBuilder()
            .setMonths(m)
            .setDays(d)
            .setMicroseconds(us)
            .build()
        )
      case LiteralValue.NullValue(dt) => b.setNullValue(toProtoType(dt))
    b.build()

  private def fromProtoLiteral(msg: pb.LiteralValueMsg): LiteralValue =
    import pb.LiteralValueMsg.ValueCase._
    msg.getValueCase match
      case BOOLEAN_VALUE           => LiteralValue.BooleanValue(msg.getBooleanValue)
      case BYTE_VALUE              => LiteralValue.ByteValue(msg.getByteValue.toByte)
      case SHORT_VALUE             => LiteralValue.ShortValue(msg.getShortValue.toShort)
      case INT_VALUE               => LiteralValue.IntValue(msg.getIntValue)
      case LONG_VALUE              => LiteralValue.LongValue(msg.getLongValue)
      case FLOAT_VALUE             => LiteralValue.FloatValue(msg.getFloatValue)
      case DOUBLE_VALUE            => LiteralValue.DoubleValue(msg.getDoubleValue)
      case STRING_VALUE            => LiteralValue.StringValue(msg.getStringValue)
      case BINARY_VALUE            => LiteralValue.BinaryValue(msg.getBinaryValue.toByteArray)
      case DECIMAL_VALUE           => LiteralValue.DecimalValue(BigDecimal(msg.getDecimalValue))
      case DATE_VALUE              => LiteralValue.DateValue(msg.getDateValue)
      case TIMESTAMP_VALUE         => LiteralValue.TimestampValue(msg.getTimestampValue)
      case TIME_VALUE              => LiteralValue.TimeValue(msg.getTimeValue)
      case CALENDAR_INTERVAL_VALUE =>
        val civ = msg.getCalendarIntervalValue
        LiteralValue.CalendarIntervalValue(civ.getMonths, civ.getDays, civ.getMicroseconds)
      case NULL_VALUE    => LiteralValue.NullValue(fromProtoType(msg.getNullValue))
      case VALUE_NOT_SET => throw new IllegalArgumentException("LiteralValueMsg value not set")

  // ============================================================================
  // ProtoExpr
  // ============================================================================

  def toProtoExpr(e: ProtoExpr): pb.ProtoExprMsg =
    val b = pb.ProtoExprMsg.newBuilder()
    val empty = pb.EmptyMsg.newBuilder().build()
    e match
      // Leaf nodes
      case ProtoExpr.Literal(v) =>
        b.setLiteral(pb.LiteralExprMsg.newBuilder().setValue(toProtoLiteral(v)).build())
      case ProtoExpr.ColumnRef(name, qual, rt, nullable) =>
        val cb = pb.ColumnRefExprMsg
          .newBuilder()
          .setName(name)
          .setResolvedType(toProtoType(rt))
          .setNullable(nullable)
        qual.foreach(cb.setQualifier)
        b.setColumnRef(cb.build())
      case ProtoExpr.BoundRef(idx, dt, nullable) =>
        b.setBoundRef(
          pb.BoundRefExprMsg
            .newBuilder()
            .setIndex(idx)
            .setDataType(toProtoType(dt))
            .setNullable(nullable)
            .build()
        )

      // Comparison
      case ProtoExpr.Eq(l, r)    => b.setEq(binaryMsg(l, r))
      case ProtoExpr.NotEq(l, r) => b.setNotEq(binaryMsg(l, r))
      case ProtoExpr.Lt(l, r)    => b.setLt(binaryMsg(l, r))
      case ProtoExpr.LtEq(l, r)  => b.setLtEq(binaryMsg(l, r))
      case ProtoExpr.Gt(l, r)    => b.setGt(binaryMsg(l, r))
      case ProtoExpr.GtEq(l, r)  => b.setGtEq(binaryMsg(l, r))

      // Logical
      case ProtoExpr.And(children) => b.setAnd(naryMsg(children))
      case ProtoExpr.Or(children)  => b.setOr(naryMsg(children))
      case ProtoExpr.Not(child)    => b.setNot(unaryMsg(child))

      // Null handling
      case ProtoExpr.IsNull(child)      => b.setIsNull(unaryMsg(child))
      case ProtoExpr.IsNotNull(child)   => b.setIsNotNull(unaryMsg(child))
      case ProtoExpr.Coalesce(children) => b.setCoalesce(naryMsg(children))
      case ProtoExpr.NullIf(l, r)       => b.setNullIf(binaryMsg(l, r))

      // Arithmetic
      case ProtoExpr.Add(l, r)      => b.setAdd(binaryMsg(l, r))
      case ProtoExpr.Subtract(l, r) => b.setSubtract(binaryMsg(l, r))
      case ProtoExpr.Multiply(l, r) => b.setMultiply(binaryMsg(l, r))
      case ProtoExpr.Divide(l, r)   => b.setDivide(binaryMsg(l, r))

      // Math functions
      case ProtoExpr.Abs(child)       => b.setAbs(unaryMsg(child))
      case ProtoExpr.Ceil(child)      => b.setCeil(unaryMsg(child))
      case ProtoExpr.Floor(child)     => b.setFloor(unaryMsg(child))
      case ProtoExpr.Round(c, s)      => b.setRound(binaryMsg(c, s))
      case ProtoExpr.Truncate(c, s)   => b.setTruncate(binaryMsg(c, s))
      case ProtoExpr.Sqrt(child)      => b.setSqrt(unaryMsg(child))
      case ProtoExpr.Cbrt(child)      => b.setCbrt(unaryMsg(child))
      case ProtoExpr.Pow(l, r)        => b.setPow(binaryMsg(l, r))
      case ProtoExpr.Pmod(l, r)       => b.setPmod(binaryMsg(l, r))
      case ProtoExpr.Sign(child)      => b.setSign(unaryMsg(child))
      case ProtoExpr.Log(child, base) =>
        val lb = pb.LogExprMsg.newBuilder().setChild(toProtoExpr(child))
        base.foreach(be => lb.setBase(toProtoExpr(be)))
        b.setLog(lb.build())
      case ProtoExpr.Exp(child) => b.setExp(unaryMsg(child))

      // String
      case ProtoExpr.Concat(children)         => b.setConcat(naryMsg(children))
      case ProtoExpr.Substring(str, pos, len) =>
        b.setSubstring(
          pb.SubstringExprMsg
            .newBuilder()
            .setStr(toProtoExpr(str))
            .setPos(toProtoExpr(pos))
            .setLen(toProtoExpr(len))
            .build()
        )
      case ProtoExpr.Upper(child)                   => b.setUpper(unaryMsg(child))
      case ProtoExpr.Lower(child)                   => b.setLower(unaryMsg(child))
      case ProtoExpr.Trim(child, trimStr, trimType) =>
        val tb = pb.TrimExprMsg
          .newBuilder()
          .setChild(toProtoExpr(child))
          .setTrimType(toProtoTrimType(trimType))
        trimStr.foreach(ts => tb.setTrimStr(toProtoExpr(ts)))
        b.setTrim(tb.build())
      case ProtoExpr.Length(child)                 => b.setLength(unaryMsg(child))
      case ProtoExpr.Replace(str, search, replace) =>
        b.setReplace(ternaryMsg(str, search, replace))
      case ProtoExpr.StringLocate(substr, str, start) =>
        val lb = pb.StringLocateExprMsg
          .newBuilder()
          .setSubstr(toProtoExpr(substr))
          .setStr(toProtoExpr(str))
        start.foreach(s => lb.setStart(toProtoExpr(s)))
        b.setStringLocate(lb.build())
      case ProtoExpr.Lpad(str, len, pad)            => b.setLpad(ternaryMsg(str, len, pad))
      case ProtoExpr.Rpad(str, len, pad)            => b.setRpad(ternaryMsg(str, len, pad))
      case ProtoExpr.StringSplit(str, delim, limit) =>
        val sb2 = pb.StringSplitExprMsg
          .newBuilder()
          .setStr(toProtoExpr(str))
          .setDelimiter(toProtoExpr(delim))
        limit.foreach(l => sb2.setLimit(toProtoExpr(l)))
        b.setStringSplit(sb2.build())
      case ProtoExpr.Reverse(child)           => b.setReverse(unaryMsg(child))
      case ProtoExpr.StringRepeat(str, times) => b.setStringRepeat(binaryMsg(str, times))

      // Aggregates
      case ProtoExpr.Count(child, dist) =>
        b.setCount(
          pb.CountExprMsg.newBuilder().setChild(toProtoExpr(child)).setDistinct(dist).build()
        )
      case ProtoExpr.Sum(child) => b.setSum(unaryMsg(child))
      case ProtoExpr.Avg(child) => b.setAvg(unaryMsg(child))
      case ProtoExpr.Min(child) => b.setMinExpr(unaryMsg(child))
      case ProtoExpr.Max(child) => b.setMaxExpr(unaryMsg(child))

      // Control flow
      case ProtoExpr.CaseWhen(branches, elseVal) =>
        val cb2 = pb.CaseWhenExprMsg.newBuilder()
        branches.foreach { (cond, value) =>
          cb2.addBranches(
            pb.CaseWhenBranchMsg
              .newBuilder()
              .setCondition(toProtoExpr(cond))
              .setValue(toProtoExpr(value))
              .build()
          )
        }
        elseVal.foreach(ev => cb2.setElseValue(toProtoExpr(ev)))
        b.setCaseWhen(cb2.build())
      case ProtoExpr.If(pred, t, f) =>
        b.setIfExpr(
          pb.IfExprMsg
            .newBuilder()
            .setPredicate(toProtoExpr(pred))
            .setTrueValue(toProtoExpr(t))
            .setFalseValue(toProtoExpr(f))
            .build()
        )
      case ProtoExpr.In(value, list) =>
        val ib = pb.InExprMsg.newBuilder().setValue(toProtoExpr(value))
        list.foreach(l => ib.addList(toProtoExpr(l)))
        b.setInExpr(ib.build())

      // Pattern matching
      case ProtoExpr.Like(value, pattern, escape) =>
        val lb =
          pb.LikeExprMsg.newBuilder().setValue(toProtoExpr(value)).setPattern(toProtoExpr(pattern))
        escape.foreach(esc => lb.setEscape(toProtoExpr(esc)))
        b.setLike(lb.build())

      // Cast and alias
      case ProtoExpr.Cast(child, targetType) =>
        b.setCast(
          pb.CastExprMsg
            .newBuilder()
            .setChild(toProtoExpr(child))
            .setTargetType(toProtoType(targetType))
            .build()
        )
      case ProtoExpr.Alias(child, name) =>
        b.setAlias(pb.AliasExprMsg.newBuilder().setChild(toProtoExpr(child)).setName(name).build())

      // Subquery expressions
      case ProtoExpr.ScalarSubquery(plan) =>
        b.setScalarSubquery(
          pb.ScalarSubqueryExprMsg.newBuilder().setPlan(toProtoPlan(plan)).build()
        )
      case ProtoExpr.Exists(plan) =>
        b.setExists(pb.ExistsExprMsg.newBuilder().setPlan(toProtoPlan(plan)).build())
      case ProtoExpr.InSubquery(value, plan) =>
        b.setInSubquery(
          pb.InSubqueryExprMsg
            .newBuilder()
            .setValue(toProtoExpr(value))
            .setPlan(toProtoPlan(plan))
            .build()
        )

      // Window functions
      case ProtoExpr.RowNumber()                  => b.setRowNumber(empty)
      case ProtoExpr.Rank()                       => b.setRank(empty)
      case ProtoExpr.DenseRank()                  => b.setDenseRank(empty)
      case ProtoExpr.Ntile(n)                     => b.setNtile(unaryMsg(n))
      case ProtoExpr.Lead(input, offset, default) =>
        val lb =
          pb.LeadLagExprMsg.newBuilder().setInput(toProtoExpr(input)).setOffset(toProtoExpr(offset))
        default.foreach(d => lb.setDefaultValue(toProtoExpr(d)))
        b.setLead(lb.build())
      case ProtoExpr.Lag(input, offset, default) =>
        val lb =
          pb.LeadLagExprMsg.newBuilder().setInput(toProtoExpr(input)).setOffset(toProtoExpr(offset))
        default.foreach(d => lb.setDefaultValue(toProtoExpr(d)))
        b.setLag(lb.build())
      case ProtoExpr.FirstValue(input, ignoreNulls) =>
        b.setFirstValue(
          pb.FirstLastValueExprMsg
            .newBuilder()
            .setInput(toProtoExpr(input))
            .setIgnoreNulls(ignoreNulls)
            .build()
        )
      case ProtoExpr.LastValue(input, ignoreNulls) =>
        b.setLastValue(
          pb.FirstLastValueExprMsg
            .newBuilder()
            .setInput(toProtoExpr(input))
            .setIgnoreNulls(ignoreNulls)
            .build()
        )
      case ProtoExpr.NthValue(input, n) => b.setNthValue(binaryMsg(input, n))

      // Window specification wrapper
      case ProtoExpr.WindowExpr(function, partSpec, orderSpec, frameSpec) =>
        val wb = pb.WindowExprMsg.newBuilder().setFunction(toProtoExpr(function))
        partSpec.foreach(p => wb.addPartitionSpec(toProtoExpr(p)))
        orderSpec.foreach(o => wb.addOrderSpec(toProtoSortOrder(o)))
        frameSpec.foreach(f => wb.setFrameSpec(toProtoWindowFrame(f)))
        b.setWindowExpr(wb.build())

      // Date/Time functions
      case ProtoExpr.CurrentDate()          => b.setCurrentDate(empty)
      case ProtoExpr.CurrentTimestamp()     => b.setCurrentTimestamp(empty)
      case ProtoExpr.DateAdd(s, d)          => b.setDateAdd(binaryMsg(s, d))
      case ProtoExpr.DateSub(s, d)          => b.setDateSub(binaryMsg(s, d))
      case ProtoExpr.DateDiff(e, s)         => b.setDateDiff(binaryMsg(e, s))
      case ProtoExpr.Extract(field, source) =>
        b.setExtract(
          pb.ExtractExprMsg
            .newBuilder()
            .setField(toProtoDateTimeField(field))
            .setSource(toProtoExpr(source))
            .build()
        )
      case ProtoExpr.DateTrunc(field, ts) =>
        b.setDateTrunc(
          pb.DateTruncExprMsg
            .newBuilder()
            .setField(toProtoDateTimeField(field))
            .setTimestamp(toProtoExpr(ts))
            .build()
        )
      case ProtoExpr.ToDate(str, fmt) =>
        val tb2 = pb.OptionalFormatExprMsg.newBuilder().setStr(toProtoExpr(str))
        fmt.foreach(f => tb2.setFormat(toProtoExpr(f)))
        b.setToDate(tb2.build())
      case ProtoExpr.ToTimestamp(str, fmt) =>
        val tb2 = pb.OptionalFormatExprMsg.newBuilder().setStr(toProtoExpr(str))
        fmt.foreach(f => tb2.setFormat(toProtoExpr(f)))
        b.setToTimestamp(tb2.build())
      case ProtoExpr.Year(child)       => b.setYear(unaryMsg(child))
      case ProtoExpr.Month(child)      => b.setMonth(unaryMsg(child))
      case ProtoExpr.DayOfMonth(child) => b.setDayOfMonth(unaryMsg(child))
      case ProtoExpr.Hour(child)       => b.setHour(unaryMsg(child))
      case ProtoExpr.Minute(child)     => b.setMinute(unaryMsg(child))
      case ProtoExpr.Second(child)     => b.setSecond(unaryMsg(child))

      // Grouping
      case ProtoExpr.Grouping(cols) => b.setGrouping(naryMsg(cols))

      // Generator functions
      case ProtoExpr.Explode(child)           => b.setExplode(unaryMsg(child))
      case ProtoExpr.PosExplode(child)        => b.setPosExplode(unaryMsg(child))
      case ProtoExpr.Inline(child)            => b.setInlineExpr(unaryMsg(child))
      case ProtoExpr.Stack(numRows, children) =>
        val sb2 = pb.StackExprMsg.newBuilder().setNumRows(toProtoExpr(numRows))
        children.foreach(c => sb2.addChildren(toProtoExpr(c)))
        b.setStack(sb2.build())

      // Opaque function call
      case ProtoExpr.OpaqueCall(name, args, retType, det) =>
        val ob = pb.OpaqueCallExprMsg
          .newBuilder()
          .setFunctionName(name)
          .setDeterministic(det)
        args.foreach(a => ob.addArguments(toProtoExpr(a)))
        retType.foreach(rt => ob.setReturnType(toProtoType(rt)))
        b.setOpaqueCall(ob.build())
    b.build()

  def fromProtoExpr(msg: pb.ProtoExprMsg): ProtoExpr =
    import pb.ProtoExprMsg.ExprCase._
    msg.getExprCase match
      // Leaf nodes
      case LITERAL    => ProtoExpr.Literal(fromProtoLiteral(msg.getLiteral.getValue))
      case COLUMN_REF =>
        val cr = msg.getColumnRef
        ProtoExpr.ColumnRef(
          cr.getName,
          if cr.hasQualifier then Some(cr.getQualifier) else None,
          fromProtoType(cr.getResolvedType),
          cr.getNullable
        )
      case BOUND_REF =>
        val br = msg.getBoundRef
        ProtoExpr.BoundRef(br.getIndex, fromProtoType(br.getDataType), br.getNullable)

      // Comparison
      case EQ     => fromBinary(msg.getEq, ProtoExpr.Eq.apply)
      case NOT_EQ => fromBinary(msg.getNotEq, ProtoExpr.NotEq.apply)
      case LT     => fromBinary(msg.getLt, ProtoExpr.Lt.apply)
      case LT_EQ  => fromBinary(msg.getLtEq, ProtoExpr.LtEq.apply)
      case GT     => fromBinary(msg.getGt, ProtoExpr.Gt.apply)
      case GT_EQ  => fromBinary(msg.getGtEq, ProtoExpr.GtEq.apply)

      // Logical
      case AND => ProtoExpr.And(fromNary(msg.getAnd))
      case OR  => ProtoExpr.Or(fromNary(msg.getOr))
      case NOT => ProtoExpr.Not(fromProtoExpr(msg.getNot.getChild))

      // Null handling
      case IS_NULL     => ProtoExpr.IsNull(fromProtoExpr(msg.getIsNull.getChild))
      case IS_NOT_NULL => ProtoExpr.IsNotNull(fromProtoExpr(msg.getIsNotNull.getChild))
      case COALESCE    => ProtoExpr.Coalesce(fromNary(msg.getCoalesce))
      case NULL_IF     => fromBinary(msg.getNullIf, ProtoExpr.NullIf.apply)

      // Arithmetic
      case ADD      => fromBinary(msg.getAdd, ProtoExpr.Add.apply)
      case SUBTRACT => fromBinary(msg.getSubtract, ProtoExpr.Subtract.apply)
      case MULTIPLY => fromBinary(msg.getMultiply, ProtoExpr.Multiply.apply)
      case DIVIDE   => fromBinary(msg.getDivide, ProtoExpr.Divide.apply)

      // Math
      case ABS      => ProtoExpr.Abs(fromProtoExpr(msg.getAbs.getChild))
      case CEIL     => ProtoExpr.Ceil(fromProtoExpr(msg.getCeil.getChild))
      case FLOOR    => ProtoExpr.Floor(fromProtoExpr(msg.getFloor.getChild))
      case ROUND    => fromBinary(msg.getRound, ProtoExpr.Round.apply)
      case TRUNCATE => fromBinary(msg.getTruncate, ProtoExpr.Truncate.apply)
      case SQRT     => ProtoExpr.Sqrt(fromProtoExpr(msg.getSqrt.getChild))
      case CBRT     => ProtoExpr.Cbrt(fromProtoExpr(msg.getCbrt.getChild))
      case POW      => fromBinary(msg.getPow, ProtoExpr.Pow.apply)
      case PMOD     => fromBinary(msg.getPmod, ProtoExpr.Pmod.apply)
      case SIGN     => ProtoExpr.Sign(fromProtoExpr(msg.getSign.getChild))
      case LOG      =>
        val lg = msg.getLog
        ProtoExpr.Log(
          fromProtoExpr(lg.getChild),
          if lg.hasBase then Some(fromProtoExpr(lg.getBase)) else None
        )
      case EXP => ProtoExpr.Exp(fromProtoExpr(msg.getExp.getChild))

      // String
      case CONCAT    => ProtoExpr.Concat(fromNary(msg.getConcat))
      case SUBSTRING =>
        val ss = msg.getSubstring
        ProtoExpr.Substring(
          fromProtoExpr(ss.getStr),
          fromProtoExpr(ss.getPos),
          fromProtoExpr(ss.getLen)
        )
      case UPPER => ProtoExpr.Upper(fromProtoExpr(msg.getUpper.getChild))
      case LOWER => ProtoExpr.Lower(fromProtoExpr(msg.getLower.getChild))
      case TRIM  =>
        val tr = msg.getTrim
        ProtoExpr.Trim(
          fromProtoExpr(tr.getChild),
          if tr.hasTrimStr then Some(fromProtoExpr(tr.getTrimStr)) else None,
          fromProtoTrimType(tr.getTrimType)
        )
      case LENGTH        => ProtoExpr.Length(fromProtoExpr(msg.getLength.getChild))
      case REPLACE       => fromTernary(msg.getReplace, ProtoExpr.Replace.apply)
      case STRING_LOCATE =>
        val sl = msg.getStringLocate
        ProtoExpr.StringLocate(
          fromProtoExpr(sl.getSubstr),
          fromProtoExpr(sl.getStr),
          if sl.hasStart then Some(fromProtoExpr(sl.getStart)) else None
        )
      case LPAD         => fromTernary(msg.getLpad, ProtoExpr.Lpad.apply)
      case RPAD         => fromTernary(msg.getRpad, ProtoExpr.Rpad.apply)
      case STRING_SPLIT =>
        val sp = msg.getStringSplit
        ProtoExpr.StringSplit(
          fromProtoExpr(sp.getStr),
          fromProtoExpr(sp.getDelimiter),
          if sp.hasLimit then Some(fromProtoExpr(sp.getLimit)) else None
        )
      case REVERSE       => ProtoExpr.Reverse(fromProtoExpr(msg.getReverse.getChild))
      case STRING_REPEAT => fromBinary(msg.getStringRepeat, ProtoExpr.StringRepeat.apply)

      // Aggregates
      case COUNT => ProtoExpr.Count(fromProtoExpr(msg.getCount.getChild), msg.getCount.getDistinct)
      case SUM   => ProtoExpr.Sum(fromProtoExpr(msg.getSum.getChild))
      case AVG   => ProtoExpr.Avg(fromProtoExpr(msg.getAvg.getChild))
      case MIN_EXPR => ProtoExpr.Min(fromProtoExpr(msg.getMinExpr.getChild))
      case MAX_EXPR => ProtoExpr.Max(fromProtoExpr(msg.getMaxExpr.getChild))

      // Control flow
      case CASE_WHEN =>
        val cw = msg.getCaseWhen
        val branches = (0 until cw.getBranchesCount).map { i =>
          val br = cw.getBranches(i)
          (fromProtoExpr(br.getCondition), fromProtoExpr(br.getValue))
        }.toVector
        ProtoExpr.CaseWhen(
          branches,
          if cw.hasElseValue then Some(fromProtoExpr(cw.getElseValue)) else None
        )
      case IF_EXPR =>
        val ie = msg.getIfExpr
        ProtoExpr.If(
          fromProtoExpr(ie.getPredicate),
          fromProtoExpr(ie.getTrueValue),
          fromProtoExpr(ie.getFalseValue)
        )
      case IN_EXPR =>
        val ine = msg.getInExpr
        val list = (0 until ine.getListCount).map(i => fromProtoExpr(ine.getList(i))).toVector
        ProtoExpr.In(fromProtoExpr(ine.getValue), list)

      // Pattern matching
      case LIKE =>
        val lk = msg.getLike
        ProtoExpr.Like(
          fromProtoExpr(lk.getValue),
          fromProtoExpr(lk.getPattern),
          if lk.hasEscape then Some(fromProtoExpr(lk.getEscape)) else None
        )

      // Cast and alias
      case CAST =>
        ProtoExpr.Cast(
          fromProtoExpr(msg.getCast.getChild),
          fromProtoType(msg.getCast.getTargetType)
        )
      case ALIAS => ProtoExpr.Alias(fromProtoExpr(msg.getAlias.getChild), msg.getAlias.getName)

      // Subquery expressions
      case SCALAR_SUBQUERY => ProtoExpr.ScalarSubquery(fromProtoPlan(msg.getScalarSubquery.getPlan))
      case EXISTS          => ProtoExpr.Exists(fromProtoPlan(msg.getExists.getPlan))
      case IN_SUBQUERY     =>
        val isq = msg.getInSubquery
        ProtoExpr.InSubquery(fromProtoExpr(isq.getValue), fromProtoPlan(isq.getPlan))

      // Window functions
      case ROW_NUMBER => ProtoExpr.RowNumber()
      case RANK       => ProtoExpr.Rank()
      case DENSE_RANK => ProtoExpr.DenseRank()
      case NTILE      => ProtoExpr.Ntile(fromProtoExpr(msg.getNtile.getChild))
      case LEAD       =>
        val ld = msg.getLead
        ProtoExpr.Lead(
          fromProtoExpr(ld.getInput),
          fromProtoExpr(ld.getOffset),
          if ld.hasDefaultValue then Some(fromProtoExpr(ld.getDefaultValue)) else None
        )
      case LAG =>
        val lg2 = msg.getLag
        ProtoExpr.Lag(
          fromProtoExpr(lg2.getInput),
          fromProtoExpr(lg2.getOffset),
          if lg2.hasDefaultValue then Some(fromProtoExpr(lg2.getDefaultValue)) else None
        )
      case FIRST_VALUE =>
        val fv = msg.getFirstValue
        ProtoExpr.FirstValue(fromProtoExpr(fv.getInput), fv.getIgnoreNulls)
      case LAST_VALUE =>
        val lv = msg.getLastValue
        ProtoExpr.LastValue(fromProtoExpr(lv.getInput), lv.getIgnoreNulls)
      case NTH_VALUE => fromBinary(msg.getNthValue, ProtoExpr.NthValue.apply)

      // Window specification wrapper
      case WINDOW_EXPR =>
        val we = msg.getWindowExpr
        val partSpec = (0 until we.getPartitionSpecCount)
          .map(i => fromProtoExpr(we.getPartitionSpec(i)))
          .toVector
        val orderSpec =
          (0 until we.getOrderSpecCount).map(i => fromProtoSortOrder(we.getOrderSpec(i))).toVector
        ProtoExpr.WindowExpr(
          fromProtoExpr(we.getFunction),
          partSpec,
          orderSpec,
          if we.hasFrameSpec then Some(fromProtoWindowFrame(we.getFrameSpec)) else None
        )

      // Date/Time
      case CURRENT_DATE      => ProtoExpr.CurrentDate()
      case CURRENT_TIMESTAMP => ProtoExpr.CurrentTimestamp()
      case DATE_ADD          => fromBinary(msg.getDateAdd, ProtoExpr.DateAdd.apply)
      case DATE_SUB          => fromBinary(msg.getDateSub, ProtoExpr.DateSub.apply)
      case DATE_DIFF         => fromBinary(msg.getDateDiff, ProtoExpr.DateDiff.apply)
      case EXTRACT           =>
        val ext = msg.getExtract
        ProtoExpr.Extract(fromProtoDateTimeField(ext.getField), fromProtoExpr(ext.getSource))
      case DATE_TRUNC =>
        val dt = msg.getDateTrunc
        ProtoExpr.DateTrunc(fromProtoDateTimeField(dt.getField), fromProtoExpr(dt.getTimestamp))
      case TO_DATE =>
        val td = msg.getToDate
        ProtoExpr.ToDate(
          fromProtoExpr(td.getStr),
          if td.hasFormat then Some(fromProtoExpr(td.getFormat)) else None
        )
      case TO_TIMESTAMP =>
        val tt = msg.getToTimestamp
        ProtoExpr.ToTimestamp(
          fromProtoExpr(tt.getStr),
          if tt.hasFormat then Some(fromProtoExpr(tt.getFormat)) else None
        )
      case YEAR         => ProtoExpr.Year(fromProtoExpr(msg.getYear.getChild))
      case MONTH        => ProtoExpr.Month(fromProtoExpr(msg.getMonth.getChild))
      case DAY_OF_MONTH => ProtoExpr.DayOfMonth(fromProtoExpr(msg.getDayOfMonth.getChild))
      case HOUR         => ProtoExpr.Hour(fromProtoExpr(msg.getHour.getChild))
      case MINUTE       => ProtoExpr.Minute(fromProtoExpr(msg.getMinute.getChild))
      case SECOND       => ProtoExpr.Second(fromProtoExpr(msg.getSecond.getChild))

      // Grouping
      case GROUPING => ProtoExpr.Grouping(fromNary(msg.getGrouping))

      // Generator functions
      case EXPLODE     => ProtoExpr.Explode(fromProtoExpr(msg.getExplode.getChild))
      case POS_EXPLODE => ProtoExpr.PosExplode(fromProtoExpr(msg.getPosExplode.getChild))
      case INLINE_EXPR => ProtoExpr.Inline(fromProtoExpr(msg.getInlineExpr.getChild))
      case STACK       =>
        val st = msg.getStack
        val children =
          (0 until st.getChildrenCount).map(i => fromProtoExpr(st.getChildren(i))).toVector
        ProtoExpr.Stack(fromProtoExpr(st.getNumRows), children)

      // Opaque call
      case OPAQUE_CALL =>
        val oc = msg.getOpaqueCall
        val args =
          (0 until oc.getArgumentsCount).map(i => fromProtoExpr(oc.getArguments(i))).toVector
        ProtoExpr.OpaqueCall(
          oc.getFunctionName,
          args,
          if oc.hasReturnType then Some(fromProtoType(oc.getReturnType)) else None,
          oc.getDeterministic
        )

      case EXPR_NOT_SET => throw new IllegalArgumentException("ProtoExprMsg expr not set")

  // ============================================================================
  // ProtoLogicalPlan
  // ============================================================================

  def toProtoPlan(p: ProtoLogicalPlan): pb.ProtoLogicalPlanMsg =
    val b = pb.ProtoLogicalPlanMsg.newBuilder()
    p match
      case ProtoLogicalPlan.RelationRef(name, alias, contract) =>
        val rb = pb.RelationRefMsg
          .newBuilder()
          .setName(name)
          .setSchemaContract(toProtoSchemaContract(contract))
        alias.foreach(rb.setAlias)
        b.setRelationRef(rb.build())

      case ProtoLogicalPlan.Values(rows, schema) =>
        val vb = pb.ValuesMsg.newBuilder().setSchema(toProtoSchema(schema))
        rows.foreach { row =>
          val rowb = pb.ValuesRowMsg.newBuilder()
          row.foreach(e => rowb.addValues(toProtoExpr(e)))
          vb.addRows(rowb.build())
        }
        b.setValues(vb.build())

      case ProtoLogicalPlan.Project(projList, child) =>
        val pb2 = pb.ProjectMsg.newBuilder().setChild(toProtoPlan(child))
        projList.foreach(e => pb2.addProjectList(toProtoExpr(e)))
        b.setProject(pb2.build())

      case ProtoLogicalPlan.Filter(condition, child) =>
        b.setFilter(
          pb.FilterMsg
            .newBuilder()
            .setCondition(toProtoExpr(condition))
            .setChild(toProtoPlan(child))
            .build()
        )

      case ProtoLogicalPlan.Aggregate(grouping, aggs, child) =>
        val ab = pb.AggregateMsg.newBuilder().setChild(toProtoPlan(child))
        grouping.foreach(e => ab.addGroupingExprs(toProtoExpr(e)))
        aggs.foreach(e => ab.addAggregateExprs(toProtoExpr(e)))
        b.setAggregate(ab.build())

      case ProtoLogicalPlan.Sort(order, child) =>
        val sb2 = pb.SortMsg.newBuilder().setChild(toProtoPlan(child))
        order.foreach(o => sb2.addOrder(toProtoSortOrder(o)))
        b.setSort(sb2.build())

      case ProtoLogicalPlan.Limit(limit, child) =>
        b.setLimit(pb.LimitMsg.newBuilder().setLimit(limit).setChild(toProtoPlan(child)).build())

      case ProtoLogicalPlan.Distinct(child) =>
        b.setDistinct(pb.DistinctMsg.newBuilder().setChild(toProtoPlan(child)).build())

      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        b.setSubqueryAlias(
          pb.SubqueryAliasMsg.newBuilder().setAlias(alias).setChild(toProtoPlan(child)).build()
        )

      case ProtoLogicalPlan.Join(left, right, joinType, condition) =>
        val jb = pb.JoinMsg
          .newBuilder()
          .setLeft(toProtoPlan(left))
          .setRight(toProtoPlan(right))
          .setJoinType(toProtoJoinType(joinType))
        condition.foreach(c => jb.setCondition(toProtoExpr(c)))
        b.setJoin(jb.build())

      case ProtoLogicalPlan.Union(children, byName, allowMissing) =>
        val ub = pb.UnionMsg.newBuilder().setByName(byName).setAllowMissingColumns(allowMissing)
        children.foreach(c => ub.addChildren(toProtoPlan(c)))
        b.setUnion(ub.build())

      case ProtoLogicalPlan.Intersect(left, right, isAll) =>
        b.setIntersect(
          pb.IntersectMsg
            .newBuilder()
            .setLeft(toProtoPlan(left))
            .setRight(toProtoPlan(right))
            .setIsAll(isAll)
            .build()
        )

      case ProtoLogicalPlan.Except(left, right, isAll) =>
        b.setExcept(
          pb.ExceptMsg
            .newBuilder()
            .setLeft(toProtoPlan(left))
            .setRight(toProtoPlan(right))
            .setIsAll(isAll)
            .build()
        )

      case ProtoLogicalPlan.Window(windowExprs, partSpec, orderSpec, child) =>
        val wb = pb.WindowMsg.newBuilder().setChild(toProtoPlan(child))
        windowExprs.foreach(e => wb.addWindowExprs(toProtoExpr(e)))
        partSpec.foreach(e => wb.addPartitionSpec(toProtoExpr(e)))
        orderSpec.foreach(o => wb.addOrderSpec(toProtoSortOrder(o)))
        b.setWindow(wb.build())

      case ProtoLogicalPlan.With(ctes, recursive, child) =>
        val wb = pb.WithMsg.newBuilder().setRecursive(recursive).setChild(toProtoPlan(child))
        ctes.foreach { (name, plan) =>
          wb.addCteRelations(
            pb.CTERelationMsg.newBuilder().setName(name).setPlan(toProtoPlan(plan)).build()
          )
        }
        b.setWithPlan(wb.build())

      case ProtoLogicalPlan.Pivot(grouping, pivotCol, pivotVals, aggs, child) =>
        val pvb = pb.PivotMsg
          .newBuilder()
          .setPivotColumn(toProtoExpr(pivotCol))
          .setChild(toProtoPlan(child))
        grouping.foreach(e => pvb.addGroupingExprs(toProtoExpr(e)))
        pivotVals.foreach(e => pvb.addPivotValues(toProtoExpr(e)))
        aggs.foreach(e => pvb.addAggregates(toProtoExpr(e)))
        b.setPivot(pvb.build())

      case ProtoLogicalPlan.Unpivot(valColName, varColName, columns, includeNulls, child) =>
        val ub = pb.UnpivotMsg
          .newBuilder()
          .setValueColumnName(valColName)
          .setVariableColumnName(varColName)
          .setIncludeNulls(includeNulls)
          .setChild(toProtoPlan(child))
        columns.foreach { (expr, alias) =>
          val eb = pb.ExprWithAliasMsg.newBuilder().setExpr(toProtoExpr(expr))
          alias.foreach(eb.setAlias)
          ub.addColumns(eb.build())
        }
        b.setUnpivot(ub.build())

      case ProtoLogicalPlan.LateralJoin(left, lateral, condition) =>
        val lb =
          pb.LateralJoinMsg.newBuilder().setLeft(toProtoPlan(left)).setLateral(toProtoPlan(lateral))
        condition.foreach(c => lb.setCondition(toProtoExpr(c)))
        b.setLateralJoin(lb.build())

      case ProtoLogicalPlan.Generate(generator, genOutput, outer, child) =>
        val gb = pb.GenerateMsg
          .newBuilder()
          .setGenerator(toProtoExpr(generator))
          .setOuter(outer)
          .setChild(toProtoPlan(child))
        genOutput.foreach(gb.addGeneratorOutput)
        b.setGenerate(gb.build())

      case ProtoLogicalPlan.ResolvedHint(hints, child) =>
        val hb = pb.ResolvedHintMsg.newBuilder().setChild(toProtoPlan(child))
        hints.foreach(h => hb.addHints(toProtoPlanHint(h)))
        b.setResolvedHint(hb.build())
    b.build()

  def fromProtoPlan(msg: pb.ProtoLogicalPlanMsg): ProtoLogicalPlan =
    import pb.ProtoLogicalPlanMsg.PlanCase._
    msg.getPlanCase match
      case RELATION_REF =>
        val rr = msg.getRelationRef
        ProtoLogicalPlan.RelationRef(
          rr.getName,
          if rr.hasAlias then Some(rr.getAlias) else None,
          fromProtoSchemaContract(rr.getSchemaContract)
        )

      case VALUES =>
        val v = msg.getValues
        val rows = (0 until v.getRowsCount).map { i =>
          val row = v.getRows(i)
          (0 until row.getValuesCount).map(j => fromProtoExpr(row.getValues(j))).toVector
        }.toVector
        ProtoLogicalPlan.Values(rows, fromProtoSchema(v.getSchema))

      case PROJECT =>
        val pr = msg.getProject
        val projList =
          (0 until pr.getProjectListCount).map(i => fromProtoExpr(pr.getProjectList(i))).toVector
        ProtoLogicalPlan.Project(projList, fromProtoPlan(pr.getChild))

      case FILTER =>
        val f = msg.getFilter
        ProtoLogicalPlan.Filter(fromProtoExpr(f.getCondition), fromProtoPlan(f.getChild))

      case AGGREGATE =>
        val ag = msg.getAggregate
        val grouping = (0 until ag.getGroupingExprsCount)
          .map(i => fromProtoExpr(ag.getGroupingExprs(i)))
          .toVector
        val aggs = (0 until ag.getAggregateExprsCount)
          .map(i => fromProtoExpr(ag.getAggregateExprs(i)))
          .toVector
        ProtoLogicalPlan.Aggregate(grouping, aggs, fromProtoPlan(ag.getChild))

      case SORT =>
        val s = msg.getSort
        val order = (0 until s.getOrderCount).map(i => fromProtoSortOrder(s.getOrder(i))).toVector
        ProtoLogicalPlan.Sort(order, fromProtoPlan(s.getChild))

      case LIMIT =>
        val l = msg.getLimit
        ProtoLogicalPlan.Limit(l.getLimit, fromProtoPlan(l.getChild))

      case DISTINCT =>
        ProtoLogicalPlan.Distinct(fromProtoPlan(msg.getDistinct.getChild))

      case SUBQUERY_ALIAS =>
        val sa = msg.getSubqueryAlias
        ProtoLogicalPlan.SubqueryAlias(sa.getAlias, fromProtoPlan(sa.getChild))

      case JOIN =>
        val j = msg.getJoin
        ProtoLogicalPlan.Join(
          fromProtoPlan(j.getLeft),
          fromProtoPlan(j.getRight),
          fromProtoJoinType(j.getJoinType),
          if j.hasCondition then Some(fromProtoExpr(j.getCondition)) else None
        )

      case UNION =>
        val u = msg.getUnion
        val children =
          (0 until u.getChildrenCount).map(i => fromProtoPlan(u.getChildren(i))).toVector
        ProtoLogicalPlan.Union(children, u.getByName, u.getAllowMissingColumns)

      case INTERSECT =>
        val is = msg.getIntersect
        ProtoLogicalPlan.Intersect(
          fromProtoPlan(is.getLeft),
          fromProtoPlan(is.getRight),
          is.getIsAll
        )

      case EXCEPT =>
        val ex = msg.getExcept
        ProtoLogicalPlan.Except(fromProtoPlan(ex.getLeft), fromProtoPlan(ex.getRight), ex.getIsAll)

      case WINDOW =>
        val w = msg.getWindow
        val windowExprs =
          (0 until w.getWindowExprsCount).map(i => fromProtoExpr(w.getWindowExprs(i))).toVector
        val partSpec =
          (0 until w.getPartitionSpecCount).map(i => fromProtoExpr(w.getPartitionSpec(i))).toVector
        val orderSpec =
          (0 until w.getOrderSpecCount).map(i => fromProtoSortOrder(w.getOrderSpec(i))).toVector
        ProtoLogicalPlan.Window(windowExprs, partSpec, orderSpec, fromProtoPlan(w.getChild))

      case WITH_PLAN =>
        val wp = msg.getWithPlan
        val ctes = (0 until wp.getCteRelationsCount).map { i =>
          val cte = wp.getCteRelations(i)
          (cte.getName, fromProtoPlan(cte.getPlan))
        }.toVector
        ProtoLogicalPlan.With(ctes, wp.getRecursive, fromProtoPlan(wp.getChild))

      case PIVOT =>
        val pv = msg.getPivot
        val grouping = (0 until pv.getGroupingExprsCount)
          .map(i => fromProtoExpr(pv.getGroupingExprs(i)))
          .toVector
        val pivotVals =
          (0 until pv.getPivotValuesCount).map(i => fromProtoExpr(pv.getPivotValues(i))).toVector
        val aggs =
          (0 until pv.getAggregatesCount).map(i => fromProtoExpr(pv.getAggregates(i))).toVector
        ProtoLogicalPlan.Pivot(
          grouping,
          fromProtoExpr(pv.getPivotColumn),
          pivotVals,
          aggs,
          fromProtoPlan(pv.getChild)
        )

      case UNPIVOT =>
        val up = msg.getUnpivot
        val columns = (0 until up.getColumnsCount).map { i =>
          val col = up.getColumns(i)
          (fromProtoExpr(col.getExpr), if col.hasAlias then Some(col.getAlias) else None)
        }.toVector
        ProtoLogicalPlan.Unpivot(
          up.getValueColumnName,
          up.getVariableColumnName,
          columns,
          up.getIncludeNulls,
          fromProtoPlan(up.getChild)
        )

      case LATERAL_JOIN =>
        val lj = msg.getLateralJoin
        ProtoLogicalPlan.LateralJoin(
          fromProtoPlan(lj.getLeft),
          fromProtoPlan(lj.getLateral),
          if lj.hasCondition then Some(fromProtoExpr(lj.getCondition)) else None
        )

      case GENERATE =>
        val g = msg.getGenerate
        val genOutput =
          (0 until g.getGeneratorOutputCount).map(i => g.getGeneratorOutput(i)).toVector
        ProtoLogicalPlan.Generate(
          fromProtoExpr(g.getGenerator),
          genOutput,
          g.getOuter,
          fromProtoPlan(g.getChild)
        )

      case RESOLVED_HINT =>
        val rh = msg.getResolvedHint
        val hints = (0 until rh.getHintsCount).map(i => fromProtoPlanHint(rh.getHints(i))).toVector
        ProtoLogicalPlan.ResolvedHint(hints, fromProtoPlan(rh.getChild))

      case PLAN_NOT_SET => throw new IllegalArgumentException("ProtoLogicalPlanMsg plan not set")

  // ============================================================================
  // Enum conversions
  // ============================================================================

  private def toProtoTrimType(t: TrimType): pb.TrimTypeEnum = t match
    case TrimType.Both     => pb.TrimTypeEnum.TRIM_TYPE_BOTH
    case TrimType.Leading  => pb.TrimTypeEnum.TRIM_TYPE_LEADING
    case TrimType.Trailing => pb.TrimTypeEnum.TRIM_TYPE_TRAILING

  private def fromProtoTrimType(t: pb.TrimTypeEnum): TrimType = t match
    case pb.TrimTypeEnum.TRIM_TYPE_BOTH     => TrimType.Both
    case pb.TrimTypeEnum.TRIM_TYPE_LEADING  => TrimType.Leading
    case pb.TrimTypeEnum.TRIM_TYPE_TRAILING => TrimType.Trailing
    case _                                  => TrimType.Both

  private def toProtoDateTimeField(f: DateTimeField): pb.DateTimeFieldEnum = f match
    case DateTimeField.Year        => pb.DateTimeFieldEnum.DATE_TIME_FIELD_YEAR
    case DateTimeField.Month       => pb.DateTimeFieldEnum.DATE_TIME_FIELD_MONTH
    case DateTimeField.Day         => pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY
    case DateTimeField.Hour        => pb.DateTimeFieldEnum.DATE_TIME_FIELD_HOUR
    case DateTimeField.Minute      => pb.DateTimeFieldEnum.DATE_TIME_FIELD_MINUTE
    case DateTimeField.Second      => pb.DateTimeFieldEnum.DATE_TIME_FIELD_SECOND
    case DateTimeField.Quarter     => pb.DateTimeFieldEnum.DATE_TIME_FIELD_QUARTER
    case DateTimeField.Week        => pb.DateTimeFieldEnum.DATE_TIME_FIELD_WEEK
    case DateTimeField.DayOfWeek   => pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY_OF_WEEK
    case DateTimeField.DayOfYear   => pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY_OF_YEAR
    case DateTimeField.Microsecond => pb.DateTimeFieldEnum.DATE_TIME_FIELD_MICROSECOND
    case DateTimeField.Millisecond => pb.DateTimeFieldEnum.DATE_TIME_FIELD_MILLISECOND

  private def fromProtoDateTimeField(f: pb.DateTimeFieldEnum): DateTimeField = f match
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_YEAR        => DateTimeField.Year
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MONTH       => DateTimeField.Month
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY         => DateTimeField.Day
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_HOUR        => DateTimeField.Hour
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MINUTE      => DateTimeField.Minute
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_SECOND      => DateTimeField.Second
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_QUARTER     => DateTimeField.Quarter
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_WEEK        => DateTimeField.Week
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY_OF_WEEK => DateTimeField.DayOfWeek
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY_OF_YEAR => DateTimeField.DayOfYear
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MICROSECOND => DateTimeField.Microsecond
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MILLISECOND => DateTimeField.Millisecond
    case _                                                => DateTimeField.Year

  private def toProtoSortDirection(d: SortDirection): pb.SortDirectionEnum = d match
    case SortDirection.Ascending  => pb.SortDirectionEnum.SORT_DIRECTION_ASCENDING
    case SortDirection.Descending => pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING

  private def fromProtoSortDirection(d: pb.SortDirectionEnum): SortDirection = d match
    case pb.SortDirectionEnum.SORT_DIRECTION_ASCENDING  => SortDirection.Ascending
    case pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING => SortDirection.Descending
    case _                                              => SortDirection.Ascending

  private def toProtoNullOrdering(n: NullOrdering): pb.NullOrderingEnum = n match
    case NullOrdering.NullsFirst => pb.NullOrderingEnum.NULL_ORDERING_NULLS_FIRST
    case NullOrdering.NullsLast  => pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST

  private def fromProtoNullOrdering(n: pb.NullOrderingEnum): NullOrdering = n match
    case pb.NullOrderingEnum.NULL_ORDERING_NULLS_FIRST => NullOrdering.NullsFirst
    case pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST  => NullOrdering.NullsLast
    case _                                             => NullOrdering.NullsFirst

  private def toProtoJoinType(jt: JoinType): pb.JoinTypeEnum = jt match
    case JoinType.Inner      => pb.JoinTypeEnum.JOIN_TYPE_INNER
    case JoinType.LeftOuter  => pb.JoinTypeEnum.JOIN_TYPE_LEFT_OUTER
    case JoinType.RightOuter => pb.JoinTypeEnum.JOIN_TYPE_RIGHT_OUTER
    case JoinType.FullOuter  => pb.JoinTypeEnum.JOIN_TYPE_FULL_OUTER
    case JoinType.LeftSemi   => pb.JoinTypeEnum.JOIN_TYPE_LEFT_SEMI
    case JoinType.LeftAnti   => pb.JoinTypeEnum.JOIN_TYPE_LEFT_ANTI
    case JoinType.Cross      => pb.JoinTypeEnum.JOIN_TYPE_CROSS

  private def fromProtoJoinType(jt: pb.JoinTypeEnum): JoinType = jt match
    case pb.JoinTypeEnum.JOIN_TYPE_INNER       => JoinType.Inner
    case pb.JoinTypeEnum.JOIN_TYPE_LEFT_OUTER  => JoinType.LeftOuter
    case pb.JoinTypeEnum.JOIN_TYPE_RIGHT_OUTER => JoinType.RightOuter
    case pb.JoinTypeEnum.JOIN_TYPE_FULL_OUTER  => JoinType.FullOuter
    case pb.JoinTypeEnum.JOIN_TYPE_LEFT_SEMI   => JoinType.LeftSemi
    case pb.JoinTypeEnum.JOIN_TYPE_LEFT_ANTI   => JoinType.LeftAnti
    case pb.JoinTypeEnum.JOIN_TYPE_CROSS       => JoinType.Cross
    case _                                     => JoinType.Inner

  private def toProtoFrameType(ft: FrameType): pb.FrameTypeEnum = ft match
    case FrameType.Rows  => pb.FrameTypeEnum.FRAME_TYPE_ROWS
    case FrameType.Range => pb.FrameTypeEnum.FRAME_TYPE_RANGE

  private def fromProtoFrameType(ft: pb.FrameTypeEnum): FrameType = ft match
    case pb.FrameTypeEnum.FRAME_TYPE_ROWS  => FrameType.Rows
    case pb.FrameTypeEnum.FRAME_TYPE_RANGE => FrameType.Range
    case _                                 => FrameType.Rows

  // ============================================================================
  // Shared message conversions
  // ============================================================================

  private def toProtoSortOrder(so: SortOrder): pb.SortOrderMsg =
    pb.SortOrderMsg
      .newBuilder()
      .setChild(toProtoExpr(so.child))
      .setDirection(toProtoSortDirection(so.direction))
      .setNullOrdering(toProtoNullOrdering(so.nullOrdering))
      .build()

  private def fromProtoSortOrder(msg: pb.SortOrderMsg): SortOrder =
    SortOrder(
      fromProtoExpr(msg.getChild),
      fromProtoSortDirection(msg.getDirection),
      fromProtoNullOrdering(msg.getNullOrdering)
    )

  private def toProtoWindowFrame(wf: WindowFrame): pb.WindowFrameMsg =
    pb.WindowFrameMsg
      .newBuilder()
      .setFrameType(toProtoFrameType(wf.frameType))
      .setLower(toProtoFrameBound(wf.lower))
      .setUpper(toProtoFrameBound(wf.upper))
      .build()

  private def fromProtoWindowFrame(msg: pb.WindowFrameMsg): WindowFrame =
    WindowFrame(
      fromProtoFrameType(msg.getFrameType),
      fromProtoFrameBound(msg.getLower),
      fromProtoFrameBound(msg.getUpper)
    )

  private def toProtoFrameBound(fb: FrameBound): pb.FrameBoundMsg =
    val b = pb.FrameBoundMsg.newBuilder()
    val empty = pb.EmptyMsg.newBuilder().build()
    fb match
      case FrameBound.UnboundedPreceding => b.setUnboundedPreceding(empty)
      case FrameBound.UnboundedFollowing => b.setUnboundedFollowing(empty)
      case FrameBound.CurrentRow         => b.setCurrentRow(empty)
      case FrameBound.Preceding(n)       => b.setPreceding(n)
      case FrameBound.Following(n)       => b.setFollowing(n)
    b.build()

  private def fromProtoFrameBound(msg: pb.FrameBoundMsg): FrameBound =
    import pb.FrameBoundMsg.BoundCase._
    msg.getBoundCase match
      case UNBOUNDED_PRECEDING => FrameBound.UnboundedPreceding
      case UNBOUNDED_FOLLOWING => FrameBound.UnboundedFollowing
      case CURRENT_ROW         => FrameBound.CurrentRow
      case PRECEDING           => FrameBound.Preceding(msg.getPreceding)
      case FOLLOWING           => FrameBound.Following(msg.getFollowing)
      case BOUND_NOT_SET       => throw new IllegalArgumentException("FrameBoundMsg bound not set")

  private def toProtoPlanHint(h: PlanHint): pb.PlanHintMsg =
    val b = pb.PlanHintMsg.newBuilder().setName(h.name)
    h.params.foreach {
      case HintParam.StringVal(v) =>
        b.addParams(pb.HintParamMsg.newBuilder().setStringVal(v).build())
      case HintParam.IntVal(v) => b.addParams(pb.HintParamMsg.newBuilder().setIntVal(v).build())
    }
    b.build()

  private def fromProtoPlanHint(msg: pb.PlanHintMsg): PlanHint =
    val params = (0 until msg.getParamsCount).map { i =>
      val p = msg.getParams(i)
      import pb.HintParamMsg.ParamCase._
      p.getParamCase match
        case STRING_VAL    => HintParam.StringVal(p.getStringVal)
        case INT_VAL       => HintParam.IntVal(p.getIntVal)
        case PARAM_NOT_SET => throw new IllegalArgumentException("HintParamMsg param not set")
    }.toVector
    PlanHint(msg.getName, params)

  // ============================================================================
  // Helper methods for structural messages
  // ============================================================================

  private def unaryMsg(child: ProtoExpr): pb.UnaryExprMsg =
    pb.UnaryExprMsg.newBuilder().setChild(toProtoExpr(child)).build()

  private def binaryMsg(left: ProtoExpr, right: ProtoExpr): pb.BinaryExprMsg =
    pb.BinaryExprMsg.newBuilder().setLeft(toProtoExpr(left)).setRight(toProtoExpr(right)).build()

  private def ternaryMsg(first: ProtoExpr, second: ProtoExpr, third: ProtoExpr): pb.TernaryExprMsg =
    pb.TernaryExprMsg
      .newBuilder()
      .setFirst(toProtoExpr(first))
      .setSecond(toProtoExpr(second))
      .setThird(toProtoExpr(third))
      .build()

  private def naryMsg(children: Vector[ProtoExpr]): pb.NaryExprMsg =
    val b = pb.NaryExprMsg.newBuilder()
    children.foreach(c => b.addChildren(toProtoExpr(c)))
    b.build()

  private def fromBinary(msg: pb.BinaryExprMsg, f: (ProtoExpr, ProtoExpr) => ProtoExpr): ProtoExpr =
    f(fromProtoExpr(msg.getLeft), fromProtoExpr(msg.getRight))

  private def fromTernary(
      msg: pb.TernaryExprMsg,
      f: (ProtoExpr, ProtoExpr, ProtoExpr) => ProtoExpr
  ): ProtoExpr =
    f(fromProtoExpr(msg.getFirst), fromProtoExpr(msg.getSecond), fromProtoExpr(msg.getThird))

  private def fromNary(msg: pb.NaryExprMsg): Vector[ProtoExpr] =
    (0 until msg.getChildrenCount).map(i => fromProtoExpr(msg.getChildren(i))).toVector
