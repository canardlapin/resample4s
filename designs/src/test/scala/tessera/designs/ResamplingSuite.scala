package tessera.designs

import tessera.core.*

final class ResamplingSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  private def vector(value: Reindexing): Vector[Int] =
    Vector.tabulate(value.domain)(index => value.at(index).toOption.get)

  private def labels(values: Int*): Labels =
    right(Labels.dense(ints(values*)))

  test("named bootstrap policies exactly expand to the explicit policy route") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64

    val space = right(IndexSpace.of(8))
    val seed = Seed.fromLong(41L)
    val population = right(Summary.of("tessera/size", space.size.toLong))
    val groups = labels(10, 10, 20, 20, 30, 30, 40, 40)

    def verify(
        named: Design[Split[Draw], Coverage],
        explicit: Design[Split[Draw], Coverage]
    ): Unit =
      assertEquals(named.randomizationKey.value, explicit.randomizationKey.value)
      assertEquals(named.fingerprint, explicit.fingerprint)
      (named.compile(space, seed), explicit.compile(space, seed)) match
        case (Left(namedError), Left(explicitError)) =>
          assertEquals(namedError, explicitError)
        case (Right(namedCompiled), Right(explicitCompiled)) =>
          assertEquals(
            namedCompiled.plan.materialize,
            explicitCompiled.plan.materialize
          )
          assertEquals(namedCompiled.diagnostics, explicitCompiled.diagnostics)
          assertEquals(
            (
              namedCompiled.cost.residentElementsUpperBound,
              namedCompiled.cost.workPerUnitUpperBound,
              namedCompiled.cost.receiptWorkPerUnitUpperBound
            ),
            (
              explicitCompiled.cost.residentElementsUpperBound,
              explicitCompiled.cost.workPerUnitUpperBound,
              explicitCompiled.cost.receiptWorkPerUnitUpperBound
            )
          )
          assertEquals(
            namedCompiled.receipt(population),
            explicitCompiled.receipt(population)
          )
        case (namedResult, explicitResult) =>
          fail(s"expansion diverged: $namedResult versus $explicitResult")

    Vector(
      (
        Bootstrap.unconditional(3),
        Bootstrap(3, OobPolicy.Allow)
      ),
      (
        Bootstrap.redrawing(3),
        Bootstrap(3, OobPolicy.Redraw(8))
      ),
      (
        Bootstrap.redrawing(3, maxAttempts = 5),
        Bootstrap(3, OobPolicy.Redraw(5))
      ),
      (
        Bootstrap.failOnEmptyOob(3),
        Bootstrap(3, OobPolicy.Fail)
      ),
      (
        GroupedBootstrap.unconditional(3, groups),
        Bootstrap.grouped(3, groups, OobPolicy.Allow)
      ),
      (
        GroupedBootstrap.redrawing(3, groups),
        Bootstrap.grouped(3, groups, OobPolicy.Redraw(8))
      ),
      (
        GroupedBootstrap.failOnEmptyOob(3, groups),
        Bootstrap.grouped(3, groups, OobPolicy.Fail)
      )
    ).foreach((named, explicit) => verify(named, explicit))
  }

  test("ordinary bootstrap emits exactly n ordered draws and exact OOB") {
    val n = 12
    val plan =
      right(
        Bootstrap(20, OobPolicy.Allow).compile(
          right(IndexSpace.of(n)),
          Seed.fromLong(77L)
        )
      ).plan
    assertEquals(plan.shape, right(PlanShape.of(20, 1)))
    plan.iterator.foreach { (_, split) =>
      assertEquals(split.analysis.domain, n)
      assertEquals(
        vector(split.assessment),
        Vector.range(0, n).filterNot(vector(split.analysis.support).contains)
      )
      assertEquals(
        split.analysis.multiplicity(0),
        vector(split.analysis).count(_ == 0)
      )
    }
    assert(
      plan.materialize.map(_._2.analysis.toIArray).map(values =>
        Vector.tabulate(values.length)(values(_))
      ).distinct.size >= 2
    )
  }

  test("Fail and Redraw resolve empty OOB during compilation") {
    val space = right(IndexSpace.of(2))
    val failingSeed =
      Vector
        .range(0, 1000)
        .find { value =>
          val compiled =
            right(
              Bootstrap(1, OobPolicy.Allow)
                .compile(space, Seed.fromLong(value.toLong))
            )
          right(compiled.plan.at(UnitKey(0, 0))).assessment.domain == 0
        }
        .get
        .toLong

    assertEquals(
      Bootstrap(1, OobPolicy.Fail)
        .compile(space, Seed.fromLong(failingSeed)),
      Left(DesignError.EmptyOutOfBag(UnitKey(0, 0), 1))
    )
    assertEquals(
      Bootstrap(1, OobPolicy.Redraw(1))
        .compile(space, Seed.fromLong(failingSeed)),
      Left(DesignError.EmptyOutOfBag(UnitKey(0, 0), 1))
    )

    val accepted =
      right(
        Bootstrap(1, OobPolicy.Redraw(32))
          .compile(space, Seed.fromLong(failingSeed))
      )
    assert(
      right(accepted.plan.at(UnitKey(0, 0))).assessment.domain > 0
    )
    assert(
      right(accepted.plan.at(UnitKey(0, 0))).assessment.domain > 0
    )
  }

  test("Allow is unbiased for n=1 while redrawing exhausts") {
    val space = right(IndexSpace.of(1))
    val allowed =
      right(
        Bootstrap(1, OobPolicy.Allow)
          .compile(space, Seed.fromLong(0L))
      )
    assertEquals(
      right(allowed.plan.at(UnitKey(0, 0))).assessment.domain,
      0
    )
    assertEquals(
      Bootstrap.redrawing(1).compile(space, Seed.fromLong(0L)),
      Left(DesignError.EmptyOutOfBag(UnitKey(0, 0), 8))
    )
  }

  test("Redraw matches the exact n=2 conditioned and exhaustion oracle") {
    val outcomes =
      Vector.range(0, 2).flatMap(first =>
        Vector.range(0, 2).map(second => Vector(first, second))
      )
    val acceptedOutcomes =
      outcomes.filter(values => values.distinct.size < 2)
    val rejectedOutcomes =
      outcomes.filterNot(acceptedOutcomes.contains)
    assertEquals(
      acceptedOutcomes,
      Vector(Vector(0, 0), Vector(1, 1))
    )

    val attempts = 3
    val samples = 10000
    val singleFailure =
      rejectedOutcomes.size.toDouble / outcomes.size.toDouble
    val expectedExhaustion = math.pow(singleFailure, attempts.toDouble)
    val expectedConditionalZero =
      acceptedOutcomes.count(_.head == 0).toDouble /
        acceptedOutcomes.size.toDouble

    val space = right(IndexSpace.of(2))
    var exhausted = 0
    var accepted = 0
    var allZero = 0
    var seed = 0
    while seed < samples do
      Bootstrap(1, OobPolicy.Redraw(attempts))
        .compile(space, Seed.fromLong(seed.toLong)) match
        case Left(DesignError.EmptyOutOfBag(UnitKey(0, 0), `attempts`)) =>
          exhausted += 1
        case Left(error) =>
          fail(s"unexpected redraw failure: $error")
        case Right(compiled) =>
          val draw =
            vector(right(compiled.plan.at(UnitKey(0, 0))).analysis)
          assert(acceptedOutcomes.contains(draw))
          accepted += 1
          if draw.head == 0 then allZero += 1
      seed += 1

    val alpha = 0.001
    val exhaustionTolerance =
      math.sqrt(math.log(4.0 / alpha) / (2.0 * samples.toDouble))
    val observedExhaustion = exhausted.toDouble / samples.toDouble
    assert(
      math.abs(observedExhaustion - expectedExhaustion) <=
        exhaustionTolerance,
      s"exhaustion=$observedExhaustion expected=$expectedExhaustion"
    )

    val conditionedTolerance =
      math.sqrt(math.log(4.0 / alpha) / (2.0 * accepted.toDouble))
    val observedConditionalZero = allZero.toDouble / accepted.toDouble
    assert(
      math.abs(observedConditionalZero - expectedConditionalZero) <=
        conditionedTolerance,
      s"conditioned=$observedConditionalZero expected=$expectedConditionalZero"
    )
  }

  test("ordinary OOB means match the exact finite-n expectation") {
    val samples = 2000
    val alpha = 0.001
    val tolerance =
      math.sqrt(math.log(2.0 / alpha) / (2.0 * samples.toDouble))
    Vector(5, 10, 50, 1000).foreach { n =>
      val plan =
        right(
          Bootstrap(samples, OobPolicy.Allow).compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(1234L + n.toLong)
          )
        ).plan
      val observed =
        plan.iterator
          .map(_._2.assessment.domain.toDouble / n.toDouble)
          .sum / samples.toDouble
      val expected = math.pow(1.0 - 1.0 / n.toDouble, n.toDouble)
      assert(
        math.abs(observed - expected) <= tolerance,
        s"n=$n observed=$observed expected=$expected tolerance=$tolerance"
      )
    }
  }

  test("grouped bootstrap draws whole groups exactly g times") {
    val groups = labels(1, 1, 2, 3, 3, 3, 4, 4)
    val members = DesignSupport.labelMembers(groups)
    val plan =
      right(
        Bootstrap
          .grouped(100, groups, OobPolicy.Allow)
          .compile(
            right(IndexSpace.of(groups.size)),
            Seed.fromLong(93L)
          )
      ).plan
    val minimum = Vector.tabulate(members.length)(members(_).length).min
    val maximum = Vector.tabulate(members.length)(members(_).length).max

    plan.iterator.foreach { (_, split) =>
      val draw = vector(split.analysis)
      assert(draw.length >= groups.cardinality * minimum)
      assert(draw.length <= groups.cardinality * maximum)
      assertEquals(parseWholeGroups(draw, members), groups.cardinality)
      assertEquals(
        vector(split.assessment),
        vector(split.analysis.support.complement)
      )
    }
  }

  test("grouped OOB fractions and variable draw length meet exact means") {
    val groups =
      labels(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 5, 5)
    val samples = 3000
    val plan =
      right(
        Bootstrap
          .grouped(samples, groups, OobPolicy.Allow)
          .compile(
            right(IndexSpace.of(groups.size)),
            Seed.fromLong(900L)
          )
      ).plan
    var rowFraction = 0.0
    var groupFraction = 0.0
    var meanLength = 0.0
    plan.iterator.foreach { (_, split) =>
      rowFraction +=
        split.assessment.domain.toDouble / groups.size.toDouble
      val missingGroups =
        Vector.range(0, groups.cardinality).count { group =>
          var present = false
          var index = 0
          while index < split.assessment.domain && !present do
            present =
              groups.at(split.assessment.at(index).toOption.get)
                .toOption
                .get == group
            index += 1
          present
        }
      groupFraction +=
        missingGroups.toDouble / groups.cardinality.toDouble
      meanLength += split.analysis.domain.toDouble
    }
    rowFraction /= samples.toDouble
    groupFraction /= samples.toDouble
    meanLength /= samples.toDouble

    val expected =
      math.pow(
        1.0 - 1.0 / groups.cardinality.toDouble,
        groups.cardinality.toDouble
      )
    val alpha = 0.001
    val tolerance =
      math.sqrt(math.log(2.0 / alpha) / (2.0 * samples.toDouble))
    assert(math.abs(rowFraction - expected) <= tolerance)
    assert(math.abs(groupFraction - expected) <= tolerance)

    val members = DesignSupport.labelMembers(groups)
    val sizes =
      Vector.tabulate(members.length)(group => members(group).length)
    val lower = groups.cardinality.toDouble * sizes.min.toDouble
    val upper = groups.cardinality.toDouble * sizes.max.toDouble
    val observedNormalized = (meanLength - lower) / (upper - lower)
    val expectedNormalized =
      (groups.size.toDouble - lower) / (upper - lower)
    assert(
      math.abs(observedNormalized - expectedNormalized) <= tolerance,
      s"normalized length=$observedNormalized expected=$expectedNormalized"
    )

    val equalGroups = labels(1, 1, 2, 2, 3, 3, 4, 4)
    val equalPlan =
      right(
        Bootstrap
          .grouped(samples, equalGroups, OobPolicy.Allow)
          .compile(
            right(IndexSpace.of(equalGroups.size)),
            Seed.fromLong(901L)
          )
      ).plan
    var equalRowFraction = 0.0
    var equalGroupFraction = 0.0
    assert(
      equalPlan.iterator.forall { (_, split) =>
        equalRowFraction +=
          split.assessment.domain.toDouble / equalGroups.size.toDouble
        val missingGroups =
          Vector.range(0, equalGroups.cardinality).count { group =>
            Vector
              .range(0, split.assessment.domain)
              .exists(index =>
                right(
                  equalGroups.at(right(split.assessment.at(index)))
                ) == group
              )
          }
        equalGroupFraction +=
          missingGroups.toDouble / equalGroups.cardinality.toDouble
        split.analysis.domain == equalGroups.size
      }
    )
    equalRowFraction /= samples.toDouble
    equalGroupFraction /= samples.toDouble
    val equalExpected =
      math.pow(
        1.0 - 1.0 / equalGroups.cardinality.toDouble,
        equalGroups.cardinality.toDouble
      )
    assert(math.abs(equalRowFraction - equalExpected) <= tolerance)
    assert(math.abs(equalGroupFraction - equalExpected) <= tolerance)
  }

  test("grouped draw representability uses a widened boundary check") {
    assertEquals(
      BootstrapSupport.validatePotentialDrawSize(46340, 46340),
      Right(())
    )
    assertEquals(
      BootstrapSupport.validatePotentialDrawSize(46341, 46341),
      Left(
        DesignError.PotentialDrawSizeExceeded(46341, 46341)
      )
    )
  }

  test("delete-d rank/unrank exhausts lexicographic combinations") {
    var n = 3
    while n <= 10 do
      var delete = 2
      while delete < n do
        val expected = combinations(n, delete)
        val observed =
          Vector
            .tabulate(expected.length)(rank =>
              val values = Combinations.unrank(n, delete, BigInt(rank))
              Vector.tabulate(values.length)(values(_))
            )
        assertEquals(observed, expected)
        assertEquals(
          Combinations.choose(n, delete),
          BigInt(expected.length)
        )
        delete += 1
      n += 1
  }

  test("exhaustive delete-d enforces the unit budget exactly") {
    val space = right(IndexSpace.of(6))
    val seed = Seed.fromLong(0L)
    val count = Combinations.choose(6, 3).toLong
    val plan =
      right(
        Jackknife.deleteD
          .exhaustive(3, count)
          .compile(space, seed)
      ).plan
    assertEquals(plan.shape, right(PlanShape.of(1, count.toInt)))
    plan.iterator.foreach { (_, split) =>
      assertEquals(split.assessment.domain, 3)
      assertEquals(split.analysis.domain, 3)
      assertEquals(
        right(split.analysis.intersection(split.assessment)).domain,
        0
      )
    }
    assertEquals(
      Jackknife.deleteD.exhaustive(3, count - 1).compile(space, seed),
      Left(DesignError.UnitCountExceeded(BigInt(count), count - 1))
    )
  }

  test("sampled delete-d is uniform with replacement") {
    val n = 5
    val delete = 2
    val times = 5000
    val plan =
      right(
        Jackknife.deleteD
          .sampled(delete, times)
          .compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(782L)
          )
      ).plan
    val table = combinations(n, delete).zipWithIndex.toMap
    val counts = Array.fill(table.size)(0)
    plan.iterator.foreach { (_, split) =>
      counts(table(vector(split.assessment))) += 1
    }
    assert(counts.exists(_ > 1))
    val expected = times.toDouble / counts.length.toDouble
    val chiSquare =
      counts.iterator
        .map(count =>
          math.pow(count.toDouble - expected, 2.0) / expected
        )
        .sum
    assert(
      chiSquare < 27.88,
      s"chi-square=$chiSquare exceeds alpha=0.001 threshold for df=9"
    )
  }

  test("delete-one is exactly covered and seed-independent") {
    val space = right(IndexSpace.of(7))
    val first =
      right(Jackknife.delete1.compile(space, Seed.fromLong(1L))).plan
    val second =
      right(Jackknife.delete1.compile(space, Seed.fromLong(2L))).plan
    assertEquals(first.shape, right(PlanShape.of(1, 7)))
    assertEquals(
      first.materialize.map((_, split) => vector(split.assessment)),
      second.materialize.map((_, split) => vector(split.assessment))
    )
  }

  test("free and within-block permutations preserve their invariants") {
    val n = 8
    val free =
      right(
        PermutationDesign(40).compile(
          right(IndexSpace.of(n)),
          Seed.fromLong(66L)
        )
      ).plan
    free.iterator.foreach { (_, permutation) =>
      assertEquals(vector(permutation).sorted, Vector.range(0, n))
    }
    assert(free.materialize.map((_, value) => vector(value)).distinct.size >= 2)

    val blocks = labels(1, 1, 2, 2, 2, 3, 3, 3)
    val within =
      right(
        PermutationDesign
          .within(blocks, 40)
          .compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(67L)
          )
      ).plan
    within.iterator.foreach { (_, permutation) =>
      assertEquals(vector(permutation).sorted, Vector.range(0, n))
      var index = 0
      while index < n do
        assertEquals(
          blocks.at(index),
          blocks.at(permutation.at(index).toOption.get)
        )
        index += 1
    }

    val repeatedIdentity =
      right(
        PermutationDesign(5).compile(
          right(IndexSpace.of(1)),
          Seed.fromLong(68L)
        )
      ).plan
    val identities =
      repeatedIdentity.materialize.map((_, permutation) =>
        vector(permutation)
      )
    assertEquals(identities.length, 5)
    assertEquals(identities.distinct, Vector(Vector(0)))
  }

  test("invalid phase-3 configurations return typed errors") {
    val space = right(IndexSpace.of(5))
    val seed = Seed.fromLong(0L)
    assertEquals(
      Bootstrap.redrawing(0).compile(space, seed),
      Left(DesignError.InvalidTimes(0))
    )
    assertEquals(
      Bootstrap(1, OobPolicy.Redraw(0)).compile(space, seed),
      Left(DesignError.InvalidRedrawAttempts(0))
    )
    assertEquals(
      Jackknife.deleteD.exhaustive(1).compile(space, seed),
      Left(DesignError.InvalidDeleteCount(1, 5))
    )
    assertEquals(
      Jackknife.deleteD.sampled(5, 3).compile(space, seed),
      Left(DesignError.InvalidDeleteCount(5, 5))
    )
    assertEquals(
      PermutationDesign(0).compile(space, seed),
      Left(DesignError.InvalidTimes(0))
    )
  }

  private def parseWholeGroups(
      draw: Vector[Int],
      members: IArray[IArray[Int]]
  ): Int =
    var offset = 0
    var groups = 0
    while offset < draw.length do
      val matched =
        Vector.range(0, members.length).find { group =>
          val rows = Vector.tabulate(members(group).length)(members(group)(_))
          draw.slice(offset, offset + rows.length) == rows
        }
      assert(matched.isDefined)
      offset += members(matched.get).length
      groups += 1
    groups

  private def combinations(n: Int, size: Int): Vector[Vector[Int]] =
    def loop(start: Int, remaining: Int): Vector[Vector[Int]] =
      if remaining == 0 then Vector(Vector.empty)
      else
        Vector
          .range(start, n - remaining + 1)
          .flatMap(value =>
            loop(value + 1, remaining - 1).map(value +: _)
          )
    loop(0, size)
