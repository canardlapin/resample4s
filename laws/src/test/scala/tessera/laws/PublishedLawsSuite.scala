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

  private def check(prop: org.scalacheck.Prop): Unit =
    val result = Test.check(Test.Parameters.default, prop)
    assert(
      result.passed,
      s"law failed: ${result.status}"
    )

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
