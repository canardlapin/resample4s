package resample4s

import scala.annotation.targetName
import resample4s.core.*
import resample4s.designs as D

final class Holdout private (
    val test: SplitSize,
    private val strata: Option[Labels],
    private val groups: Option[Labels]
):
  def plan(
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    for
      fraction <- testFraction(samples)
      design <- holdoutDesign(fraction)
      compiled <- Facade.plan(design, samples, seed)
    yield compiled

  def plan(
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    population.flatMap(samples => plan(samples, seed))

  private def population: Either[DesignError, Int] =
    (strata, groups) match
      case (Some(labels), None) => Right(labels.size)
      case (None, Some(labels)) => Right(labels.size)
      case (Some(_), Some(_)) =>
        Left(DesignError.InvalidIdentifier("holdout", "strata-and-groups"))
      case (None, None) => Left(DesignError.EmptyPopulation)

  private def testFraction(samples: Int): Either[DesignError, Fraction] =
    groups match
      case Some(labels) =>
        SplitSize.toGroupFraction(test, labels.cardinality)
      case None =>
        SplitSize.toFraction(test, samples)

  private def holdoutDesign(
      fraction: Fraction
  ): Either[DesignError, D.Holdout] =
    (strata, groups) match
      case (None, None) =>
        Right(D.Holdout.assessing(fraction))
      case (Some(labels), None) =>
        Right(D.Holdout.assessingStratified(fraction, labels))
      case (None, Some(labels)) =>
        Right(D.Holdout.assessingGrouped(fraction, labels))
      case (Some(_), Some(_)) =>
        Left(DesignError.InvalidIdentifier("holdout", "strata-and-groups"))

  override def toString: String = s"Holdout(test=$test)"

object Holdout:
  def apply(test: SplitSize): Holdout =
    new Holdout(test, None, None)

  def stratified(test: SplitSize, strata: Strata): Holdout =
    new Holdout(test, Some(strata.labels), None)

  def stratified(
      test: SplitSize,
      strata: IndexedSeq[Int]
  ): Either[DesignError, Holdout] =
    Strata.from(strata).map(stratified(test, _))

  @targetName("stratifiedArray")
  def stratified(
      test: SplitSize,
      strata: Array[Int]
  ): Either[DesignError, Holdout] =
    Strata.from(strata).map(stratified(test, _))

  def grouped(test: SplitSize, groups: Groups): Holdout =
    new Holdout(test, None, Some(groups.labels))

  def grouped(
      test: SplitSize,
      groups: IndexedSeq[Int]
  ): Either[DesignError, Holdout] =
    Groups.from(groups).map(grouped(test, _))

  @targetName("groupedArray")
  def grouped(
      test: SplitSize,
      groups: Array[Int]
  ): Either[DesignError, Holdout] =
    Groups.from(groups).map(grouped(test, _))

final class ShuffleSplit private (
    val test: SplitSize,
    val resamples: Int,
    private val strata: Option[Labels],
    private val groups: Option[Labels]
):
  def plan(
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    for
      fraction <- testFraction(samples)
      design <- shuffleDesign(fraction)
      compiled <- Facade.plan(design, samples, seed)
    yield compiled

  def plan(
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    population.flatMap(samples => plan(samples, seed))

  private def population: Either[DesignError, Int] =
    (strata, groups) match
      case (Some(labels), None) => Right(labels.size)
      case (None, Some(labels)) => Right(labels.size)
      case (Some(_), Some(_)) =>
        Left(
          DesignError.InvalidIdentifier("shuffle-split", "strata-and-groups")
        )
      case (None, None) => Left(DesignError.EmptyPopulation)

  private def testFraction(samples: Int): Either[DesignError, Fraction] =
    groups match
      case Some(labels) =>
        SplitSize.toGroupFraction(test, labels.cardinality)
      case None =>
        SplitSize.toFraction(test, samples)

  private def shuffleDesign(
      fraction: Fraction
  ): Either[DesignError, Design[Split[Selection], Coverage]] =
    (strata, groups) match
      case (None, None) =>
        Right(
          if resamples == 1 then D.Holdout.assessing(fraction)
          else D.MonteCarlo.assessing(fraction, resamples)
        )
      case (Some(labels), None) =>
        Right(
          if resamples == 1 then D.Holdout.assessingStratified(fraction, labels)
          else D.MonteCarlo.assessingStratified(fraction, resamples, labels)
        )
      case (None, Some(labels)) =>
        Right(
          if resamples == 1 then D.Holdout.assessingGrouped(fraction, labels)
          else D.MonteCarlo.assessingGrouped(fraction, resamples, labels)
        )
      case (Some(_), Some(_)) =>
        Left(
          DesignError.InvalidIdentifier("shuffle-split", "strata-and-groups")
        )

  override def toString: String =
    s"ShuffleSplit(test=$test, resamples=$resamples)"

object ShuffleSplit:
  def apply(test: SplitSize, resamples: Int): ShuffleSplit =
    new ShuffleSplit(test, resamples, None, None)

  /** Statistical alias for repeated random splits. */
  def monteCarlo(test: SplitSize, resamples: Int): ShuffleSplit =
    apply(test, resamples)

  def stratified(
      test: SplitSize,
      resamples: Int,
      strata: Strata
  ): ShuffleSplit =
    new ShuffleSplit(test, resamples, Some(strata.labels), None)

  def stratified(
      test: SplitSize,
      resamples: Int,
      strata: IndexedSeq[Int]
  ): Either[DesignError, ShuffleSplit] =
    Strata.from(strata).map(stratified(test, resamples, _))

  @targetName("stratifiedArray")
  def stratified(
      test: SplitSize,
      resamples: Int,
      strata: Array[Int]
  ): Either[DesignError, ShuffleSplit] =
    Strata.from(strata).map(stratified(test, resamples, _))

  def grouped(
      test: SplitSize,
      resamples: Int,
      groups: Groups
  ): ShuffleSplit =
    new ShuffleSplit(test, resamples, None, Some(groups.labels))

  def grouped(
      test: SplitSize,
      resamples: Int,
      groups: IndexedSeq[Int]
  ): Either[DesignError, ShuffleSplit] =
    Groups.from(groups).map(grouped(test, resamples, _))

  @targetName("groupedArray")
  def grouped(
      test: SplitSize,
      resamples: Int,
      groups: Array[Int]
  ): Either[DesignError, ShuffleSplit] =
    Groups.from(groups).map(grouped(test, resamples, _))

final class Bootstrap private (
    val resamples: Int,
    private val policy: D.OobPolicy,
    private val groups: Option[Labels]
):
  def plan(
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Draw], Coverage]] =
    groups match
      case None =>
        Facade.plan(D.Bootstrap(resamples, policy), samples, seed)
      case Some(labels) =>
        Facade.plan(
          D.Bootstrap.grouped(resamples, labels, policy),
          samples,
          seed
        )

  def plan(seed: Long): Either[DesignError, SplitPlan[Split[Draw], Coverage]] =
    groups match
      case Some(labels) => plan(labels.size, seed)
      case None => Left(DesignError.EmptyPopulation)

  override def toString: String =
    s"Bootstrap(resamples=$resamples)"

object Bootstrap:
  def unconditional(resamples: Int): Bootstrap =
    new Bootstrap(resamples, D.OobPolicy.Allow, None)

  def redrawing(resamples: Int, maxAttempts: Int = 8): Bootstrap =
    new Bootstrap(resamples, D.OobPolicy.Redraw(maxAttempts), None)

  def failOnEmptyOob(resamples: Int): Bootstrap =
    new Bootstrap(resamples, D.OobPolicy.Fail, None)

  def grouped(resamples: Int, groups: Groups): Bootstrap =
    new Bootstrap(resamples, D.OobPolicy.Allow, Some(groups.labels))

  def grouped(
      resamples: Int,
      groups: IndexedSeq[Int]
  ): Either[DesignError, Bootstrap] =
    Groups.from(groups).map(grouped(resamples, _))

  @targetName("groupedArray")
  def grouped(
      resamples: Int,
      groups: Array[Int]
  ): Either[DesignError, Bootstrap] =
    Groups.from(groups).map(grouped(resamples, _))

object LeaveOneOut:
  def plan(
      samples: Int
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage.ExactOnce]] =
    Facade.plan(D.LeaveOneOut(), samples)

  def plan(
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage.ExactOnce]] =
    Facade.plan(D.LeaveOneOut(), samples, seed)

object Jackknife:
  def deleteOne: DeleteOne =
    new DeleteOne

  final class DeleteOne private[resample4s]:
    def plan(
        samples: Int
    ): Either[DesignError, SplitPlan[Split[Selection], Coverage.ExactOnce]] =
      Facade.plan(D.Jackknife.delete1, samples)

    def plan(
        samples: Int,
        seed: Long
    ): Either[DesignError, SplitPlan[Split[Selection], Coverage.ExactOnce]] =
      Facade.plan(D.Jackknife.delete1, samples, seed)

final class PermutationTest private (
    val resamples: Int,
    private val blocks: Option[Labels]
):
  def plan(
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[Permutation, Coverage]] =
    blocks match
      case None =>
        Facade.plan(D.PermutationDesign(resamples), samples, seed)
      case Some(labels) =>
        Facade.plan(
          D.PermutationDesign.within(labels, resamples),
          samples,
          seed
        )

object PermutationTest:
  def apply(resamples: Int): PermutationTest =
    new PermutationTest(resamples, None)

  def within(blocks: Blocks, resamples: Int): PermutationTest =
    new PermutationTest(resamples, Some(blocks.labels))

  def within(
      blocks: IndexedSeq[Int],
      resamples: Int
  ): Either[DesignError, PermutationTest] =
    Blocks.from(blocks).map(within(_, resamples))

/** Import external train/test allocations or fold assignments. */
object PredefinedSplit:
  def once(
      split: Split[Selection]
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    Facade.plan(
      D.PredefinedSplit.once(split),
      split.analysis.codomain,
      Facade.DeterministicSeed.value
    )

  def once(
      train: Selection,
      test: Selection
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    Split.of(train, test) match
      case Left(_: CodomainMismatch) =>
        Left(DesignError.LengthMismatch(train.codomain, test.codomain))
      case Left(error: DesignError) => Left(error)
      case Right(split) => once(split)

  def of(
      shape: PlanShape,
      units: IArray[Split[Selection]]
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage]] =
    for
      design <- D.PredefinedSplit.of(shape, units)
      plan <- Facade.plan(
        design,
        units(0).analysis.codomain,
        Facade.DeterministicSeed.value
      )
    yield plan

  def fromAssignments(
      foldOfRow: IndexedSeq[Int]
  ): Either[
    DesignError,
    SplitPlan[Split[Selection], Coverage.ExactOnce]
  ] =
    for
      labels <- Labels.retained(IArray.from(foldOfRow))
      design <- D.PredefinedSplit.partitions(labels)
      plan <- Facade.plan(design, labels.size)
    yield plan

  @targetName("fromAssignmentsArray")
  def fromAssignments(
      foldOfRow: Array[Int]
  ): Either[
    DesignError,
    SplitPlan[Split[Selection], Coverage.ExactOnce]
  ] =
    fromAssignments(foldOfRow.toIndexedSeq)

  def completeOnce(
      foldOfRow: IndexedSeq[Int]
  ): Either[DesignError, CompleteOnce] =
    Labels
      .retained(IArray.from(foldOfRow))
      .flatMap(D.PredefinedSplit.completeOnce)

/** Nest compatible ExactOnce designs. */
object Nested:
  def combine(
      outer: Design[Split[Selection], Coverage.ExactOnce],
      innerFor: Selection => Design[Split[Selection], Coverage.ExactOnce]
  ): D.Nested =
    D.Nested.combine(outer, innerFor)

  def of(
      outer: Design[Split[Selection], Coverage.ExactOnce],
      inner: Design[Split[Selection], Coverage.ExactOnce]
  ): D.Nested =
    D.Nested.of(outer, inner)

  def kFold(
      outerFolds: Int,
      innerFolds: Int
  ): D.NestedCrossValidation =
    D.NestedCrossValidation(outerFolds, innerFolds)

  def plan(
      outer: Design[Split[Selection], Coverage.ExactOnce],
      inner: Design[Split[Selection], Coverage.ExactOnce],
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[D.NestedFold, Coverage.ExactOnce]] =
    Facade.plan(D.Nested.of(outer, inner), samples, seed)

  def plan(
      outerFolds: Int,
      innerFolds: Int,
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[D.NestedFold, Coverage.ExactOnce]] =
    Facade.plan(
      D.NestedCrossValidation(outerFolds, innerFolds),
      samples,
      seed
    )
