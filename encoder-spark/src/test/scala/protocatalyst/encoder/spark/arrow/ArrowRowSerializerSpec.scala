package protocatalyst.encoder.spark.arrow

import java.nio.charset.StandardCharsets

import munit.FunSuite
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{
  BigIntVector,
  BitVector,
  Float4Vector,
  Float8Vector,
  IntVector,
  LargeVarCharVector,
  SmallIntVector,
  TinyIntVector,
  VarCharVector
}

/** Per-row writer smoke tests for [[ArrowRowSerializer]] (Phase A4 — primitives + String).
  *
  * These verify that values round-trip through Arrow vectors at the macro-emitted writer layer.
  * Byte-level parity against Spark's `ArrowSerializer[T]` (full IPC stream) lives in A7.
  */
class ArrowRowSerializerSpec extends FunSuite:

  case class Simple(id: Int, name: String)
  case class AllPrimitives(
      b: Boolean,
      i8: Byte,
      i16: Short,
      i32: Int,
      i64: Long,
      f32: Float,
      f64: Double
  )

  private def withSerializer[T](
      makeSer: RootAllocator => ArrowRowSerializer[T]
  )(body: ArrowRowSerializer[T] => Unit): Unit = {
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = makeSer(alloc)
    try body(ser)
    finally
      ser.close()
      alloc.close()
  }

  // ---------------------------------------------------------------------------
  // Simple(Int, String): basic round-trip + null handling.
  // ---------------------------------------------------------------------------

  test("Simple: append 3 rows and read back via vectors"):
    withSerializer[Simple](ArrowRowSerializer.derived[Simple](_)) { ser =>
      ser.append(Simple(1, "alice"))
      ser.append(Simple(2, "bob"))
      ser.append(Simple(3, "carol"))
      assertEquals(ser.rowCount, 3)

      val id = ser.root.getVector(0).asInstanceOf[IntVector]
      val name = ser.root.getVector(1).asInstanceOf[VarCharVector]
      assertEquals(id.get(0), 1)
      assertEquals(id.get(1), 2)
      assertEquals(id.get(2), 3)
      assertEquals(new String(name.get(0), StandardCharsets.UTF_8), "alice")
      assertEquals(new String(name.get(1), StandardCharsets.UTF_8), "bob")
      assertEquals(new String(name.get(2), StandardCharsets.UTF_8), "carol")
    }

  test("Simple: null String → setNull"):
    withSerializer[Simple](ArrowRowSerializer.derived[Simple](_)) { ser =>
      ser.append(Simple(7, null))
      val name = ser.root.getVector(1).asInstanceOf[VarCharVector]
      assert(name.isNull(0), "name vector should be null at index 0")
    }

  // ---------------------------------------------------------------------------
  // All primitives — verify each Arrow vector subtype gets the right value.
  // ---------------------------------------------------------------------------

  test("AllPrimitives: each unboxed primitive lands in the right Arrow vector"):
    withSerializer[AllPrimitives](ArrowRowSerializer.derived[AllPrimitives](_)) { ser =>
      ser.append(
        AllPrimitives(
          b = true,
          i8 = -7,
          i16 = 12345,
          i32 = Int.MinValue,
          i64 = Long.MaxValue,
          f32 = 1.5f,
          f64 = 3.14159
        )
      )
      assertEquals(ser.root.getVector(0).asInstanceOf[BitVector].get(0), 1)
      assertEquals(ser.root.getVector(1).asInstanceOf[TinyIntVector].get(0), -7: Byte)
      assertEquals(ser.root.getVector(2).asInstanceOf[SmallIntVector].get(0), 12345: Short)
      assertEquals(ser.root.getVector(3).asInstanceOf[IntVector].get(0), Int.MinValue)
      assertEquals(ser.root.getVector(4).asInstanceOf[BigIntVector].get(0), Long.MaxValue)
      assertEquals(ser.root.getVector(5).asInstanceOf[Float4Vector].get(0), 1.5f)
      assertEquals(ser.root.getVector(6).asInstanceOf[Float8Vector].get(0), 3.14159)
    }

  // ---------------------------------------------------------------------------
  // Lifecycle: reset clears row count; close idempotent.
  // ---------------------------------------------------------------------------

  test("reset: drops row count, root is reusable"):
    withSerializer[Simple](ArrowRowSerializer.derived[Simple](_)) { ser =>
      ser.append(Simple(1, "one"))
      ser.append(Simple(2, "two"))
      assertEquals(ser.rowCount, 2)
      ser.reset()
      assertEquals(ser.rowCount, 0)
      ser.append(Simple(42, "answer"))
      assertEquals(ser.rowCount, 1)
      val id = ser.root.getVector(0).asInstanceOf[IntVector]
      assertEquals(id.get(0), 42)
    }

  test("close: idempotent"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Simple](alloc)
    ser.append(Simple(1, "x"))
    ser.close()
    ser.close() // second call must not throw
    alloc.close()

  // ---------------------------------------------------------------------------
  // Schema/allocator exposure.
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // largeVarTypes=true: schema emits LargeUtf8 (so the root has LargeVarCharVector).
  // Writer must dispatch correctly — previous regression cast unconditionally to VarCharVector.
  // ---------------------------------------------------------------------------

  test("Simple: largeVarTypes=true → writes via LargeVarCharVector"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Simple](alloc, "UTC", true)
    try
      ser.append(Simple(1, "alice"))
      ser.append(Simple(2, null))
      // Schema must be LargeUtf8, root must be LargeVarCharVector — and writes must hit it.
      val name = ser.root.getVector(1)
      assert(name.isInstanceOf[LargeVarCharVector], s"expected LargeVarCharVector, got ${name.getClass.getSimpleName}")
      val lvc = name.asInstanceOf[LargeVarCharVector]
      assertEquals(new String(lvc.get(0), StandardCharsets.UTF_8), "alice")
      assert(lvc.isNull(1))
    finally
      ser.close()
      alloc.close()

  test("schema and allocator: exposed for downstream IPC framing"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Simple](alloc)
    try
      assert(ser.schema ne null)
      assertEquals(ser.schema.getFields.size, 2)
      assert(ser.allocator eq alloc)
    finally
      ser.close()
      alloc.close()
