package org.apache.spark.sql.protocatalyst

import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.benchmark.tpch.Schemas._

/** Cold-start probe for Spark's reflective derivation in a *fresh* JVM. The first
  * `ExpressionEncoder[T: TypeTag]()` call forces `scala.reflect.runtime.universe` to build its symbol
  * table (a one-time cost paid once per JVM) on top of the derivation itself; subsequent calls reuse
  * the warm universe. Reports cold-first vs warm-steady so the one-time init cost is visible.
  *
  * Run (one number per fresh JVM): {{{
  * sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.ColdStartProbe' }}}
  */
object ColdStartProbe {
  def main(args: Array[String]): Unit = {
    val t0 = System.nanoTime()
    val e1 = ExpressionEncoder[Lineitem]() // cold: triggers universe init + class loading + derivation
    val t1 = System.nanoTime()
    val e2 = ExpressionEncoder[Orders]()   // distinct type, universe now warm
    val t2 = System.nanoTime()
    if ((e1 eq null) || (e2 eq null)) throw new AssertionError()

    // Warm steady-state: many derivations of an already-seen type.
    val warm = new Array[Long](200)
    var i = 0
    while (i < warm.length) {
      val a = System.nanoTime()
      val e = ExpressionEncoder[Lineitem]()
      if (e eq null) throw new AssertionError()
      warm(i) = System.nanoTime() - a
      i += 1
    }
    java.util.Arrays.sort(warm)
    val warmMedMs = warm(warm.length / 2) / 1e6

    println(f"cold first derivation (incl. scala.reflect.runtime.universe init): ${(t1 - t0) / 1e6}%9.1f ms")
    println(f"second derivation (distinct type, universe warm):                 ${(t2 - t1) / 1e6}%9.1f ms")
    println(f"warm steady-state median (same type):                             $warmMedMs%9.3f ms")
    println(f"=> one-time cold-start overhead (cold - warm):                    ${(t1 - t0) / 1e6 - warmMedMs}%9.1f ms")
  }
}
