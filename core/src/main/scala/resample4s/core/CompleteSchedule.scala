package resample4s.core

/**
 * Constructive witness that assessments partition the population once per
 * repeat.
 *
 * Unlike phantom [[Coverage.Exact]], this value retains the fold assignment
 * that establishes the claim.
 */
final class CompletePerRepeat private (
    private val partitions: IArray[FoldPartition]
):
  val repeats: Int = partitions.length
  val populationSize: Int = partitions(0).populationSize
  val foldsPerRepeat: Int = partitions(0).folds
  val layout: FoldLayout =
    FoldLayout.unsafe(repeats, foldsPerRepeat)

  def partition(repeat: Int): Either[OutOfDomain, FoldPartition] =
    if repeat >= 0 && repeat < repeats then Right(partitions(repeat))
    else Left(OutOfDomain(repeat, repeats))

  def assessmentFold(
      repeat: Int,
      row: Int
  ): Either[OutOfDomain, Int] =
    partition(repeat).flatMap(_.assignmentAt(row))

  /**
   * Lazy plan carrying [[Coverage.Exact]] (or [[Coverage.ExactOnce]] when
   * `repeats == 1`).
   */
  def plan: Plan[Split[Selection], Coverage.Exact] =
    val shape = layout.shape
    Plan.fromGenerator(
      shape,
      key =>
        val part = partitions(key.repeat)
        Split.unsafe(
          Selection.complementBlock(part, key.fold),
          Selection.block(part, key.fold)
        )
    )

  def planOnce
      : Either[DesignError, Plan[Split[Selection], Coverage.ExactOnce]] =
    if repeats != 1 then Left(DesignError.ExpectedSingleRepeat(repeats))
    else
      Right(
        Plan.fromGenerator(
          layout.shape,
          key =>
            val part = partitions(0)
            Split.unsafe(
              Selection.complementBlock(part, key.fold),
              Selection.block(part, key.fold)
            )
        )
      )

object CompletePerRepeat:
  def fromPartitions(
      partitions: IArray[FoldPartition]
  ): Either[DesignError, CompletePerRepeat] =
    if partitions.isEmpty then Left(DesignError.InvalidPlanShape(0, 0))
    else
      val expectedPopulation = partitions(0).populationSize
      val expectedFolds = partitions(0).folds
      var index = 1
      var failure: Option[DesignError] = None
      while index < partitions.length && failure.isEmpty do
        val part = partitions(index)
        if part.populationSize != expectedPopulation then
          failure = Some(
            DesignError.PartitionPopulationMismatch(
              expectedPopulation,
              part.populationSize
            )
          )
        else if part.folds != expectedFolds then
          failure = Some(
            DesignError.PartitionFoldMismatch(expectedFolds, part.folds)
          )
        index += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          val owned = new Array[FoldPartition](partitions.length)
          index = 0
          while index < partitions.length do
            owned(index) = partitions(index)
            index += 1
          Right(new CompletePerRepeat(IArray.unsafeFromArray(owned)))

  private[resample4s] def unsafe(
      partitions: IArray[FoldPartition]
  ): CompletePerRepeat =
    new CompletePerRepeat(partitions)

/**
 * Constructive witness that assessments partition the population exactly once
 * over the whole plan (one repeat).
 */
final class CompleteOnce private (
    private[resample4s] val partition: FoldPartition
):
  val populationSize: Int = partition.populationSize
  val folds: Int = partition.folds
  val layout: FoldLayout = FoldLayout.unsafe(1, folds)

  def assessmentFold(row: Int): Either[OutOfDomain, Int] =
    partition.assignmentAt(row)

  def foldOfRow: IArray[Int] =
    val values = new Array[Int](populationSize)
    var index = 0
    while index < populationSize do
      values(index) = partition.assignmentUnsafe(index)
      index += 1
    IArray.unsafeFromArray(values)

  def asPerRepeat: CompletePerRepeat =
    CompletePerRepeat.unsafe(IArray.unsafeFromArray(Array(partition)))

  def plan: Plan[Split[Selection], Coverage.ExactOnce] =
    Plan.fromGenerator(
      layout.shape,
      key =>
        Split.unsafe(
          Selection.complementBlock(partition, key.fold),
          Selection.block(partition, key.fold)
        )
    )

object CompleteOnce:
  def fromAssignments(
      foldOfRow: IArray[Int]
  ): Either[DesignError, CompleteOnce] =
    if foldOfRow.isEmpty then Left(DesignError.EmptyPopulation)
    else
      val folds =
        var maximum = -1
        var index = 0
        while index < foldOfRow.length do
          val fold = foldOfRow(index)
          if fold > maximum then maximum = fold
          index += 1
        maximum + 1
      FoldPartition
        .fromAssignments(foldOfRow.length, folds, foldOfRow)
        .map(partition => new CompleteOnce(partition))

  def fromPartition(
      partition: FoldPartition
  ): CompleteOnce =
    new CompleteOnce(partition)

  private[resample4s] def unsafe(partition: FoldPartition): CompleteOnce =
    new CompleteOnce(partition)

/** Validated construction of split plans without going through a Design. */
object SplitPlans:
  /**
   * Accepts an arbitrary sequence of already-validated splits.
   *
   * Coverage is ordinary [[Coverage]]: assessments need not partition.
   */
  def validate(
      populationSize: Int,
      shape: PlanShape,
      splits: IArray[Split[Selection]]
  ): Either[DesignError, Plan[Split[Selection], Coverage]] =
    if splits.length != shape.unitCount then
      Left(
        DesignError.FixedUnitCountMismatch(shape.unitCount, splits.length)
      )
    else if splits.isEmpty then
      Left(DesignError.InvalidPlanShape(shape.repeats, shape.foldsPerRepeat))
    else
      val owned = new Array[Split[Selection]](splits.length)
      var index = 0
      var failure: Option[DesignError] = None
      while index < splits.length && failure.isEmpty do
        val split = splits(index)
        if split.analysis.codomain != populationSize then
          failure = Some(
            DesignError.FixedUnitPopulationMismatch(
              UnitKey(
                index / shape.foldsPerRepeat,
                index % shape.foldsPerRepeat
              ),
              populationSize,
              split.analysis.codomain
            )
          )
        else if split.assessment.codomain != populationSize then
          failure = Some(
            DesignError.FixedUnitPopulationMismatch(
              UnitKey(
                index / shape.foldsPerRepeat,
                index % shape.foldsPerRepeat
              ),
              populationSize,
              split.assessment.codomain
            )
          )
        else owned(index) = split
        index += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          val units = IArray.unsafeFromArray(owned)
          Right(
            Plan.fromGenerator(
              shape,
              key => units(key.repeat * shape.foldsPerRepeat + key.fold)
            )
          )

  /** Builds an exact-once plan from fold-of-row assignments. */
  def fromAssignments(
      foldOfRow: IArray[Int]
  ): Either[
    DesignError,
    (CompleteOnce, Plan[Split[Selection], Coverage.ExactOnce])
  ] =
    CompleteOnce.fromAssignments(foldOfRow).map { complete =>
      (complete, complete.plan)
    }

  /** Builds a repeated exact plan from one assignment vector per repeat. */
  def fromRepeatedAssignments(
      foldOfRowByRepeat: IArray[IArray[Int]]
  ): Either[
    DesignError,
    (CompletePerRepeat, Plan[Split[Selection], Coverage.Exact])
  ] =
    if foldOfRowByRepeat.isEmpty then Left(DesignError.InvalidPlanShape(0, 0))
    else
      val partitions = new Array[FoldPartition](foldOfRowByRepeat.length)
      var index = 0
      var failure: Option[DesignError] = None
      while index < foldOfRowByRepeat.length && failure.isEmpty do
        CompleteOnce.fromAssignments(foldOfRowByRepeat(index)) match
          case Left(error) => failure = Some(error)
          case Right(once) =>
            partitions(index) = once.partition
        index += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          CompletePerRepeat
            .fromPartitions(IArray.unsafeFromArray(partitions))
            .map(complete => (complete, complete.plan))
