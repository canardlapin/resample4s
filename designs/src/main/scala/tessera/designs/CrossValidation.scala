package tessera.designs

import tessera.core.*

trait RepeatableDesign[+A, +Cov <: Coverage.Exact] extends Design[A, Cov]:
  def repeat(
      repeats: Int
  ): Either[DesignError, Design[A, Coverage.Exact]]

final class PlainKFold[Cov <: Coverage.Exact] private[designs] (
    val folds: Int,
    val repeats: Int,
    route: ExactDefinitionRoute[Cov]
) extends RepeatableDesign[Split[Selection], Cov]:
  private val descriptor =
    DesignSupport.descriptor(
      "kfold/v1",
      "folds" -> DescriptorValue.int(folds),
      "repeats" -> DescriptorValue.int(repeats)
    )

  val definition
      : DesignDefinition[Split[Selection], Cov] =
    route.oneLabel(descriptor, None) { context =>
      DesignSupport.exactSpec(
        context,
        folds,
        repeats,
        repeat =>
          DesignSupport.plainPartition(context, folds, repeat)
      )
    }

  def repeat(
      repeatCount: Int
  ): Either[DesignError, PlainKFold[Coverage.Exact]] =
    if repeatCount < 1 then
      Left(DesignError.InvalidRepeatCount(repeatCount))
    else
      Right(
        new PlainKFold(
          folds,
          repeatCount,
          ExactDefinitionRoute.repeated
        )
      )

final class StratifiedKFold[Cov <: Coverage.Exact] private[designs] (
    val folds: Int,
    val strata: Labels,
    val repeats: Int,
    route: ExactDefinitionRoute[Cov]
) extends RepeatableDesign[Split[Selection], Cov]:
  private val descriptor =
    DesignSupport.descriptor(
      "kfold-stratified/v1",
      "folds" -> DescriptorValue.int(folds),
      "repeats" -> DescriptorValue.int(repeats)
    )

  val definition
      : DesignDefinition[Split[Selection], Cov] =
    route.oneLabel(descriptor, Some(strata)) { context =>
      DesignSupport.exactSpec(
        context,
        folds,
        repeats,
        repeat =>
          DesignSupport.stratifiedPartition(
            context,
            folds,
            strata,
            repeat
          )
      )
    }

  def repeat(
      repeatCount: Int
  ): Either[DesignError, StratifiedKFold[Coverage.Exact]] =
    if repeatCount < 1 then
      Left(DesignError.InvalidRepeatCount(repeatCount))
    else
      Right(
        new StratifiedKFold(
          folds,
          strata,
          repeatCount,
          ExactDefinitionRoute.repeated
        )
      )

/** Group-atomic LPT K-fold.
  *
  * Group atomicity is absolute; fold-size balance is best-effort and reported
  * through `PlanDiagnostics`.
  */
final class GroupedKFold[Cov <: Coverage.Exact] private[designs] (
    val folds: Int,
    val groups: Labels,
    val repeats: Int,
    route: ExactDefinitionRoute[Cov]
) extends RepeatableDesign[Split[Selection], Cov]:
  private val descriptor =
    DesignSupport.descriptor(
      "kfold-grouped/v1",
      "folds" -> DescriptorValue.int(folds),
      "repeats" -> DescriptorValue.int(repeats)
    )

  val definition
      : DesignDefinition[Split[Selection], Cov] =
    route.oneLabel(descriptor, Some(groups)) { context =>
      DesignSupport.exactSpec(
        context,
        folds,
        repeats,
        repeat =>
          DesignSupport.groupedPartition(
            context,
            folds,
            groups,
            repeat
          ),
        observed =>
          DesignSupport.groupedDiagnostics(groups, folds, observed)
      )
    }

  def repeat(
      repeatCount: Int
  ): Either[DesignError, GroupedKFold[Coverage.Exact]] =
    if repeatCount < 1 then
      Left(DesignError.InvalidRepeatCount(repeatCount))
    else
      Right(
        new GroupedKFold(
          folds,
          groups,
          repeatCount,
          ExactDefinitionRoute.repeated
        )
      )

/** Group-atomic K-fold minimizing the exact incremental `BigInt` objective.
  *
  * Stratum and size balance are best-effort; no approximation guarantee is
  * claimed. Diagnostics expose the achieved objective and deviations.
  */
final class GroupedStratifiedKFold[
    Cov <: Coverage.Exact
] private[designs] (
    val folds: Int,
    val groups: Labels,
    val strata: Labels,
    val repeats: Int,
    route: ExactDefinitionRoute[Cov]
) extends RepeatableDesign[Split[Selection], Cov]:
  private val descriptor =
    DesignSupport.descriptor(
      "kfold-grouped-stratified/v1",
      "folds" -> DescriptorValue.int(folds),
      "repeats" -> DescriptorValue.int(repeats)
    )

  val definition
      : DesignDefinition[Split[Selection], Cov] =
    route.manyLabels(
      descriptor,
      IArray.unsafeFromArray(Array(groups, strata))
    ) { context =>
      if strata.size != context.space.size then
        Left(DesignError.LengthMismatch(context.space.size, strata.size))
      else
        val partitions = new Array[FoldPartition](repeats)
        val observedDiagnostics = Vector.newBuilder[PlanDiagnostics]
        var repeat = 0
        var error: Option[DesignError] = None
        if folds < 2 then error = Some(DesignError.TooFewFolds(folds, 2))
        else if folds > context.space.size then
          error = Some(
            DesignError.TooManyFolds(folds, context.space.size)
          )
        else if repeats < 1 then
          error = Some(DesignError.InvalidRepeatCount(repeats))
        while repeat < repeats && error.isEmpty do
          DesignSupport.groupedStratifiedPartition(
            context,
            folds,
            groups,
            strata,
            repeat
          ) match
            case Left(value) => error = Some(value)
            case Right((partition, observed)) =>
              partitions(repeat) = partition
              observedDiagnostics += observed
          repeat += 1
        error match
          case Some(value) => Left(value)
          case None =>
            ExactPartitionSpec.of(
              IArray.unsafeFromArray(partitions),
              DesignSupport.groupedStratifiedDiagnostics(
                groups,
                strata,
                folds,
                observedDiagnostics.result()
              )
            )
    }

  def repeat(
      repeatCount: Int
  ): Either[DesignError, GroupedStratifiedKFold[Coverage.Exact]] =
    if repeatCount < 1 then
      Left(DesignError.InvalidRepeatCount(repeatCount))
    else
      Right(
        new GroupedStratifiedKFold(
          folds,
          groups,
          strata,
          repeatCount,
          ExactDefinitionRoute.repeated
        )
      )

object KFold:
  def apply(folds: Int): PlainKFold[Coverage.ExactOnce] =
    new PlainKFold(folds, 1, ExactDefinitionRoute.once)

  def stratified(
      folds: Int,
      strata: Labels
  ): StratifiedKFold[Coverage.ExactOnce] =
    new StratifiedKFold(folds, strata, 1, ExactDefinitionRoute.once)

  def grouped(
      folds: Int,
      groups: Labels
  ): GroupedKFold[Coverage.ExactOnce] =
    new GroupedKFold(folds, groups, 1, ExactDefinitionRoute.once)

  def groupedStratified(
      folds: Int,
      groups: Labels,
      strata: Labels
  ): GroupedStratifiedKFold[Coverage.ExactOnce] =
    new GroupedStratifiedKFold(
      folds,
      groups,
      strata,
      1,
      ExactDefinitionRoute.once
    )

enum NamedRole derives CanEqual:
  case Assessing
  case Analyzing

final class Holdout private[designs] (
    val fraction: Fraction,
    val role: NamedRole
) extends Design[Split[Selection], Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "holdout/v1",
      "fraction" -> DescriptorValue.fraction(fraction),
      "role" -> DescriptorValue.variantUnchecked(
        role match
          case NamedRole.Assessing => "assessing"
          case NamedRole.Analyzing => "analyzing",
        DescriptorValue.bool(true)
      )
    )

  val definition: DesignDefinition[Split[Selection], Coverage] =
    DesignDefinition.general(descriptor, None) { context =>
      ShuffleSplitSupport.spec(
        context,
        fraction,
        role,
        times = 1
      )
    }

object Holdout:
  def assessing(fraction: Fraction): Holdout =
    new Holdout(fraction, NamedRole.Assessing)

  def analyzing(fraction: Fraction): Holdout =
    new Holdout(fraction, NamedRole.Analyzing)

final class MonteCarlo private[designs] (
    val fraction: Fraction,
    val role: NamedRole,
    val times: Int
) extends Design[Split[Selection], Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "monte-carlo/v1",
      "fraction" -> DescriptorValue.fraction(fraction),
      "role" -> DescriptorValue.variantUnchecked(
        role match
          case NamedRole.Assessing => "assessing"
          case NamedRole.Analyzing => "analyzing",
        DescriptorValue.bool(true)
      ),
      "times" -> DescriptorValue.int(times)
    )

  val definition: DesignDefinition[Split[Selection], Coverage] =
    DesignDefinition.general(descriptor, None) { context =>
      ShuffleSplitSupport.spec(
        context,
        fraction,
        role,
        times
      )
    }

object MonteCarlo:
  def assessing(fraction: Fraction, times: Int): MonteCarlo =
    new MonteCarlo(fraction, NamedRole.Assessing, times)

  def analyzing(fraction: Fraction, times: Int): MonteCarlo =
    new MonteCarlo(fraction, NamedRole.Analyzing, times)

private[designs] object ShuffleSplitSupport:
  def spec(
      context: BuildContext,
      fraction: Fraction,
      role: NamedRole,
      times: Int
  ): Either[DesignError, GeneralPlanSpec[Split[Selection]]] =
    val n = context.space.size
    val namedSize = fraction.sizeOf(n)
    val assessmentSize =
      role match
        case NamedRole.Assessing => namedSize
        case NamedRole.Analyzing => n - namedSize
    if times < 1 then Left(DesignError.InvalidTimes(times))
    else if namedSize <= 0 || namedSize >= n then
      Left(DesignError.DegenerateSplit(n, assessmentSize))
    else
      for
        shape <- PlanShape.of(times, 1)
        cost <- PlanCost.of(times.toLong, n.toLong, n.toLong)
        result <-
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
          GeneralPlanSpec.of(
            shape,
            PlanDiagnostics.empty,
            cost
          )(
            key => split(n, namedSize, role, seeds(key.repeat)),
            CanonicalAssignmentEncoder.selectionSplit
          )
      yield result

  private def split(
      n: Int,
      namedSize: Int,
      role: NamedRole,
      seed: Seed
  ): Split[Selection] =
    val shuffled = DesignSupport.shuffledIndices(n, seed)
    val named = Array.tabulate(namedSize)(shuffled(_))
    val other = Array.tabulate(n - namedSize)(index =>
      shuffled(namedSize + index)
    )
    scala.util.Sorting.quickSort(named)
    scala.util.Sorting.quickSort(other)
    val namedSelection =
      Selection.fromOwned(IArray.unsafeFromArray(named), n)
    val otherSelection =
      Selection.fromOwned(IArray.unsafeFromArray(other), n)
    role match
      case NamedRole.Assessing =>
        Split.unsafe(otherSelection, namedSelection)
      case NamedRole.Analyzing =>
        Split.unsafe(namedSelection, otherSelection)

final class LeaveOneOut private ()
    extends Design[Split[Selection], Coverage.ExactOnce]:
  private val descriptor =
    DesignSupport.descriptor("leave-one-out/v1")

  val definition
      : DesignDefinition[Split[Selection], Coverage.ExactOnce] =
    DesignDefinition.exactOncePartitions(descriptor, None) { context =>
      val n = context.space.size
      if n < 2 then Left(DesignError.DegenerateSplit(n, 1))
      else
        ExactPartitionSpec.of(
          IArray.unsafeFromArray(
            Array(FoldPartition.singletonIdentity(n))
          ),
          PlanDiagnostics.empty
        )
    }

object LeaveOneOut:
  val design: LeaveOneOut = new LeaveOneOut()

  def apply(): LeaveOneOut = design

final class LeaveOneGroupOut private (
    val groups: Labels
) extends Design[Split[Selection], Coverage.ExactOnce]:
  private val descriptor =
    DesignSupport.descriptor("leave-one-group-out/v1")

  val definition
      : DesignDefinition[Split[Selection], Coverage.ExactOnce] =
    DesignDefinition.exactOncePartitions(descriptor, Some(groups)) { context =>
      if groups.cardinality < 2 then
        Left(DesignError.TooFewGroups(groups.cardinality, 2))
      else
        FoldPartition
          .fromAssignments(
            context.space.size,
            groups.cardinality,
            groups.toIArray
          )
          .flatMap { partition =>
            ExactPartitionSpec.of(
              IArray.unsafeFromArray(Array(partition)),
              PlanDiagnostics.empty
            )
          }
    }

object LeaveOneGroupOut:
  def apply(groups: Labels): LeaveOneGroupOut =
    new LeaveOneGroupOut(groups)
