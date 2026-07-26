package tessera.consumer

import tessera.core.*

final class PublicGeneralDesign private (
    offset: Int,
    descriptor: DesignDescriptor
) extends Design[Int, Coverage]:
  val definition: DesignDefinition[Int, Coverage] =
    DesignDefinition.general(descriptor) { context =>
      for
        shape <- PlanShape.of(1, 3)
        cost <- PlanCost.of(3, 1, 1)
      yield GeneralPlanSpec(
        shape,
        PlanDiagnostics.empty,
        cost
      )(
        key =>
          context.seed.value.toInt + offset + key.fold,
        new CanonicalAssignmentEncoder[Int]:
          def encode(
              value: Int,
              out: CanonicalWriter
          ): Either[DigestError, Unit] =
            out.int(value)
            Right(())
      )
    }

object PublicGeneralDesign:
  def of(offset: Int): Either[DesignError, PublicGeneralDesign] =
    DesignDescriptor
      .named(
        "public-general/v1",
        "offset" -> DescriptorValue.int(offset)
      )
      .map(descriptor => new PublicGeneralDesign(offset, descriptor))

final class PublicExactDesign private (
    designLabels: Option[Labels],
    descriptor: DesignDescriptor
) extends Design[Split[Selection], Coverage.Exact]:

  val definition
      : DesignDefinition[Split[Selection], Coverage.Exact] =
    DesignDefinition.exactPartitions(descriptor, designLabels) { context =>
      val n = context.space.size
      if n < 2 then Left(DesignError.InvalidFoldCount(2, n))
      else
        val assignments =
          IArray.unsafeFromArray(Array.tabulate(n)(index => index % 2))
        FoldPartition
          .fromAssignments(n, 2, assignments)
          .flatMap { partition =>
            ExactPartitionSpec.of(
              IArray.unsafeFromArray(Array(partition)),
              PlanDiagnostics.empty
            )
          }
    }

object PublicExactDesign:
  def of(
      designLabels: Option[Labels] = None
  ): Either[DesignError, PublicExactDesign] =
    DesignDescriptor
      .named("public-exact/v1")
      .map(descriptor => new PublicExactDesign(designLabels, descriptor))
