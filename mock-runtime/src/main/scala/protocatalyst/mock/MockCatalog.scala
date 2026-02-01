package protocatalyst.mock

import protocatalyst.schema.ProtoSchema

/** Mock table catalog that simulates Spark's catalog. Provides schemas for table names referenced
  * in queries.
  */
trait MockCatalog:
  def getTableSchema(tableName: String): Option[MockDataType.StructType]
  def tableExists(tableName: String): Boolean
  def listTables: Vector[String]

/** In-memory catalog implementation for testing.
  */
class InMemoryCatalog extends MockCatalog:
  private var tables: Map[String, MockDataType.StructType] = Map.empty

  def registerTable(name: String, schema: MockDataType.StructType): Unit =
    tables = tables + (name.toLowerCase -> schema)

  def registerTableFromProto(name: String, protoSchema: ProtoSchema): Unit =
    registerTable(name, MockSchemaConverter.toMockSchema(protoSchema))

  def getTableSchema(tableName: String): Option[MockDataType.StructType] =
    tables.get(tableName.toLowerCase)

  def tableExists(tableName: String): Boolean =
    tables.contains(tableName.toLowerCase)

  def listTables: Vector[String] = tables.keys.toVector

  def clear(): Unit =
    tables = Map.empty

object InMemoryCatalog:
  def apply(): InMemoryCatalog = new InMemoryCatalog()

  def withTables(tables: (String, MockDataType.StructType)*): InMemoryCatalog =
    val catalog = new InMemoryCatalog()
    tables.foreach((name, schema) => catalog.registerTable(name, schema))
    catalog
