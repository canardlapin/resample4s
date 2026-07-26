package tessera.designs

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import tessera.core.*

final class CataloguePropertySuite extends munit.ScalaCheckSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  private def labels(
      codes: IArray[Int],
      size: Int
  ): Labels =
    right(Labels.dense(codes, size))

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
        result(right(assessment.at(index))) = fold
        index += 1
      fold += 1
    result.toVector

  private def exact(
      plan: Plan[Split[Selection], Coverage.Exact],
      n: Int
  ): Boolean =
    var repeat = 0
    var valid = true
    while repeat < plan.shape.repeats && valid do
      val observed = assignment(plan, repeat, n)
      valid = observed.forall(_ >= 0)
      var fold = 0
      while fold < plan.shape.foldsPerRepeat && valid do
        val split = right(plan.at(UnitKey(repeat, fold)))
        valid =
          right(
            split.analysis.support.intersection(split.assessment)
          ).domain == 0
        fold += 1
      repeat += 1
    valid

  private def shuffledCodes(
      n: Int,
      cardinality: Int,
      seed: Long,
      oversizedFirst: Boolean
  ): IArray[Int] =
    val raw =
      if oversizedFirst then
        val large = n - cardinality + 1
        Array.tabulate(n)(index =>
          if index < large then 0 else index - large + 1
        )
      else Array.tabulate(n)(index => index % cardinality)
    Rand
      .fromSeed(Seed.fromLong(seed))
      .shuffle(IArray.unsafeFromArray(raw))
      ._2

  property("plain K-fold is exact and balanced over generated boundaries") {
    val cases =
      for
        n <- Gen.choose(2, 40)
        folds <- Gen.choose(2, n)
        seed <- Gen.long
      yield (n, folds, seed)

    forAll(cases) { (n, folds, seed) =>
      val plan =
        right(
          KFold(folds).compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(seed)
          )
        ).plan
      val assigned = assignment(plan, 0, n)
      val sizes =
        Vector.range(0, folds).map(fold => assigned.count(_ == fold))
      assert(exact(plan, n))
      assert(sizes.max - sizes.min <= 1)
    }
  }

  property("stratified K-fold obeys floor/ceiling for generated small strata") {
    val cases =
      for
        n <- Gen.choose(2, 40)
        folds <- Gen.choose(2, n)
        cardinality <- Gen.choose(1, n)
        seed <- Gen.long
      yield (n, folds, cardinality, seed)

    forAll(cases) { (n, folds, cardinality, seed) =>
      val strata =
        labels(
          shuffledCodes(
            n,
            cardinality,
            seed ^ 0x51f15eL,
            oversizedFirst = false
          ),
          n
        )
      val plan =
        right(
          KFold
            .stratified(folds, strata)
            .compile(
              right(IndexSpace.of(n)),
              Seed.fromLong(seed)
            )
        ).plan
      val assigned = assignment(plan, 0, n)
      assert(exact(plan, n))
      var stratum = 0
      while stratum < strata.cardinality do
        val total =
          Vector.range(0, n).count(index =>
            right(strata.at(index)) == stratum
          )
        val lower = total / folds
        val upper = (total + folds - 1) / folds
        var fold = 0
        while fold < folds do
          val count =
            Vector.range(0, n).count(index =>
              right(strata.at(index)) == stratum &&
                assigned(index) == fold
            )
          assert(count == lower || count == upper)
          fold += 1
        stratum += 1
    }
  }

  property("grouped K-fold is exact and atomic with an oversized group") {
    val cases =
      for
        n <- Gen.choose(2, 40)
        folds <- Gen.choose(2, math.min(n, 8))
        groupCount <- Gen.choose(folds, n)
        seed <- Gen.long
      yield (n, folds, groupCount, seed)

    forAll(cases) { (n, folds, groupCount, seed) =>
      val groups =
        labels(
          shuffledCodes(
            n,
            groupCount,
            seed ^ 0x6a09e667L,
            oversizedFirst = true
          ),
          n
        )
      val plan =
        right(
          KFold
            .grouped(folds, groups)
            .compile(
              right(IndexSpace.of(n)),
              Seed.fromLong(seed)
            )
        ).plan
      val assigned = assignment(plan, 0, n)
      assert(exact(plan, n))
      var group = 0
      while group < groups.cardinality do
        val groupFolds =
          Vector
            .range(0, n)
            .filter(index => right(groups.at(index)) == group)
            .map(assigned)
            .distinct
        assertEquals(groupFolds.size, 1)
        group += 1
    }
  }

  property("nested selection composition excludes outer assessment") {
    val cases =
      for
        n <- Gen.choose(6, 30)
        outerFolds <- Gen.choose(2, math.min(n, 6))
        seed <- Gen.long
      yield (n, outerFolds, seed)

    forAll(cases) { (n, outerFolds, seed) =>
      val outer =
        right(
          KFold(outerFolds).compile(
            right(IndexSpace.of(n)),
            Seed.fromLong(seed)
          )
        ).plan
      outer.iterator.foreach { (_, outerSplit) =>
        val inner =
          right(
            KFold(2).compile(
              right(IndexSpace.of(outerSplit.analysis.domain)),
              Seed.fromLong(seed ^ 0x3c6ef372L)
            )
          ).plan
        inner.iterator.foreach { (_, innerSplit) =>
          val embedded =
            right(outerSplit.analysis.after(innerSplit.assessment))
          assertEquals(
            right(
              embedded.intersection(outerSplit.assessment)
            ).domain,
            0
          )
        }
      }
    }
  }

  property("adversarial catalogue configurations remain typed and total") {
    val cases =
      for
        n <- Gen.choose(0, 20)
        folds <- Gen.choose(-3, n + 3)
        times <- Gen.choose(-3, 5)
        delete <- Gen.choose(-3, n + 3)
        seed <- Gen.long
      yield (n, folds, times, delete, seed)

    forAll(cases) { (n, folds, times, delete, seedValue) =>
      val space = right(IndexSpace.of(n))
      val seed = Seed.fromLong(seedValue)

      def total[A, Cov <: Coverage](
          result: Either[DesignError, Compiled[A, Cov]]
      ): Boolean =
        result match
          case Left(_) => true
          case Right(compiled) =>
            compiled.plan.keys.forall(key => compiled.plan.at(key).isRight)

      assert(total(KFold(folds).compile(space, seed)))
      assert(total(Bootstrap(times).compile(space, seed)))
      assert(total(PermutationDesign(times).compile(space, seed)))
      assert(
        total(
          Jackknife.deleteD
            .exhaustive(delete, budget = 100L)
            .compile(space, seed)
        )
      )
      assert(
        total(
          Jackknife.deleteD
            .sampled(delete, times)
            .compile(space, seed)
        )
      )
    }
  }
