package resample4s.designs

import resample4s.core.*

final class CostGuardrailSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private final class CountingBootstrapObserver extends BootstrapWorkObserver:
    var candidates = 0L
    var preflightGroupIds = 0L
    var emittedRows = 0L

    def candidate(unit: UnitKey): Unit =
      candidates += 1L

    def preflightGroupId(unit: UnitKey): Unit =
      preflightGroupIds += 1L

    def emittedRow(unit: UnitKey): Unit =
      emittedRows += 1L

  private def observedOrdinary(
      times: Int,
      policy: OobPolicy,
      observer: BootstrapWorkObserver
  ): Design[Split[Draw], Coverage] =
    new Design[Split[Draw], Coverage]:
      val definition =
        DesignDefinition.general(
          DesignSupport.descriptor(
            "observed-bootstrap/v1",
            "times" -> DescriptorValue.int(times)
          ),
          None
        )(context =>
          BootstrapSupport.ordinary(context, times, policy, observer)
        )

  private def observedGrouped(
      times: Int,
      groups: Labels,
      policy: OobPolicy,
      observer: BootstrapWorkObserver
  ): Design[Split[Draw], Coverage] =
    new Design[Split[Draw], Coverage]:
      val definition =
        DesignDefinition.general(
          DesignSupport.descriptor(
            "observed-grouped-bootstrap/v1",
            "times" -> DescriptorValue.int(times)
          ),
          Some(groups)
        )(context =>
          BootstrapSupport.grouped(
            context,
            times,
            groups,
            policy,
            observer
          )
        )

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

  test("nested K-fold declares retained inner partitions and receipt work") {
    val n = 10000
    val outerFolds = 5
    val innerFolds = 4
    val compiled =
      right(
        NestedCrossValidation(outerFolds, innerFolds)
          .compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(14L)
          )
      )

    assertEquals(
      compiled.cost.residentElementsUpperBound,
      10L * n + 2L * outerFolds
    )
    assertEquals(compiled.cost.workPerUnitUpperBound, 1L)
    assertEquals(
      compiled.cost.receiptWorkPerUnitUpperBound,
      n.toLong + innerFolds.toLong * (n - n / outerFolds)
    )
    compiled.plan.iterator.foreach { (_, nested) =>
      assertEquals(
        nested.innerCost.residentElementsUpperBound,
        2L * nested.outer.analysis.domain
      )
    }
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
    assertEquals(
      compiled.cost.receiptWorkPerUnitUpperBound,
      compiled.cost.workPerUnitUpperBound + delete.toLong
    )
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

  test("bootstrap preflight counts candidates and never expands group rows") {
    val ordinaryTimes = 20
    val ordinarySpace = right(IndexSpace.of(12))
    val ordinarySeed =
      Vector
        .range(0, 100)
        .map(value => Seed.fromLong(value.toLong))
        .find(seed =>
          Bootstrap(ordinaryTimes, OobPolicy.Fail)
            .compile(ordinarySpace, seed)
            .isRight
        )
        .getOrElse(fail("expected a successful fixed-seed Fail fixture"))
    val ordinaryObserver = new CountingBootstrapObserver()
    right(
      observedOrdinary(
        ordinaryTimes,
        OobPolicy.Fail,
        ordinaryObserver
      ).compile(ordinarySpace, ordinarySeed)
    )
    assertEquals(ordinaryObserver.candidates, ordinaryTimes.toLong)

    val redrawObserver = new CountingBootstrapObserver()
    val attempts = 5
    assertEquals(
      observedOrdinary(
        times = 1,
        OobPolicy.Redraw(attempts),
        redrawObserver
      ).compile(right(IndexSpace.of(1)), Seed.fromLong(0L)),
      Left(DesignError.EmptyOutOfBag(UnitKey(0, 0), attempts))
    )
    assertEquals(redrawObserver.candidates, attempts.toLong)

    val groups =
      right(Labels.dense(ints(1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 8, 8), 12))
    val groupedTimes = 10
    val groupedSpace = right(IndexSpace.of(groups.size))
    val groupedSeed =
      Vector
        .range(0, 100)
        .map(value => Seed.fromLong(value.toLong))
        .find(seed =>
          Bootstrap
            .grouped(groupedTimes, groups, OobPolicy.Fail)
            .compile(groupedSpace, seed)
            .isRight
        )
        .getOrElse(
          fail("expected a successful fixed-seed grouped Fail fixture")
        )
    val groupedObserver = new CountingBootstrapObserver()
    val grouped =
      right(
        observedGrouped(
          groupedTimes,
          groups,
          OobPolicy.Fail,
          groupedObserver
        ).compile(groupedSpace, groupedSeed)
      )
    assertEquals(groupedObserver.candidates, groupedTimes.toLong)
    assertEquals(
      groupedObserver.preflightGroupIds,
      groupedTimes.toLong * groups.cardinality.toLong
    )
    assertEquals(groupedObserver.emittedRows, 0L)

    val first = right(grouped.plan.at(UnitKey(0, 0)))
    assertEquals(
      groupedObserver.emittedRows,
      first.analysis.domain.toLong
    )
  }

  test("grouped fold allocation uses logarithmic seeded-priority updates") {
    val priority = Vector(3, 1, 6, 0, 7, 2, 5, 4)
    val sizes = Vector(8, 7, 6, 5, 5, 4, 3, 2, 1)
    val queue = FoldLoadQueue(priority)
    val referenceLoads = Array.fill(priority.length)(0)
    val expected =
      sizes.map { size =>
        val minimum = referenceLoads.min
        val fold = priority
          .find(referenceLoads(_) == minimum)
          .getOrElse(
            fail("the non-empty priority permutation must select a fold")
          )
        referenceLoads(fold) += size
        fold
      }
    val observed = sizes.map(queue.takeAndAdd)
    assertEquals(observed, expected)

    val foldCount = 1024
    val groupCount = 10000
    val wide = FoldLoadQueue(Vector.range(0, foldCount).reverse)
    var group = 0
    while group < groupCount do
      wide.takeAndAdd(group % 17 + 1)
      group += 1
    val levels = 10L
    assert(
      wide.comparisonCount <= 2L * groupCount.toLong * levels,
      s"${wide.comparisonCount} heap comparisons exceeded the logarithmic bound"
    )

    val groupCodes =
      IArray.unsafeFromArray(Array.tabulate(groupCount)(identity))
    val groups = right(Labels.dense(groupCodes, groupCount))
    val space = right(IndexSpace.of(groupCount))
    val context =
      new BuildContext(
        space,
        Vector(groups),
        Seed.fromLong(101L),
        DesignKey.fromLong(0x243f6a8885a308d3L)
      )
    var productionComparisons: Option[Long] = None
    right(
      DesignSupport.groupedPartition(
        context,
        foldCount,
        groups,
        repeat = 0,
        observeComparisons =
          comparisons => productionComparisons = Some(comparisons)
      )
    )
    val productionObserved =
      productionComparisons.getOrElse(
        fail("the production grouped allocator did not report its work")
      )
    assert(
      productionObserved <= 2L * groupCount.toLong * levels,
      s"$productionObserved production comparisons exceeded the logarithmic bound"
    )
  }

  test("receipt traversal streams each primitive with constant lag") {
    var sessions = 0
    var assignmentUpdates = 0
    val countingAlgorithm =
      new DigestAlgorithm:
        val id: DigestAlgorithmId = DigestAlgorithm.fnv1a64.id

        def newAccumulator(): Either[DigestError, DigestAccumulator] =
          sessions += 1
          DigestAlgorithm.fnv1a64.newAccumulator().map { delegate =>
            val currentSession = sessions
            new DigestAccumulator:
              def update(
                  chunk: IArray[Byte]
              ): Either[DigestError, Unit] =
                if currentSession == 2 then assignmentUpdates += 1
                delegate.update(chunk)

              def finish(): Either[DigestError, DigestValue] =
                delegate.finish()
          }
    given DigestAlgorithm = countingAlgorithm
    var evaluations = 0
    val descriptor =
      DesignDescriptor.unsafe(
        AlgorithmId.unsafe("receipt-work/v1"),
        IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
      )
    val design =
      new Design[Int, Coverage]:
        val definition =
          DesignDefinition.general(descriptor) { _ =>
            for
              shape <- PlanShape.of(10, 1)
              cost <- PlanCost.of(0, 1, 1)
            yield GeneralPlanSpec(
              shape,
              PlanDiagnostics.empty,
              cost
            )(
              key =>
                evaluations += 1
                key.repeat
              ,
              new CanonicalAssignmentEncoder[Int]:
                def encode(
                    value: Int,
                    out: CanonicalWriter
                ): Either[DigestError, Unit] =
                  val before = assignmentUpdates
                  out.int(value)
                  assert(
                    assignmentUpdates > before,
                    "canonical output was buffered instead of consumed incrementally"
                  )
                  Right(())
            )
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
        right(Summary.of("resample4s/size", 1L))
      )
    )
    assertEquals(evaluations, 10)
    assertEquals(sessions, 2)
    assert(assignmentUpdates > 0)
    assertEquals(right(compiled.plan.at(UnitKey(0, 0))), 0)
    assertEquals(evaluations, 11)
  }
