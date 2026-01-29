package protocatalyst.spark

import protocatalyst.encoder.ProtoEncoder
import scala.deriving.Mirror

/** Spark encoder integration - entry point for users. */
object Encoders:

  /** Create a Spark-compatible encoder from ProtoEncoder. */
  inline def encoder[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    ProtoEncoder.derived[T]

  // Future: Convert to actual Spark Encoder
  // def toSparkEncoder[T](proto: ProtoEncoder[T]): org.apache.spark.sql.Encoder[T] = ???
