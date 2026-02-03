package protocatalyst.artifact

class ArtifactVersionSuite extends munit.FunSuite:

  // === Version Creation Tests ===

  test("version creates with correct fields"):
    val version = ArtifactVersion(1, 2, 3)
    assertEquals(version.major, 1)
    assertEquals(version.minor, 2)
    assertEquals(version.patch, 3)

  test("version.current is defined"):
    val current = ArtifactVersion.current
    assertEquals(current.major, 1)
    assertEquals(current.minor, 0)
    assertEquals(current.patch, 0)

  // === Version toString Tests ===

  test("toString formats correctly"):
    val v1 = ArtifactVersion(1, 0, 0)
    val v2 = ArtifactVersion(2, 3, 4)
    val v3 = ArtifactVersion(10, 20, 30)

    assertEquals(v1.toString, "1.0.0")
    assertEquals(v2.toString, "2.3.4")
    assertEquals(v3.toString, "10.20.30")

  test("toString with zero components"):
    val v = ArtifactVersion(0, 0, 0)
    assertEquals(v.toString, "0.0.0")

  // === Version Compatibility Tests ===

  test("same version is compatible"):
    val v1 = ArtifactVersion(1, 2, 3)
    val v2 = ArtifactVersion(1, 2, 3)
    assert(v1.isCompatibleWith(v2))
    assert(v2.isCompatibleWith(v1))

  test("same major, higher minor is compatible"):
    val older = ArtifactVersion(1, 0, 0)
    val newer = ArtifactVersion(1, 5, 0)
    // newer should be compatible with older (has higher minor)
    assert(newer.isCompatibleWith(older))
    // older is NOT compatible with newer (lower minor)
    assert(!older.isCompatibleWith(newer))

  test("same major and minor, different patch is compatible"):
    val v1 = ArtifactVersion(1, 2, 0)
    val v2 = ArtifactVersion(1, 2, 5)
    // Both are compatible with each other (same major and minor >= minor)
    assert(v1.isCompatibleWith(v2))
    assert(v2.isCompatibleWith(v1))

  test("different major is not compatible"):
    val v1 = ArtifactVersion(1, 5, 0)
    val v2 = ArtifactVersion(2, 0, 0)
    assert(!v1.isCompatibleWith(v2))
    assert(!v2.isCompatibleWith(v1))

  test("major 0 to major 1 is not compatible"):
    val v0 = ArtifactVersion(0, 9, 9)
    val v1 = ArtifactVersion(1, 0, 0)
    assert(!v0.isCompatibleWith(v1))
    assert(!v1.isCompatibleWith(v0))

  test("compatibility edge cases"):
    // v1.1.0 is compatible with v1.0.0 (same major, higher minor)
    val v100 = ArtifactVersion(1, 0, 0)
    val v110 = ArtifactVersion(1, 1, 0)
    assert(v110.isCompatibleWith(v100))
    assert(!v100.isCompatibleWith(v110))

    // v1.0.1 is compatible with v1.0.0 (same major and minor)
    val v101 = ArtifactVersion(1, 0, 1)
    assert(v101.isCompatibleWith(v100))
    assert(v100.isCompatibleWith(v101))

  test("compatibility with version 0.x"):
    // Even in 0.x, same major is required
    val v010 = ArtifactVersion(0, 1, 0)
    val v020 = ArtifactVersion(0, 2, 0)
    assert(v020.isCompatibleWith(v010))
    assert(!v010.isCompatibleWith(v020))

  // === Version Equality Tests ===

  test("version equality"):
    val v1 = ArtifactVersion(1, 2, 3)
    val v2 = ArtifactVersion(1, 2, 3)
    val v3 = ArtifactVersion(1, 2, 4)

    assertEquals(v1, v2)
    assertNotEquals(v1, v3)

  test("version equality with different components"):
    val v1 = ArtifactVersion(1, 0, 0)
    val v2 = ArtifactVersion(0, 1, 0)
    val v3 = ArtifactVersion(0, 0, 1)

    assertNotEquals(v1, v2)
    assertNotEquals(v2, v3)
    assertNotEquals(v1, v3)

  // === SourceInfo Tests ===

  test("SourceInfo creation"):
    val info = SourceInfo("file.scala", 42, Some("SELECT * FROM t"))
    assertEquals(info.sourceFile, "file.scala")
    assertEquals(info.lineNumber, 42)
    assertEquals(info.originalSql, Some("SELECT * FROM t"))

  test("SourceInfo without SQL"):
    val info = SourceInfo("file.scala", 10, None)
    assertEquals(info.sourceFile, "file.scala")
    assertEquals(info.lineNumber, 10)
    assertEquals(info.originalSql, None)

  test("SourceInfo equality"):
    val info1 = SourceInfo("a.scala", 1, Some("sql"))
    val info2 = SourceInfo("a.scala", 1, Some("sql"))
    val info3 = SourceInfo("b.scala", 1, Some("sql"))

    assertEquals(info1, info2)
    assertNotEquals(info1, info3)

  // === CompiledArtifact Tests ===

  test("CompiledArtifact creation with all fields"):
    import protocatalyst.plan.*
    import protocatalyst.schema.*
    import protocatalyst.types.*

    val schema = ProtoSchema(Vector(ProtoStructField("id", ProtoType.LongType, nullable = false)))
    val contract = SchemaContract("t", Vector.empty, SchemaFingerprint.fromLong(0L))
    val plan = ProtoLogicalPlan.RelationRef("t", None, contract)

    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion(1, 0, 0),
      protocatalystVersion = "0.1.0",
      compiledAt = 1700000000000L,
      contentHash = 12345L,
      schemaContracts = Vector(contract),
      plan = plan,
      outputSchema = schema,
      sourceInfo = Some(SourceInfo("test.scala", 1, None))
    )

    assertEquals(artifact.formatVersion.major, 1)
    assertEquals(artifact.protocatalystVersion, "0.1.0")
    assertEquals(artifact.compiledAt, 1700000000000L)
    assertEquals(artifact.contentHash, 12345L)
    assertEquals(artifact.schemaContracts.size, 1)
    assert(artifact.sourceInfo.isDefined)

  test("CompiledArtifact with empty contracts"):
    import protocatalyst.plan.*
    import protocatalyst.schema.*
    import protocatalyst.types.*

    val schema = ProtoSchema(Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false)))
    val contract = SchemaContract("t", Vector.empty, SchemaFingerprint.fromLong(0L))
    val plan = ProtoLogicalPlan.RelationRef("t", None, contract)

    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0",
      compiledAt = 0L,
      contentHash = 0L,
      schemaContracts = Vector.empty,
      plan = plan,
      outputSchema = schema,
      sourceInfo = None
    )

    assertEquals(artifact.schemaContracts, Vector.empty)
    assertEquals(artifact.sourceInfo, None)

  // === Version Boundary Tests ===

  test("version with large numbers"):
    val v = ArtifactVersion(999, 999, 999)
    assertEquals(v.toString, "999.999.999")
    assertEquals(v.major, 999)
    assertEquals(v.minor, 999)
    assertEquals(v.patch, 999)

  test("compatibility transitivity"):
    // If A is compatible with B, and B is compatible with C,
    // then A should be compatible with C (for same major)
    val v100 = ArtifactVersion(1, 0, 0)
    val v150 = ArtifactVersion(1, 5, 0)
    val v190 = ArtifactVersion(1, 9, 0)

    assert(v150.isCompatibleWith(v100))
    assert(v190.isCompatibleWith(v150))
    assert(v190.isCompatibleWith(v100)) // transitive
