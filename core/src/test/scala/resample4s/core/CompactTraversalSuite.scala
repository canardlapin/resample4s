package resample4s.core

/**
 * Guardrails that ordinary compact-selection traversal stays linear.
 *
 * Compact backings may keep linear random access. Full passes through pull,
 * equality, hashing, set algebra, and [[Reindexing.foreachIndex]] must not
 * invoke that random access once per selected ordinal.
 */
final class CompactTraversalSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private def complementBlock(
      population: Int,
      folds: Int,
      blockId: Int
  ): Selection =
    val assignment = Array.tabulate(population)(index => index % folds)
    val partition =
      right(
        FoldPartition.fromAssignments(
          population,
          folds,
          IArray.unsafeFromArray(assignment)
        )
      )
    Selection.complementBlock(partition, blockId)

  private def labelClasses(population: Int): Selection =
    val codes =
      Array.tabulate(population)(index => if index % 2 == 0 then 0 else 1)
    val labels = right(Labels.dense(IArray.unsafeFromArray(codes)))
    Selection.labelClasses(
      labels,
      IArray.unsafeFromArray(Array(true, false))
    )

  private def linearBudget(population: Int, size: Int): Long =
    // One population pass plus a small constant for cursor bookkeeping.
    2L * population + size + 8L

  test("pull on ComplementBlock inspects each population row once") {
    val population = 512
    val selection = complementBlock(population, folds = 4, blockId = 0)
    val values = IArray.tabulate(population)(identity)
    val (pulled, inspections) =
      CompactTraversalProbe.count(right(pull(values, selection)))
    assertEquals(pulled.length, selection.domain)
    assert(inspections <= linearBudget(population, selection.domain))
  }

  test("equality and hashing of compact versus explicit stay linear") {
    val population = 384
    val compact = complementBlock(population, folds = 5, blockId = 1)
    val explicit = Selection.fromOwned(compact.toIArray, population)
    val (equal, eqInspections) =
      CompactTraversalProbe.count(compact == explicit)
    assert(equal)
    assert(eqInspections <= linearBudget(population, compact.domain))

    val (_, hashInspections) =
      CompactTraversalProbe.count {
        compact.hashCode()
        ()
      }
    assert(hashInspections <= linearBudget(population, compact.domain))
  }

  test("set algebra over compact selections stays linear") {
    val population = 256
    val left = complementBlock(population, folds = 4, blockId = 0)
    val rightSelection = complementBlock(population, folds = 4, blockId = 1)
    val budget = linearBudget(population, left.domain) +
      linearBudget(population, rightSelection.domain)

    val (intersection, intersectionInspections) =
      CompactTraversalProbe.count(right(left.intersection(rightSelection)))
    assert(intersection.domain > 0)
    assert(intersectionInspections <= budget)

    val (_, unionInspections) =
      CompactTraversalProbe.count(right(left.union(rightSelection)))
    assert(unionInspections <= budget)

    val (_, differenceInspections) =
      CompactTraversalProbe.count(right(left.difference(rightSelection)))
    assert(differenceInspections <= budget)
  }

  test("foreachIndex on LabelClasses and ComplementOf stays linear") {
    val population = 320
    val classes = labelClasses(population)
    val (_, classInspections) =
      CompactTraversalProbe.count {
        var count = 0
        classes.foreachIndex(_ => count += 1)
        count
      }
    assert(classInspections <= population.toLong)
    assert(classInspections >= classes.domain.toLong)

    val base = right(
      Selection.from(
        IArray.tabulate(population / 4)(_ * 4),
        right(IndexSpace.of(population))
      )
    )
    val complement = base.complement
    val (emitted, complementInspections) =
      CompactTraversalProbe.count {
        var count = 0
        complement.foreachIndex(_ => count += 1)
        count
      }
    assertEquals(emitted, complement.domain)
    assert(complementInspections <= linearBudget(population, complement.domain))
  }

  test("quadratic unsafeAt path remains detectable by the probe") {
    val population = 128
    val selection = complementBlock(population, folds = 2, blockId = 0)
    val (_, inspections) =
      CompactTraversalProbe.count {
        var index = 0
        while index < selection.domain do
          selection.unsafeAt(index)
          index += 1
      }
    // Restarting random access inspects far more than one population pass.
    assert(inspections > 4L * population)
  }
