package protocatalyst.benchmark

import java.io.{File, PrintWriter}
import java.time.Instant
import java.util.Base64

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.util.{ArrayData, MapData}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/** Generates golden files from Spark's ExpressionEncoder output.
  *
  * These files capture the exact byte-level representation that Spark produces, allowing
  * ProtoCatalyst's output to be verified for parity.
  *
  * Run with: sbt "benchmarkSpark/runMain protocatalyst.benchmark.GoldenFileGenerator"
  */
object GoldenFileGenerator {

  /** Recursively extract a value from InternalRow to JSON-compatible format */
  def extractValue(value: Any, dataType: DataType): String = {
    if (value == null) {
      """{"type":"Null"}"""
    } else {
      dataType match {
        case StringType =>
          val utf8 = value.asInstanceOf[UTF8String]
          val base64 = Base64.getEncoder.encodeToString(utf8.getBytes)
          s"""{"type":"UTF8String","base64":"$base64"}"""

        case IntegerType =>
          s"""{"type":"Int","value":${value.asInstanceOf[Int]}}"""

        case LongType =>
          s"""{"type":"Long","value":${value.asInstanceOf[Long]}}"""

        case DoubleType =>
          s"""{"type":"Double","value":${value.asInstanceOf[Double]}}"""

        case FloatType =>
          s"""{"type":"Float","value":${value.asInstanceOf[Float]}}"""

        case BooleanType =>
          s"""{"type":"Boolean","value":${value.asInstanceOf[Boolean]}}"""

        case ByteType =>
          s"""{"type":"Byte","value":${value.asInstanceOf[Byte]}}"""

        case ShortType =>
          s"""{"type":"Short","value":${value.asInstanceOf[Short]}}"""

        case BinaryType =>
          val bytes = value.asInstanceOf[Array[Byte]]
          val base64 = Base64.getEncoder.encodeToString(bytes)
          s"""{"type":"Binary","base64":"$base64"}"""

        case DateType =>
          // Stored as epoch days (Int)
          s"""{"type":"Date","epochDays":${value.asInstanceOf[Int]}}"""

        case TimestampType =>
          // Stored as microseconds since epoch (Long)
          s"""{"type":"Timestamp","micros":${value.asInstanceOf[Long]}}"""

        case TimestampNTZType =>
          // Stored as microseconds (Long)
          s"""{"type":"TimestampNTZ","micros":${value.asInstanceOf[Long]}}"""

        case ArrayType(elementType, _) =>
          val arr = value.asInstanceOf[ArrayData]
          val elements = (0 until arr.numElements()).map { i =>
            val elem = arr.get(i, elementType)
            extractValue(elem, elementType)
          }
          s"""{"type":"ArrayData","elements":[${elements.mkString(",")}]}"""

        case MapType(keyType, valueType, _) =>
          val map = value.asInstanceOf[MapData]
          val keys = (0 until map.numElements()).map { i =>
            extractValue(map.keyArray().get(i, keyType), keyType)
          }
          val values = (0 until map.numElements()).map { i =>
            extractValue(map.valueArray().get(i, valueType), valueType)
          }
          s"""{"type":"MapData","keys":[${keys.mkString(",")}],"values":[${values.mkString(",")}]}"""

        case st: StructType =>
          val row = value.asInstanceOf[InternalRow]
          val fields = st.fields.zipWithIndex.map { case (field, i) =>
            val fieldValue = row.get(i, field.dataType)
            s""""${field.name}":${extractValue(fieldValue, field.dataType)}"""
          }
          s"""{"type":"InternalRow","fields":{${fields.mkString(",")}}}"""

        case dt: DecimalType =>
          val decimal = value.asInstanceOf[org.apache.spark.sql.types.Decimal]
          s"""{"type":"Decimal","value":"${decimal.toString}","precision":${dt.precision},"scale":${dt.scale}}"""

        case _ =>
          s"""{"type":"Unknown","class":"${value.getClass.getName}","toString":"${value.toString}"}"""
      }
    }
  }

  /** Generate golden file for a type */
  def generate[T](
      typeName: String,
      encoder: ExpressionEncoder[T],
      testCases: Seq[(String, T)]
  ): String = {
    val serializer = encoder.createSerializer()
    val schema = encoder.schema

    val schemaJson = schemaToJson(schema)

    val cases = testCases.map { case (name, data) =>
      val row = serializer(data)
      val fields = schema.fields.zipWithIndex.map { case (field, i) =>
        val value = row.get(i, field.dataType)
        s""""${field.name}":${extractValue(value, field.dataType)}"""
      }
      s"""{
      "name": "$name",
      "serialized": {${fields.mkString(",")}}
    }"""
    }

    s"""{
  "typeName": "$typeName",
  "sparkVersion": "${org.apache.spark.SPARK_VERSION}",
  "generatedAt": "${Instant.now()}",
  "schema": $schemaJson,
  "testCases": [
    ${cases.mkString(",\n    ")}
  ]
}"""
  }

  /** Convert Spark schema to JSON */
  def schemaToJson(schema: StructType): String = {
    val fields = schema.fields.map { field =>
      s"""{"name":"${field.name}","type":"${dataTypeToString(field.dataType)}","nullable":${field.nullable}}"""
    }
    s"""{"fields":[${fields.mkString(",")}]}"""
  }

  def dataTypeToString(dt: DataType): String = dt match {
    case StringType      => "StringType"
    case IntegerType     => "IntType"
    case LongType        => "LongType"
    case DoubleType      => "DoubleType"
    case FloatType       => "FloatType"
    case BooleanType     => "BooleanType"
    case ByteType        => "ByteType"
    case ShortType       => "ShortType"
    case BinaryType      => "BinaryType"
    case DateType        => "DateType"
    case TimestampType   => "TimestampType"
    case TimestampNTZType => "TimestampNTZType"
    case ArrayType(et, cn) =>
      s"ArrayType(${dataTypeToString(et)},$cn)"
    case MapType(kt, vt, vcn) =>
      s"MapType(${dataTypeToString(kt)},${dataTypeToString(vt)},$vcn)"
    case st: StructType =>
      val fields = st.fields.map(f => s"${f.name}:${dataTypeToString(f.dataType)}").mkString(",")
      s"StructType($fields)"
    case dt: DecimalType => s"DecimalType(${dt.precision},${dt.scale})"
    case other => other.typeName
  }

  def main(args: Array[String]): Unit = {
    // Use class location to find the correct resource path
    val classLocation = getClass.getProtectionDomain.getCodeSource.getLocation.getPath
    val projectRoot = new File(classLocation).getParentFile.getParentFile.getParentFile.getParentFile
    val outputDir = new File(projectRoot, "src/main/resources/golden")
    outputDir.mkdirs()

    println(s"Generating golden files to: ${outputDir.getAbsolutePath}")

    // Simple
    val simpleJson = generate(
      "Simple",
      ExpressionEncoder[Simple](),
      Seq(("standard", BenchmarkData.simple))
    )
    writeFile(new File(outputDir, "simple.json"), simpleJson)
    println("  ✓ simple.json")

    // Address
    val addressJson = generate(
      "Address",
      ExpressionEncoder[Address](),
      Seq(("standard", BenchmarkData.address))
    )
    writeFile(new File(outputDir, "address.json"), addressJson)
    println("  ✓ address.json")

    // Person (nested)
    val personJson = generate(
      "Person",
      ExpressionEncoder[Person](),
      Seq(("standard", BenchmarkData.person))
    )
    writeFile(new File(outputDir, "person.json"), personJson)
    println("  ✓ person.json")

    // WithCollections
    val collectionsJson = generate(
      "WithCollections",
      ExpressionEncoder[WithCollections](),
      Seq(("standard", BenchmarkData.withCollections))
    )
    writeFile(new File(outputDir, "with_collections.json"), collectionsJson)
    println("  ✓ with_collections.json")

    // Team (List[Person])
    val teamJson = generate(
      "Team",
      ExpressionEncoder[Team](),
      Seq(("standard", BenchmarkData.team))
    )
    writeFile(new File(outputDir, "team.json"), teamJson)
    println("  ✓ team.json")

    // Complex
    val complexJson = generate(
      "Complex",
      ExpressionEncoder[Complex](),
      Seq(("standard", BenchmarkData.complex))
    )
    writeFile(new File(outputDir, "complex.json"), complexJson)
    println("  ✓ complex.json")

    println("\nDone! Copy these files to mock-runtime/src/test/resources/golden/")
  }

  private def writeFile(file: File, content: String): Unit = {
    val writer = new PrintWriter(file)
    try {
      writer.write(content)
    } finally {
      writer.close()
    }
  }
}
