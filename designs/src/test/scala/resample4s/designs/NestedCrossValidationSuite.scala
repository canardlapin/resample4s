package resample4s.designs

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import resample4s.core.*

final class NestedCrossValidationSuite extends munit.ScalaCheckSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private def labels(values: Int*): Labels =
    right(Labels.dense(ints(values*)))

  private def vector(value: Reindexing): Vector[Int] =
    Vector.tabulate(value.domain)(index => right(value.at(index)))

  private def sameSplit(
      left: Split[Selection],
      rightValue: Split[Selection]
  ): Boolean =
    left.analysis == rightValue.analysis &&
      left.assessment == rightValue.assessment

  private def sameNestedPlan(
      left: Plan[NestedFold, Coverage.ExactOnce],
      rightValue: Plan[NestedFold, Coverage.ExactOnce]
  ): Boolean =
    left.shape == rightValue.shape &&
      left.keys.forall { key =>
        (left.at(key), rightValue.at(key)) match
          case (Right(first), Right(second)) =>
            sameSplit(first.outer, second.outer) &&
            first.innerSeed.value == second.innerSeed.value &&
            first.inner.shape == second.inner.shape &&
            first.inner.keys.forall(innerKey =>
              (
                first.inner.at(innerKey),
                second.inner.at(innerKey)
              ) match
                case (Right(firstInner), Right(secondInner)) =>
                  sameSplit(firstInner, secondInner)
                case _ => false
            )
          case _ => false
      }

  private def assertEmbeddedLaws(
      plan: Plan[NestedFold, Coverage.ExactOnce],
      populationSize: Int
  ): Unit =
    plan.iterator.foreach { (_, nested) =>
      assertEquals(nested.outer.analysis.codomain, populationSize)
      assertEquals(nested.outer.assessment.codomain, populationSize)
      val counts = Array.fill(populationSize)(0)
      nested.inner.iterator.foreach { (_, inner) =>
        assertEquals(inner.analysis.codomain, populationSize)
        assertEquals(inner.assessment.codomain, populationSize)
        assertEquals(
          right(inner.analysis.intersection(inner.assessment)).domain,
          0
        )
        assertEquals(
          right(inner.analysis.union(inner.assessment)),
          nested.outer.analysis
        )
        assertEquals(
          right(
            inner.assessment.intersection(nested.outer.assessment)
          ).domain,
          0
        )
        var index = 0
        while index < inner.assessment.domain do
          counts(right(inner.assessment.at(index))) += 1
          index += 1
      }
      val outerMembers = vector(nested.outer.analysis).toSet
      var ordinal = 0
      while ordinal < populationSize do
        assertEquals(counts(ordinal), if outerMembers(ordinal) then 1 else 0)
        ordinal += 1
    }

  test("one call compiles typed outer and embedded inner plans") {
    val space = right(IndexSpace.of(12))
    val compiled: Compiled[NestedFold, Coverage.ExactOnce] =
      right(
        NestedCrossValidation(
          outerFolds = 3,
          innerFolds = 2
        ).compile(space, Seed.fromLong(42L))
      )

    assertEquals(compiled.plan.shape, right(PlanShape.of(1, 3)))
    compiled.plan.iterator.foreach { (_, nested) =>
      val inner: Plan[
        Split[Selection],
        Coverage.ExactOnce
      ] = nested.inner
      assertEquals(inner.shape, right(PlanShape.of(1, 2)))
    }
    assertEmbeddedLaws(compiled.plan, space.size)
  }

  test("general Nested.of embeds plain ExactOnce designs") {
    val space = right(IndexSpace.of(12))
    val seed = Seed.fromLong(42L)
    val plan =
      right(
        Nested
          .of(KFold(3), KFold(2))
          .compile(space, seed)
      ).plan
    assertEquals(plan.shape, right(PlanShape.of(1, 3)))
    assertEmbeddedLaws(plan, space.size)

    val leaveOne =
      right(
        Nested
          .of(LeaveOneOut(), KFold(2))
          .compile(right(IndexSpace.of(6)), Seed.fromLong(3L))
      ).plan
    assertEquals(leaveOne.shape, right(PlanShape.of(1, 6)))
    assertEmbeddedLaws(leaveOne, 6)
  }

  test("public map on nested plan drops ExactOnce while embed keeps it") {
    val compiled = right(
      NestedCrossValidation(3, 2)
        .compile(right(IndexSpace.of(12)), Seed.fromLong(7L))
    )
    val inner: Plan[Split[Selection], Coverage.ExactOnce] =
      compiled.plan.first.inner
    val forgotten: Plan[NestedFold, Coverage] =
      compiled.plan.map(identity)
    assertEquals(forgotten.shape, compiled.plan.shape)
    // Embedding path retained ExactOnce on the inner plan itself.
    assertEquals(inner.shape.foldsPerRepeat, 2)
    assertEquals(inner.materialize.length, 2)
  }

  test("nested compilation exactly expands standalone K-fold composition") {
    val space = right(IndexSpace.of(13))
    val seed = Seed.fromLong(731L)
    val nested =
      right(NestedCrossValidation(4, 3).compile(space, seed)).plan
    val directOuter = right(KFold(4).compile(space, seed)).plan

    nested.iterator.foreach { (outerKey, nestedFold) =>
      val outer = right(directOuter.at(outerKey))
      assert(sameSplit(nestedFold.outer, outer))
      val localSpace = right(IndexSpace.of(outer.analysis.domain))
      val localInner =
        right(
          KFold(3).compile(localSpace, nestedFold.innerSeed)
        ).plan
      localInner.iterator.foreach { (innerKey, local) =>
        val embedded = right(nestedFold.inner.at(innerKey))
        assertEquals(
          embedded.analysis,
          right(outer.analysis.after(local.analysis))
        )
        assertEquals(
          embedded.assessment,
          right(outer.analysis.after(local.assessment))
        )
      }
    }
  }

  test("label-aware constructors project their labels at both levels") {
    val space = right(IndexSpace.of(12))
    val seed = Seed.fromLong(19L)
    val groups = labels(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6)
    val strata = labels(1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2)
    val designs =
      Vector(
        NestedCrossValidation.stratified(3, 2, strata),
        NestedCrossValidation.grouped(3, 2, groups),
        NestedCrossValidation.groupedStratified(
          3,
          2,
          groups,
          strata
        )
      )

    designs.foreach { design =>
      val compiled = right(design.compile(space, seed))
      assertEmbeddedLaws(compiled.plan, space.size)
    }
    assertEquals(designs(0).strata, Some(strata))
    assertEquals(designs(0).groups, None)
    assertEquals(designs(1).groups, Some(groups))
    assertEquals(designs(1).strata, None)
    assertEquals(designs(2).groups, Some(groups))
    assertEquals(designs(2).strata, Some(strata))
  }

  test("inner infeasibility is reported during nested compilation") {
    val space = right(IndexSpace.of(4))
    assertEquals(
      NestedCrossValidation(2, 3).compile(space, Seed.fromLong(1L)),
      Left(
        DesignError.NestedInnerFailure(
          UnitKey(0, 0),
          DesignError.TooManyFolds(3, 2)
        )
      )
    )

    val groupedSpace = right(IndexSpace.of(6))
    val groups = labels(1, 1, 2, 2, 3, 3)
    NestedCrossValidation
      .grouped(2, 2, groups)
      .compile(groupedSpace, Seed.fromLong(2L)) match
      case Left(
            DesignError.NestedInnerFailure(
              outer,
              DesignError.TooFewGroups(1, 2)
            )
          ) =>
        assertEquals(outer.repeat, 0)
        assert(outer.fold >= 0 && outer.fold < 2)
      case result =>
        fail(s"expected contextual inner failure, obtained $result")
  }

  test("the complete nested assignment participates in receipt replay") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val space = right(IndexSpace.of(12))
    val population = right(Summary.of("resample4s/size", 12L))
    val design = NestedCrossValidation(3, 2)
    val compiled = right(design.compile(space, Seed.fromLong(81L)))
    val receipt = right(compiled.receipt(population))

    assertEquals(
      receipt.verify(design, space, population),
      Right(())
    )
    assertEquals(
      receipt.verify(
        NestedCrossValidation(3, 3),
        space,
        population
      ),
      Left(ReceiptError.Mismatch(ReceiptComponent.Design))
    )
  }

  test("bijective label recoding preserves the complete nested plan") {
    val space = right(IndexSpace.of(12))
    val seed = Seed.fromLong(27L)
    val firstGroups =
      labels(10, 10, 20, 20, 30, 30, 40, 40, 50, 50, 60, 60)
    val secondGroups =
      labels(6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1)
    val firstDesign =
      NestedCrossValidation.grouped(3, 2, firstGroups)
    val secondDesign =
      NestedCrossValidation.grouped(3, 2, secondGroups)
    val first =
      right(
        firstDesign.compile(space, seed)
      )
    val second =
      right(
        secondDesign.compile(space, seed)
      )

    assertEquals(firstGroups, secondGroups)
    assert(
      sameNestedPlan(first.plan, second.plan)
    )
    assertEquals(
      firstDesign.randomizationKey,
      secondDesign.randomizationKey
    )
  }

  property("generated plain nested plans reconstruct every outer analysis") {
    val cases =
      for
        n <- Gen.choose(6, 36)
        outerFolds <- Gen.choose(2, math.min(n, 6))
        maximumInner = n - (n + outerFolds - 1) / outerFolds
        innerFolds <- Gen.choose(2, math.min(maximumInner, 6))
        seed <- Gen.long
      yield (n, outerFolds, innerFolds, seed)

    forAll(cases) { (n, outerFolds, innerFolds, seed) =>
      val plan =
        right(
          NestedCrossValidation(outerFolds, innerFolds)
            .compile(
              right(IndexSpace.of(n)),
              Seed.fromLong(seed)
            )
        ).plan
      assertEmbeddedLaws(plan, n)
    }
  }
