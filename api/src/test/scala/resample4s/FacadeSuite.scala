package resample4s

final class FacadeSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  test("sixty-second shuffled K-fold uses only import resample4s.*") {
    val result =
      KFold(folds = 5, shuffle = true).plan(samples = 120, seed = 42L)
    val plan = right(result)
    assertEquals(plan.size, 5)
    var folds = 0
    plan.foreach { split =>
      val train: Selection = split.train
      val test: Selection = split.test
      assertEquals(train.populationSize, 120)
      assertEquals(test.populationSize, 120)
      assertEquals(train.size + test.size, 120)
      folds += 1
    }
    assertEquals(folds, 5)
  }

  test("ordered K-fold is seed-invariant") {
    val first = right(KFold.ordered(4).plan(samples = 20, seed = 1L))
    val second = right(KFold.ordered(4).plan(samples = 20, seed = 99L))
    assert(
      first.splits.zip(second.splits).forall((a, b) => a == b)
    )
  }

  test("stratified and grouped accept ordinary arrays") {
    val strata = Array(0, 0, 1, 1, 0, 1, 0, 1)
    val groups = Array(0, 0, 1, 1, 2, 2, 3, 3)
    val stratified = right(
      right(KFold.stratified(2, strata)).plan(seed = 7L)
    )
    assertEquals(stratified.size, 2)
    val grouped = right(right(KFold.grouped(2, groups)).plan(seed = 7L))
    assertEquals(grouped.size, 2)
  }

  test("bootstrap training is a Draw") {
    val plan = right(
      Bootstrap.unconditional(resamples = 10).plan(samples = 30, seed = 3L)
    )
    plan.foreach { split =>
      val train: Draw = split.train
      val test: Selection = split.test
      assertEquals(train.size, 30)
      assertEquals(test.populationSize, 30)
    }
  }

  test("holdout and shuffle split accept SplitSize") {
    val holdout = right(
      Holdout(SplitSize.count(24)).plan(samples = 120, seed = 1L)
    )
    assertEquals(holdout.size, 1)
    assertEquals(holdout.splits.head.test.size, 24)
    val percent = right(SplitSize.percent(20))
    val shuffle = right(
      ShuffleSplit(percent, resamples = 3).plan(samples = 100, seed = 2L)
    )
    assertEquals(shuffle.size, 3)
  }

  test("stratified shuffle split and predefined assignments") {
    val strata = Array(0, 0, 0, 0, 1, 1, 1, 1)
    val stratified = right(
      right(
        ShuffleSplit.stratified(
          SplitSize.percent(50).toOption.get,
          resamples = 2,
          strata
        )
      ).plan(seed = 5L)
    )
    assertEquals(stratified.size, 2)
    stratified.foreach { split =>
      assertEquals(split.test.size, 4)
    }

    val predefined = right(
      PredefinedSplit.fromAssignments(Array(0, 0, 1, 1, 2, 2))
    )
    assertEquals(predefined.size, 3)
  }

  test("predefined assignments keep literal fold ids") {
    val foldOfRow = Array(1, 1, 0, 0, 2, 2)
    val plan = right(PredefinedSplit.fromAssignments(foldOfRow))
    assertEquals(plan.size, 3)
    val tests =
      plan.splits.toVector.map(split =>
        Vector.tabulate(split.test.size)(i => right(split.test.at(i)))
      )
    assertEquals(tests(0), Vector(2, 3))
    assertEquals(tests(1), Vector(0, 1))
    assertEquals(tests(2), Vector(4, 5))
  }

  test("grouped shuffle-split counts are group counts") {
    val groups = Array(0, 0, 1, 1, 2, 2, 3, 3)
    val plan = right(
      right(
        ShuffleSplit.grouped(
          SplitSize.count(1),
          resamples = 1,
          groups
        )
      ).plan(seed = 4L)
    )
    assertEquals(plan.size, 1)
    assertEquals(plan.splits.head.test.size, 2)
  }

  test("nested façade compiles outer and embedded inner plans") {
    val plan = right(
      Nested.plan(outerFolds = 3, innerFolds = 2, samples = 12, seed = 9L)
    )
    assertEquals(plan.size, 3)
    plan.foreach { nested =>
      val outer: Split[Selection] = nested.outer
      val inner: Plan[Split[Selection], Coverage.ExactOnce] = nested.inner
      assertEquals(outer.train.populationSize, 12)
      assertEquals(inner.shape.foldsPerRepeat, 2)
    }
  }

  test("leave-one-out and jackknife are seedless at the façade") {
    val loo = right(LeaveOneOut.plan(samples = 5))
    assertEquals(loo.size, 5)
    val jack = right(Jackknife.deleteOne.plan(samples = 5))
    assertEquals(jack.size, 5)
  }

  test("readable rendering stays bounded") {
    val selection = right(
      KFold.ordered(2).plan(samples = 8, seed = 0L)
    ).splits.head.train
    assert(selection.toString.startsWith("Selection(size="))
    assert(selection.toString.contains("population=8"))
  }
