package tessera.laws

import org.scalacheck.Test
import tessera.core.*
import tessera.designs.*

final class PublishedLawsSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  private def labels(values: Int*): Labels =
    right(Labels.dense(ints(values*), values.length))

  private def check(prop: org.scalacheck.Prop): Unit =
    val result = Test.check(Test.Parameters.default, prop)
    assert(
      result.passed,
      s"law failed: ${result.status}"
    )

  private def checkFails(prop: org.scalacheck.Prop): Unit =
    val result = Test.check(Test.Parameters.default, prop)
    assert(!result.passed, "expected the deliberately broken fixture to fail")

  test("published reindexing laws pass on representative values") {
    val space = right(IndexSpace.of(6))
    val outer = right(Selection.from(ints(0, 2, 3, 5), space))
    val inner =
      right(Selection.from(ints(0, 2), right(IndexSpace.of(4))))
    check(
      ReindexingLaws.pullbackFunctoriality(
        IArray.unsafeFromArray(Array("a", "b", "c", "d", "e", "f")),
        outer,
        inner
      )
    )
    check(
      ReindexingLaws.identity(
        IArray.unsafeFromArray(Array(1, 2, 3))
      )
    )
    check(
      ReindexingLaws.injectionFactorization(
        right(Injection.from(ints(4, 0, 3), space))
      )
    )
    check(
      ReindexingLaws.permutationGroup(
        right(Permutation.from(ints(1, 2, 0))),
        right(Permutation.from(ints(2, 0, 1))),
        right(Permutation.from(ints(0, 2, 1)))
      )
    )
  }
  test("published exact-plan laws pass on K-fold") {
    val space = right(IndexSpace.of(12))
    val plan =
      right(KFold(4).compile(space, Seed.fromLong(5L))).plan
    check(PlanLaws.exactCoverage(plan, 12))
    check(PlanLaws.disjointness(plan))
    check(PlanLaws.reconstruction(plan, 12))
  }

  test("published grouping and stratification laws detect role violations") {
    val groups = labels(1, 1, 2, 2, 3, 3, 4, 4)
    val strata = labels(1, 2, 1, 2, 1, 2, 1, 2)
    val space = right(IndexSpace.of(groups.size))
    val grouped =
      right(
        KFold
          .grouped(4, groups)
          .compile(space, Seed.fromLong(19L))
      ).plan
    val stratified =
      right(
        KFold
          .stratified(4, strata)
          .compile(space, Seed.fromLong(20L))
      ).plan
    check(PlanLaws.groupAtomicity(grouped, groups))
    check(PlanLaws.stratificationBalance(stratified, strata))

    val analysis = right(Selection.from(ints(0, 1), space))
    val assessment = right(Selection.from(ints(2, 3, 4, 5, 6, 7), space))
    val broken =
      Plan.fromGenerator[Split[Selection], Coverage](
        right(PlanShape.of(1, 1)),
        _ => Split.unsafe(analysis, assessment)
      )
    val crossingGroups = labels(1, 2, 1, 2, 3, 3, 4, 4)
    checkFails(PlanLaws.groupAtomicity(broken, crossingGroups))
  }

  test("published bootstrap and permutation laws pass catalogue values") {
    val n = 8
    val space = right(IndexSpace.of(n))
    val bootstrap =
      right(
        Bootstrap(20, OobPolicy.Allow)
          .compile(space, Seed.fromLong(71L))
      ).plan
    bootstrap.iterator.foreach { (_, split) =>
      check(ResamplingLaws.bootstrapSplit(split, n, n))
      val embedding =
        right(
          Selection.from(
            ints(0, 2, 3, 6, 8, 9, 12, 15),
            right(IndexSpace.of(16))
          )
        )
      check(ResamplingLaws.bootstrapComposition(split, embedding))
    }

    val free =
      right(
        PermutationDesign(20).compile(space, Seed.fromLong(72L))
      ).plan
    free.iterator.foreach { (_, permutation) =>
      check(PermutationLaws.bijection(permutation))
    }

    val blocks = labels(1, 1, 2, 2, 2, 3, 3, 3)
    val within =
      right(
        PermutationDesign
          .within(blocks, 20)
          .compile(space, Seed.fromLong(73L))
      ).plan
    within.iterator.foreach { (_, permutation) =>
      check(PermutationLaws.withinBlocks(permutation, blocks))
    }
  }

  test("published metamorphic and perturbation laws observe assignments") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64

    def intDesign(offset: Int): Design[Int, Coverage] =
      val descriptor =
        right(
          DesignDescriptor.of(
            right(AlgorithmId.of("law-int-design/v1")),
            IArray.unsafeFromArray(
              Array(
                "offset" -> DescriptorValue.int(offset)
              )
            )
          )
        )
      new Design[Int, Coverage]:
        val definition =
          DesignDefinition.general(descriptor, None) { _ =>
            for
              shape <- PlanShape.of(1, 2)
              cost <- PlanCost.of(0, 1, 1)
              spec <- GeneralPlanSpec.of(
                shape,
                PlanDiagnostics.empty,
                cost
              )(
                key => offset + key.fold,
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

    val space = right(IndexSpace.of(4))
    val seed = Seed.fromLong(9L)
    check(
      DesignLaws.equivalentCompilations(
        intDesign(4),
        intDesign(4),
        space,
        seed
      )(_ == _)
    )
    check(
      DesignLaws.assignmentPerturbation(
        intDesign(4),
        intDesign(5),
        space,
        seed,
        right(Summary.of("tessera/size", 4L)),
        UnitKey(0, 0)
      )(_ != _)
    )

    val firstGroups = labels(10, 10, 20, 20, 30, 30, 40, 40)
    val secondGroups = labels(-7, -7, 3, 3, 99, 99, 1, 1)
    val firstStrata = labels(5, 6, 5, 6, 5, 6, 5, 6)
    val secondStrata = labels(100, -1, 100, -1, 100, -1, 100, -1)
    check(
      DesignLaws.labelRecoding(
        KFold.groupedStratified(2, firstGroups, firstStrata),
        KFold.groupedStratified(2, secondGroups, secondStrata),
        IArray.unsafeFromArray(
          Array(
            (firstGroups, secondGroups),
            (firstStrata, secondStrata)
          )
        ),
        right(IndexSpace.of(8)),
        Seed.fromLong(21L)
      )(_ == _)
    )
    val brokenGroups = labels(10, 20, 10, 20, 30, 30, 40, 40)
    checkFails(
      DesignLaws.labelRecoding(
        KFold.groupedStratified(2, firstGroups, firstStrata),
        KFold.groupedStratified(2, brokenGroups, secondStrata),
        IArray.unsafeFromArray(
          Array(
            (firstGroups, brokenGroups),
            (firstStrata, secondStrata)
          )
        ),
        right(IndexSpace.of(8)),
        Seed.fromLong(21L)
      )(_ == _)
    )
  }

  test("design conformance laws detect broken generators and costs") {
    val descriptor =
      right(
        DesignDescriptor.of(
          right(AlgorithmId.of("broken-general/v1")),
          IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
        )
      )
    val cost = right(PlanCost.of(0, 0, 0))
    val brokenGenerator =
      new Design[Int, Coverage]:
        val definition =
          DesignDefinition.general(descriptor, None) { _ =>
            PlanShape.of(1, 2).flatMap { shape =>
              GeneralPlanSpec.of(
                shape,
                PlanDiagnostics.empty,
                cost
              )(
                key =>
                  if key.fold == 1 then
                    throw new IllegalStateException("broken")
                  else 0,
                new CanonicalAssignmentEncoder[Int]:
                  def encode(
                      value: Int,
                      out: CanonicalWriter
                  ): Either[DigestError, Unit] =
                    out.int(value)
                    Right(())
              )
            }
          }
    val space = right(IndexSpace.of(2))
    val seed = Seed.fromLong(0L)
    val total =
      Test.check(
        Test.Parameters.default,
        DesignLaws.totalUnits(brokenGenerator, space, seed)
      )
    assert(!total.passed)

    val underdeclared =
      new Design[Int, Coverage]:
        val definition =
          DesignDefinition.general(descriptor, None) { _ =>
            PlanShape.of(1, 1).flatMap { shape =>
              GeneralPlanSpec.of(
                shape,
                PlanDiagnostics.empty,
                cost
              )(
                _ => 1,
                new CanonicalAssignmentEncoder[Int]:
                  def encode(
                      value: Int,
                      out: CanonicalWriter
                  ): Either[DigestError, Unit] =
                    out.int(value)
                    Right(())
              )
            }
          }
    val measurement =
      WorkMeasurement.of[Int](residentElements = 1L)(
        _ => 1L,
        _ => 1L
      )
    val costResult =
      Test.check(
        Test.Parameters.default,
        DesignLaws.costConformance(
          underdeclared,
          space,
          seed,
          measurement
        )
      )
    assert(!costResult.passed)
  }

  test("receipt replay law detects an encoder failure") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val descriptor =
      right(
        DesignDescriptor.of(
          right(AlgorithmId.of("broken-encoder/v1")),
          IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
        )
      )
    val broken =
      new Design[Int, Coverage]:
        val definition =
          DesignDefinition.general(descriptor, None) { _ =>
            for
              shape <- PlanShape.of(1, 1)
              cost <- PlanCost.of(1, 1, 1)
              spec <- GeneralPlanSpec.of(
                shape,
                PlanDiagnostics.empty,
                cost
              )(
                _ => 1,
                new CanonicalAssignmentEncoder[Int]:
                  def encode(
                      value: Int,
                      out: CanonicalWriter
                  ): Either[DigestError, Unit] =
                    Left(DigestError.ProviderFailure("broken encoder"))
              )
            yield spec
          }
    val result =
      Test.check(
        Test.Parameters.default,
        DesignLaws.receiptReplay(
          broken,
          right(IndexSpace.of(1)),
          Seed.fromLong(0L),
          right(Summary.of("tessera/size", 1L))
        )
      )
    assert(!result.passed)
  }
