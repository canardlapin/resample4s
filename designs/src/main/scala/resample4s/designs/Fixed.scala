package resample4s.designs

import resample4s.core.*

/**
 * An externally supplied plan whose units are retained in repeat-major order.
 *
 * Fixed splits carry ordinary
 * [[resample4s.core.Coverage]] because validation is deliberately local to
 * each split. Assessment selections may overlap across units or omit
 * population rows entirely.
 */
final class FixedSplits private[designs] (
    val shape: PlanShape,
    private val units: IArray[Split[Selection]],
    populationSize: Int,
    cost: PlanCost
) extends Design[Split[Selection], Coverage]:
  private val descriptor =
    val encoded = new Array[DescriptorValue](units.length)
    var index = 0
    while index < units.length do
      encoded(index) = DescriptorValue.selectionSplit(units(index))
      index += 1
    DesignSupport.descriptor(
      "fixed-splits/v1",
      "population" -> DescriptorValue.int(populationSize),
      "repeats" -> DescriptorValue.int(shape.repeats),
      "folds" -> DescriptorValue.int(shape.foldsPerRepeat),
      "units" -> DescriptorValue.sequence(
        IArray.unsafeFromArray(encoded)
      )
    )

  val definition: DesignDefinition[Split[Selection], Coverage] =
    DesignDefinition.general(descriptor) { context =>
      if context.space.size != populationSize then
        Left(
          DesignError.LengthMismatch(
            context.space.size,
            populationSize
          )
        )
      else
        Right(
          GeneralPlanSpec(
            shape,
            PlanDiagnostics.empty,
            cost
          )(
            key =>
              units(
                key.repeat * shape.foldsPerRepeat + key.fold
              ),
            CanonicalAssignmentEncoder.selectionSplit
          )
        )
    }

object FixedSplits:
  private val OnceShape =
    PlanShape.of(1, 1) match
      case Right(value) => value
      case Left(error) =>
        throw new IllegalStateException(
          s"invalid fixed singleton shape: $error"
        )

  /** Imports one already-validated split. */
  def once(split: Split[Selection]): FixedSplits =
    build(
      OnceShape,
      IArray.unsafeFromArray(Array(split)),
      split.analysis.codomain
    )

  /**
   * Imports a repeat-major array of already-validated splits.
   *
   * The outer array is copied. The immutable split values and their owned
   * selection backing are retained.
   */
  def of(
      shape: PlanShape,
      units: IArray[Split[Selection]]
  ): Either[DesignError, FixedSplits] =
    if units.length != shape.unitCount then
      Left(
        DesignError.FixedUnitCountMismatch(
          shape.unitCount,
          units.length
        )
      )
    else
      val owned = new Array[Split[Selection]](units.length)
      val populationSize = units(0).analysis.codomain
      var index = 0
      var failure: Option[DesignError] = None
      while index < units.length && failure.isEmpty do
        val unit = units(index)
        val actual = unit.analysis.codomain
        if actual != populationSize then
          failure = Some(
            DesignError.FixedUnitPopulationMismatch(
              UnitKey(
                index / shape.foldsPerRepeat,
                index % shape.foldsPerRepeat
              ),
              populationSize,
              actual
            )
          )
        else owned(index) = unit
        index += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          Right(
            build(
              shape,
              IArray.unsafeFromArray(owned),
              populationSize
            )
          )

  private def build(
      shape: PlanShape,
      units: IArray[Split[Selection]],
      populationSize: Int
  ): FixedSplits =
    var retained = units.length.toLong
    var maximumReceiptWork = 0L
    var index = 0
    while index < units.length do
      val unit = units(index)
      val roleElements =
        unit.analysis.domain.toLong + unit.assessment.domain.toLong
      retained += roleElements
      maximumReceiptWork = math.max(maximumReceiptWork, roleElements)
      index += 1
    new FixedSplits(
      shape,
      units,
      populationSize,
      PlanCost.unsafe(
        residentElementsUpperBound = retained,
        workPerUnitUpperBound = 1L,
        receiptWorkPerUnitUpperBound = maximumReceiptWork
      )
    )

/** Canonical external fold assignments compiled through the exact route. */
final class FixedPartitions[Cov <: Coverage.Exact] private[designs] (
    val repeats: Int,
    val folds: Int,
    assignments: IArray[Labels],
    spec: ExactPartitionSpec,
    route: ExactDefinitionRoute[Cov]
) extends Design[Split[Selection], Cov]:
  private val descriptor =
    DesignSupport.descriptor(
      "fixed-partitions/v1",
      "repeats" -> DescriptorValue.int(repeats),
      "folds" -> DescriptorValue.int(folds)
    )

  val definition: DesignDefinition[Split[Selection], Cov] =
    route.manyLabels(descriptor, assignments)(_ => Right(spec))

object FixedPartitions:
  /** Imports one canonical assignment and proves exact-once coverage. */
  def once(
      assignments: Labels
  ): Either[
    DesignError,
    FixedPartitions[Coverage.ExactOnce]
  ] =
    val owned =
      IArray.unsafeFromArray(Array(assignments))
    build(owned, ExactDefinitionRoute.once)

  /** Constructive exact-once schedule from canonical fold assignments. */
  def completeOnce(
      assignments: Labels
  ): Either[DesignError, CompleteOnce] =
    FoldPartition
      .fromAssignments(
        assignments.size,
        assignments.cardinality,
        assignments.toIArray
      )
      .map(CompleteOnce.fromPartition)

  /** Imports one canonical assignment per repeat and proves exact coverage. */
  def repeated(
      assignments: IArray[Labels]
  ): Either[
    DesignError,
    FixedPartitions[Coverage.Exact]
  ] =
    if assignments.isEmpty then Left(DesignError.InvalidPlanShape(0, 0))
    else
      val owned = new Array[Labels](assignments.length)
      var index = 0
      while index < assignments.length do
        owned(index) = assignments(index)
        index += 1
      build(
        IArray.unsafeFromArray(owned),
        ExactDefinitionRoute.repeated
      )

  private def build[Cov <: Coverage.Exact](
      assignments: IArray[Labels],
      route: ExactDefinitionRoute[Cov]
  ): Either[DesignError, FixedPartitions[Cov]] =
    val expectedPopulation = assignments(0).size
    val expectedFolds = assignments(0).cardinality
    val partitions = new Array[FoldPartition](assignments.length)
    var index = 0
    var failure: Option[DesignError] = None
    while index < assignments.length && failure.isEmpty do
      val labels = assignments(index)
      if labels.size != expectedPopulation then
        failure = Some(
          DesignError.PartitionPopulationMismatch(
            expectedPopulation,
            labels.size
          )
        )
      else if labels.cardinality != expectedFolds then
        failure = Some(
          DesignError.PartitionFoldMismatch(
            expectedFolds,
            labels.cardinality
          )
        )
      else
        FoldPartition
          .fromAssignments(
            labels.size,
            labels.cardinality,
            labels.toIArray
          ) match
          case Left(error) => failure = Some(error)
          case Right(partition) =>
            partitions(index) = partition
      index += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        ExactPartitionSpec
          .of(
            IArray.unsafeFromArray(partitions),
            PlanDiagnostics.empty
          )
          .map { spec =>
            new FixedPartitions(
              assignments.length,
              expectedFolds,
              assignments,
              spec,
              route
            )
          }

/** Familiar alias for importing externally supplied train/test allocations. */
object PredefinedSplit:
  export FixedSplits.{once, of}

  /** Exact-once fold assignments (sklearn-style predefined split). */
  def partitions(
      assignments: Labels
  ): Either[DesignError, FixedPartitions[Coverage.ExactOnce]] =
    FixedPartitions.once(assignments)

  /** Exact per-repeat fold assignments. */
  def partitions(
      assignments: IArray[Labels]
  ): Either[DesignError, FixedPartitions[Coverage.Exact]] =
    FixedPartitions.repeated(assignments)

  /** Constructive exact-once schedule from fold-of-row assignments. */
  def completeOnce(
      assignments: Labels
  ): Either[DesignError, CompleteOnce] =
    FixedPartitions.completeOnce(assignments)
