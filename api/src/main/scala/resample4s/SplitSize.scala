package resample4s

import resample4s.core.*

/** Test-set size as an absolute count or a proportion of the population. */
enum SplitSize derives CanEqual:
  case Count(value: Int)
  case Proportion(value: Fraction)

object SplitSize:
  def count(value: Int): SplitSize = Count(value)

  def percent(value: Int): Either[DesignError, SplitSize] =
    Fraction.of(value, 100).map(Proportion(_))

  def proportion(num: Int, den: Int): Either[DesignError, SplitSize] =
    Fraction.of(num, den).map(Proportion(_))

  private[resample4s] def toFraction(
      size: SplitSize,
      samples: Int
  ): Either[DesignError, Fraction] =
    size match
      case SplitSize.Proportion(value) => Right(value)
      case SplitSize.Count(value) =>
        if value <= 0 || value >= samples then
          Left(DesignError.DegenerateSplit(samples, value))
        else Fraction.of(value, samples)

  /**
   * Interprets `SplitSize` relative to group cardinality.
   *
   * Grouped holdout / shuffle-split select whole groups, so a count is a group
   * count and a proportion is a fraction of groups — not of rows.
   */
  private[resample4s] def toGroupFraction(
      size: SplitSize,
      groupCount: Int
  ): Either[DesignError, Fraction] =
    size match
      case SplitSize.Proportion(value) => Right(value)
      case SplitSize.Count(value) =>
        if value <= 0 || value >= groupCount then
          Left(DesignError.DegenerateSplit(groupCount, value))
        else Fraction.of(value, groupCount)
