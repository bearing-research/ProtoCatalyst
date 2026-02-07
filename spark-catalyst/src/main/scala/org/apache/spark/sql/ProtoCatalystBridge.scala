package org.apache.spark.sql

import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.classic.{Dataset => ClassicDataset}

/** Bridge to access Spark-internal APIs from ProtoCatalyst.
  *
  * `Dataset.ofRows` is `private[sql]`, so this bridge class — placed in the `org.apache.spark.sql`
  * package — provides access for ProtoCatalyst's query runner.
  */
object ProtoCatalystBridge {

  /** Create a DataFrame from an unresolved LogicalPlan.
    *
    * Spark's analyzer will resolve table/column names against the catalog.
    */
  def createDataFrame(spark: SparkSession, plan: LogicalPlan): DataFrame = {
    ClassicDataset.ofRows(spark.asInstanceOf[classic.SparkSession], plan)
  }
}
