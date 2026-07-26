package tessera.consumer

import tessera.core.*

final class PublicGeneralDesign(offset: Int)
    extends Design[Int, Coverage]:
  private val descriptor =
    DesignDescriptor
      .of(
        AlgorithmId.of("public-general/v1").toOption.get,
        IArray.unsafeFromArray(
          Array[(String, DescriptorValue)](
            ("offset", DescriptorValue.int(offset))
          )
        )
      )
      .toOption
      .get

  val definition: DesignDefinition[Int, Coverage] =
    DesignDefinition.general(descriptor, None) { context =>
      for
        shape <- PlanShape.of(1, 3)
        cost <- PlanCost.of(3, 1, 1)
        spec <- GeneralPlanSpec.of(
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
      yield spec
    }
final class PublicExactDesign(
    designLabels: Option[Labels] = None
) extends Design[Split[Selection], Coverage.Exact]:
  private val descriptor =
    DesignDescriptor
      .of(
        AlgorithmId.of("public-exact/v1").toOption.get,
        IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
      )
      .toOption
      .get

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
