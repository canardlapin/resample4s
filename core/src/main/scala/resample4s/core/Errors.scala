package resample4s.core

sealed trait DesignError derives CanEqual

object DesignError:
  case object EmptyPopulation extends DesignError
  final case class NegativePopulation(size: Int) extends DesignError
  final case class LengthMismatch(expected: Int, actual: Int) extends DesignError
  final case class OutOfRangeIndex(index: Int, codomain: Int) extends DesignError
  final case class DuplicateIndex(index: Int) extends DesignError
  final case class NonIncreasingIndex(previous: Int, current: Int)
      extends DesignError
  final case class InvalidPermutationValue(index: Int, value: Int, size: Int)
      extends DesignError
  final case class InvalidCardinality(cardinality: Int) extends DesignError
  final case class MissingLabel(code: Int) extends DesignError
  final case class InvalidLabel(index: Int, code: Int, cardinality: Int)
      extends DesignError
  final case class InvalidFoldCount(folds: Int, populationSize: Int)
      extends DesignError
  final case class InvalidFoldAssignment(index: Int, fold: Int, folds: Int)
      extends DesignError
  final case class EmptyFold(fold: Int) extends DesignError
  case object EmptyAnalysis extends DesignError
  final case class OverlappingRoles(index: Int) extends DesignError
  final case class InvalidPlanShape(repeats: Int, foldsPerRepeat: Int)
      extends DesignError
  final case class UnitCountExceeded(requested: BigInt, budget: Long)
      extends DesignError
  final case class InvalidFraction(num: Int, den: Int) extends DesignError
  final case class InvalidBound(bound: BigInt) extends DesignError
  final case class InvalidStreamOrdinal(ordinal: Int) extends DesignError
  final case class InvalidIdentifier(kind: String, value: String)
      extends DesignError
  final case class InvalidText(reason: String) extends DesignError
  final case class DuplicateField(name: String) extends DesignError
  final case class DuplicateDiagnostic(metric: DiagnosticMetric)
      extends DesignError
  final case class InvalidPlanCost(
      residentElementsUpperBound: Long,
      workPerUnitUpperBound: Long,
      receiptWorkPerUnitUpperBound: Long
  ) extends DesignError
  final case class PartitionPopulationMismatch(
      expected: Int,
      actual: Int
  ) extends DesignError
  final case class PartitionFoldMismatch(expected: Int, actual: Int)
      extends DesignError
  final case class TooFewFolds(folds: Int, minimum: Int) extends DesignError
  final case class TooManyFolds(folds: Int, populationSize: Int)
      extends DesignError
  final case class TooFewGroups(groups: Int, minimum: Int) extends DesignError
  final case class DegenerateSplit(populationSize: Int, assessmentSize: Int)
      extends DesignError
  final case class InvalidTimes(times: Int) extends DesignError
  final case class InvalidRepeatCount(repeats: Int) extends DesignError
  final case class ExpectedSingleRepeat(actual: Int) extends DesignError
  final case class EmptyOutOfBag(unit: UnitKey, attempts: Int)
      extends DesignError
  final case class NestedInnerFailure(outer: UnitKey, cause: DesignError)
      extends DesignError
  final case class InvalidRedrawAttempts(attempts: Int) extends DesignError
  final case class PotentialDrawSizeExceeded(groups: Int, maxGroupSize: Int)
      extends DesignError
  final case class InvalidDeleteCount(delete: Int, populationSize: Int)
      extends DesignError

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
