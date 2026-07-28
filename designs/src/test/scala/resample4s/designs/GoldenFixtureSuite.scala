package resample4s.designs

import resample4s.core.*

final class GoldenFixtureSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private def labels(values: Int*): Labels =
    right(Labels.dense(ints(values*)))

  private def vector(value: Reindexing): Vector[Int] =
    Vector.tabulate(value.domain)(index => value.at(index).toOption.get)

  private def assignment(
      plan: Plan[Split[Selection], ? <: Coverage],
      n: Int
  ): Vector[Int] =
    val result = Array.fill(n)(-1)
    var fold = 0
    while fold < plan.shape.foldsPerRepeat do
      val assessment = right(plan.at(UnitKey(0, fold))).assessment
      var index = 0
      while index < assessment.domain do
        result(assessment.at(index).toOption.get) = fold
        index += 1
      fold += 1
    result.toVector

  test("catalogue golden fixtures are platform-identical") {
    val space = right(IndexSpace.of(8))
    val seed = Seed.fromLong(42L)
    val groups = labels(1, 1, 2, 2, 3, 3, 4, 4)
    val strata = labels(1, 2, 1, 2, 1, 2, 1, 2)
    val nested =
      right(
        NestedCrossValidation(3, 2).compile(space, seed)
      ).plan.first

    val observed =
      Vector(
        "kfold" -> assignment(
          right(KFold(3).compile(space, seed)).plan,
          8
        ),
        "stratified" -> assignment(
          right(KFold.stratified(3, strata).compile(space, seed)).plan,
          8
        ),
        "grouped" -> assignment(
          right(KFold.grouped(3, groups).compile(space, seed)).plan,
          8
        ),
        "grouped-stratified" -> assignment(
          right(
            KFold
              .groupedStratified(3, groups, strata)
              .compile(space, seed)
          ).plan,
          8
        ),
        "nested-outer-assessment" -> vector(
          nested.outer.assessment
        ),
        "nested-inner-assessment" -> vector(
          nested.inner.first.assessment
        ),
        "holdout" -> vector(
          right(
            right(
              Holdout
                .assessing(right(Fraction.of(3, 8)))
                .compile(space, seed)
            ).plan.at(UnitKey(0, 0))
          ).assessment
        ),
        "monte-carlo" -> vector(
          right(
            right(
              MonteCarlo
                .assessing(right(Fraction.of(3, 8)), 2)
                .compile(space, seed)
            ).plan.at(UnitKey(1, 0))
          ).assessment
        ),
        "leave-one-out" -> vector(
          right(
            right(LeaveOneOut().compile(space, seed)).plan
              .at(UnitKey(0, 5))
          ).assessment
        ),
        "leave-one-group-out" -> vector(
          right(
            right(LeaveOneGroupOut(groups).compile(space, seed)).plan
              .at(UnitKey(0, 2))
          ).assessment
        ),
        "bootstrap" -> vector(
          right(
            right(
              Bootstrap(1, OobPolicy.Allow).compile(space, seed)
            ).plan.at(UnitKey(0, 0))
          ).analysis
        ),
        "grouped-bootstrap" -> vector(
          right(
            right(
              Bootstrap
                .grouped(1, groups, OobPolicy.Allow)
                .compile(space, seed)
            ).plan.at(UnitKey(0, 0))
          ).analysis
        ),
        "delete-one" -> vector(
          right(
            right(Jackknife.delete1.compile(space, seed)).plan
              .at(UnitKey(0, 6))
          ).assessment
        ),
        "delete-d-exhaustive" -> vector(
          right(
            right(
              Jackknife.deleteD
                .exhaustive(3)
                .compile(space, seed)
            ).plan.at(UnitKey(0, 7))
          ).assessment
        ),
        "delete-d-sampled" -> vector(
          right(
            right(
              Jackknife.deleteD.sampled(3, 2).compile(space, seed)
            ).plan.at(UnitKey(1, 0))
          ).assessment
        ),
        "permutation" -> vector(
          right(
            right(PermutationDesign(1).compile(space, seed)).plan
              .at(UnitKey(0, 0))
          )
        ),
        "permutation-within" -> vector(
          right(
            right(
              PermutationDesign.within(groups, 1).compile(space, seed)
            ).plan.at(UnitKey(0, 0))
          )
        )
      )

    assertEquals(
      observed,
      Vector(
        "kfold" -> Vector(2, 1, 2, 0, 1, 0, 1, 0),
        "stratified" -> Vector(0, 0, 1, 1, 0, 1, 2, 2),
        "grouped" -> Vector(0, 0, 1, 1, 1, 1, 2, 2),
        "grouped-stratified" -> Vector(2, 2, 2, 2, 0, 0, 1, 1),
        "nested-outer-assessment" -> Vector(3, 5, 7),
        "nested-inner-assessment" -> Vector(0, 2, 4),
        "holdout" -> Vector(0, 5, 6),
        "monte-carlo" -> Vector(1, 2, 4),
        "leave-one-out" -> Vector(5),
        "leave-one-group-out" -> Vector(4, 5),
        "bootstrap" -> Vector(0, 5, 1, 0, 2, 0, 0, 2),
        "grouped-bootstrap" -> Vector(4, 5, 4, 5, 0, 1, 2, 3),
        "delete-one" -> Vector(6),
        "delete-d-exhaustive" -> Vector(0, 2, 4),
        "delete-d-sampled" -> Vector(2, 5, 7),
        "permutation" -> Vector(5, 4, 3, 1, 6, 2, 0, 7),
        "permutation-within" -> Vector(0, 1, 3, 2, 5, 4, 7, 6)
      )
    )
  }
