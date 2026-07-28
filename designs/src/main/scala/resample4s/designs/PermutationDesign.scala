package resample4s.designs

import resample4s.core.*

final class PermutationDesign private[designs] (
    val times: Int,
    val blocks: Option[Labels]
) extends Design[Permutation, Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      blocks match
        case Some(_) => "permutation-within/v1"
        case None => "permutation/v1",
      "times" -> DescriptorValue.int(times)
    )

  val definition: DesignDefinition[Permutation, Coverage] =
    DesignDefinition.general(descriptor, blocks) { context =>
      if times < 1 then Left(DesignError.InvalidTimes(times))
      else
        val n = context.space.size
        val seeds =
          Array.tabulate(times)(repeat =>
            context.derive(
              DesignSupport.childPath(
                repeat,
                StreamDomain.Unit,
                repeat
              )
            )
          )
        for
          shape <- PlanShape.of(times, 1)
          cost <- PlanCost.of(
            times.toLong + blocks.fold(0L)(_.size.toLong),
            n.toLong,
            n.toLong
          )
        yield GeneralPlanSpec(
          shape,
          PlanDiagnostics.empty,
          cost
        )(
          key =>
            blocks match
              case None =>
                Permutation.fromOwned(
                  DesignSupport.shuffledIndices(
                    n,
                    seeds(key.repeat)
                  )
                )
              case Some(labels) =>
                within(context, labels, key.repeat),
          CanonicalAssignmentEncoder.permutation
        )
    }

  private def within(
      context: BuildContext,
      labels: Labels,
      repeat: Int
  ): Permutation =
    val members = DesignSupport.labelMembers(labels)
    val values = Array.tabulate(labels.size)(identity)
    var block = 0
    while block < labels.cardinality do
      val path =
        DesignSupport
          .childPath(repeat, StreamDomain.Unit, repeat)
          .appendUnchecked(
            StreamDomain.ExchangeabilityBlock,
            block
          )
      val (_, shuffled) =
        Rand.fromSeed(context.derive(path)).shuffle(members(block))
      var index = 0
      while index < members(block).length do
        values(members(block)(index)) = shuffled(index)
        index += 1
      block += 1
    Permutation.fromOwned(IArray.unsafeFromArray(values))

object PermutationDesign:
  def apply(times: Int): PermutationDesign =
    new PermutationDesign(times, None)

  def within(blocks: Labels, times: Int): PermutationDesign =
    new PermutationDesign(times, Some(blocks))
