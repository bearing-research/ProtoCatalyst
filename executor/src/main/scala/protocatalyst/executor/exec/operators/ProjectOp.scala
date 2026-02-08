package protocatalyst.executor.exec.operators

import scala.jdk.CollectionConverters._

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._
import org.apache.arrow.vector.types.pojo.{Field, Schema}

import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Project operator: evaluates a list of expressions to produce output columns. */
object ProjectOp:

  def execute(
      input: Batch,
      projectList: Vector[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    val outputVectors = projectList.map(expr => evaluator.eval(expr, input))
    val outputFields = projectList.zip(outputVectors).map { (expr, vec) =>
      val name = extractName(expr)
      val protoType = inferProtoType(vec)
      ProtoStructField(name, protoType, nullable = true)
    }

    val schema = ProtoSchema(outputFields)
    val arrowFields = outputFields.zip(outputVectors).map { (f, vec) =>
      vec.getField
    }
    val arrowSchema = new Schema(
      arrowFields
        .map(f =>
          new Field(extractName(projectList(arrowFields.indexOf(f))), f.getFieldType, f.getChildren)
        )
        .toList
        .asJava
    )

    val root = new VectorSchemaRoot(
      outputVectors.map(_.asInstanceOf[FieldVector]).toList.asJava
    )
    root.setRowCount(input.rowCount)
    Batch.fromRoot(root, schema)

  /** Extract the output column name from an expression. */
  private[operators] def extractName(expr: ProtoExpr): String = expr match
    case ProtoExpr.Alias(_, name)           => name
    case ProtoExpr.ColumnRef(name, _, _, _) => name
    case ProtoExpr.BoundRef(index, _, _)    => s"col$index"
    case _                                  => "expr"

  /** Infer ProtoType from an Arrow FieldVector. */
  private[operators] def inferProtoType(vec: FieldVector): ProtoType = vec match
    case _: BitVector              => ProtoType.BooleanType
    case _: TinyIntVector          => ProtoType.ByteType
    case _: SmallIntVector         => ProtoType.ShortType
    case _: IntVector              => ProtoType.IntegerType
    case _: BigIntVector           => ProtoType.LongType
    case _: Float4Vector           => ProtoType.FloatType
    case _: Float8Vector           => ProtoType.DoubleType
    case _: VarCharVector          => ProtoType.StringType
    case _: VarBinaryVector        => ProtoType.BinaryType
    case v: DecimalVector          => ProtoType.DecimalType(v.getPrecision, v.getScale)
    case _: DateDayVector          => ProtoType.DateType
    case _: TimeStampMicroTZVector => ProtoType.TimestampType
    case _: TimeMicroVector        => ProtoType.TimeType(6)
    case _                         => ProtoType.StringType // fallback
