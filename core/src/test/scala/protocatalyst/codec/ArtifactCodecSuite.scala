package protocatalyst.codec

import protocatalyst.artifact.{ArtifactVersion, CompiledArtifact, SourceInfo}
import protocatalyst.codec.{ArtifactCodec => CodecImpl}
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class ArtifactCodecSuite extends munit.FunSuite:

  // === Magic Header Tests ===

  test("serialize with header produces magic bytes"):
    val artifact = simpleArtifact
    val bytes = CodecImpl.serializeWithHeader(artifact)

    // Check magic bytes: "PCAT" + format byte
    assertEquals[String, String](new String(bytes.take(4), "UTF-8"), "PCAT")
    assertEquals[Byte, Byte](bytes(4), 0x01.toByte) // JSON format

  test("deserialize with valid header succeeds"):
    val artifact = simpleArtifact
    val bytes = CodecImpl.serializeWithHeader(artifact)
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isRight, "Expected Right")
    assertEquals(result.toOption.get.plan, artifact.plan)

  test("roundtrip through header serialization"):
    val artifact = complexArtifact
    val bytes = CodecImpl.serializeWithHeader(artifact)
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isRight, "Expected Right")
    assertEquals(result.toOption.get, artifact)

  // === Error Handling Tests ===

  test("deserialize too short returns error"):
    val bytes = Array[Byte]('P', 'C')
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isLeft, "Expected Left")
    assertEquals[String, String](result.left.getOrElse(""), "Invalid artifact: too short")

  test("deserialize invalid magic returns error"):
    val bytes = Array[Byte]('X', 'X', 'X', 'X', 0x01) ++ "{}".getBytes("UTF-8")
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isLeft, "Expected Left")
    assertEquals[String, String](result.left.getOrElse(""), "Invalid magic header")

  test("deserialize unknown format byte returns error"):
    val magic = "PCAT".getBytes("UTF-8")
    val bytes = magic ++ Array[Byte](0x99.toByte) ++ "{}".getBytes("UTF-8")
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isLeft, "Expected Left")
    assert(
      result.left.getOrElse("").contains("Unknown format byte"),
      "Expected unknown format error"
    )

  test("deserialize protobuf format byte returns not implemented"):
    val magic = "PCAT".getBytes("UTF-8")
    val bytes = magic ++ Array[Byte](0x02.toByte) ++ "{}".getBytes("UTF-8")
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isLeft, "Expected Left")
    assertEquals[String, String](result.left.getOrElse(""), "Protobuf codec not yet implemented")

  test("deserialize corrupted payload returns error"):
    val magic = "PCAT".getBytes("UTF-8")
    val bytes = magic ++ Array[Byte](0x01.toByte) ++ "not json".getBytes("UTF-8")
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isLeft, "Expected Left")
    assert(result.left.getOrElse("").contains("JSON deserialization failed"), "Expected JSON error")

  // === Default Codec Tests ===

  test("default codec is JSON"):
    assertEquals(CodecImpl.default.format, "json")

  test("default codec serializes correctly"):
    val artifact = simpleArtifact
    val bytes = CodecImpl.default.serialize(artifact)
    val result = CodecImpl.default.deserialize(bytes)

    assert(result.isRight, "Expected Right")
    assertEquals(result.toOption.get.plan, artifact.plan)

  // === Unknown Codec Format Tests ===

  test("serializeWithHeader rejects unknown format"):
    val unknownCodec = new ArtifactCodec:
      def format: String = "unknown"
      def serialize(artifact: CompiledArtifact): Array[Byte] = Array.emptyByteArray
      def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact] =
        Left("not implemented")

    val artifact = simpleArtifact
    interceptMessage[IllegalArgumentException]("Unknown codec format: unknown"):
      CodecImpl.serializeWithHeader(artifact, unknownCodec)

  // === Artifact Convenience Object Tests ===

  test("protocatalyst.artifact.ArtifactCodec.serialize delegates correctly"):
    val artifact = simpleArtifact
    val bytes = protocatalyst.artifact.ArtifactCodec.serialize(artifact)

    // Should have magic header
    assertEquals[String, String](new String(bytes.take(4), "UTF-8"), "PCAT")

  test("protocatalyst.artifact.ArtifactCodec.deserialize delegates correctly"):
    val artifact = simpleArtifact
    val bytes = protocatalyst.artifact.ArtifactCodec.serialize(artifact)
    val result = protocatalyst.artifact.ArtifactCodec.deserialize(bytes)

    assert(result.isRight, "Expected Right")
    assertEquals(result.toOption.get.plan, artifact.plan)

  // === Edge Cases ===

  test("roundtrip empty schema contracts"):
    val artifact = simpleArtifact.copy(schemaContracts = Vector.empty)
    val bytes = CodecImpl.serializeWithHeader(artifact)
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isRight, "Expected Right")
    assertEquals(result.toOption.get.schemaContracts, Vector.empty)

  test("roundtrip with all metadata fields"):
    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion(1, 2, 3),
      protocatalystVersion = "0.1.0-SNAPSHOT",
      compiledAt = 1700000000000L,
      contentHash = Long.MaxValue,
      schemaContracts = Vector(
        SchemaContract(
          "table1",
          Vector(FieldContract("col", ProtoType.StringType, true, 0)),
          SchemaFingerprint.fromLong(123456L)
        )
      ),
      plan = baseRelation,
      outputSchema =
        ProtoSchema(Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))),
      sourceInfo = Some(SourceInfo("file.scala", 100, Some("SELECT * FROM t")))
    )

    val bytes = CodecImpl.serializeWithHeader(artifact)
    val result = CodecImpl.deserializeWithHeader(bytes)

    assert(result.isRight, "Expected Right")
    val restored = result.toOption.get
    assertEquals(restored.formatVersion, artifact.formatVersion)
    assertEquals(restored.protocatalystVersion, artifact.protocatalystVersion)
    assertEquals(restored.compiledAt, artifact.compiledAt)
    assertEquals(restored.contentHash, artifact.contentHash)
    assertEquals(restored.schemaContracts, artifact.schemaContracts)
    assertEquals(restored.sourceInfo, artifact.sourceInfo)

  test("roundtrip preserves exact bytes for payload"):
    val artifact = simpleArtifact
    val bytes1 = CodecImpl.serializeWithHeader(artifact)
    val result = CodecImpl.deserializeWithHeader(bytes1)
    assert(result.isRight, "Expected Right")
    val bytes2 = CodecImpl.serializeWithHeader(result.toOption.get)

    // JSON serialization should be deterministic
    assert(bytes1.sameElements(bytes2), "Bytes should be identical")

  // === Helper Methods ===

  private val baseRelation = ProtoLogicalPlan.RelationRef(
    "test_table",
    None,
    SchemaContract(
      "test_table",
      Vector(FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0)),
      SchemaFingerprint.fromLong(99999L)
    )
  )

  private val simpleArtifact = CompiledArtifact(
    formatVersion = ArtifactVersion.current,
    protocatalystVersion = "0.1.0",
    compiledAt = 1700000000000L,
    contentHash = 12345L,
    schemaContracts = Vector.empty,
    plan = baseRelation,
    outputSchema =
      ProtoSchema(Vector(ProtoStructField("id", ProtoType.LongType, nullable = false))),
    sourceInfo = None
  )

  private val complexArtifact = CompiledArtifact(
    formatVersion = ArtifactVersion.current,
    protocatalystVersion = "0.1.0",
    compiledAt = 1700000000000L,
    contentHash = 12345L,
    schemaContracts = Vector(
      SchemaContract(
        "users",
        Vector(
          FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0),
          FieldContract("name", ProtoType.StringType, expectedNullable = true, position = 1)
        ),
        SchemaFingerprint.fromLong(11111L)
      )
    ),
    plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
        ProtoExpr.Alias(
          ProtoExpr.Upper(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)),
          "upper_name"
        )
      ),
      ProtoLogicalPlan.Filter(
        ProtoExpr.Gt(
          ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
          ProtoExpr.lit(0)
        ),
        baseRelation
      )
    ),
    outputSchema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.LongType, nullable = false),
        ProtoStructField("upper_name", ProtoType.StringType, nullable = true)
      )
    ),
    sourceInfo = Some(
      SourceInfo(
        "query.scala",
        42,
        Some("SELECT id, UPPER(name) AS upper_name FROM users WHERE id > 0")
      )
    )
  )
