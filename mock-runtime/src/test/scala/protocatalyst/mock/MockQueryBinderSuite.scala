package protocatalyst.mock

import protocatalyst.dsl.*
import protocatalyst.encoder.*
import protocatalyst.expr.*
import protocatalyst.plan.*

case class BinderTestUser(name: String, age: Int, salary: Double) derives ProtoEncoder

class MockQueryBinderSuite extends munit.FunSuite:

  test("binds ColumnRef to BoundRef"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[BinderTestUser]("users")
    val compiled = users.filter(_.age > 18).compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case ProtoLogicalPlan.Filter(cond, _) =>
            assert(containsBoundRef(cond), "Expected BoundRef in condition")
          case _ => fail("Expected Filter plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  test("fails on missing table"):
    val catalog = InMemoryCatalog() // empty catalog

    val users = Table[BinderTestUser]("users")
    val compiled = users.compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BindingError(msg, _) =>
        assert(msg.contains("not found"), s"Expected 'not found' error, got: $msg")
      case MockQueryBinder.BoundPlan(_) =>
        fail("Expected BindingError for missing table")

  test("binds project expressions"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[BinderTestUser]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")
    val compiled = users.select(name, age).compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case ProtoLogicalPlan.Project(exprs, _) =>
            // Both expressions should be bound
            assert(exprs.forall(containsBoundRef), "Expected BoundRef in all project expressions")
          case _ => fail("Expected Project plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  test("binds aggregate expressions"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[BinderTestUser]("users")
    val name = users.col[String]("name")
    val compiled = users.groupBy(name).agg(functions.count).compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case ProtoLogicalPlan.Aggregate(grouping, aggs, _) =>
            assert(grouping.forall(containsBoundRef), "Expected BoundRef in grouping")
          case _ => fail("Expected Aggregate plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  test("binds sort expressions"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[BinderTestUser]("users")
    val age = users.col[Int]("age")
    val compiled = users.orderBy(age.desc).compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case ProtoLogicalPlan.Sort(orders, _, _) =>
            assert(orders.forall(o => containsBoundRef(o.child)), "Expected BoundRef in sort order")
          case _ => fail("Expected Sort plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  test("binds limit plan"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[BinderTestUser]("users")
    val compiled = users.limit(10).compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case ProtoLogicalPlan.Limit(10, _) => () // ok
          case _ => fail("Expected Limit plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  test("binds distinct plan"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[BinderTestUser]("users")
    val compiled = users.distinct.compile

    MockQueryBinder.bind(compiled.artifact.plan, catalog) match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case ProtoLogicalPlan.Distinct(_) => () // ok
          case _ => fail("Expected Distinct plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  private def containsBoundRef(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.BoundRef(_, _, _) => true
    case ProtoExpr.Gt(l, r)       => containsBoundRef(l) || containsBoundRef(r)
    case ProtoExpr.GtEq(l, r)     => containsBoundRef(l) || containsBoundRef(r)
    case ProtoExpr.Lt(l, r)       => containsBoundRef(l) || containsBoundRef(r)
    case ProtoExpr.LtEq(l, r)     => containsBoundRef(l) || containsBoundRef(r)
    case ProtoExpr.Eq(l, r)       => containsBoundRef(l) || containsBoundRef(r)
    case ProtoExpr.NotEq(l, r)    => containsBoundRef(l) || containsBoundRef(r)
    case ProtoExpr.And(children)  => children.exists(containsBoundRef)
    case ProtoExpr.Or(children)   => children.exists(containsBoundRef)
    case ProtoExpr.Not(child)     => containsBoundRef(child)
    case ProtoExpr.Alias(child, _) => containsBoundRef(child)
    case ProtoExpr.Count(child, _) => containsBoundRef(child)
    case _ => false
