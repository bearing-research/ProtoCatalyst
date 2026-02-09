package protocatalyst.arrow.parquet

import java.io.Serializable
import java.nio.file.{Path => JPath}

import scala.jdk.CollectionConverters._

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.{ColumnIOFactory, LocalInputFile, LocalOutputFile}

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.plan.Statistics
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Read and write Parquet files using Arrow VectorSchemaRoot as the in-memory representation. */
object ParquetIO:

  enum Compression extends Serializable:
    case Uncompressed, Snappy, Gzip, Zstd

  case class WriteConfig(
      compression: Compression = Compression.Snappy,
      rowGroupSize: Long = 128L * 1024 * 1024,
      pageSize: Int = 1024 * 1024
  ) extends Serializable

  // ============================================================
  // Writing
  // ============================================================

  def write(
      root: VectorSchemaRoot,
      schema: ProtoSchema,
      path: String,
      config: WriteConfig = WriteConfig()
  ): Unit =
    val parquetSchema = ParquetSchemaConverter.toParquetSchema(schema)
    val outputFile = new LocalOutputFile(JPath.of(path))
    val codec = config.compression match
      case Compression.Uncompressed => CompressionCodecName.UNCOMPRESSED
      case Compression.Snappy       => CompressionCodecName.SNAPPY
      case Compression.Gzip         => CompressionCodecName.GZIP
      case Compression.Zstd         => CompressionCodecName.ZSTD

    val writer = ExampleParquetWriter
      .builder(outputFile)
      .withType(parquetSchema)
      .withConf(new PlainParquetConfiguration())
      .withCompressionCodec(codec)
      .withRowGroupSize(config.rowGroupSize)
      .withPageSize(config.pageSize)
      .build()

    try
      val factory = new SimpleGroupFactory(parquetSchema)
      val rowCount = root.getRowCount
      for row <- 0 until rowCount do
        val group = factory.newGroup()
        for col <- 0 until schema.fields.size do
          val vec = root.getVector(col)
          val field = schema.fields(col)
          if !vec.isNull(row) then writeValue(group, col, vec, row, field.dataType)
        writer.write(group)
    finally writer.close()

  private def writeValue(
      group: Group,
      col: Int,
      vec: FieldVector,
      row: Int,
      dt: ProtoType
  ): Unit =
    dt match
      case ProtoType.BooleanType =>
        group.add(col, vec.asInstanceOf[BitVector].get(row) != 0)
      case ProtoType.ByteType =>
        group.add(col, vec.asInstanceOf[TinyIntVector].get(row).toInt)
      case ProtoType.ShortType =>
        group.add(col, vec.asInstanceOf[SmallIntVector].get(row).toInt)
      case ProtoType.IntegerType =>
        group.add(col, vec.asInstanceOf[IntVector].get(row))
      case ProtoType.LongType =>
        group.add(col, vec.asInstanceOf[BigIntVector].get(row))
      case ProtoType.FloatType =>
        group.add(col, vec.asInstanceOf[Float4Vector].get(row))
      case ProtoType.DoubleType =>
        group.add(col, vec.asInstanceOf[Float8Vector].get(row))
      case ProtoType.StringType | ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
        val bytes = vec.asInstanceOf[VarCharVector].get(row)
        group.add(col, new String(bytes, "UTF-8"))
      case ProtoType.BinaryType =>
        val bytes = vec.asInstanceOf[VarBinaryVector].get(row)
        group.add(col, org.apache.parquet.io.api.Binary.fromReusedByteArray(bytes))
      case ProtoType.DateType =>
        group.add(col, vec.asInstanceOf[DateDayVector].get(row))
      case ProtoType.TimestampType =>
        group.add(col, vec.asInstanceOf[TimeStampMicroTZVector].get(row))
      case ProtoType.TimestampNTZType =>
        group.add(col, vec.asInstanceOf[TimeStampMicroVector].get(row))
      case ProtoType.DecimalType(precision, scale) =>
        val dec = vec.asInstanceOf[DecimalVector].getObject(row)
        if precision <= 9 then group.add(col, dec.unscaledValue().intValueExact())
        else if precision <= 18 then group.add(col, dec.unscaledValue().longValueExact())
        else
          val bytes = dec.unscaledValue().toByteArray
          val byteLen = decimalByteLength(precision)
          val padded = padBigEndian(bytes, byteLen)
          group.add(
            col,
            org.apache.parquet.io.api.Binary.fromReusedByteArray(padded)
          )
      case _ =>
        throw IllegalArgumentException(s"Unsupported write type: $dt")

  // ============================================================
  // Reading
  // ============================================================

  def read(path: String, allocator: BufferAllocator): (VectorSchemaRoot, ProtoSchema) =
    val reader = ParquetFileReader.open(new LocalInputFile(JPath.of(path)))
    try
      val parquetSchema = reader.getFooter.getFileMetaData.getSchema
      val protoSchema = ParquetSchemaConverter.fromParquetSchema(parquetSchema)
      val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
      val root = VectorSchemaRoot.create(arrowSchema, allocator)
      root.allocateNew()

      val totalRows = reader.getRecordCount.toInt
      var rowIdx = 0
      var pages: PageReadStore = reader.readNextRowGroup()
      while pages != null do
        val columnIO = new ColumnIOFactory().getColumnIO(parquetSchema)
        val recordReader = columnIO.getRecordReader(pages, new GroupRecordConverter(parquetSchema))
        val rowsInGroup = pages.getRowCount.toInt
        for _ <- 0 until rowsInGroup do
          val group = recordReader.read()
          for col <- 0 until protoSchema.fields.size do
            val field = protoSchema.fields(col)
            val vec = root.getVector(col)
            val fieldDef = parquetSchema.getType(col)
            val repCount = group.getFieldRepetitionCount(col)
            if repCount == 0 then setNull(vec, rowIdx)
            else readValue(group, col, vec, rowIdx, field.dataType)
          rowIdx += 1
        pages = reader.readNextRowGroup()

      root.setRowCount(totalRows)
      (root, protoSchema)
    finally reader.close()

  def readSchema(path: String): ProtoSchema =
    val reader = ParquetFileReader.open(new LocalInputFile(JPath.of(path)))
    try
      val parquetSchema = reader.getFooter.getFileMetaData.getSchema
      ParquetSchemaConverter.fromParquetSchema(parquetSchema)
    finally reader.close()

  def readRowCount(path: String): Long =
    val reader = ParquetFileReader.open(new LocalInputFile(JPath.of(path)))
    try reader.getRecordCount
    finally reader.close()

  def readStatistics(path: String): Statistics =
    val reader = ParquetFileReader.open(new LocalInputFile(JPath.of(path)))
    try
      val rowCount = reader.getRecordCount
      val blocks = reader.getFooter.getBlocks.asScala
      val sizeInBytes = blocks.map(_.getTotalByteSize).sum
      Statistics(rowCount = rowCount, sizeInBytes = sizeInBytes)
    finally reader.close()

  private def readValue(
      group: Group,
      col: Int,
      vec: FieldVector,
      row: Int,
      dt: ProtoType
  ): Unit =
    dt match
      case ProtoType.BooleanType =>
        vec.asInstanceOf[BitVector].setSafe(row, if group.getBoolean(col, 0) then 1 else 0)
      case ProtoType.ByteType =>
        vec.asInstanceOf[TinyIntVector].setSafe(row, group.getInteger(col, 0).toByte)
      case ProtoType.ShortType =>
        vec.asInstanceOf[SmallIntVector].setSafe(row, group.getInteger(col, 0).toShort)
      case ProtoType.IntegerType =>
        vec.asInstanceOf[IntVector].setSafe(row, group.getInteger(col, 0))
      case ProtoType.LongType =>
        vec.asInstanceOf[BigIntVector].setSafe(row, group.getLong(col, 0))
      case ProtoType.FloatType =>
        vec.asInstanceOf[Float4Vector].setSafe(row, group.getFloat(col, 0))
      case ProtoType.DoubleType =>
        vec.asInstanceOf[Float8Vector].setSafe(row, group.getDouble(col, 0))
      case ProtoType.StringType | ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
        val str = group.getString(col, 0)
        val bytes = str.getBytes("UTF-8")
        vec.asInstanceOf[VarCharVector].setSafe(row, bytes, 0, bytes.length)
      case ProtoType.BinaryType =>
        val bin = group.getBinary(col, 0)
        vec.asInstanceOf[VarBinaryVector].setSafe(row, bin.getBytes, 0, bin.length())
      case ProtoType.DateType =>
        vec.asInstanceOf[DateDayVector].setSafe(row, group.getInteger(col, 0))
      case ProtoType.TimestampType =>
        vec.asInstanceOf[TimeStampMicroTZVector].setSafe(row, group.getLong(col, 0))
      case ProtoType.TimestampNTZType =>
        vec.asInstanceOf[TimeStampMicroVector].setSafe(row, group.getLong(col, 0))
      case ProtoType.DecimalType(precision, scale) =>
        if precision <= 9 then
          val unscaled = group.getInteger(col, 0)
          val dec = java.math.BigDecimal.valueOf(unscaled.toLong, scale)
          vec.asInstanceOf[DecimalVector].setSafe(row, dec)
        else if precision <= 18 then
          val unscaled = group.getLong(col, 0)
          val dec = java.math.BigDecimal.valueOf(unscaled, scale)
          vec.asInstanceOf[DecimalVector].setSafe(row, dec)
        else
          val bin = group.getBinary(col, 0)
          val unscaled = new java.math.BigInteger(bin.getBytes)
          val dec = new java.math.BigDecimal(unscaled, scale)
          vec.asInstanceOf[DecimalVector].setSafe(row, dec)
      case _ =>
        throw IllegalArgumentException(s"Unsupported read type: $dt")

  private def setNull(vec: FieldVector, row: Int): Unit =
    vec match
      case v: BitVector              => v.setNull(row)
      case v: TinyIntVector          => v.setNull(row)
      case v: SmallIntVector         => v.setNull(row)
      case v: IntVector              => v.setNull(row)
      case v: BigIntVector           => v.setNull(row)
      case v: Float4Vector           => v.setNull(row)
      case v: Float8Vector           => v.setNull(row)
      case v: VarCharVector          => v.setNull(row)
      case v: VarBinaryVector        => v.setNull(row)
      case v: DecimalVector          => v.setNull(row)
      case v: DateDayVector          => v.setNull(row)
      case v: TimeStampMicroTZVector => v.setNull(row)
      case v: TimeStampMicroVector   => v.setNull(row)
      case v: TimeMicroVector        => v.setNull(row)
      case _                         => vec.setNull(row)

  private def decimalByteLength(precision: Int): Int =
    math.ceil(math.log10(math.pow(10, precision)) * math.log(10) / math.log(2) / 8.0).toInt.max(1)

  /** Pad (or truncate) a big-endian byte array to exactly `len` bytes, sign-extending. */
  private def padBigEndian(bytes: Array[Byte], len: Int): Array[Byte] =
    if bytes.length == len then bytes
    else if bytes.length > len then bytes.takeRight(len)
    else
      val pad = if bytes.nonEmpty && bytes(0) < 0 then 0xff.toByte else 0.toByte
      Array.fill(len - bytes.length)(pad) ++ bytes
