package resample4s

import scala.annotation.targetName
import resample4s.core.*
import resample4s.designs as D

/**
 * Ordinary-user K-fold configuration.
 *
 * `shuffle` is explicit for plain K-fold. The default `true` preserves the
 * historical Resample4s allocator; `false` selects ordered `kfold-ordered/v1`.
 * Stratified and grouped variants remain shuffled at the allocator.
 */
final class KFold private (
    val folds: Int,
    val shuffle: Boolean,
    private val strata: Option[Labels],
    private val groups: Option[Labels]
):
  def plan(
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage.ExactOnce]] =
    design.flatMap(Facade.plan(_, samples, seed))

  /** Binds population size from attached group/strata labels. */
  def plan(
      seed: Long
  ): Either[DesignError, SplitPlan[Split[Selection], Coverage.ExactOnce]] =
    population.flatMap(samples => plan(samples, seed))

  private def population: Either[DesignError, Int] =
    (strata, groups) match
      case (Some(labels), None) => Right(labels.size)
      case (None, Some(labels)) => Right(labels.size)
      case (Some(stratumLabels), Some(groupLabels)) =>
        if stratumLabels.size != groupLabels.size then
          Left(
            DesignError.LengthMismatch(groupLabels.size, stratumLabels.size)
          )
        else Right(groupLabels.size)
      case (None, None) => Left(DesignError.EmptyPopulation)

  private def design: Either[
    DesignError,
    Design[Split[Selection], Coverage.ExactOnce]
  ] =
    (strata, groups) match
      case (None, None) =>
        Right(
          if shuffle then D.KFold.shuffled(folds) else D.KFold.ordered(folds)
        )
      case (Some(labels), None) =>
        Right(D.KFold.stratified(folds, labels))
      case (None, Some(labels)) =>
        Right(D.KFold.grouped(folds, labels))
      case (Some(stratumLabels), Some(groupLabels)) =>
        if stratumLabels.size != groupLabels.size then
          Left(
            DesignError.LengthMismatch(groupLabels.size, stratumLabels.size)
          )
        else
          Right(
            D.KFold.groupedStratified(folds, groupLabels, stratumLabels)
          )

  override def toString: String =
    s"KFold(folds=$folds, shuffle=$shuffle)"

object KFold:
  def apply(folds: Int, shuffle: Boolean = true): KFold =
    new KFold(folds, shuffle, None, None)

  def shuffled(folds: Int): KFold = apply(folds, shuffle = true)

  def ordered(folds: Int): KFold = apply(folds, shuffle = false)

  def stratified(folds: Int, strata: Strata): KFold =
    new KFold(folds, shuffle = true, Some(strata.labels), None)

  def stratified(
      folds: Int,
      strata: IndexedSeq[Int]
  ): Either[DesignError, KFold] =
    Strata.from(strata).map(stratified(folds, _))

  @targetName("stratifiedArray")
  def stratified(
      folds: Int,
      strata: Array[Int]
  ): Either[DesignError, KFold] =
    Strata.from(strata).map(stratified(folds, _))

  def grouped(folds: Int, groups: Groups): KFold =
    new KFold(folds, shuffle = true, None, Some(groups.labels))

  def grouped(
      folds: Int,
      groups: IndexedSeq[Int]
  ): Either[DesignError, KFold] =
    Groups.from(groups).map(grouped(folds, _))

  @targetName("groupedArray")
  def grouped(
      folds: Int,
      groups: Array[Int]
  ): Either[DesignError, KFold] =
    Groups.from(groups).map(grouped(folds, _))

  def groupedStratified(
      folds: Int,
      groups: Groups,
      strata: Strata
  ): Either[DesignError, KFold] =
    if groups.labels.size != strata.labels.size then
      Left(DesignError.LengthMismatch(groups.labels.size, strata.labels.size))
    else
      Right(
        new KFold(
          folds,
          shuffle = true,
          Some(strata.labels),
          Some(groups.labels)
        )
      )

  def groupedStratified(
      folds: Int,
      groups: IndexedSeq[Int],
      strata: IndexedSeq[Int]
  ): Either[DesignError, KFold] =
    for
      groupLabels <- Groups.from(groups)
      stratumLabels <- Strata.from(strata)
      config <- groupedStratified(folds, groupLabels, stratumLabels)
    yield config
