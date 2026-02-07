package protocatalyst.catalyst

import java.nio.file.{Files, Paths}

import org.apache.spark.sql.SparkSession

/** Integration tests for SparkQueryRunner — executes ProtoCatalyst compiled artifacts as real Spark
  * DataFrames against test data.
  */
class SparkQueryRunnerSuite extends munit.FunSuite {

  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession
      .builder()
      .master("local[1]")
      .appName("SparkQueryRunnerTest")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.driver.host", "localhost")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()

    // Create test data using SQL (avoids Spark 4.0 implicits issues)
    // users(id: Int, name: String, age: Int, salary: Double, email: String)
    spark.sql(
      """CREATE OR REPLACE TEMP VIEW users AS
        |SELECT * FROM VALUES
        |  (1, 'Alice', 30, 80000.0, 'alice@example.com'),
        |  (2, 'Bob', 25, 65000.0, 'bob@example.com'),
        |  (3, 'Carol', 35, 90000.0, CAST(NULL AS STRING)),
        |  (4, 'Dave', 22, 55000.0, 'dave@example.com'),
        |  (5, 'Eve', 28, 75000.0, 'eve@example.com')
        |AS users(id, name, age, salary, email)""".stripMargin
    )
  }

  override def afterAll(): Unit = {
    if (spark != null) {
      spark.stop()
    }
  }

  // === Helper methods ===

  private def makeArtifactBytes(json: String): Array[Byte] = {
    val header = Array[Byte]('P', 'C', 'A', 'T', 0x01)
    header ++ json.getBytes("UTF-8")
  }

  private def loadArtifactJson(name: String): Option[String] = {
    val possiblePaths = Seq(
      s"spark-catalyst/src/test/resources/parity-artifacts/$name.json",
      s"src/test/resources/parity-artifacts/$name.json",
      s"../spark-catalyst/src/test/resources/parity-artifacts/$name.json"
    )
    possiblePaths
      .map(Paths.get(_))
      .find(Files.exists(_))
      .map(Files.readString(_))
  }

  // === Execution from resource artifacts ===

  test("execute select_columns artifact") {
    loadArtifactJson("select_columns").foreach { json =>
      val df = SparkQueryRunner.executeFromJson(json, spark)
      val columns = df.columns.toSeq
      assert(columns.contains("name"), s"Expected 'name' column, got: $columns")
      assert(columns.contains("age"), s"Expected 'age' column, got: $columns")
      assert(df.count() == 5)
    }
  }

  test("execute where_gt artifact") {
    loadArtifactJson("where_gt").foreach { json =>
      val df = SparkQueryRunner.executeFromJson(json, spark)
      // SELECT name FROM users WHERE age > 18 — all 5 users are > 18
      assert(df.count() == 5)
    }
  }

  test("execute limit_simple artifact") {
    loadArtifactJson("limit_simple").foreach { json =>
      val df = SparkQueryRunner.executeFromJson(json, spark)
      // SELECT name FROM users LIMIT 10 — we have 5 users, so all returned
      assert(df.count() <= 10)
      assert(df.count() == 5)
    }
  }

  test("execute distinct_simple artifact") {
    loadArtifactJson("distinct_simple").foreach { json =>
      val df = SparkQueryRunner.executeFromJson(json, spark)
      // SELECT DISTINCT name FROM users — all names are unique
      assert(df.count() == 5)
    }
  }

  test("execute arithmetic_add artifact") {
    loadArtifactJson("arithmetic_add").foreach { json =>
      val df = SparkQueryRunner.executeFromJson(json, spark)
      // SELECT salary + 1000 FROM users
      assert(df.count() == 5)
      // Verify all values are non-null and numeric (type may vary)
      val values = df.collect().map(r => r.get(0).toString.toDouble).sorted
      // salary values: 55000, 65000, 75000, 80000, 90000 → +1000 each
      assert(values.toSeq == Seq(56000.0, 66000.0, 76000.0, 81000.0, 91000.0))
    }
  }

  // === Inline artifact tests ===

  test("execute simple select") {
    val json =
      """{
        |  "plan": {
        |    "$type": "Project",
        |    "projectList": [
        |      {"$type":"ColumnRef","name":"name","qualifier":null,"resolvedType":"StringType","nullable":false}
        |    ],
        |    "child": {
        |      "$type": "RelationRef",
        |      "name": "users",
        |      "alias": null,
        |      "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |    }
        |  }
        |}""".stripMargin

    val df = SparkQueryRunner.executeFromJson(json, spark)
    assert(df.columns.toSeq == Seq("name"))
    assert(df.count() == 5)
    val names = df.collect().map(_.getString(0)).sorted.toSeq
    assert(names == Seq("Alice", "Bob", "Carol", "Dave", "Eve"))
  }

  test("execute filter with age > 25") {
    val json =
      """{
        |  "plan": {
        |    "$type": "Filter",
        |    "condition": {
        |      "$type": "Gt",
        |      "left": {"$type":"ColumnRef","name":"age","qualifier":null,"resolvedType":"IntegerType","nullable":false},
        |      "right": {"$type":"Literal","value":{"$type":"IntValue","value":25}}
        |    },
        |    "child": {
        |      "$type": "RelationRef",
        |      "name": "users",
        |      "alias": null,
        |      "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |    }
        |  }
        |}""".stripMargin

    val df = SparkQueryRunner.executeFromJson(json, spark)
    // age > 25: Alice(30), Carol(35), Eve(28)
    assert(df.count() == 3)
    val names = df.collect().map(_.getAs[String]("name")).sorted.toSeq
    assert(names == Seq("Alice", "Carol", "Eve"))
  }

  test("execute order by salary desc") {
    val json =
      """{
        |  "plan": {
        |    "$type": "Sort",
        |    "order": [{
        |      "child": {"$type":"ColumnRef","name":"salary","qualifier":null,"resolvedType":"DoubleType","nullable":true},
        |      "direction": "Descending",
        |      "nullOrdering": "NullsFirst"
        |    }],
        |    "child": {
        |      "$type": "Project",
        |      "projectList": [
        |        {"$type":"ColumnRef","name":"name","qualifier":null,"resolvedType":"StringType","nullable":false},
        |        {"$type":"ColumnRef","name":"salary","qualifier":null,"resolvedType":"DoubleType","nullable":true}
        |      ],
        |      "child": {
        |        "$type": "RelationRef",
        |        "name": "users",
        |        "alias": null,
        |        "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |      }
        |    }
        |  }
        |}""".stripMargin

    val df = SparkQueryRunner.executeFromJson(json, spark)
    val rows = df.collect()
    assert(rows.length == 5)
    // First row should be Carol (highest salary: 90000)
    assert(rows(0).getAs[String]("name") == "Carol")
    // Last row should be Dave (lowest salary: 55000)
    assert(rows(4).getAs[String]("name") == "Dave")
  }

  test("execute limit 2") {
    val json =
      """{
        |  "plan": {
        |    "$type": "Limit",
        |    "limit": 2,
        |    "child": {
        |      "$type": "RelationRef",
        |      "name": "users",
        |      "alias": null,
        |      "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |    }
        |  }
        |}""".stripMargin

    val df = SparkQueryRunner.executeFromJson(json, spark)
    assert(df.count() == 2)
  }

  // === PCAT bytes execution ===

  test("execute from PCAT bytes") {
    val json =
      """{
        |  "plan": {
        |    "$type": "RelationRef",
        |    "name": "users",
        |    "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |  }
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val df = SparkQueryRunner.execute(bytes, spark)
    assert(df.count() == 5)
  }

  // === Plan inspection ===

  test("toPlan returns LogicalPlan") {
    val json =
      """{
        |  "plan": {
        |    "$type": "RelationRef",
        |    "name": "users",
        |    "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |  }
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SparkQueryRunner.toPlan(bytes)
    assert(result.isRight)
    assert(result.toOption.get.toString.contains("users"))
  }

  test("toPlanFromJson returns LogicalPlan") {
    val json =
      """{
        |  "plan": {
        |    "$type": "RelationRef",
        |    "name": "users",
        |    "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |  }
        |}""".stripMargin

    val result = SparkQueryRunner.toPlanFromJson(json)
    assert(result.isRight)
  }

  // === Metadata inspection ===

  test("inspectArtifact extracts metadata") {
    val json =
      """{
        |  "compiledAt": 1700000000000,
        |  "contentHash": 12345,
        |  "schemaContracts": [{
        |    "relationName": "users",
        |    "requiredFields": [
        |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0},
        |      {"name": "name", "expectedType": "StringType", "expectedNullable": false, "position": 1}
        |    ],
        |    "fingerprint": 0
        |  }],
        |  "outputSchema": {
        |    "fields": [
        |      {"name": "id", "dataType": "IntegerType", "nullable": false},
        |      {"name": "name", "dataType": "StringType", "nullable": false}
        |    ]
        |  },
        |  "sourceInfo": {
        |    "originalSql": "SELECT id, name FROM users"
        |  },
        |  "plan": {"$type": "RelationRef", "name": "users", "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}}
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SparkQueryRunner.inspectArtifact(bytes)
    assert(result.isRight)

    val meta = result.toOption.get
    assert(meta.compiledAt == 1700000000000L)
    assert(meta.contentHash == 12345L)
    assert(meta.schemaContracts.size == 1)
    assert(meta.schemaContracts.head.relationName == "users")
    assert(meta.schemaContracts.head.requiredFields.size == 2)
    assert(meta.outputSchemaFields.size == 2)
    assert(meta.originalSql == Some("SELECT id, name FROM users"))
  }

  test("inspectArtifactFromJson works") {
    val json =
      """{
        |  "compiledAt": 1700000000000,
        |  "contentHash": 999,
        |  "schemaContracts": [],
        |  "plan": {"$type": "RelationRef", "name": "users", "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}}
        |}""".stripMargin

    val result = SparkQueryRunner.inspectArtifactFromJson(json)
    assert(result.isRight)
    assert(result.toOption.get.contentHash == 999L)
  }

  // === Schema validation integration ===

  test("execute with validateSchema=true succeeds for valid table") {
    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "users",
        |    "requiredFields": [
        |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0},
        |      {"name": "name", "expectedType": "StringType", "expectedNullable": false, "position": 1}
        |    ],
        |    "fingerprint": 0
        |  }],
        |  "plan": {
        |    "$type": "RelationRef",
        |    "name": "users",
        |    "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |  }
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val config = SparkQueryRunner.ExecutionConfig(validateSchema = true)
    val df = SparkQueryRunner.execute(bytes, spark, config)
    assert(df.count() == 5)
  }

  test("execute with validateSchema=true fails for missing table") {
    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "nonexistent_table",
        |    "requiredFields": [
        |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }],
        |  "plan": {
        |    "$type": "RelationRef",
        |    "name": "nonexistent_table",
        |    "alias": null,
        |    "schemaContract": {"relationName":"nonexistent_table","requiredFields":[],"fingerprint":0}
        |  }
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val config = SparkQueryRunner.ExecutionConfig(validateSchema = true)
    val ex = intercept[ProtoCatalystExecutionException] {
      SparkQueryRunner.execute(bytes, spark, config)
    }
    assert(ex.getMessage.contains("Schema validation failed"))
    assert(ex.getMessage.contains("nonexistent_table"))
  }

  // === Error handling ===

  test("invalid PCAT bytes fail gracefully") {
    val bytes = Array[Byte](1, 2, 3, 4, 5)
    val result = SparkQueryRunner.toPlan(bytes)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Invalid magic header"))
  }

  test("too-short bytes fail gracefully") {
    val bytes = Array[Byte]('P', 'C')
    val result = SparkQueryRunner.toPlan(bytes)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("too short"))
  }

  test("invalid JSON in PCAT bytes fails gracefully") {
    val bytes = makeArtifactBytes("not valid json {{{")
    val result = SparkQueryRunner.toPlan(bytes)
    assert(result.isLeft)
  }

  test("executeFromJson with invalid JSON throws") {
    val ex = intercept[ProtoCatalystExecutionException] {
      SparkQueryRunner.executeFromJson("not valid json", spark)
    }
    assert(ex.getMessage.contains("Failed to parse plan"))
  }

  // === implicits ===

  test("implicits extension methods work") {
    import protocatalyst.catalyst.implicits._

    val json =
      """{
        |  "plan": {
        |    "$type": "RelationRef",
        |    "name": "users",
        |    "alias": null,
        |    "schemaContract": {"relationName":"users","requiredFields":[],"fingerprint":0}
        |  }
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val df = spark.executeArtifact(bytes)
    assert(df.count() == 5)
  }

  test("implicits validateArtifact works") {
    import protocatalyst.catalyst.implicits._

    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "users",
        |    "requiredFields": [
        |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = spark.validateArtifact(bytes)
    assert(result == SchemaValidator.Valid)
  }
}
