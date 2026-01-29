package protocatalyst.mock

import protocatalyst.expr.*
import protocatalyst.plan.*
import protocatalyst.schema.*
import protocatalyst.types.*

/**
 * Binds ColumnRef expressions to BoundRef with ordinal positions.
 * Simulates Spark's attribute resolution phase.
 */
object MockQueryBinder:

  case class BindingContext(
      schemas: Map[String, MockDataType.StructType],
      aliasStack: Vector[String] = Vector.empty
  ):
    def withAlias(alias: String, schema: MockDataType.StructType): BindingContext =
      copy(schemas = schemas + (alias -> schema))

    def resolveColumn(name: String, qualifier: Option[String]): Either[String, (Int, ProtoType, Boolean)] =
      val candidates = schemas.flatMap { case (tableName, schema) =>
        schema.fields.zipWithIndex.collectFirst {
          case (field, idx) if field.name.equalsIgnoreCase(name) =>
            (idx, MockSchemaConverter.toProtoType(field.dataType), field.nullable, tableName)
        }
      }.toVector

      candidates match
        case Vector() =>
          Left(s"Column '$name' not found in any table")
        case Vector((idx, dt, nullable, _)) =>
          Right((idx, dt, nullable))
        case multiple if qualifier.isDefined =>
          multiple.find(_._4.equalsIgnoreCase(qualifier.get)) match
            case Some((idx, dt, nullable, _)) => Right((idx, dt, nullable))
            case None => Left(s"Column '${qualifier.get}.$name' not found")
        case multiple =>
          Left(s"Ambiguous column reference '$name' found in tables: ${multiple.map(_._4).mkString(", ")}")

  sealed trait BindingResult
  case class BoundPlan(plan: ProtoLogicalPlan) extends BindingResult
  case class BindingError(message: String, location: Option[String] = None) extends BindingResult

  /**
   * Bind a plan, resolving all ColumnRef to BoundRef.
   */
  def bind(plan: ProtoLogicalPlan, catalog: MockCatalog): BindingResult =
    collectSchemas(plan, catalog) match
      case Left(err) => BindingError(err)
      case Right(schemaMap) =>
        val ctx = BindingContext(schemaMap)
        bindPlan(plan, ctx)

  private def collectSchemas(
      plan: ProtoLogicalPlan,
      catalog: MockCatalog
  ): Either[String, Map[String, MockDataType.StructType]] =
    import ProtoLogicalPlan.*
    plan match
      case RelationRef(name, alias, _) =>
        catalog.getTableSchema(name) match
          case Some(schema) =>
            val key = alias.getOrElse(name)
            Right(Map(key -> schema))
          case None =>
            Left(s"Table '$name' not found in catalog")

      case Project(_, child)    => collectSchemas(child, catalog)
      case Filter(_, child)     => collectSchemas(child, catalog)
      case Aggregate(_, _, child) => collectSchemas(child, catalog)
      case Sort(_, _, child)    => collectSchemas(child, catalog)
      case Limit(_, child)      => collectSchemas(child, catalog)
      case Distinct(child)      => collectSchemas(child, catalog)
      case Window(_, _, _, child) => collectSchemas(child, catalog)

      case SubqueryAlias(alias, child) =>
        collectSchemas(child, catalog).map { schemas =>
          // Rebind all schemas under new alias
          val merged = MockDataType.StructType(schemas.values.flatMap(_.fields).toVector)
          Map(alias -> merged)
        }

      case Join(left, right, _, _) =>
        for
          l <- collectSchemas(left, catalog)
          r <- collectSchemas(right, catalog)
        yield l ++ r

      case Union(children, _, _) =>
        children.foldLeft(Right(Map.empty): Either[String, Map[String, MockDataType.StructType]]) {
          case (Left(err), _) => Left(err)
          case (Right(acc), child) => collectSchemas(child, catalog).map(acc ++ _)
        }

      case Intersect(left, right, _) =>
        for
          l <- collectSchemas(left, catalog)
          r <- collectSchemas(right, catalog)
        yield l ++ r

      case Except(left, right, _) =>
        for
          l <- collectSchemas(left, catalog)
          r <- collectSchemas(right, catalog)
        yield l ++ r

      case Values(_, schema) =>
        Right(Map("values" -> MockSchemaConverter.toMockSchema(schema)))

  private def bindPlan(plan: ProtoLogicalPlan, ctx: BindingContext): BindingResult =
    import ProtoLogicalPlan.*
    plan match
      case r: RelationRef => BoundPlan(r)

      case Project(exprs, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) =>
            val boundExprs = exprs.map(bindExpr(_, ctx))
            BoundPlan(Project(boundExprs, boundChild))
          case err => err

      case Filter(cond, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) =>
            BoundPlan(Filter(bindExpr(cond, ctx), boundChild))
          case err => err

      case Aggregate(grouping, aggs, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) =>
            val boundGrouping = grouping.map(bindExpr(_, ctx))
            val boundAggs = aggs.map(bindExpr(_, ctx))
            BoundPlan(Aggregate(boundGrouping, boundAggs, boundChild))
          case err => err

      case Sort(order, global, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) =>
            val boundOrder = order.map(o => SortOrder(bindExpr(o.child, ctx), o.direction, o.nullOrdering))
            BoundPlan(Sort(boundOrder, global, boundChild))
          case err => err

      case Limit(n, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) => BoundPlan(Limit(n, boundChild))
          case err => err

      case Distinct(child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) => BoundPlan(Distinct(boundChild))
          case err => err

      case SubqueryAlias(alias, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) => BoundPlan(SubqueryAlias(alias, boundChild))
          case err => err

      case Join(left, right, joinType, cond) =>
        (bindPlan(left, ctx), bindPlan(right, ctx)) match
          case (BoundPlan(l), BoundPlan(r)) =>
            val boundCond = cond.map(bindExpr(_, ctx))
            BoundPlan(Join(l, r, joinType, boundCond))
          case (err: BindingError, _) => err
          case (_, err: BindingError) => err

      case Union(children, byName, allowMissing) =>
        val boundChildren = children.map(bindPlan(_, ctx))
        val errors = boundChildren.collect { case e: BindingError => e }
        if errors.nonEmpty then errors.head
        else BoundPlan(Union(boundChildren.collect { case BoundPlan(p) => p }, byName, allowMissing))

      case Intersect(left, right, isAll) =>
        (bindPlan(left, ctx), bindPlan(right, ctx)) match
          case (BoundPlan(l), BoundPlan(r)) => BoundPlan(Intersect(l, r, isAll))
          case (err: BindingError, _) => err
          case (_, err: BindingError) => err

      case Except(left, right, isAll) =>
        (bindPlan(left, ctx), bindPlan(right, ctx)) match
          case (BoundPlan(l), BoundPlan(r)) => BoundPlan(Except(l, r, isAll))
          case (err: BindingError, _) => err
          case (_, err: BindingError) => err

      case Window(windowExprs, partitionSpec, orderSpec, child) =>
        bindPlan(child, ctx) match
          case BoundPlan(boundChild) =>
            val boundWindow = windowExprs.map(bindExpr(_, ctx))
            val boundPartition = partitionSpec.map(bindExpr(_, ctx))
            val boundOrder = orderSpec.map(o => SortOrder(bindExpr(o.child, ctx), o.direction, o.nullOrdering))
            BoundPlan(Window(boundWindow, boundPartition, boundOrder, boundChild))
          case err => err

      case v: Values => BoundPlan(v)

  private def bindExpr(expr: ProtoExpr, ctx: BindingContext): ProtoExpr =
    import ProtoExpr.*
    expr match
      case ColumnRef(name, qualifier, _, _) =>
        ctx.resolveColumn(name, qualifier) match
          case Right((ordinal, dt, nullable)) => BoundRef(ordinal, dt, nullable)
          case Left(_) => expr // Keep unresolved if not found

      // Binary expressions
      case Eq(l, r)       => Eq(bindExpr(l, ctx), bindExpr(r, ctx))
      case NotEq(l, r)    => NotEq(bindExpr(l, ctx), bindExpr(r, ctx))
      case Lt(l, r)       => Lt(bindExpr(l, ctx), bindExpr(r, ctx))
      case LtEq(l, r)     => LtEq(bindExpr(l, ctx), bindExpr(r, ctx))
      case Gt(l, r)       => Gt(bindExpr(l, ctx), bindExpr(r, ctx))
      case GtEq(l, r)     => GtEq(bindExpr(l, ctx), bindExpr(r, ctx))
      case Add(l, r)      => Add(bindExpr(l, ctx), bindExpr(r, ctx))
      case Subtract(l, r) => Subtract(bindExpr(l, ctx), bindExpr(r, ctx))
      case Multiply(l, r) => Multiply(bindExpr(l, ctx), bindExpr(r, ctx))
      case Divide(l, r)   => Divide(bindExpr(l, ctx), bindExpr(r, ctx))

      // Logical
      case And(children) => And(children.map(bindExpr(_, ctx)))
      case Or(children)  => Or(children.map(bindExpr(_, ctx)))
      case Not(child)    => Not(bindExpr(child, ctx))

      // Null handling
      case IsNull(child)    => IsNull(bindExpr(child, ctx))
      case IsNotNull(child) => IsNotNull(bindExpr(child, ctx))
      case Coalesce(children) => Coalesce(children.map(bindExpr(_, ctx)))

      // String
      case Concat(children) => Concat(children.map(bindExpr(_, ctx)))
      case Substring(str, pos, len) =>
        Substring(bindExpr(str, ctx), bindExpr(pos, ctx), bindExpr(len, ctx))
      case Upper(child) => Upper(bindExpr(child, ctx))
      case Lower(child) => Lower(bindExpr(child, ctx))

      // Aggregates
      case Count(child, distinct) => Count(bindExpr(child, ctx), distinct)
      case Sum(child)   => Sum(bindExpr(child, ctx))
      case Avg(child)   => Avg(bindExpr(child, ctx))
      case Min(child)   => Min(bindExpr(child, ctx))
      case Max(child)   => Max(bindExpr(child, ctx))

      // Control flow
      case CaseWhen(branches, elseVal) =>
        CaseWhen(
          branches.map((cond, result) => (bindExpr(cond, ctx), bindExpr(result, ctx))),
          elseVal.map(bindExpr(_, ctx))
        )
      case If(pred, trueVal, falseVal) =>
        If(bindExpr(pred, ctx), bindExpr(trueVal, ctx), bindExpr(falseVal, ctx))
      case In(value, list) =>
        In(bindExpr(value, ctx), list.map(bindExpr(_, ctx)))

      // Cast and alias
      case Cast(child, targetType) => Cast(bindExpr(child, ctx), targetType)
      case Alias(child, name)      => Alias(bindExpr(child, ctx), name)

      // Opaque call
      case OpaqueCall(name, args, returnType, det) =>
        OpaqueCall(name, args.map(bindExpr(_, ctx)), returnType, det)

      // Leaf nodes that don't need binding
      case lit: Literal  => lit
      case ref: BoundRef => ref
