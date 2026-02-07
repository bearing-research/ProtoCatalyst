package protocatalyst.catalyst

import io.circe.parser.parse
import org.apache.spark.sql.SparkSession

class SchemaValidatorSuite extends munit.FunSuite {

  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession
      .builder()
      .master("local[1]")
      .appName("SchemaValidatorTest")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.driver.host", "localhost")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()

    // Create test tables using SQL (avoids Spark 4.0 implicits issues)
    spark.sql(
      """CREATE OR REPLACE TEMP VIEW users AS
        |SELECT * FROM VALUES
        |  (1, 'Alice', 30, 80000.0, 'alice@example.com'),
        |  (2, 'Bob', 25, 65000.0, 'bob@example.com')
        |AS users(id, name, age, salary, email)""".stripMargin
    )
  }

  override def afterAll(): Unit = {
    if (spark != null) {
      spark.stop()
    }
  }

  private def makeArtifactBytes(json: String): Array[Byte] = {
    val header = Array[Byte]('P', 'C', 'A', 'T', 0x01)
    header ++ json.getBytes("UTF-8")
  }

  private val validArtifactJson =
    """{
      |  "schemaContracts": [{
      |    "relationName": "users",
      |    "requiredFields": [
      |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0},
      |      {"name": "name", "expectedType": "StringType", "expectedNullable": false, "position": 1},
      |      {"name": "age", "expectedType": "IntegerType", "expectedNullable": false, "position": 2}
      |    ],
      |    "fingerprint": 0
      |  }],
      |  "plan": {
      |    "$type": "RelationRef",
      |    "name": "users",
      |    "alias": null,
      |    "schemaContract": {
      |      "relationName": "users",
      |      "requiredFields": [],
      |      "fingerprint": 0
      |    }
      |  }
      |}""".stripMargin

  test("valid schema passes") {
    val bytes = makeArtifactBytes(validArtifactJson)
    val result = SchemaValidator.validate(bytes, spark)
    assert(result == SchemaValidator.Valid)
  }

  test("missing table fails") {
    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "nonexistent_table",
        |    "requiredFields": [
        |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    result match {
      case SchemaValidator.Invalid(errors) =>
        assert(errors.size == 1)
        assert(errors.head.isInstanceOf[SchemaValidator.TableNotFound])
        assert(errors.head.message.contains("nonexistent_table"))
      case SchemaValidator.Valid =>
        fail("Expected validation failure for missing table")
    }
  }

  test("missing field fails") {
    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "users",
        |    "requiredFields": [
        |      {"name": "nonexistent_field", "expectedType": "StringType", "expectedNullable": true, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    result match {
      case SchemaValidator.Invalid(errors) =>
        assert(errors.size == 1)
        assert(errors.head.isInstanceOf[SchemaValidator.FieldNotFound])
        assert(errors.head.message.contains("nonexistent_field"))
      case SchemaValidator.Valid =>
        fail("Expected validation failure for missing field")
    }
  }

  test("type mismatch fails") {
    // 'name' is StringType in the actual table, but we expect IntegerType
    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "users",
        |    "requiredFields": [
        |      {"name": "name", "expectedType": "IntegerType", "expectedNullable": false, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    result match {
      case SchemaValidator.Invalid(errors) =>
        assert(errors.size == 1)
        assert(errors.head.isInstanceOf[SchemaValidator.TypeMismatch])
        assert(errors.head.message.contains("IntegerType"))
      case SchemaValidator.Valid =>
        fail("Expected validation failure for type mismatch")
    }
  }

  test("widening compatible: Int field accepted when Long expected") {
    // age is IntegerType, expecting LongType should pass (widening)
    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "users",
        |    "requiredFields": [
        |      {"name": "age", "expectedType": "LongType", "expectedNullable": false, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    assert(result == SchemaValidator.Valid)
  }

  test("case-insensitive matching") {
    spark.sql(
      """CREATE OR REPLACE TEMP VIEW upper_table AS
        |SELECT * FROM VALUES (1, 'test') AS t(ID, NAME)""".stripMargin
    )

    val json =
      """{
        |  "schemaContracts": [{
        |    "relationName": "upper_table",
        |    "requiredFields": [
        |      {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0}
        |    ],
        |    "fingerprint": 0
        |  }]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    assert(result == SchemaValidator.Valid)
  }

  test("multiple contracts validated") {
    spark.sql(
      """CREATE OR REPLACE TEMP VIEW departments AS
        |SELECT * FROM VALUES (1, 'Engineering') AS t(id, name)""".stripMargin
    )

    val json =
      """{
        |  "schemaContracts": [
        |    {
        |      "relationName": "users",
        |      "requiredFields": [
        |        {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0}
        |      ],
        |      "fingerprint": 0
        |    },
        |    {
        |      "relationName": "departments",
        |      "requiredFields": [
        |        {"name": "id", "expectedType": "IntegerType", "expectedNullable": false, "position": 0},
        |        {"name": "name", "expectedType": "StringType", "expectedNullable": false, "position": 1}
        |      ],
        |      "fingerprint": 0
        |    }
        |  ]
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    assert(result == SchemaValidator.Valid)
  }

  test("validateFromJson works") {
    val json = parse(validArtifactJson).toOption.get
    val result = SchemaValidator.validateFromJson(json, spark)
    assert(result == SchemaValidator.Valid)
  }

  test("empty schema contracts passes") {
    val json =
      """{
        |  "schemaContracts": []
        |}""".stripMargin

    val bytes = makeArtifactBytes(json)
    val result = SchemaValidator.validate(bytes, spark)
    assert(result == SchemaValidator.Valid)
  }
}
