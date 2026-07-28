package resample4s.core

opaque type ErrorCode = String

object ErrorCode:
  def fromString(value: String): Either[DesignError, ErrorCode] =
    if value.isEmpty || value.exists(ch =>
        !(ch.isLetterOrDigit || ch == '-' || ch == '_')
      )
    then Left(DesignError.InvalidIdentifier("error-code", value))
    else Right(value)

  private[resample4s] def unsafe(value: String): ErrorCode = value

  extension (code: ErrorCode) def value: String = code

  given CanEqual[ErrorCode, ErrorCode] = CanEqual.derived

object ErrorCodes:
  val emptyPopulation: ErrorCode = ErrorCode.unsafe("empty-population")
  val negativePopulation: ErrorCode = ErrorCode.unsafe("negative-population")
  val lengthMismatch: ErrorCode = ErrorCode.unsafe("length-mismatch")
  val outOfRangeIndex: ErrorCode = ErrorCode.unsafe("out-of-range-index")
  val duplicateIndex: ErrorCode = ErrorCode.unsafe("duplicate-index")
  val nonIncreasingIndex: ErrorCode = ErrorCode.unsafe("non-increasing-index")
  val invalidPermutationValue: ErrorCode =
    ErrorCode.unsafe("invalid-permutation-value")
  val invalidCardinality: ErrorCode = ErrorCode.unsafe("invalid-cardinality")
  val missingLabel: ErrorCode = ErrorCode.unsafe("missing-label")
  val invalidLabel: ErrorCode = ErrorCode.unsafe("invalid-label")
  val invalidFoldCount: ErrorCode = ErrorCode.unsafe("invalid-fold-count")
  val invalidFoldAssignment: ErrorCode =
    ErrorCode.unsafe("invalid-fold-assignment")
  val emptyFold: ErrorCode = ErrorCode.unsafe("empty-fold")
  val emptyAnalysis: ErrorCode = ErrorCode.unsafe("empty-analysis")
  val overlappingRoles: ErrorCode = ErrorCode.unsafe("overlapping-roles")
  val invalidPlanShape: ErrorCode = ErrorCode.unsafe("invalid-plan-shape")
  val fixedUnitCountMismatch: ErrorCode =
    ErrorCode.unsafe("fixed-unit-count-mismatch")
  val fixedUnitPopulationMismatch: ErrorCode =
    ErrorCode.unsafe("fixed-unit-population-mismatch")
  val unitCountExceeded: ErrorCode = ErrorCode.unsafe("unit-count-exceeded")
  val invalidFraction: ErrorCode = ErrorCode.unsafe("invalid-fraction")
  val invalidBound: ErrorCode = ErrorCode.unsafe("invalid-bound")
  val invalidStreamOrdinal: ErrorCode =
    ErrorCode.unsafe("invalid-stream-ordinal")
  val invalidStreamTag: ErrorCode = ErrorCode.unsafe("invalid-stream-tag")
  val invalidIdentifier: ErrorCode = ErrorCode.unsafe("invalid-identifier")
  val invalidText: ErrorCode = ErrorCode.unsafe("invalid-text")
  val duplicateField: ErrorCode = ErrorCode.unsafe("duplicate-field")
  val duplicateDiagnostic: ErrorCode =
    ErrorCode.unsafe("duplicate-diagnostic")
  val invalidPlanCost: ErrorCode = ErrorCode.unsafe("invalid-plan-cost")
  val invalidMetricId: ErrorCode = ErrorCode.unsafe("invalid-metric-id")
  val partitionPopulationMismatch: ErrorCode =
    ErrorCode.unsafe("partition-population-mismatch")
  val partitionFoldMismatch: ErrorCode =
    ErrorCode.unsafe("partition-fold-mismatch")
  val tooFewFolds: ErrorCode = ErrorCode.unsafe("too-few-folds")
  val tooManyFolds: ErrorCode = ErrorCode.unsafe("too-many-folds")
  val tooFewGroups: ErrorCode = ErrorCode.unsafe("too-few-groups")
  val degenerateSplit: ErrorCode = ErrorCode.unsafe("degenerate-split")
  val invalidTimes: ErrorCode = ErrorCode.unsafe("invalid-times")
  val invalidRepeatCount: ErrorCode = ErrorCode.unsafe("invalid-repeat-count")
  val expectedSingleRepeat: ErrorCode =
    ErrorCode.unsafe("expected-single-repeat")
  val emptyOutOfBag: ErrorCode = ErrorCode.unsafe("empty-out-of-bag")
  val nestedInnerFailure: ErrorCode = ErrorCode.unsafe("nested-inner-failure")
  val invalidRedrawAttempts: ErrorCode =
    ErrorCode.unsafe("invalid-redraw-attempts")
  val potentialDrawSizeExceeded: ErrorCode =
    ErrorCode.unsafe("potential-draw-size-exceeded")
  val invalidDeleteCount: ErrorCode = ErrorCode.unsafe("invalid-delete-count")
  val labelRefinementViolation: ErrorCode =
    ErrorCode.unsafe("label-refinement-violation")
  val unequalGroupSizes: ErrorCode = ErrorCode.unsafe("unequal-group-sizes")

/**
 * Open design/compile failure channel.
 *
 * Library cases live under [[DesignError]]; external authors may define their
 * own subtypes with stable [[ErrorCode]] values.
 */
trait DesignError:
  def code: ErrorCode
  def message: String

object DesignError:
  case object EmptyPopulation extends DesignError:
    val code: ErrorCode = ErrorCodes.emptyPopulation
    val message: String = "population must contain at least one row"

  final case class NegativePopulation(size: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.negativePopulation
    val message: String =
      s"population size must be non-negative, obtained $size"

  final case class LengthMismatch(expected: Int, actual: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.lengthMismatch
    val message: String = s"expected length $expected, obtained $actual"

  final case class OutOfRangeIndex(index: Int, codomain: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.outOfRangeIndex
    val message: String =
      s"index $index is outside codomain of size $codomain"

  final case class DuplicateIndex(index: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.duplicateIndex
    val message: String = s"duplicate index $index"

  final case class NonIncreasingIndex(previous: Int, current: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.nonIncreasingIndex
    val message: String =
      s"selection indices must increase: $previous then $current"

  final case class InvalidPermutationValue(index: Int, value: Int, size: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidPermutationValue
    val message: String =
      s"permutation index $index has value $value outside 0 until $size"

  final case class InvalidCardinality(cardinality: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidCardinality
    val message: String = s"invalid label cardinality $cardinality"

  final case class MissingLabel(labelCode: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.missingLabel
    val message: String = s"missing label code $labelCode"

  final case class InvalidLabel(index: Int, labelCode: Int, cardinality: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidLabel
    val message: String =
      s"label at $index has code $labelCode outside 0 until $cardinality"

  final case class InvalidFoldCount(folds: Int, populationSize: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidFoldCount
    val message: String =
      s"fold count $folds is invalid for population $populationSize"

  final case class InvalidFoldAssignment(index: Int, fold: Int, folds: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidFoldAssignment
    val message: String =
      s"row $index assigned to fold $fold outside 0 until $folds"

  final case class EmptyFold(fold: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.emptyFold
    val message: String = s"fold $fold is empty"

  case object EmptyAnalysis extends DesignError:
    val code: ErrorCode = ErrorCodes.emptyAnalysis
    val message: String = "analysis selection must be non-empty"

  final case class OverlappingRoles(index: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.overlappingRoles
    val message: String =
      s"analysis and assessment both contain ordinal $index"

  final case class InvalidPlanShape(repeats: Int, foldsPerRepeat: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidPlanShape
    val message: String =
      s"invalid plan shape repeats=$repeats foldsPerRepeat=$foldsPerRepeat"

  final case class FixedUnitCountMismatch(expected: Int, actual: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.fixedUnitCountMismatch
    val message: String =
      s"expected $expected fixed units, obtained $actual"

  final case class FixedUnitPopulationMismatch(
      unit: UnitKey,
      expected: Int,
      actual: Int
  ) extends DesignError:
    val code: ErrorCode = ErrorCodes.fixedUnitPopulationMismatch
    val message: String =
      s"unit $unit has population $actual, expected $expected"

  final case class UnitCountExceeded(requested: BigInt, budget: Long)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.unitCountExceeded
    val message: String =
      s"requested unit count $requested exceeds budget $budget"

  final case class InvalidFraction(num: Int, den: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidFraction
    val message: String = s"invalid fraction $num/$den"

  final case class InvalidBound(bound: BigInt) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidBound
    val message: String = s"invalid bound $bound"

  final case class InvalidStreamOrdinal(ordinal: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidStreamOrdinal
    val message: String =
      s"stream ordinal must be non-negative, obtained $ordinal"

  final case class InvalidStreamTag(tag: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidStreamTag
    val message: String =
      s"custom stream tag must be >= 100, obtained $tag"

  final case class InvalidIdentifier(kind: String, value: String)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidIdentifier
    val message: String = s"invalid $kind identifier: $value"

  final case class InvalidText(reason: String) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidText
    val message: String = reason

  final case class DuplicateField(name: String) extends DesignError:
    val code: ErrorCode = ErrorCodes.duplicateField
    val message: String = s"duplicate field $name"

  final case class DuplicateDiagnostic(metric: MetricId) extends DesignError:
    val code: ErrorCode = ErrorCodes.duplicateDiagnostic
    val message: String = s"duplicate diagnostic ${metric.value}"

  final case class InvalidPlanCost(
      residentElementsUpperBound: Long,
      workPerUnitUpperBound: Long,
      receiptWorkPerUnitUpperBound: Long
  ) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidPlanCost
    val message: String =
      s"invalid plan cost resident=$residentElementsUpperBound work=$workPerUnitUpperBound receipt=$receiptWorkPerUnitUpperBound"

  final case class InvalidMetricId(value: String) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidMetricId
    val message: String = s"invalid metric id: $value"

  final case class PartitionPopulationMismatch(
      expected: Int,
      actual: Int
  ) extends DesignError:
    val code: ErrorCode = ErrorCodes.partitionPopulationMismatch
    val message: String =
      s"partition population $actual does not match expected $expected"

  final case class PartitionFoldMismatch(expected: Int, actual: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.partitionFoldMismatch
    val message: String =
      s"partition fold count $actual does not match expected $expected"

  final case class TooFewFolds(folds: Int, minimum: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.tooFewFolds
    val message: String = s"fold count $folds is below minimum $minimum"

  final case class TooManyFolds(folds: Int, populationSize: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.tooManyFolds
    val message: String =
      s"fold count $folds exceeds population $populationSize"

  final case class TooFewGroups(groups: Int, minimum: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.tooFewGroups
    val message: String = s"group count $groups is below minimum $minimum"

  final case class DegenerateSplit(populationSize: Int, assessmentSize: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.degenerateSplit
    val message: String =
      s"degenerate split population=$populationSize assessment=$assessmentSize"

  final case class InvalidTimes(times: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidTimes
    val message: String = s"times must be positive, obtained $times"

  final case class InvalidRepeatCount(repeats: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidRepeatCount
    val message: String = s"repeat count must be positive, obtained $repeats"

  final case class ExpectedSingleRepeat(actual: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.expectedSingleRepeat
    val message: String =
      s"exact-once coverage requires one repeat, obtained $actual"

  final case class EmptyOutOfBag(unit: UnitKey, attempts: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.emptyOutOfBag
    val message: String =
      s"empty out-of-bag for $unit after $attempts attempts"

  final case class NestedInnerFailure(outer: UnitKey, cause: DesignError)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.nestedInnerFailure
    val message: String =
      s"nested inner failure at $outer: ${cause.message}"

  final case class InvalidRedrawAttempts(attempts: Int) extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidRedrawAttempts
    val message: String =
      s"redraw attempts must be positive, obtained $attempts"

  final case class PotentialDrawSizeExceeded(groups: Int, maxGroupSize: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.potentialDrawSizeExceeded
    val message: String =
      s"potential draw size exceeded for $groups groups (max group $maxGroupSize)"

  final case class InvalidDeleteCount(delete: Int, populationSize: Int)
      extends DesignError:
    val code: ErrorCode = ErrorCodes.invalidDeleteCount
    val message: String =
      s"delete count $delete is invalid for population $populationSize"

  final case class LabelRefinementViolation(
      finerClass: Int,
      expectedCoarserClass: Int,
      actualCoarserClass: Int,
      index: Int
  ) extends DesignError:
    val code: ErrorCode = ErrorCodes.labelRefinementViolation
    val message: String =
      s"finer class $finerClass crosses coarser classes $expectedCoarserClass and $actualCoarserClass at index $index"

  final case class UnequalGroupSizes(
      expected: Int,
      actual: Int,
      group: Int
  ) extends DesignError:
    val code: ErrorCode = ErrorCodes.unequalGroupSizes
    val message: String =
      s"group $group has size $actual, expected $expected"

  given CanEqual[DesignError, DesignError] = CanEqual.derived

sealed trait DigestError derives CanEqual

object DigestError:
  final case class InvalidAlgorithmId(value: String) extends DigestError
  case object EmptyDigestValue extends DigestError
  final case class InvalidCanonicalText(reason: String) extends DigestError
  final case class ProviderFailure(message: String) extends DigestError

sealed trait FingerprintError derives CanEqual

object FingerprintError:
  final case class InvalidSourceIdentity(uri: String, version: String)
      extends FingerprintError
  final case class InvalidPolicyId(value: String) extends FingerprintError

enum ReceiptComponent derives CanEqual:
  case Design
  case Population
  case Labels
  case Assignment

sealed trait ReceiptError derives CanEqual

object ReceiptError:
  final case class ProviderMismatch(
      expected: DigestAlgorithmId,
      actual: DigestAlgorithmId
  ) extends ReceiptError
  final case class DigestFailure(error: DigestError) extends ReceiptError
  final case class CompilationFailure(error: DesignError) extends ReceiptError
  final case class Mismatch(component: ReceiptComponent) extends ReceiptError

final case class OutOfDomain(index: Int, domain: Int) derives CanEqual

final case class CodomainMismatch(left: Int, right: Int) derives CanEqual

final case class DomainMismatch(leftDomain: Int, rightCodomain: Int)
    derives CanEqual

final case class UnknownUnit(key: UnitKey, shape: PlanShape) derives CanEqual

final case class ShapeMismatch(left: PlanShape, right: PlanShape)
    derives CanEqual

final case class UnknownFold(fold: Int, folds: Int) derives CanEqual
