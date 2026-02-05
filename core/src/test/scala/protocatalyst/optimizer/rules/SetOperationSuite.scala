package protocatalyst.optimizer.rules

import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for Set operation optimization rules: CombineUnions.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class SetOperationSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // CombineUnions Tests
  // ========================================================

  test("CombineUnions: flatten nested unions"):
    // Union(Union(A, B), C) -> Union(A, B, C)
    val plan = ProtoLogicalPlan.Union(
      Vector(
        ProtoLogicalPlan.Union(
          Vector(relation("a"), relation("b")),
          byName = false,
          allowMissingColumns = false
        ),
        relation("c")
      ),
      byName = false,
      allowMissingColumns = false
    )

    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected Union with 3 children, got $optimized")

  test("CombineUnions: flatten deeply nested unions"):
    // Union(A, Union(B, Union(C, D))) -> Union(A, B, C, D)
    val plan = ProtoLogicalPlan.Union(
      Vector(
        relation("a"),
        ProtoLogicalPlan.Union(
          Vector(
            relation("b"),
            ProtoLogicalPlan.Union(
              Vector(relation("c"), relation("d")),
              byName = false,
              allowMissingColumns = false
            )
          ),
          byName = false,
          allowMissingColumns = false
        )
      ),
      byName = false,
      allowMissingColumns = false
    )

    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 4)
      case _ => fail(s"Expected Union with 4 children, got $optimized")

  test("CombineUnions: don't flatten unions with different settings"):
    // Union with byName=true should not be flattened into Union with byName=false
    val plan = ProtoLogicalPlan.Union(
      Vector(
        ProtoLogicalPlan.Union(
          Vector(relation("a"), relation("b")),
          byName = true, // Different setting
          allowMissingColumns = false
        ),
        relation("c")
      ),
      byName = false,
      allowMissingColumns = false
    )

    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 2)
        children.head match
          case ProtoLogicalPlan.Union(_, true, _) => ()
          case _                                  => fail("Expected inner Union to remain")
      case _ => fail(s"Expected Union with 2 children, got $optimized")

  test("CombineUnions: single-child union becomes child"):
    // Union(A) -> A
    val plan = ProtoLogicalPlan.Union(
      Vector(relation("a")),
      byName = false,
      allowMissingColumns = false
    )

    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("a", _, _) => ()
      case _                                       => fail(s"Expected single child, got $optimized")
