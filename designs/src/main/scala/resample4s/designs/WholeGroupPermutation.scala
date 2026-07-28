package resample4s.designs

import resample4s.core.*

/**
 * Permutes equal-sized groups as whole units and emits the induced row-level
 * permutation.
 *
 * Canonical group order is determined by
 * [[resample4s.core.Labels]]. Rows retain their ordinal order within each
 * group.
 */
final class WholeGroupPermutation private (
    val times: Int,
    val groups: Labels
) extends Design[Permutation, Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "permutation-whole-groups/v1",
      "times" -> DescriptorValue.int(times)
    )

  val definition: DesignDefinition[Permutation, Coverage] =
    DesignDefinition.general(descriptor, Some(groups)) { context =>
      if times < 1 then Left(DesignError.InvalidTimes(times))
      else
        val members = DesignSupport.labelMembers(groups)
        validateEqualSizes(members).flatMap { _ =>
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
              times.toLong + n.toLong,
              2L * n.toLong,
              n.toLong
            )
          yield GeneralPlanSpec(
            shape,
            PlanDiagnostics.empty,
            cost
          )(
            key => permute(members, seeds(key.repeat)),
            CanonicalAssignmentEncoder.permutation
          )
        }
    }

  private def validateEqualSizes(
      members: IArray[IArray[Int]]
  ): Either[DesignError, Unit] =
    val expected = members(0).length
    var group = 1
    var error: Option[DesignError] = None
    while group < members.length && error.isEmpty do
      val actual = members(group).length
      if actual != expected then
        error = Some(
          DesignError.UnequalGroupSizes(expected, actual, group)
        )
      group += 1
    error.toLeft(())

  private def permute(
      members: IArray[IArray[Int]],
      seed: Seed
  ): Permutation =
    val groupOrdinals =
      IArray.unsafeFromArray(Array.tabulate(members.length)(identity))
    val (_, sourceGroups) =
      Rand.fromSeed(seed).shuffle(groupOrdinals)
    val values = new Array[Int](groups.size)
    var destinationGroup = 0
    while destinationGroup < members.length do
      val destination = members(destinationGroup)
      val source = members(sourceGroups(destinationGroup))
      var position = 0
      while position < destination.length do
        values(destination(position)) = source(position)
        position += 1
      destinationGroup += 1
    Permutation.fromOwned(IArray.unsafeFromArray(values))

object WholeGroupPermutation:
  def apply(
      groups: Labels,
      times: Int
  ): WholeGroupPermutation =
    new WholeGroupPermutation(times, groups)
