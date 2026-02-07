package protocatalyst.optimizer

import protocatalyst.expr._
import protocatalyst.plan._

/** Utilities for transforming expression and plan trees.
  *
  * These follow a functional transformation pattern where:
  *   - Transformations return new nodes only if children changed
  *   - Same-instance return indicates no change (enables efficient fixed-point detection)
  */
object TreeTransform:

  /** Transform an expression tree bottom-up (children first, then parent).
    *
    * @param expr
    *   The expression to transform
    * @param rule
    *   A partial function applied to each node after children are transformed
    * @return
    *   The transformed expression
    */
  def transformExprUp(expr: ProtoExpr)(rule: PartialFunction[ProtoExpr, ProtoExpr]): ProtoExpr =
    // First transform children
    val withTransformedChildren = transformExprChildren(expr)(e => transformExprUp(e)(rule))
    // Then apply rule to this node
    rule.applyOrElse(withTransformedChildren, identity[ProtoExpr])

  /** Transform an expression tree top-down (parent first, then children).
    *
    * @param expr
    *   The expression to transform
    * @param rule
    *   A partial function applied to each node before children are transformed
    * @return
    *   The transformed expression
    */
  def transformExprDown(expr: ProtoExpr)(rule: PartialFunction[ProtoExpr, ProtoExpr]): ProtoExpr =
    // First apply rule to this node
    val transformed = rule.applyOrElse(expr, identity[ProtoExpr])
    // Then transform children
    transformExprChildren(transformed)(e => transformExprDown(e)(rule))

  /** Transform all child expressions of an expression.
    *
    * @param expr
    *   The parent expression
    * @param transform
    *   The transformation to apply to each child
    * @return
    *   A new expression with transformed children (or same instance if no change)
    */
  def transformExprChildren(expr: ProtoExpr)(transform: ProtoExpr => ProtoExpr): ProtoExpr =
    import ProtoExpr._

    expr match
      // Leaf nodes - no children
      case _: Literal | _: ColumnRef | _: BoundRef | _: RowNumber | _: Rank | _: DenseRank |
          _: CurrentDate | _: CurrentTimestamp =>
        expr

      // Unary expressions
      case Not(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Not(newChild)

      case IsNull(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else IsNull(newChild)

      case IsNotNull(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else IsNotNull(newChild)

      case Abs(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Abs(newChild)

      case Ceil(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Ceil(newChild)

      case Floor(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Floor(newChild)

      case Sqrt(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Sqrt(newChild)

      case Cbrt(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Cbrt(newChild)

      case Sign(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Sign(newChild)

      case Exp(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Exp(newChild)

      case Upper(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Upper(newChild)

      case Lower(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Lower(newChild)

      case Length(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Length(newChild)

      case Reverse(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Reverse(newChild)

      case Sum(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Sum(newChild)

      case Avg(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Avg(newChild)

      case Min(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Min(newChild)

      case Max(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Max(newChild)

      case Year(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Year(newChild)

      case Month(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Month(newChild)

      case DayOfMonth(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else DayOfMonth(newChild)

      case Hour(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Hour(newChild)

      case Minute(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Minute(newChild)

      case Second(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Second(newChild)

      case Explode(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Explode(newChild)

      case PosExplode(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else PosExplode(newChild)

      case Inline(child) =>
        val newChild = transform(child)
        if newChild eq child then expr else Inline(newChild)

      case Ntile(n) =>
        val newN = transform(n)
        if newN eq n then expr else Ntile(newN)

      // Binary expressions
      case Eq(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Eq(newLeft, newRight)

      case NotEq(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else NotEq(newLeft, newRight)

      case Lt(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Lt(newLeft, newRight)

      case LtEq(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else LtEq(newLeft, newRight)

      case Gt(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Gt(newLeft, newRight)

      case GtEq(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else GtEq(newLeft, newRight)

      case Add(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Add(newLeft, newRight)

      case Subtract(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Subtract(newLeft, newRight)

      case Multiply(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Multiply(newLeft, newRight)

      case Divide(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Divide(newLeft, newRight)

      case Pow(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Pow(newLeft, newRight)

      case Pmod(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else Pmod(newLeft, newRight)

      case NullIf(left, right) =>
        val (newLeft, newRight) = (transform(left), transform(right))
        if (newLeft eq left) && (newRight eq right) then expr else NullIf(newLeft, newRight)

      case DateDiff(end, start) =>
        val (newEnd, newStart) = (transform(end), transform(start))
        if (newEnd eq end) && (newStart eq start) then expr else DateDiff(newEnd, newStart)

      // Expressions with optional children
      case Log(child, base) =>
        val newChild = transform(child)
        val newBase = base.map(transform)
        if (newChild eq child) && (newBase.zip(base).forall((a, b) => a eq b)) then expr
        else Log(newChild, newBase)

      case Round(child, scale) =>
        val (newChild, newScale) = (transform(child), transform(scale))
        if (newChild eq child) && (newScale eq scale) then expr else Round(newChild, newScale)

      case Truncate(child, scale) =>
        val (newChild, newScale) = (transform(child), transform(scale))
        if (newChild eq child) && (newScale eq scale) then expr else Truncate(newChild, newScale)

      case StringRepeat(str, times) =>
        val (newStr, newTimes) = (transform(str), transform(times))
        if (newStr eq str) && (newTimes eq times) then expr else StringRepeat(newStr, newTimes)

      case DateAdd(start, days) =>
        val (newStart, newDays) = (transform(start), transform(days))
        if (newStart eq start) && (newDays eq days) then expr else DateAdd(newStart, newDays)

      case DateSub(start, days) =>
        val (newStart, newDays) = (transform(start), transform(days))
        if (newStart eq start) && (newDays eq days) then expr else DateSub(newStart, newDays)

      case Extract(field, source) =>
        val newSource = transform(source)
        if newSource eq source then expr else Extract(field, newSource)

      case DateTrunc(field, timestamp) =>
        val newTimestamp = transform(timestamp)
        if newTimestamp eq timestamp then expr else DateTrunc(field, newTimestamp)

      // N-ary expressions
      case And(children) =>
        val newChildren = transformVector(children)(transform)
        if newChildren eq children then expr else And(newChildren)

      case Or(children) =>
        val newChildren = transformVector(children)(transform)
        if newChildren eq children then expr else Or(newChildren)

      case Coalesce(children) =>
        val newChildren = transformVector(children)(transform)
        if newChildren eq children then expr else Coalesce(newChildren)

      case Concat(children) =>
        val newChildren = transformVector(children)(transform)
        if newChildren eq children then expr else Concat(newChildren)

      case Grouping(columns) =>
        val newColumns = transformVector(columns)(transform)
        if newColumns eq columns then expr else Grouping(newColumns)

      // More complex expressions
      case Count(child, distinct) =>
        val newChild = transform(child)
        if newChild eq child then expr else Count(newChild, distinct)

      case Alias(child, name) =>
        val newChild = transform(child)
        if newChild eq child then expr else Alias(newChild, name)

      case Cast(child, targetType) =>
        val newChild = transform(child)
        if newChild eq child then expr else Cast(newChild, targetType)

      case Substring(str, pos, len) =>
        val (newStr, newPos, newLen) = (transform(str), transform(pos), transform(len))
        if (newStr eq str) && (newPos eq pos) && (newLen eq len) then expr
        else Substring(newStr, newPos, newLen)

      case Trim(child, trimStr, trimType) =>
        val newChild = transform(child)
        val newTrimStr = trimStr.map(transform)
        if (newChild eq child) && (newTrimStr.zip(trimStr).forall((a, b) => a eq b)) then expr
        else Trim(newChild, newTrimStr, trimType)

      case Replace(str, search, replace) =>
        val (newStr, newSearch, newReplace) =
          (transform(str), transform(search), transform(replace))
        if (newStr eq str) && (newSearch eq search) && (newReplace eq replace) then expr
        else Replace(newStr, newSearch, newReplace)

      case StringLocate(substr, str, start) =>
        val (newSubstr, newStr) = (transform(substr), transform(str))
        val newStart = start.map(transform)
        if (newSubstr eq substr) && (newStr eq str) && (newStart
            .zip(start)
            .forall((a, b) => a eq b))
        then expr
        else StringLocate(newSubstr, newStr, newStart)

      case Lpad(str, len, pad) =>
        val (newStr, newLen, newPad) = (transform(str), transform(len), transform(pad))
        if (newStr eq str) && (newLen eq len) && (newPad eq pad) then expr
        else Lpad(newStr, newLen, newPad)

      case Rpad(str, len, pad) =>
        val (newStr, newLen, newPad) = (transform(str), transform(len), transform(pad))
        if (newStr eq str) && (newLen eq len) && (newPad eq pad) then expr
        else Rpad(newStr, newLen, newPad)

      case StringSplit(str, delimiter, limit) =>
        val (newStr, newDelimiter) = (transform(str), transform(delimiter))
        val newLimit = limit.map(transform)
        if (newStr eq str) && (newDelimiter eq delimiter) && (newLimit
            .zip(limit)
            .forall((a, b) => a eq b))
        then expr
        else StringSplit(newStr, newDelimiter, newLimit)

      case If(predicate, trueValue, falseValue) =>
        val (newPred, newTrue, newFalse) =
          (transform(predicate), transform(trueValue), transform(falseValue))
        if (newPred eq predicate) && (newTrue eq trueValue) && (newFalse eq falseValue) then expr
        else If(newPred, newTrue, newFalse)

      case In(value, list) =>
        val newValue = transform(value)
        val newList = transformVector(list)(transform)
        if (newValue eq value) && (newList eq list) then expr else In(newValue, newList)

      case Like(value, pattern, escape) =>
        val (newValue, newPattern) = (transform(value), transform(pattern))
        val newEscape = escape.map(transform)
        if (newValue eq value) && (newPattern eq pattern) && (newEscape
            .zip(escape)
            .forall((a, b) => a eq b))
        then expr
        else Like(newValue, newPattern, newEscape)

      case CaseWhen(branches, elseValue) =>
        val newBranches = branches.map { (cond, result) =>
          (transform(cond), transform(result))
        }
        val newElse = elseValue.map(transform)
        val branchesChanged = newBranches.zip(branches).exists { case ((nc, nr), (oc, or)) =>
          !(nc eq oc) || !(nr eq or)
        }
        val elseChanged = newElse.zip(elseValue).exists((a, b) => !(a eq b))
        if !branchesChanged && !elseChanged then expr else CaseWhen(newBranches, newElse)

      case Lead(input, offset, default) =>
        val (newInput, newOffset) = (transform(input), transform(offset))
        val newDefault = default.map(transform)
        if (newInput eq input) && (newOffset eq offset) && (newDefault
            .zip(default)
            .forall((a, b) => a eq b))
        then expr
        else Lead(newInput, newOffset, newDefault)

      case Lag(input, offset, default) =>
        val (newInput, newOffset) = (transform(input), transform(offset))
        val newDefault = default.map(transform)
        if (newInput eq input) && (newOffset eq offset) && (newDefault
            .zip(default)
            .forall((a, b) => a eq b))
        then expr
        else Lag(newInput, newOffset, newDefault)

      case FirstValue(input, ignoreNulls) =>
        val newInput = transform(input)
        if newInput eq input then expr else FirstValue(newInput, ignoreNulls)

      case LastValue(input, ignoreNulls) =>
        val newInput = transform(input)
        if newInput eq input then expr else LastValue(newInput, ignoreNulls)

      case NthValue(input, n) =>
        val (newInput, newN) = (transform(input), transform(n))
        if (newInput eq input) && (newN eq n) then expr else NthValue(newInput, newN)

      case WindowExpr(function, partitionSpec, orderSpec, frameSpec) =>
        val newFunction = transform(function)
        val newPartitionSpec = transformVector(partitionSpec)(transform)
        val newOrderSpec = orderSpec.map { so =>
          val newChild = transform(so.child)
          if newChild eq so.child then so else so.copy(child = newChild)
        }
        val orderSpecChanged = newOrderSpec.zip(orderSpec).exists((a, b) => !(a eq b))
        if (newFunction eq function) && (newPartitionSpec eq partitionSpec) && !orderSpecChanged
        then expr
        else WindowExpr(newFunction, newPartitionSpec, newOrderSpec, frameSpec)

      case ToDate(str, format) =>
        val newStr = transform(str)
        val newFormat = format.map(transform)
        if (newStr eq str) && (newFormat.zip(format).forall((a, b) => a eq b)) then expr
        else ToDate(newStr, newFormat)

      case ToTimestamp(str, format) =>
        val newStr = transform(str)
        val newFormat = format.map(transform)
        if (newStr eq str) && (newFormat.zip(format).forall((a, b) => a eq b)) then expr
        else ToTimestamp(newStr, newFormat)

      case Stack(numRows, children) =>
        val newNumRows = transform(numRows)
        val newChildren = transformVector(children)(transform)
        if (newNumRows eq numRows) && (newChildren eq children) then expr
        else Stack(newNumRows, newChildren)

      case OpaqueCall(name, args, returnType, deterministic) =>
        val newArgs = transformVector(args)(transform)
        if newArgs eq args then expr else OpaqueCall(name, newArgs, returnType, deterministic)

      // Subquery expressions - don't transform the subquery plan itself here
      case _: ScalarSubquery | _: Exists | _: InSubquery =>
        expr

  /** Transform all expressions in a plan (but not the plan structure itself).
    *
    * @param plan
    *   The plan whose expressions to transform
    * @param transform
    *   The transformation to apply to each expression
    * @return
    *   A new plan with transformed expressions (or same instance if no change)
    */
  def transformPlanExprs(plan: ProtoLogicalPlan)(
      transform: ProtoExpr => ProtoExpr
  ): ProtoLogicalPlan =
    import ProtoLogicalPlan._

    plan match
      case RelationRef(_, _, _) => plan
      case Values(rows, schema) =>
        val newRows = rows.map(row => transformVector(row)(transform))
        val changed = newRows.zip(rows).exists((a, b) => !(a eq b))
        if !changed then plan else Values(newRows, schema)

      case Project(projectList, child) =>
        val newProjectList = transformVector(projectList)(transform)
        val newChild = transformPlanExprs(child)(transform)
        if (newProjectList eq projectList) && (newChild eq child) then plan
        else Project(newProjectList, newChild)

      case Filter(condition, child) =>
        val newCondition = transform(condition)
        val newChild = transformPlanExprs(child)(transform)
        if (newCondition eq condition) && (newChild eq child) then plan
        else Filter(newCondition, newChild)

      case Aggregate(groupingExprs, aggregateExprs, child) =>
        val newGrouping = transformVector(groupingExprs)(transform)
        val newAggregates = transformVector(aggregateExprs)(transform)
        val newChild = transformPlanExprs(child)(transform)
        if (newGrouping eq groupingExprs) && (newAggregates eq aggregateExprs) && (newChild eq child)
        then plan
        else Aggregate(newGrouping, newAggregates, newChild)

      case Sort(order, child) =>
        val newOrder = order.map { so =>
          val newExpr = transform(so.child)
          if newExpr eq so.child then so else so.copy(child = newExpr)
        }
        val orderChanged = newOrder.zip(order).exists((a, b) => !(a eq b))
        val newChild = transformPlanExprs(child)(transform)
        if !orderChanged && (newChild eq child) then plan
        else Sort(newOrder, newChild)

      case Limit(limit, child) =>
        val newChild = transformPlanExprs(child)(transform)
        if newChild eq child then plan else Limit(limit, newChild)

      case Distinct(child) =>
        val newChild = transformPlanExprs(child)(transform)
        if newChild eq child then plan else Distinct(newChild)

      case SubqueryAlias(alias, child) =>
        val newChild = transformPlanExprs(child)(transform)
        if newChild eq child then plan else SubqueryAlias(alias, newChild)

      case Join(left, right, joinType, condition) =>
        val newLeft = transformPlanExprs(left)(transform)
        val newRight = transformPlanExprs(right)(transform)
        val newCondition = condition.map(transform)
        val condChanged = newCondition.zip(condition).exists((a, b) => !(a eq b))
        if (newLeft eq left) && (newRight eq right) && !condChanged then plan
        else Join(newLeft, newRight, joinType, newCondition)

      case Union(children, byName, allowMissingColumns) =>
        val newChildren = transformVector(children)(c => transformPlanExprs(c)(transform))
        if newChildren eq children then plan else Union(newChildren, byName, allowMissingColumns)

      case Intersect(left, right, isAll) =>
        val newLeft = transformPlanExprs(left)(transform)
        val newRight = transformPlanExprs(right)(transform)
        if (newLeft eq left) && (newRight eq right) then plan
        else Intersect(newLeft, newRight, isAll)

      case Except(left, right, isAll) =>
        val newLeft = transformPlanExprs(left)(transform)
        val newRight = transformPlanExprs(right)(transform)
        if (newLeft eq left) && (newRight eq right) then plan else Except(newLeft, newRight, isAll)

      case Window(windowExprs, partitionSpec, orderSpec, child) =>
        val newWindowExprs = transformVector(windowExprs)(transform)
        val newPartitionSpec = transformVector(partitionSpec)(transform)
        val newOrderSpec = orderSpec.map { so =>
          val newExpr = transform(so.child)
          if newExpr eq so.child then so else so.copy(child = newExpr)
        }
        val orderChanged = newOrderSpec.zip(orderSpec).exists((a, b) => !(a eq b))
        val newChild = transformPlanExprs(child)(transform)
        if (newWindowExprs eq windowExprs) && (newPartitionSpec eq partitionSpec) && !orderChanged && (newChild eq child)
        then plan
        else Window(newWindowExprs, newPartitionSpec, newOrderSpec, newChild)

      case With(cteRelations, recursive, child) =>
        val newCtes = cteRelations.map { (name, p) =>
          (name, transformPlanExprs(p)(transform))
        }
        val ctesChanged =
          newCtes.zip(cteRelations).exists { case ((_, np), (_, op)) => !(np eq op) }
        val newChild = transformPlanExprs(child)(transform)
        if !ctesChanged && (newChild eq child) then plan else With(newCtes, recursive, newChild)

      case Pivot(groupingExprs, pivotColumn, pivotValues, aggregates, child) =>
        val newGrouping = transformVector(groupingExprs)(transform)
        val newPivotColumn = transform(pivotColumn)
        val newPivotValues = transformVector(pivotValues)(transform)
        val newAggregates = transformVector(aggregates)(transform)
        val newChild = transformPlanExprs(child)(transform)
        if (newGrouping eq groupingExprs) && (newPivotColumn eq pivotColumn) &&
          (newPivotValues eq pivotValues) && (newAggregates eq aggregates) && (newChild eq child)
        then plan
        else Pivot(newGrouping, newPivotColumn, newPivotValues, newAggregates, newChild)

      case Unpivot(valueColumnName, variableColumnName, columns, includeNulls, child) =>
        val newColumns = columns.map { (expr, alias) =>
          (transform(expr), alias)
        }
        val columnsChanged =
          newColumns.zip(columns).exists { case ((ne, _), (oe, _)) => !(ne eq oe) }
        val newChild = transformPlanExprs(child)(transform)
        if !columnsChanged && (newChild eq child) then plan
        else Unpivot(valueColumnName, variableColumnName, newColumns, includeNulls, newChild)

      case LateralJoin(left, lateral, condition) =>
        val newLeft = transformPlanExprs(left)(transform)
        val newLateral = transformPlanExprs(lateral)(transform)
        val newCondition = condition.map(transform)
        val condChanged = newCondition.zip(condition).exists((a, b) => !(a eq b))
        if (newLeft eq left) && (newLateral eq lateral) && !condChanged then plan
        else LateralJoin(newLeft, newLateral, newCondition)

      case Generate(generator, generatorOutput, outer, child) =>
        val newGenerator = transform(generator)
        val newChild = transformPlanExprs(child)(transform)
        if (newGenerator eq generator) && (newChild eq child) then plan
        else Generate(newGenerator, generatorOutput, outer, newChild)

      case ResolvedHint(hints, child) =>
        val newChild = transformPlanExprs(child)(transform)
        if newChild eq child then plan else ResolvedHint(hints, newChild)

  /** Transform a plan tree bottom-up (children first, then parent).
    *
    * @param plan
    *   The plan to transform
    * @param rule
    *   A partial function applied to each node after children are transformed
    * @return
    *   The transformed plan
    */
  def transformPlanUp(plan: ProtoLogicalPlan)(
      rule: PartialFunction[ProtoLogicalPlan, ProtoLogicalPlan]
  ): ProtoLogicalPlan =
    // First transform children
    val withTransformedChildren = transformPlanChildren(plan)(p => transformPlanUp(p)(rule))
    // Then apply rule to this node
    rule.applyOrElse(withTransformedChildren, identity[ProtoLogicalPlan])

  /** Transform a plan tree top-down (parent first, then children).
    *
    * @param plan
    *   The plan to transform
    * @param rule
    *   A partial function applied to each node before children are transformed
    * @return
    *   The transformed plan
    */
  def transformPlanDown(plan: ProtoLogicalPlan)(
      rule: PartialFunction[ProtoLogicalPlan, ProtoLogicalPlan]
  ): ProtoLogicalPlan =
    // First apply rule to this node
    val transformed = rule.applyOrElse(plan, identity[ProtoLogicalPlan])
    // Then transform children
    transformPlanChildren(transformed)(p => transformPlanDown(p)(rule))

  /** Transform all child plans of a plan node.
    */
  def transformPlanChildren(plan: ProtoLogicalPlan)(
      transform: ProtoLogicalPlan => ProtoLogicalPlan
  ): ProtoLogicalPlan =
    import ProtoLogicalPlan._

    plan match
      case _: RelationRef | _: Values => plan

      case Project(projectList, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Project(projectList, newChild)

      case Filter(condition, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Filter(condition, newChild)

      case Aggregate(groupingExprs, aggregateExprs, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Aggregate(groupingExprs, aggregateExprs, newChild)

      case Sort(order, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Sort(order, newChild)

      case Limit(limit, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Limit(limit, newChild)

      case Distinct(child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Distinct(newChild)

      case SubqueryAlias(alias, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else SubqueryAlias(alias, newChild)

      case Join(left, right, joinType, condition) =>
        val newLeft = transform(left)
        val newRight = transform(right)
        if (newLeft eq left) && (newRight eq right) then plan
        else Join(newLeft, newRight, joinType, condition)

      case Union(children, byName, allowMissingColumns) =>
        val newChildren = transformVector(children)(transform)
        if newChildren eq children then plan else Union(newChildren, byName, allowMissingColumns)

      case Intersect(left, right, isAll) =>
        val newLeft = transform(left)
        val newRight = transform(right)
        if (newLeft eq left) && (newRight eq right) then plan
        else Intersect(newLeft, newRight, isAll)

      case Except(left, right, isAll) =>
        val newLeft = transform(left)
        val newRight = transform(right)
        if (newLeft eq left) && (newRight eq right) then plan else Except(newLeft, newRight, isAll)

      case Window(windowExprs, partitionSpec, orderSpec, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Window(windowExprs, partitionSpec, orderSpec, newChild)

      case With(cteRelations, recursive, child) =>
        val newCtes = cteRelations.map { (name, p) =>
          (name, transform(p))
        }
        val ctesChanged =
          newCtes.zip(cteRelations).exists { case ((_, np), (_, op)) => !(np eq op) }
        val newChild = transform(child)
        if !ctesChanged && (newChild eq child) then plan else With(newCtes, recursive, newChild)

      case Pivot(groupingExprs, pivotColumn, pivotValues, aggregates, child) =>
        val newChild = transform(child)
        if newChild eq child then plan
        else Pivot(groupingExprs, pivotColumn, pivotValues, aggregates, newChild)

      case Unpivot(valueColumnName, variableColumnName, columns, includeNulls, child) =>
        val newChild = transform(child)
        if newChild eq child then plan
        else Unpivot(valueColumnName, variableColumnName, columns, includeNulls, newChild)

      case LateralJoin(left, lateral, condition) =>
        val newLeft = transform(left)
        val newLateral = transform(lateral)
        if (newLeft eq left) && (newLateral eq lateral) then plan
        else LateralJoin(newLeft, newLateral, condition)

      case Generate(generator, generatorOutput, outer, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else Generate(generator, generatorOutput, outer, newChild)

      case ResolvedHint(hints, child) =>
        val newChild = transform(child)
        if newChild eq child then plan else ResolvedHint(hints, newChild)

  // Helper to transform vectors efficiently
  private def transformVector[T <: AnyRef](vec: Vector[T])(f: T => T): Vector[T] =
    var changed = false
    val newVec = vec.map { elem =>
      val newElem = f(elem)
      if !(newElem eq elem) then changed = true
      newElem
    }
    if changed then newVec else vec
