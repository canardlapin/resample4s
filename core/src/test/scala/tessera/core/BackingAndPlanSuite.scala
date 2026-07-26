package tessera.core

final class BackingAndPlanSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def vector(value: Reindexing): Vector[Int] =
    Vector.tabulate(value.domain)(index => value.at(index).toOption.get)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  test("law 14: partition and selection backings are extensional") {
    val assignment = ints(0, 1, 2)
    val explicit = right(FoldPartition.fromAssignments(3, 3, assignment))
    val implicitIdentity = FoldPartition.singletonIdentity(3)
    assertEquals(explicit, implicitIdentity)
    assertEquals(explicit.hashCode(), implicitIdentity.hashCode())

    val space = right(IndexSpace.of(3))
    val explicitBlock = right(Selection.from(ints(1), space))
    val block = right(explicit.block(1))
    val implicitBlock = right(implicitIdentity.block(1))
    assertEquals(block, explicitBlock)
    assertEquals(implicitBlock, explicitBlock)
    assertEquals(block.hashCode(), explicitBlock.hashCode())

    val explicitComplement = right(Selection.from(ints(0, 2), space))
    val complementBlock = right(explicit.complementBlock(1))
    assertEquals(complementBlock, explicitComplement)
    assertEquals(complementBlock.hashCode(), explicitComplement.hashCode())

    val labels = right(Labels.dense(ints(7, 9, 7), 3))
    val classes =
      Selection.labelClasses(
        labels,
        IArray.unsafeFromArray(Array(true, false))
      )
    assertEquals(classes, explicitComplement)
    assertEquals(classes.hashCode(), explicitComplement.hashCode())

    val complementOf = explicitBlock.complement
    assertEquals(complementOf, explicitComplement)
    assertEquals(complementOf.complement, explicitBlock)
    assert(complementOf.complement eq explicitBlock)
    assert(block.complement.complement == block)
  }

  test("selection set algebra is canonical and codomain checked") {
    val space = right(IndexSpace.of(7))
    val left = right(Selection.from(ints(0, 2, 4, 6), space))
    val rightSelection = right(Selection.from(ints(1, 2, 3, 6), space))

    assertEquals(vector(right(left.intersection(rightSelection))), Vector(2, 6))
    assertEquals(
      vector(right(left.union(rightSelection))),
      Vector(0, 1, 2, 3, 4, 6)
    )
    assertEquals(vector(right(left.difference(rightSelection))), Vector(0, 4))
    assertEquals(vector(left.complement), Vector(1, 3, 5))

    val other = Selection.empty(right(IndexSpace.of(8)))
    assertEquals(left.union(other), Left(CodomainMismatch(7, 8)))
  }

  test("fold partitions and splits validate their invariants") {
    assertEquals(
      FoldPartition.fromAssignments(4, 2, ints(0, 0, 0, 0)),
      Left(DesignError.EmptyFold(1))
    )
    assertEquals(
      FoldPartition.fromAssignments(4, 2, ints(0, 1, 2, 0)),
      Left(DesignError.InvalidFoldAssignment(2, 2, 2))
    )

    val space = right(IndexSpace.of(4))
    val analysis = right(Selection.from(ints(0, 1, 2), space))
    val assessment = right(Selection.from(ints(2, 3), space))
    assertEquals(
      Split.of(analysis, assessment),
      Left(DesignError.OverlappingRoles(2))
    )
    assertEquals(
      Split.of(Selection.empty(space), assessment),
      Left(DesignError.EmptyAnalysis)
    )
  }

  test("plans are lazy until traversal and materialized plans are stable") {
    val shape = right(PlanShape.of(2, 3))
    var evaluations = 0
    val source =
      Plan.fromGenerator[Int, Coverage.Exact](
        shape,
        key =>
          evaluations += 1
          key.repeat * 10 + key.fold
      )

    val keys = source.keys
    val iterator = source.iterator
    assertEquals(evaluations, 0)
    assertEquals(keys.length, 6)
    assertEquals(keys(4), UnitKey(1, 1))
    assertEquals(evaluations, 0)

    assertEquals(iterator.next(), (UnitKey(0, 0), 0))
    assertEquals(evaluations, 1)
    val eager = source.materialized
    assertEquals(evaluations, 7)
    assertEquals(right(eager.at(UnitKey(1, 2))), 12)
    assertEquals(right(eager.at(UnitKey(1, 2))), 12)
    assertEquals(evaluations, 7)

    assertEquals(right(source.at(UnitKey(0, 1))), 1)
    assertEquals(evaluations, 8)
    assertEquals(
      source.at(UnitKey(2, 0)),
      Left(UnknownUnit(UnitKey(2, 0), shape))
    )
  }

  test("map preserves coverage and zip validates shape") {
    val shape = right(PlanShape.of(1, 2))
    val exact =
      Plan.fromGenerator[Int, Coverage.Exact](shape, _.fold)
    val mapped: Plan[String, Coverage.Exact] = exact.map(_.toString)
    assertEquals(right(mapped.at(UnitKey(0, 1))), "1")

    val other =
      Plan.fromGenerator[Int, Coverage](
        right(PlanShape.of(2, 1)),
        _.repeat
      )
    assertEquals(
      exact.zip(other),
      Left(ShapeMismatch(shape, other.shape))
    )

    val exactOnce =
      Plan.fromGenerator[Int, Coverage.ExactOnce](shape, _.fold)
    val mappedOnce: Plan[String, Coverage.ExactOnce] =
      exactOnce.map(_.toString)
    assertEquals(mappedOnce.first, "0")
  }

  test("fraction reduction and round-half-up use integer arithmetic") {
    assertEquals(right(Fraction.of(2, 10)), right(Fraction.of(1, 5)))
    assertEquals(right(Fraction.of(1, 4)).sizeOf(10), 3)
    assertEquals(right(Fraction.of(1, 2)).sizeOf(Int.MaxValue), 1073741824)
    assertEquals(
      Fraction.of(1, 1),
      Left(DesignError.InvalidFraction(1, 1))
    )
  }

  test("labels canonicalize by minimum ordinal and own their source") {
    val source = Array(8, 3, 8, 5, 3)
    val labels = right(Labels.dense(IArray.unsafeFromArray(source), 5))
    source(0) = 3

    assertEquals(labels.cardinality, 3)
    assertEquals(
      Vector.tabulate(labels.size)(index => labels.at(index).toOption.get),
      Vector(0, 1, 0, 2, 1)
    )
    val recoded = right(Labels.dense(ints(100, -4, 100, 9, -4), 5))
    assertEquals(labels, recoded)
    assertEquals(labels.hashCode(), recoded.hashCode())
  }
