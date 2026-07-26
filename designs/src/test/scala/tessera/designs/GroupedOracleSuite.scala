package tessera.designs

import tessera.core.*

final class GroupedOracleSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  private def labels(codes: Vector[Int]): Labels =
    right(
      Labels.dense(
        IArray.unsafeFromArray(codes.toArray),
        codes.length
      )
    )

  private def canonicalPartitions(size: Int): Vector[Vector[Int]] =
    if size == 0 then Vector(Vector.empty)
    else
      def loop(prefix: Vector[Int], maximum: Int): Vector[Vector[Int]] =
        if prefix.length == size then Vector(prefix)
        else
          Vector
            .range(0, maximum + 2)
            .flatMap(code => loop(prefix :+ code, math.max(maximum, code)))
      loop(Vector(0), 0)

  private def assignment(
      plan: Plan[Split[Selection], ? <: Coverage],
      size: Int
  ): Vector[Int] =
    val result = Array.fill(size)(-1)
    plan.iterator.foreach { (key, split) =>
      if key.repeat == 0 then
        var index = 0
        while index < split.assessment.domain do
          result(right(split.assessment.at(index))) = key.fold
          index += 1
    }
    result.toVector

  private def allocations(
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
    loop(items).filter(_.distinct.size == folds)

  private def minimumImbalance(
      groups: Labels,
      folds: Int
  ): Int =
    allocations(groups.cardinality, folds)
      .map { allocation =>
        val loads = Array.fill(folds)(0)
        var row = 0
        while row < groups.size do
          loads(allocation(right(groups.at(row)))) += 1
          row += 1
        loads.max - loads.min
      }
      .min

  private def objective(
      assigned: Vector[Int],
      folds: Int,
      strata: Labels
  ): BigInt =
    var result = BigInt(0)
    var fold = 0
    while fold < folds do
      var stratum = 0
      while stratum < strata.cardinality do
        var total = 0
        var count = 0
        var row = 0
        while row < strata.size do
          if right(strata.at(row)) == stratum then
            total += 1
            if assigned(row) == fold then count += 1
          row += 1
        result += BigInt(folds * count - total).pow(2)
        stratum += 1
      val foldSize = assigned.count(_ == fold)
      result += BigInt(folds * foldSize - assigned.length).pow(2)
      fold += 1
    result

  private def minimumObjective(
      groups: Labels,
      strata: Labels,
      folds: Int
  ): BigInt =
    allocations(groups.cardinality, folds)
      .map { allocation =>
        val assigned =
          Vector.tabulate(groups.size)(row =>
            allocation(right(groups.at(row)))
          )
        objective(assigned, folds, strata)
      }
      .min

  test("grouped LPT regret is bounded over the exhaustive small lattice") {
    var maximumRegret = 0
    var configurations = 0
    var size = 2
    while size <= 6 do
      canonicalPartitions(size).foreach { codes =>
        val groups = labels(codes)
        var folds = 2
        while folds <= math.min(3, groups.cardinality) do
          val optimum = minimumImbalance(groups, folds)
          Vector(0L, 1L).foreach { seedValue =>
            val compiled =
              right(
                KFold
                  .grouped(folds, groups)
                  .compile(
                    right(IndexSpace.of(size)),
                    Seed.fromLong(seedValue)
                  )
              )
            val assigned = assignment(compiled.plan, size)
            val loads =
              Vector
                .range(0, folds)
                .map(fold => assigned.count(_ == fold))
            val achieved = loads.max - loads.min
            val regret = achieved - optimum
            assert(regret >= 0)
            assertEquals(
              compiled.diagnostics.value(DiagnosticMetric.Optimum),
              Some(BigInt(optimum))
            )
            assertEquals(
              compiled.diagnostics.value(DiagnosticMetric.Regret),
              Some(BigInt(regret))
            )
            maximumRegret = math.max(maximumRegret, regret)
            configurations += 1
          }
          folds += 1
      }
      size += 1
    assertEquals(configurations, 974)
    assert(
      maximumRegret <= 0,
      s"grouped exhaustive-lattice regret $maximumRegret exceeded baseline 0"
    )
  }

  test("grouped-stratified regret is bounded over the exhaustive label lattice") {
    var maximumRegret = BigInt(0)
    var configurations = 0
    var size = 2
    while size <= 5 do
      val partitions = canonicalPartitions(size)
      partitions.foreach { groupCodes =>
        val groups = labels(groupCodes)
        partitions.foreach { stratumCodes =>
          val strata = labels(stratumCodes)
          var folds = 2
          while folds <= math.min(3, groups.cardinality) do
            val optimum = minimumObjective(groups, strata, folds)
            val compiled =
              right(
                KFold
                  .groupedStratified(folds, groups, strata)
                  .compile(
                    right(IndexSpace.of(size)),
                    Seed.fromLong(0L)
                  )
              )
            val achieved = objective(assignment(compiled.plan, size), folds, strata)
            val regret = achieved - optimum
            assert(regret >= 0)
            assertEquals(
              compiled.diagnostics.value(DiagnosticMetric.Optimum),
              Some(optimum)
            )
            assertEquals(
              compiled.diagnostics.value(DiagnosticMetric.Regret),
              Some(regret)
            )
            maximumRegret = maximumRegret.max(regret)
            configurations += 1
            folds += 1
        }
      }
      size += 1
    assertEquals(configurations, 4866)
    assert(
      maximumRegret <= BigInt(18),
      s"grouped-stratified exhaustive-lattice regret $maximumRegret exceeded baseline 18"
    )
  }

  test("repeated grouped-stratified plans retain worst-case diagnostics") {
    val groups = labels(Vector(0, 0, 1, 1, 2, 2, 3, 3))
    val strata = labels(Vector(0, 1, 0, 1, 0, 1, 0, 1))
    val compiled =
      right(
        right(KFold.groupedStratified(2, groups, strata).repeat(3))
          .compile(
            right(IndexSpace.of(groups.size)),
            Seed.fromLong(91L)
          )
      )
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.Repeats),
      Some(BigInt(3))
    )
    assert(compiled.diagnostics.value(DiagnosticMetric.Objective).nonEmpty)
    assert(compiled.diagnostics.value(DiagnosticMetric.Optimum).nonEmpty)
    assert(compiled.diagnostics.value(DiagnosticMetric.Regret).nonEmpty)
  }

  test("exact diagnostics stop at the declared bounded-oracle frontier") {
    val groups = labels(Vector.range(0, 33))
    val compiled =
      right(
        KFold
          .grouped(2, groups)
          .compile(
            right(IndexSpace.of(groups.size)),
            Seed.fromLong(0L)
          )
      )
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.Optimum),
      None
    )
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.Regret),
      None
    )
    assert(compiled.diagnostics.value(DiagnosticMetric.SizeImbalance).nonEmpty)
  }

  test("bounded oracle remains exact near its allocation frontier") {
    val groups = labels(Vector.range(0, 8))
    val strata = labels(Vector.range(0, 8))
    val compiled =
      right(
        KFold
          .groupedStratified(4, groups, strata)
          .compile(
            right(IndexSpace.of(8)),
            Seed.fromLong(7L)
          )
      )
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.Optimum),
      Some(BigInt(96))
    )
    assertEquals(
      compiled.diagnostics.value(DiagnosticMetric.Regret),
      Some(BigInt(0))
    )
  }
