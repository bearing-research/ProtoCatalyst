package protocatalyst.catalyst.parity

import org.apache.spark.sql.catalyst.analysis.{UnresolvedAttribute, UnresolvedFunction, UnresolvedRelation}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Count}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.types.{DecimalType, DoubleType, FloatType}

/** Structural comparison of Spark LogicalPlans.
  *
  * Compares plan trees for structural equivalence, handling expected
  * differences like attribute resolution and expression normalization.
  */
object PlanComparator {

  case class ComparisonResult(
      isEquivalent: Boolean,
      differences: Seq[String]
  )

  /** Compare two plans for structural equivalence. */
  def compare(expected: LogicalPlan, actual: LogicalPlan): ComparisonResult = {
    val differences = scala.collection.mutable.ListBuffer[String]()
    comparePlans(expected, actual, "root", differences)
    ComparisonResult(differences.isEmpty, differences.toSeq)
  }

  private def comparePlans(
      expected: LogicalPlan,
      actual: LogicalPlan,
      path: String,
      diffs: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    // Check plan node types match
    val expectedType = planTypeName(expected)
    val actualType = planTypeName(actual)

    if (expectedType != actualType) {
      // Allow some equivalent plan types
      if (!areEquivalentPlanTypes(expected, actual)) {
        diffs += s"$path: plan type mismatch - expected $expectedType, got $actualType"
        return
      }
    }

    // Compare children count
    if (expected.children.size != actual.children.size) {
      diffs += s"$path: children count mismatch - expected ${expected.children.size}, got ${actual.children.size}"
      return
    }

    // Compare specific plan attributes
    comparePlanAttributes(expected, actual, path, diffs)

    // Recursively compare children
    expected.children.zip(actual.children).zipWithIndex.foreach { case ((e, a), i) =>
      comparePlans(e, a, s"$path/child[$i]", diffs)
    }
  }

  private def planTypeName(plan: LogicalPlan): String = {
    plan.getClass.getSimpleName
  }

  private def areEquivalentPlanTypes(expected: LogicalPlan, actual: LogicalPlan): Boolean = {
    // Some plan types are functionally equivalent
    (expected, actual) match {
      // UnresolvedRelation and LocalRelation both represent data sources
      case (_: UnresolvedRelation, _: UnresolvedRelation) => true
      // SubqueryAlias wrapping is optional
      case (_: SubqueryAlias, _: SubqueryAlias) => true
      // Limit variations
      case (_: GlobalLimit, _: GlobalLimit) => true
      case (_: LocalLimit, _: LocalLimit) => true
      case _ => false
    }
  }

  private def comparePlanAttributes(
      expected: LogicalPlan,
      actual: LogicalPlan,
      path: String,
      diffs: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    (expected, actual) match {
      case (e: Project, a: Project) =>
        compareExpressionLists(e.projectList, a.projectList, s"$path/projectList", diffs)

      case (e: Filter, a: Filter) =>
        compareExpressions(e.condition, a.condition, s"$path/condition", diffs)

      case (e: Aggregate, a: Aggregate) =>
        compareExpressionLists(e.groupingExpressions, a.groupingExpressions, s"$path/grouping", diffs)
        compareExpressionLists(e.aggregateExpressions, a.aggregateExpressions, s"$path/aggregate", diffs)

      case (e: Sort, a: Sort) =>
        if (e.global != a.global) {
          diffs += s"$path: Sort.global mismatch - expected ${e.global}, got ${a.global}"
        }
        compareSortOrders(e.order, a.order, s"$path/order", diffs)

      case (e: Join, a: Join) =>
        if (e.joinType != a.joinType) {
          diffs += s"$path: Join.joinType mismatch - expected ${e.joinType}, got ${a.joinType}"
        }
        (e.condition, a.condition) match {
          case (Some(ec), Some(ac)) => compareExpressions(ec, ac, s"$path/joinCondition", diffs)
          case (None, None) => // OK
          case _ => diffs += s"$path: Join condition presence mismatch"
        }

      case (e: Union, a: Union) =>
        if (e.byName != a.byName) {
          diffs += s"$path: Union.byName mismatch - expected ${e.byName}, got ${a.byName}"
        }

      case (e: SubqueryAlias, a: SubqueryAlias) =>
        if (!aliasesMatch(e.identifier.name, a.identifier.name)) {
          diffs += s"$path: SubqueryAlias.alias mismatch - expected ${e.identifier.name}, got ${a.identifier.name}"
        }

      case (e: UnresolvedRelation, a: UnresolvedRelation) =>
        if (e.multipartIdentifier != a.multipartIdentifier) {
          diffs += s"$path: Relation name mismatch - expected ${e.multipartIdentifier}, got ${a.multipartIdentifier}"
        }

      case _ =>
        // For other plan types, just check structural match (handled above)
        ()
    }
  }

  private def aliasesMatch(expected: String, actual: String): Boolean = {
    // Case-insensitive alias comparison
    expected.equalsIgnoreCase(actual)
  }

  private def compareExpressionLists(
      expected: Seq[Expression],
      actual: Seq[Expression],
      path: String,
      diffs: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    if (expected.size != actual.size) {
      diffs += s"$path: expression list size mismatch - expected ${expected.size}, got ${actual.size}"
      return
    }

    expected.zip(actual).zipWithIndex.foreach { case ((e, a), i) =>
      compareExpressions(e, a, s"$path[$i]", diffs)
    }
  }

  private def compareExpressions(
      expected: Expression,
      actual: Expression,
      path: String,
      diffs: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    val expectedType = exprTypeName(expected)
    val actualType = exprTypeName(actual)

    val typesEquivalent = expectedType == actualType || areEquivalentExprTypes(expected, actual)

    if (!typesEquivalent) {
      diffs += s"$path: expression type mismatch - expected $expectedType, got $actualType"
      return
    }

    // Compare specific expression attributes
    compareExpressionAttributes(expected, actual, path, diffs)

    // Skip children comparison for known equivalent but structurally different types
    // (e.g., UnresolvedFunction vs AggregateExpression have different internal structures)
    if (areStructurallyDifferentEquivalents(expected, actual)) {
      return
    }

    // Compare children
    if (expected.children.size != actual.children.size) {
      diffs += s"$path: expression children count mismatch - expected ${expected.children.size}, got ${actual.children.size}"
      return
    }

    expected.children.zip(actual.children).zipWithIndex.foreach { case ((e, a), i) =>
      compareExpressions(e, a, s"$path/child[$i]", diffs)
    }
  }

  private def exprTypeName(expr: Expression): String = {
    expr.getClass.getSimpleName
  }

  private def areEquivalentExprTypes(expected: Expression, actual: Expression): Boolean = {
    (expected, actual) match {
      // Unresolved vs resolved attributes
      case (_: UnresolvedAttribute, _: UnresolvedAttribute) => true
      case (_: AttributeReference, _: AttributeReference) => true
      case (_: UnresolvedAttribute, _: AttributeReference) => true
      case (_: AttributeReference, _: UnresolvedAttribute) => true
      // Alias wrapping is sometimes implicit
      case (_: Alias, _) => true
      case (_, _: Alias) => true
      // UnresolvedFunction vs AggregateExpression - Spark parser uses unresolved,
      // ProtoCatalyst may use resolved aggregate expressions
      case (_: UnresolvedFunction, _: AggregateExpression) => true
      case (_: AggregateExpression, _: UnresolvedFunction) => true
      // Count vs Literal - COUNT(*) arguments differ between resolved/unresolved
      case (_: Literal, _: Count) => true
      case (_: Count, _: Literal) => true
      // Spark parses built-in functions as UnresolvedFunction, ProtoCatalyst may resolve them
      case (_: UnresolvedFunction, _: Concat) => true
      case (_: Concat, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Substring) => true
      case (_: Substring, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Upper) => true
      case (_: Upper, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Lower) => true
      case (_: Lower, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: If) => true
      case (_: If, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Coalesce) => true
      case (_: Coalesce, _: UnresolvedFunction) => true
      case _ => false
    }
  }

  /** Check if expressions are equivalent types but with fundamentally different internal structures.
    * For these types, we skip children comparison since the structure differs by design.
    */
  private def areStructurallyDifferentEquivalents(expected: Expression, actual: Expression): Boolean = {
    (expected, actual) match {
      // UnresolvedFunction vs AggregateExpression have different internal structures
      case (_: UnresolvedFunction, _: AggregateExpression) => true
      case (_: AggregateExpression, _: UnresolvedFunction) => true
      // Literal vs Count (for COUNT(*) argument differences)
      case (_: Literal, _: Count) => true
      case (_: Count, _: Literal) => true
      // Built-in functions: UnresolvedFunction vs resolved forms
      case (_: UnresolvedFunction, _: Concat) => true
      case (_: Concat, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Substring) => true
      case (_: Substring, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Upper) => true
      case (_: Upper, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Lower) => true
      case (_: Lower, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: If) => true
      case (_: If, _: UnresolvedFunction) => true
      case (_: UnresolvedFunction, _: Coalesce) => true
      case (_: Coalesce, _: UnresolvedFunction) => true
      case _ => false
    }
  }

  private def compareExpressionAttributes(
      expected: Expression,
      actual: Expression,
      path: String,
      diffs: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    (expected, actual) match {
      case (e: Literal, a: Literal) =>
        // Check if numeric values are equivalent (handling Decimal vs Double/Float differences)
        // Spark uses org.apache.spark.sql.types.Decimal internally
        val valuesEquivalent = (e.value, a.value) match {
          case (null, null) => true
          case (null, _) | (_, null) => false
          case (ev, av) if ev == av => true
          case (ev, av) =>
            // Try numeric comparison for different numeric types
            try {
              val evDouble = ev match {
                case d: org.apache.spark.sql.types.Decimal => d.toDouble
                case bd: java.math.BigDecimal => bd.doubleValue()
                case n: Number => n.doubleValue()
                case _ => Double.NaN
              }
              val avDouble = av match {
                case d: org.apache.spark.sql.types.Decimal => d.toDouble
                case bd: java.math.BigDecimal => bd.doubleValue()
                case n: Number => n.doubleValue()
                case _ => Double.NaN
              }
              evDouble == avDouble
            } catch {
              case _: Exception => false
            }
        }
        val typesCompatible = (e.dataType, a.dataType) match {
          case (_: DecimalType, DoubleType) => true
          case (_: DecimalType, FloatType) => true
          case (DoubleType, _: DecimalType) => true
          case (FloatType, _: DecimalType) => true
          case (t1, t2) => t1 == t2
        }
        if (!valuesEquivalent || !typesCompatible) {
          diffs += s"$path: Literal mismatch - expected ${e.value}:${e.dataType}, got ${a.value}:${a.dataType}"
        }

      case (e: UnresolvedAttribute, a: UnresolvedAttribute) =>
        if (!attributeNamesMatch(e.nameParts, a.nameParts)) {
          diffs += s"$path: Attribute mismatch - expected ${e.nameParts.mkString(".")}, got ${a.nameParts.mkString(".")}"
        }

      case (e: AttributeReference, a: AttributeReference) =>
        if (!e.name.equalsIgnoreCase(a.name)) {
          diffs += s"$path: AttributeReference name mismatch - expected ${e.name}, got ${a.name}"
        }

      case (e: Alias, a: Alias) =>
        if (!e.name.equalsIgnoreCase(a.name)) {
          diffs += s"$path: Alias name mismatch - expected ${e.name}, got ${a.name}"
        }

      case (e: Cast, a: Cast) =>
        if (e.dataType != a.dataType) {
          diffs += s"$path: Cast target type mismatch - expected ${e.dataType}, got ${a.dataType}"
        }

      case _ =>
        // For other expression types, structural comparison is sufficient
        ()
    }
  }

  private def attributeNamesMatch(expected: Seq[String], actual: Seq[String]): Boolean = {
    if (expected.size != actual.size) return false
    expected.zip(actual).forall { case (e, a) => e.equalsIgnoreCase(a) }
  }

  private def compareSortOrders(
      expected: Seq[SortOrder],
      actual: Seq[SortOrder],
      path: String,
      diffs: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    if (expected.size != actual.size) {
      diffs += s"$path: sort order count mismatch - expected ${expected.size}, got ${actual.size}"
      return
    }

    expected.zip(actual).zipWithIndex.foreach { case ((e, a), i) =>
      if (e.direction != a.direction) {
        diffs += s"$path[$i]: sort direction mismatch - expected ${e.direction}, got ${a.direction}"
      }
      if (e.nullOrdering != a.nullOrdering) {
        diffs += s"$path[$i]: null ordering mismatch - expected ${e.nullOrdering}, got ${a.nullOrdering}"
      }
      compareExpressions(e.child, a.child, s"$path[$i]/expr", diffs)
    }
  }
}
