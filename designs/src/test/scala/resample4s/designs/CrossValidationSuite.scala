package resample4s.designs

import resample4s.core.*
import resample4s.examples.NestedCrossValidation
import scala.compiletime.testing.typeCheckErrors

final class CrossValidationSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  private def assignment(
      plan: Plan[Split[Selection], ? <: Coverage],
      repeat: Int,
      n: Int
  ): Vector[Int] =
    val result = Array.fill(n)(-1)
    var fold = 0
    while fold < plan.shape.foldsPerRepeat do
      val assessment =
        right(plan.at(UnitKey(repeat, fold))).assessment
      var index = 0
      while index < assessment.domain do
        result(assessment.at(index).toOption.get) = fold
        index += 1
      fold += 1
    result.toVector

  private def assertExact(
      plan: Plan[Split[Selection], Coverage.Exact],
      n: Int
  ): Unit =
    var repeat = 0
    while repeat < plan.shape.repeats do
      val observed = assignment(plan, repeat, n)
      assert(observed.forall(_ >= 0))
      var fold = 0
      while fold < plan.shape.foldsPerRepeat do
        val split = right(plan.at(UnitKey(repeat, fold)))
        assertEquals(
          right(split.analysis.intersection(split.assessment)).domain,
          0
        )
        fold += 1
      repeat += 1

  private def labels(values: Int*): Labels =
    right(Labels.dense(ints(values*)))

  test("plain K-fold is exact, balanced, replayable, and repeatable") {
    val n = 11
    val space = right(IndexSpace.of(n))
    val seed = Seed.fromLong(123L)
    val repeated = right(KFold(4).repeat(3))
    val first = right(repeated.compile(space, seed))
    val second = right(repeated.compile(space, seed))

    assertExact(first.plan, n)
    assertEquals(first.plan.shape, right(PlanShape.of(3, 4)))
    assertEquals(
      first.plan.materialize.map((key, _) =>
        assignment(first.plan, key.repeat, n)
      ).distinct,
      second.plan.materialize.map((key, _) =>
        assignment(second.plan, key.repeat, n)
      ).distinct
    )
    var repeat = 0
    while repeat < 3 do
      val counts =
        assignment(first.plan, repeat, n)
          .groupMapReduce(identity)(_ => 1)(_ + _)
          .values
          .toVector
          .sorted
      assertEquals(counts, Vector(2, 3, 3, 3))
      repeat += 1
  }

  test("single K-fold is ExactOnce while repeat drops to per-repeat Exact") {
    val space = right(IndexSpace.of(8))
    val seed = Seed.fromLong(4L)
    val once
        : Compiled[
          Split[Selection],
          Coverage.ExactOnce
        ] = right(KFold(4).compile(space, seed))
    val repeated
        : Compiled[
          Split[Selection],
          Coverage.Exact
        ] = right(right(KFold(4).repeat(2)).compile(space, seed))
    assertEquals(once.plan.shape.repeats, 1)
    assertEquals(repeated.plan.shape.repeats, 2)

    val errors = typeCheckErrors(
      """import resample4s.core.*
import resample4s.designs.*
def illegal(
  repeated: Design[Split[Selection], Coverage.Exact],
  space: IndexSpace,
  seed: Seed
): Compiled[Split[Selection], Coverage.ExactOnce] =
  repeated.compile(space, seed).toOption.get
"""
    )
    assert(errors.nonEmpty)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("Coverage.Exact"))
    assert(message.contains("Coverage.ExactOnce"))
  }

  test("stratified K-fold obeys the per-stratum floor/ceiling law") {
    val strata =
      labels(10, 20, 10, 30, 20, 10, 40, 30, 10, 50, 20, 10, 60)
    val n = strata.size
    val plan =
      right(
        KFold
          .stratified(4, strata)
          .compile(right(IndexSpace.of(n)), Seed.fromLong(99L))
      ).plan
    assertExact(plan, n)

    val assigned = assignment(plan, 0, n)
    var stratum = 0
    while stratum < strata.cardinality do
      val total =
        Vector.range(0, n).count(index =>
          strata.at(index).toOption.get == stratum
        )
      val lower = total / 4
      val upper = (total + 3) / 4
      var fold = 0
      while fold < 4 do
        val count =
          Vector.range(0, n).count(index =>
            strata.at(index).toOption.get == stratum &&
              assigned(index) == fold
          )
        assert(count == lower || count == upper)
        fold += 1
      stratum += 1
  }

  test("grouped K-fold preserves groups and reports size imbalance") {
    val groups = labels(1, 1, 2, 3, 3, 3, 4, 4, 5, 6, 6, 6, 6)
    val n = groups.size
    val compiled =
      right(
        KFold
          .grouped(3, groups)
          .compile(right(IndexSpace.of(n)), Seed.fromLong(72L))
      )
    assertExact(compiled.plan, n)
    assertGroupAtomic(assignment(compiled.plan, 0, n), groups)

    val observed = assignment(compiled.plan, 0, n)
    val loads =
      Vector.range(0, 3).map(fold => observed.count(_ == fold))
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.SizeImbalance),
      Some(BigInt(loads.max - loads.min))
    )
    val optimum = minimumGroupImbalance(groups, 3)
    val regret = loads.max - loads.min - optimum
    assert(regret >= 0)
    assert(
      regret <= 0,
      s"grouped size-imbalance regret $regret exceeded baseline 0"
    )
  }

  test("grouped-stratified allocation uses the exact BigInt objective") {
    val groups = labels(1, 1, 2, 2, 3, 3, 4, 4, 5)
    val strata = labels(9, 9, 8, 9, 8, 7, 7, 8, 9)
    val n = groups.size
    val compiled =
      right(
        KFold
          .groupedStratified(2, groups, strata)
          .compile(right(IndexSpace.of(n)), Seed.fromLong(11L))
      )
    val observed = assignment(compiled.plan, 0, n)
    assertExact(compiled.plan, n)
    assertGroupAtomic(observed, groups)

    val observedObjective = objective(observed, 2, strata)
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.Objective),
      Some(observedObjective)
    )
    val optimum = minimumGroupedObjective(groups, strata, 2)
    val regret = observedObjective - optimum
    assert(regret >= 0)
    assert(
      regret <= BigInt(0),
      s"grouped-stratified objective regret $regret exceeded baseline 0"
    )
  }

  test("grouped additive-regret baseline is one-sided") {
    val sizes = Vector(8, 7, 6, 5, 4)
    val rawCodes =
      sizes.zipWithIndex.flatMap { (size, group) =>
        Vector.fill(size)(100 + 17 * group)
      }
    val groups =
      right(
        Labels.dense(
          IArray.unsafeFromArray(rawCodes.toArray),
          rawCodes.length
        )
      )
    val folds = 2
    val compiled =
      right(
        KFold
          .grouped(folds, groups)
          .compile(
            right(IndexSpace.of(groups.size)),
            Seed.fromLong(31L)
          )
      )
    val assigned = assignment(compiled.plan, 0, groups.size)
    val loads =
      Vector.range(0, folds).map(fold => assigned.count(_ == fold))
    val optimum = minimumGroupImbalance(groups, folds)
    val regret = loads.max - loads.min - optimum

    assertEquals(optimum, 0)
    assert(regret >= 0)
    assert(
      regret <= 4,
      s"grouped size-imbalance regret $regret exceeded baseline 4"
    )
  }

  test("label recoding leaves keys, streams, and partitions invariant") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val firstGroups = labels(10, 10, 20, 20, 30, 30, 40, 40)
    val secondGroups = labels(-7, -7, 3, 3, 99, 99, 1, 1)
    val firstStrata = labels(5, 6, 5, 6, 5, 6, 5, 6)
    val secondStrata = labels(100, -1, 100, -1, 100, -1, 100, -1)
    assertEquals(firstGroups, secondGroups)
    assertEquals(firstStrata, secondStrata)

    val first =
      KFold.groupedStratified(2, firstGroups, firstStrata)
    val second =
      KFold.groupedStratified(2, secondGroups, secondStrata)
    assertEquals(first.randomizationKey.value, second.randomizationKey.value)
    assertEquals(
      right(first.fingerprint),
      right(second.fingerprint)
    )
    assertEquals(
      right(first.labelsFingerprint),
      right(second.labelsFingerprint)
    )
    val space = right(IndexSpace.of(8))
    val seed = Seed.fromLong(201L)
    assertEquals(
      assignment(right(first.compile(space, seed)).plan, 0, 8),
      assignment(right(second.compile(space, seed)).plan, 0, 8)
    )
  }

  test("grouped-stratified receipts diagnose either label authority") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val groups = labels(1, 1, 2, 2, 3, 3)
    val firstStrata = labels(9, 8, 9, 8, 9, 8)
    val secondStrata = labels(9, 9, 8, 8, 9, 8)
    val first = KFold.groupedStratified(2, groups, firstStrata)
    val second = KFold.groupedStratified(2, groups, secondStrata)
    val space = right(IndexSpace.of(6))
    val population = right(Summary.of("resample4s/size", 6L))
    val receipt =
      right(
        right(first.compile(space, Seed.fromLong(1L)))
          .receipt(population)
      )
    assertEquals(
      receipt.verify(second, space, population),
      Left(ReceiptError.Mismatch(ReceiptComponent.Labels))
    )
  }

  test("tie-rich grouped designs replay but expose seed-sensitive ties") {
    val groups = labels(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6)
    val strata = labels(1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2)
    val space = right(IndexSpace.of(groups.size))
    val grouped = KFold.grouped(3, groups)
    val groupedStratified =
      KFold.groupedStratified(3, groups, strata)

    def outputs(
        design: Design[Split[Selection], Coverage.Exact]
    ): Vector[Vector[Int]] =
      Vector.range(0, 20).map { seed =>
        assignment(
          right(design.compile(space, Seed.fromLong(seed.toLong))).plan,
          0,
          groups.size
        )
      }

    val groupedOutputs = outputs(grouped)
    val stratifiedOutputs = outputs(groupedStratified)
    assert(groupedOutputs.distinct.size >= 2)
    assert(stratifiedOutputs.distinct.size >= 2)
    assertEquals(groupedOutputs.head, outputs(grouped).head)
  }

  test("holdout names its role and Monte Carlo owns independent units") {
    val fraction = right(Fraction.of(1, 3))
    val space = right(IndexSpace.of(10))
    val seed = Seed.fromLong(44L)
    val assessing =
      right(Holdout.assessing(fraction).compile(space, seed)).plan
    val analyzing =
      right(Holdout.analyzing(fraction).compile(space, seed)).plan
    val assessingSplit = right(assessing.at(UnitKey(0, 0)))
    val analyzingSplit = right(analyzing.at(UnitKey(0, 0)))
    assertEquals(assessingSplit.assessment.domain, 3)
    assertEquals(analyzingSplit.analysis.domain, 3)
    Vector(assessingSplit, analyzingSplit).foreach { split =>
      assertEquals(split.analysis.domain + split.assessment.domain, 10)
      assertEquals(
        right(split.analysis.intersection(split.assessment)).domain,
        0
      )
    }

    val monte =
      right(MonteCarlo.assessing(fraction, 5).compile(space, seed)).plan
    assertEquals(monte.shape, right(PlanShape.of(5, 1)))
    assert(monte.materialize.map(_._2.assessment).distinct.size >= 2)
  }

  test("shuffle split shortcut matches complete Fisher-Yates semantics") {
    def vector(values: IArray[Int]): Vector[Int] =
      Vector.tabulate(values.length)(values(_))

    val seeds = Vector(0L, 1L, -1L, 42L, Long.MinValue)
    var n = 2
    while n <= 48 do
      var namedSize = 1
      while namedSize < n do
        seeds.foreach { seedValue =>
          val seed = Seed.fromLong(seedValue)
          val full = DesignSupport.shuffledIndices(n, seed)
          val expectedNamed =
            Vector.tabulate(namedSize)(full(_)).sorted
          val expectedOther =
            Vector
              .tabulate(n - namedSize)(index =>
                full(namedSize + index)
              )
              .sorted
          val (named, other) =
            ShuffleSplitSupport.sampledRoles(n, namedSize, seed)
          assertEquals(vector(named), expectedNamed)
          assertEquals(vector(other), expectedOther)
        }
        namedSize += 1
      n += 1
  }

  test("LOO and LOGO are exact and enforce degenerate boundaries") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val loo =
      right(
        LeaveOneOut().compile(
          right(IndexSpace.of(6)),
          Seed.fromLong(0L)
        )
      ).plan
    assertExact(loo, 6)
    assertEquals(loo.shape, right(PlanShape.of(1, 6)))
    val population = right(Summary.of("resample4s/size", 6L))
    val looReceipt =
      right(
        right(
          LeaveOneOut().compile(
            right(IndexSpace.of(6)),
            Seed.fromLong(1L)
          )
        ).receipt(population)
      )
    assertEquals(
      looReceipt
        .withSeed(Seed.fromLong(999L))
        .verify(LeaveOneOut(), right(IndexSpace.of(6)), population),
      Right(())
    )
    assertEquals(
      LeaveOneOut().compile(
        right(IndexSpace.of(1)),
        Seed.fromLong(0L)
      ),
      Left(DesignError.DegenerateSplit(1, 1))
    )

    val groups = labels(8, 8, 3, 5, 5, 3)
    val logo =
      right(
        LeaveOneGroupOut(groups).compile(
          right(IndexSpace.of(6)),
          Seed.fromLong(0L)
        )
      ).plan
    assertExact(logo, 6)
    assertEquals(logo.shape, right(PlanShape.of(1, 3)))
    assertGroupAtomic(assignment(logo, 0, 6), groups)
  }

  test("invalid configurations fail before a plan exists") {
    val space = right(IndexSpace.of(4))
    val seed = Seed.fromLong(0L)
    assertEquals(
      KFold(1).compile(space, seed),
      Left(DesignError.TooFewFolds(1, 2))
    )
    assertEquals(
      KFold(5).compile(space, seed),
      Left(DesignError.TooManyFolds(5, 4))
    )
    assertEquals(
      MonteCarlo
        .assessing(right(Fraction.of(1, 2)), 0)
        .compile(space, seed),
      Left(DesignError.InvalidTimes(0))
    )
    assertEquals(
      KFold(2).repeat(0),
      Left(DesignError.InvalidRepeatCount(0))
    )
    val oneGroup = labels(9, 9, 9, 9)
    assertEquals(
      KFold.grouped(2, oneGroup).compile(space, seed),
      Left(DesignError.TooFewGroups(1, 2))
    )
  }

  test("nested selection composition cannot reach the outer assessment") {
    val n = 12
    val outer =
      right(
        KFold(3).compile(
          right(IndexSpace.of(n)),
          Seed.fromLong(101L)
        )
      ).plan
    outer.iterator.foreach { (_, outerSplit) =>
      val inner =
        right(
          KFold(2).compile(
            right(IndexSpace.of(outerSplit.analysis.domain)),
            Seed.fromLong(202L)
          )
        ).plan
      inner.iterator.foreach { (_, innerSplit) =>
        val embedded: Selection =
          right(outerSplit.analysis.after(innerSplit.assessment))
        assertEquals(
          right(embedded.intersection(outerSplit.assessment)).domain,
          0
        )
      }
    }
    assertEquals(
      NestedCrossValidation.verifyExclusion(
        outer,
        innerFolds = 2,
        Seed.fromLong(202L)
      ),
      Right(true)
    )
  }

  private def assertGroupAtomic(
      assigned: Vector[Int],
      groups: Labels
  ): Unit =
    var group = 0
    while group < groups.cardinality do
      val folds =
        Vector
          .range(0, groups.size)
          .filter(index => groups.at(index).toOption.get == group)
          .map(assigned)
          .distinct
      assertEquals(folds.size, 1)
      group += 1

  private def minimumGroupImbalance(
      groups: Labels,
      folds: Int
  ): Int =
    val allocations = allAllocations(groups.cardinality, folds)
    allocations
      .filter(values => values.distinct.size == folds)
      .map { allocation =>
        val assigned =
          Vector.range(0, groups.size).map(index =>
            allocation(groups.at(index).toOption.get)
          )
        val loads =
          Vector.range(0, folds).map(fold => assigned.count(_ == fold))
        loads.max - loads.min
      }
      .min

  private def minimumGroupedObjective(
      groups: Labels,
      strata: Labels,
      folds: Int
  ): BigInt =
    allAllocations(groups.cardinality, folds)
      .filter(values => values.distinct.size == folds)
      .map { allocation =>
        val assigned =
          Vector.range(0, groups.size).map(index =>
            allocation(groups.at(index).toOption.get)
          )
        objective(assigned, folds, strata)
      }
      .min

  private def allAllocations(
      items: Int,
      folds: Int
  ): Vector[Vector[Int]] =
    def loop(remaining: Int): Vector[Vector[Int]] =
      if remaining == 0 then Vector(Vector.empty)
      else
        for
          prefix <- loop(remaining - 1)
          fold <- Vector.range(0, folds)
        yield prefix :+ fold
    loop(items)

  private def objective(
      assigned: Vector[Int],
      folds: Int,
      strata: Labels
  ): BigInt =
    val n = assigned.length
    var result = BigInt(0)
    var fold = 0
    while fold < folds do
      var stratum = 0
      while stratum < strata.cardinality do
        val total =
          Vector.range(0, n).count(index =>
            strata.at(index).toOption.get == stratum
          )
        val count =
          Vector.range(0, n).count(index =>
            assigned(index) == fold &&
              strata.at(index).toOption.get == stratum
          )
        result += BigInt(folds * count - total).pow(2)
        stratum += 1
      val foldSize = assigned.count(_ == fold)
      result += BigInt(folds * foldSize - n).pow(2)
      fold += 1
    result
