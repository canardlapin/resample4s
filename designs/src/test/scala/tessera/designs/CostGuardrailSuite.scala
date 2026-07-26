package tessera.designs

import tessera.core.*

final class CostGuardrailSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  test("million-unit keys and an unstarted iterator retain constant state") {
    val shape = right(PlanShape.of(1000, 1000))
    var evaluations = 0
    val plan =
      Plan.fromGenerator[Int, Coverage](
        shape,
        key =>
          evaluations += 1
          key.repeat + key.fold
      )
    val keys = plan.keys
    val iterator = plan.iterator
    assertEquals(keys.length, 1000000)
    assertEquals(keys(999999), UnitKey(999, 999))
    assertEquals(evaluations, 0)
    assertEquals(iterator.next()._1, UnitKey(0, 0))
    assertEquals(evaluations, 1)
  }
  test("LOO at n=100000 retains no ordinal elements") {
    val n = 100000
    val compiled =
      right(
        LeaveOneOut().compile(
          right(IndexSpace.of(n)),
          Seed.fromLong(0L)
        )
      )
    assertEquals(compiled.cost.residentElementsUpperBound, 0L)
    assertEquals(compiled.plan.shape.unitCount, n)
    val last = right(compiled.plan.at(UnitKey(0, n - 1)))
    assertEquals(last.assessment.domain, 1)
    assertEquals(last.analysis.domain, n - 1)
  }

  test("exhaustive delete-d stays compact at the budget edge") {
    val n = 25
    val delete = 4
    val count = Combinations.choose(n, delete)
    val compiled =
      right(
        Jackknife.deleteD
          .exhaustive(delete, count.toLong)
          .compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(0L)
          )
      )
    assertEquals(compiled.cost.residentElementsUpperBound, 0L)
    assertEquals(compiled.plan.shape.unitCount, count.toInt)
    val last =
      right(
        compiled.plan.at(UnitKey(0, compiled.plan.shape.unitCount - 1))
      )
    assertEquals(last.assessment.domain, delete)
    assertEquals(last.analysis.domain, n - delete)
    assertEquals(
      Vector.tabulate(delete)(last.assessment.at(_).toOption.get),
      Vector(21, 22, 23, 24)
    )
  }

  test("grouped bootstrap declares g plus maximum emitted length work") {
    val groups =
      right(Labels.dense(ints(1, 1, 2, 3, 3, 3, 4, 4), 8))
    val times = 20
    val compiled =
      right(
        Bootstrap
          .grouped(times, groups, OobPolicy.Allow)
          .compile(
            right(IndexSpace.of(groups.size)),
            Seed.fromLong(0L)
          )
      )
    val groupsCount = groups.cardinality.toLong
    val maximum = 3L
    assertEquals(
      compiled.cost.residentElementsUpperBound,
      groups.size.toLong + times.toLong
    )
    assertEquals(
      compiled.cost.workPerUnitUpperBound,
      groupsCount + groupsCount * maximum
    )
    compiled.plan.iterator.foreach { (_, split) =>
      assert(
        split.analysis.domain.toLong + groupsCount <=
          compiled.cost.workPerUnitUpperBound
      )
    }
  }

  test("receipt traversal is explicit, streaming, and not memoized") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    var evaluations = 0
    val descriptor =
      DesignDescriptor.unsafe(
        AlgorithmId.unsafe("receipt-work/v1"),
        IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
      )
    val design =
      new Design[Int, Coverage]:
        val definition =
          DesignDefinition.general(descriptor, None) { _ =>
            for
              shape <- PlanShape.of(10, 1)
              cost <- PlanCost.of(0, 1, 1)
              spec <- GeneralPlanSpec.of(
                shape,
                PlanDiagnostics.empty,
                cost
              )(
                key =>
                  evaluations += 1
                  key.repeat,
                new CanonicalAssignmentEncoder[Int]:
                  def encode(
                      value: Int,
                      out: CanonicalWriter
                  ): Either[DigestError, Unit] =
                    out.int(value)
                    Right(())
              )
            yield spec
          }
    val compiled =
      right(
        design.compile(
          right(IndexSpace.of(1)),
          Seed.fromLong(0L)
        )
      )
    assertEquals(evaluations, 0)
    right(
      compiled.receipt(
        right(Summary.of("tessera/size", 1L))
      )
    )
    assertEquals(evaluations, 10)
    assertEquals(right(compiled.plan.at(UnitKey(0, 0))), 0)
    assertEquals(evaluations, 11)
  }
