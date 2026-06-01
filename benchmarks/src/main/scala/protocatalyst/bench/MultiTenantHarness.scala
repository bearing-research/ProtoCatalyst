package protocatalyst.bench

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/** Shared load generator for the multi-tenant derivation experiment (Scala 3 copy; a structurally
  * identical Scala 2.13 copy lives in `benchmark-spark` for the reflective side).
  *
  * `S` session threads each spin a tight derive loop over `requests` (one thunk per type). After a
  * warmup window, a measurement window counts derivations and samples per-request latency; we report
  * throughput and p50/p99 as `S` grows past the core count — the shape a server operator sees.
  */
object MultiTenantHarness:

  private val WarmupMs       = 2000L
  private val MeasureMs      = 5000L
  private val SessionCounts  = Array(1, 2, 4, 8, 16, 32)
  private val SampleCap      = 300000 // per-thread latency samples retained

  def run(label: String, requests: Array[() => AnyRef]): Unit =
    val cores = Runtime.getRuntime.availableProcessors
    println(s"# Multi-tenant derivation — $label")
    println(s"# cores=$cores  warmup=${WarmupMs}ms  measure=${MeasureMs}ms  types=${requests.length}")
    println(f"${"sessions"}%8s ${"thrpt(op/s)"}%14s ${"p50(us)"}%10s ${"p99(us)"}%10s ${"max(us)"}%10s")
    for s <- SessionCounts do
      val (thrpt, p50, p99, mx) = runOnce(s, requests)
      println(f"$s%8d $thrpt%14.0f $p50%10.1f $p99%10.1f $mx%10.1f")

  private def runOnce(sessions: Int, requests: Array[() => AnyRef]): (Double, Double, Double, Double) =
    val pool      = Executors.newFixedThreadPool(sessions)
    val startGate = new CountDownLatch(1)
    val ready     = new CountDownLatch(sessions)
    val stop      = new AtomicBoolean(false)
    val measuring = new AtomicBoolean(false)
    val totalOps  = new AtomicLong(0)
    val samples   = new Array[Array[Long]](sessions)

    val futures = (0 until sessions).map { tid =>
      pool.submit(new Runnable:
        def run(): Unit =
          val lat = new Array[Long](SampleCap)
          var li  = 0
          var i   = tid
          var localOps = 0L
          ready.countDown()
          startGate.await()
          while !stop.get() do
            val t0 = System.nanoTime()
            val r  = requests(i % requests.length)()
            val dt = System.nanoTime() - t0
            if r eq null then throw new AssertionError("derivation returned null")
            if measuring.get() then
              localOps += 1
              if li < SampleCap then { lat(li) = dt; li += 1 }
            i += 1
          totalOps.addAndGet(localOps)
          samples(tid) = java.util.Arrays.copyOf(lat, li)
      )
    }

    ready.await()
    startGate.countDown()
    Thread.sleep(WarmupMs)
    measuring.set(true)
    val measStart = System.nanoTime()
    Thread.sleep(MeasureMs)
    measuring.set(false)
    val elapsed = (System.nanoTime() - measStart) / 1e9
    stop.set(true)
    futures.foreach(_.get())
    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.SECONDS)

    val all = samples.filter(_ != null).flatten
    java.util.Arrays.sort(all)
    val thrpt = totalOps.get() / elapsed
    def pctUs(p: Double): Double =
      if all.isEmpty then 0.0 else all(math.min(all.length - 1, (p * all.length).toInt)) / 1000.0
    val maxUs = if all.isEmpty then 0.0 else all(all.length - 1) / 1000.0
    (thrpt, pctUs(0.50), pctUs(0.99), maxUs)
