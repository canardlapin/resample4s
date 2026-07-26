package tessera.designs

import scala.collection.mutable
import tessera.core.*

private[designs] trait ExactDefinitionRoute[Cov <: Coverage.Exact]:
  def oneLabel(
      descriptor: DesignDescriptor,
      labels: Option[Labels]
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Cov]

  def manyLabels(
      descriptor: DesignDescriptor,
      labels: IArray[Labels]
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Cov]

private[designs] object ExactDefinitionRoute:
  val once: ExactDefinitionRoute[Coverage.ExactOnce] =
    new ExactDefinitionRoute[Coverage.ExactOnce]:
      def oneLabel(
          descriptor: DesignDescriptor,
          labels: Option[Labels]
      )(
          build: BuildContext => Either[DesignError, ExactPartitionSpec]
      ): DesignDefinition[Split[Selection], Coverage.ExactOnce] =
        DesignDefinition.exactOncePartitions(descriptor, labels)(build)

      def manyLabels(
          descriptor: DesignDescriptor,
          labels: IArray[Labels]
      )(
          build: BuildContext => Either[DesignError, ExactPartitionSpec]
      ): DesignDefinition[Split[Selection], Coverage.ExactOnce] =
        DesignDefinition.exactOncePartitions(descriptor, labels)(build)

  val repeated: ExactDefinitionRoute[Coverage.Exact] =
    new ExactDefinitionRoute[Coverage.Exact]:
      def oneLabel(
          descriptor: DesignDescriptor,
          labels: Option[Labels]
      )(
          build: BuildContext => Either[DesignError, ExactPartitionSpec]
      ): DesignDefinition[Split[Selection], Coverage.Exact] =
        DesignDefinition.exactPartitions(descriptor, labels)(build)

      def manyLabels(
          descriptor: DesignDescriptor,
          labels: IArray[Labels]
      )(
          build: BuildContext => Either[DesignError, ExactPartitionSpec]
      ): DesignDefinition[Split[Selection], Coverage.Exact] =
        DesignDefinition.exactPartitions(descriptor, labels)(build)

/** Min-heap of folds ordered by current load, then seeded priority.
  *
  * The initial heap is the priority permutation itself: with every load at
  * zero, a parent always precedes its children by construction. A load only
  * increases after the root is selected, so restoring the heap requires one
  * downward pass.
  */
private[designs] final class FoldLoadQueue private (
    private val heap: Array[Int],
    private val priorityRank: Array[Int],
    private val loads: Array[Int]
):
  private var observedComparisons = 0L

  def takeAndAdd(size: Int): Int =
    val fold = heap(0)
    loads(fold) += size
    siftDown()
    fold

  private[designs] def comparisonCount: Long = observedComparisons

  private def precedes(left: Int, right: Int): Boolean =
    observedComparisons += 1L
    loads(left) < loads(right) ||
      (loads(left) == loads(right) &&
        priorityRank(left) < priorityRank(right))

  private def siftDown(): Unit =
    var parent = 0
    var settled = false
    while !settled do
      val left = 2 * parent + 1
      if left >= heap.length then settled = true
      else
        val right = left + 1
        val preferredChild =
          if right < heap.length && precedes(heap(right), heap(left)) then
            right
          else left
        if precedes(heap(preferredChild), heap(parent)) then
          val held = heap(parent)
          heap(parent) = heap(preferredChild)
          heap(preferredChild) = held
          parent = preferredChild
        else settled = true

private[designs] object FoldLoadQueue:
  def apply(priority: Vector[Int]): FoldLoadQueue =
    val ranks = new Array[Int](priority.length)
    var index = 0
    while index < priority.length do
      ranks(priority(index)) = index
      index += 1
    new FoldLoadQueue(
      priority.toArray,
      ranks,
      Array.fill(priority.length)(0)
    )

private[designs] object DesignSupport:
  def descriptor(
      algorithm: String,
      fields: (String, DescriptorValue)*
  ): DesignDescriptor =
    DesignDescriptor.unsafe(
      AlgorithmId.unsafe(algorithm),
      IArray.unsafeFromArray(fields.toArray)
    )

  def repeatPath(repeat: Int): StreamPath =
    StreamPath.unsafe(StreamDomain.Repeat, repeat)

  def childPath(
      repeat: Int,
      domain: StreamDomain,
      ordinal: Int
  ): StreamPath =
    repeatPath(repeat).appendUnchecked(domain, ordinal)

  def shuffledIndices(size: Int, seed: Seed): IArray[Int] =
    val values =
      IArray.unsafeFromArray(Array.tabulate(size)(identity))
    Rand.fromSeed(seed).shuffle(values)._2

  def exactSpec(
      context: BuildContext,
      folds: Int,
      repeats: Int,
      allocate: Int => Either[DesignError, FoldPartition],
      diagnostics: Vector[PlanDiagnostics] => PlanDiagnostics =
        aggregatePartitionDiagnostics
  ): Either[DesignError, ExactPartitionSpec] =
    val n = context.space.size
    if folds < 2 then Left(DesignError.TooFewFolds(folds, 2))
    else if folds > n then Left(DesignError.TooManyFolds(folds, n))
    else if repeats < 1 then
      Left(DesignError.InvalidRepeatCount(repeats))
    else
      val partitions = new Array[FoldPartition](repeats)
      val observed = Vector.newBuilder[PlanDiagnostics]
      var repeat = 0
      var error: Option[DesignError] = None
      while repeat < repeats && error.isEmpty do
        allocate(repeat) match
          case Left(value) => error = Some(value)
          case Right(partition) =>
            partitions(repeat) = partition
            observed += partitionDiagnostics(partition)
        repeat += 1
      error match
        case Some(value) => Left(value)
        case None =>
          ExactPartitionSpec.of(
            IArray.unsafeFromArray(partitions),
            diagnostics(observed.result())
          )

  private def partitionDiagnostics(
      partition: FoldPartition
  ): PlanDiagnostics =
    val sizes = Array.fill(partition.folds)(0)
    var index = 0
    while index < partition.populationSize do
      sizes(partition.assignmentUnsafe(index)) += 1
      index += 1
    val maximum = sizes.max
    val minimum = sizes.min
    PlanDiagnostics.unsafe(
      (DiagnosticMetric.MaxFoldSize, BigInt(maximum)),
      (DiagnosticMetric.MinFoldSize, BigInt(minimum)),
      (DiagnosticMetric.SizeImbalance, BigInt(maximum - minimum))
    )

  def groupedDiagnostics(
      groups: Labels,
      folds: Int,
      observed: Vector[PlanDiagnostics]
  ): PlanDiagnostics =
    val base = aggregatePartitionEntries(observed)
    (
      exactMinimumGroupImbalance(groups, folds),
      base
        .find(_._1 == DiagnosticMetric.SizeImbalance)
        .map(_._2)
    ) match
      case (Some(optimum), Some(achieved)) =>
        PlanDiagnostics.unsafe(
          (base ++ Vector(
            (DiagnosticMetric.Optimum, optimum),
            (DiagnosticMetric.Regret, achieved - optimum)
          ))*
        )
      case _ => PlanDiagnostics.unsafe(base*)

  def groupedStratifiedDiagnostics(
      groups: Labels,
      strata: Labels,
      folds: Int,
      observed: Vector[PlanDiagnostics]
  ): PlanDiagnostics =
    val entries = Vector.newBuilder[(DiagnosticMetric, BigInt)]
    val maxMetrics =
      Vector(
        DiagnosticMetric.GroupPurityDenominator,
        DiagnosticMetric.GroupPurityNumerator,
        DiagnosticMetric.MaxFoldSize,
        DiagnosticMetric.MaxStratumDeviation,
        DiagnosticMetric.Objective,
        DiagnosticMetric.SizeImbalance
      )
    maxMetrics.foreach { metric =>
      val values = observed.flatMap(_.value(metric))
      if values.nonEmpty then entries += ((metric, values.max))
    }
    val minimums =
      observed.flatMap(_.value(DiagnosticMetric.MinFoldSize))
    if minimums.nonEmpty then
      entries += ((DiagnosticMetric.MinFoldSize, minimums.min))
    if observed.lengthCompare(1) > 0 then
      entries += ((DiagnosticMetric.Repeats, BigInt(observed.length)))
    val base = entries.result()
    (
      exactMinimumGroupedObjective(groups, strata, folds),
      base
        .find(_._1 == DiagnosticMetric.Objective)
        .map(_._2)
    ) match
      case (Some(optimum), Some(achieved)) =>
        PlanDiagnostics.unsafe(
          (base ++ Vector(
            (DiagnosticMetric.Optimum, optimum),
            (DiagnosticMetric.Regret, achieved - optimum)
          ))*
        )
      case _ => PlanDiagnostics.unsafe(base*)

  private def aggregatePartitionDiagnostics(
      observed: Vector[PlanDiagnostics]
  ): PlanDiagnostics =
    PlanDiagnostics.unsafe(aggregatePartitionEntries(observed)*)

  private def aggregatePartitionEntries(
      observed: Vector[PlanDiagnostics]
  ): Vector[(DiagnosticMetric, BigInt)] =
    val entries = Vector.newBuilder[(DiagnosticMetric, BigInt)]
    val maximums =
      observed.flatMap(_.value(DiagnosticMetric.MaxFoldSize))
    if maximums.nonEmpty then
      entries += ((DiagnosticMetric.MaxFoldSize, maximums.max))
    val minimums =
      observed.flatMap(_.value(DiagnosticMetric.MinFoldSize))
    if minimums.nonEmpty then
      entries += ((DiagnosticMetric.MinFoldSize, minimums.min))
    val imbalances =
      observed.flatMap(_.value(DiagnosticMetric.SizeImbalance))
    if imbalances.nonEmpty then
      entries += ((DiagnosticMetric.SizeImbalance, imbalances.max))
    if observed.lengthCompare(1) > 0 then
      entries += ((DiagnosticMetric.Repeats, BigInt(observed.length)))
    entries.result()

  private val ExactOraclePopulationLimit = 32
  private val ExactOracleAllocationLimit = 100000L

  private def exactMinimumGroupImbalance(
      groups: Labels,
      folds: Int
  ): Option[BigInt] =
    if !oracleAvailable(groups.size, groups.cardinality, folds) then None
    else
      val members = labelMembers(groups)
      val loads = Array.fill(folds)(0)
      val used = Array.fill(folds)(false)
      var optimum: Option[Int] = None

      def loop(group: Int): Unit =
        if group == members.length then
          if used.forall(identity) then
            val imbalance = loads.max - loads.min
            if optimum.forall(imbalance < _) then optimum = Some(imbalance)
        else
          var fold = 0
          while fold < folds do
            val size = members(group).length
            val wasUsed = used(fold)
            loads(fold) += size
            used(fold) = true
            loop(group + 1)
            loads(fold) -= size
            used(fold) = wasUsed
            fold += 1

      loop(0)
      optimum.map(BigInt(_))

  private def exactMinimumGroupedObjective(
      groups: Labels,
      strata: Labels,
      folds: Int
  ): Option[BigInt] =
    if !oracleAvailable(groups.size, groups.cardinality, folds) then None
    else
      val assignment = Array.fill(groups.cardinality)(0)
      val used = Array.fill(folds)(false)
      val groupSizes = Array.fill(groups.cardinality)(0)
      val groupProfiles =
        Array.fill(groups.cardinality, strata.cardinality)(0)
      val stratumTotals = Array.fill(strata.cardinality)(0)
      var row = 0
      while row < groups.size do
        val group = groups.unsafeAt(row)
        val stratum = strata.unsafeAt(row)
        groupSizes(group) += 1
        groupProfiles(group)(stratum) += 1
        stratumTotals(stratum) += 1
        row += 1
      val foldSizes = Array.fill(folds)(0)
      val foldProfiles =
        Array.fill(folds, strata.cardinality)(0)
      var optimum: Option[BigInt] = None

      def evaluate(): BigInt =
        var fold = 0
        while fold < folds do
          foldSizes(fold) = 0
          var stratum = 0
          while stratum < strata.cardinality do
            foldProfiles(fold)(stratum) = 0
            stratum += 1
          fold += 1
        var group = 0
        while group < groups.cardinality do
          val assignedFold = assignment(group)
          foldSizes(assignedFold) += groupSizes(group)
          var stratum = 0
          while stratum < strata.cardinality do
            foldProfiles(assignedFold)(stratum) +=
              groupProfiles(group)(stratum)
            stratum += 1
          group += 1
        var result = BigInt(0)
        fold = 0
        while fold < folds do
          var stratum = 0
          while stratum < strata.cardinality do
            result +=
              BigInt(
                folds * foldProfiles(fold)(stratum) -
                  stratumTotals(stratum)
              ).pow(2)
            stratum += 1
          result += BigInt(folds * foldSizes(fold) - groups.size).pow(2)
          fold += 1
        result

      def loop(group: Int): Unit =
        if group == groups.cardinality then
          if used.forall(identity) then
            val candidate = evaluate()
            if optimum.forall(candidate < _) then optimum = Some(candidate)
        else
          var fold = 0
          while fold < folds do
            val wasUsed = used(fold)
            assignment(group) = fold
            used(fold) = true
            loop(group + 1)
            used(fold) = wasUsed
            fold += 1

      loop(0)
      optimum

  private def oracleAvailable(
      population: Int,
      groups: Int,
      folds: Int
  ): Boolean =
    if population > ExactOraclePopulationLimit then false
    else
      var count = 1L
      var group = 0
      while group < groups && count <= ExactOracleAllocationLimit do
        if count > ExactOracleAllocationLimit / folds.toLong then
          count = ExactOracleAllocationLimit + 1L
        else count *= folds.toLong
        group += 1
      count <= ExactOracleAllocationLimit

  def plainPartition(
      context: BuildContext,
      folds: Int,
      repeat: Int
  ): Either[DesignError, FoldPartition] =
    val n = context.space.size
    val shuffled =
      shuffledIndices(n, context.derive(repeatPath(repeat)))
    val assignments = new Array[Int](n)
    var position = 0
    while position < n do
      assignments(shuffled(position)) = position % folds
      position += 1
    FoldPartition.fromAssignments(
      n,
      folds,
      IArray.unsafeFromArray(assignments)
    )

  def stratifiedPartition(
      context: BuildContext,
      folds: Int,
      labels: Labels,
      repeat: Int
  ): Either[DesignError, FoldPartition] =
    val n = context.space.size
    val members = labelMembers(labels)
    val order =
      Vector
        .range(0, labels.cardinality)
        .sortBy(code => (-members(code).length, members(code)(0)))
    val assignments = Array.fill(n)(-1)
    var offset = 0
    var ordinal = 0
    while ordinal < order.length do
      val code = order(ordinal)
      val seed =
        context.derive(
          childPath(repeat, StreamDomain.Stratum, ordinal)
        )
      val (_, shuffled) =
        Rand.fromSeed(seed).shuffle(members(code))
      var position = 0
      while position < shuffled.length do
        assignments(shuffled(position)) = (offset + position) % folds
        position += 1
      offset = (offset + shuffled.length) % folds
      ordinal += 1
    FoldPartition.fromAssignments(
      n,
      folds,
      IArray.unsafeFromArray(assignments)
    )

  def groupedPartition(
      context: BuildContext,
      folds: Int,
      groups: Labels,
      repeat: Int,
      observeComparisons: Long => Unit = _ => ()
  ): Either[DesignError, FoldPartition] =
    if groups.cardinality < folds then
      Left(DesignError.TooFewGroups(groups.cardinality, folds))
    else
      val members = labelMembers(groups)
      val groupOrder = seededLptOrder(context, members, repeat)
      val priority = foldPriority(context, folds, repeat)
      val loads = FoldLoadQueue(priority)
      val groupFold = Array.fill(groups.cardinality)(-1)
      groupOrder.foreach { group =>
        val fold = loads.takeAndAdd(members(group).length)
        groupFold(group) = fold
      }
      observeComparisons(loads.comparisonCount)
      assignmentsFromGroups(groups, groupFold, folds)

  def groupedStratifiedPartition(
      context: BuildContext,
      folds: Int,
      groups: Labels,
      strata: Labels,
      repeat: Int
  ): Either[DesignError, (FoldPartition, PlanDiagnostics)] =
    if groups.cardinality < folds then
      Left(DesignError.TooFewGroups(groups.cardinality, folds))
    else
      val groupMembers = labelMembers(groups)
      val groupOrder = seededLptOrder(context, groupMembers, repeat)
      val priority = foldPriority(context, folds, repeat)
      val stratumTotals =
        Array.tabulate(strata.cardinality)(code =>
          countLabel(strata, code)
        )
      val profiles =
        Array.fill(groups.cardinality)(
          Vector.empty[(Int, Int)]
        )
      val builders =
        Array.fill(groups.cardinality)(
          mutable.HashMap.empty[Int, Int]
        )
      var row = 0
      while row < groups.size do
        val group = groups.unsafeAt(row)
        val stratum = strata.unsafeAt(row)
        builders(group).updateWith(stratum) {
          case Some(count) => Some(count + 1)
          case None        => Some(1)
        }
        row += 1
      var group = 0
      while group < groups.cardinality do
        profiles(group) = builders(group).toVector.sortBy(_._1)
        group += 1

      val foldSizes = Array.fill(folds)(0)
      val foldProfiles =
        Array.fill(folds)(mutable.HashMap.empty[Int, Int])
      val groupFold = Array.fill(groups.cardinality)(-1)
      val k = BigInt(folds)
      val population = BigInt(groups.size)
      var objective =
        k * population.pow(2) +
          k * stratumTotals.iterator
            .map(value => BigInt(value).pow(2))
            .sum

      groupOrder.foreach { currentGroup =>
        val groupSize = groupMembers(currentGroup).length
        var bestDelta: Option[BigInt] = None
        var bestFold = -1
        var chosenDelta = BigInt(0)
        priority.foreach { fold =>
          val sizeBefore = k * BigInt(foldSizes(fold)) - population
          val sizeAfter = sizeBefore + k * BigInt(groupSize)
          var delta =
            sizeAfter.pow(2) - sizeBefore.pow(2)
          profiles(currentGroup).foreach { (stratum, count) =>
            val before =
              k * BigInt(foldProfiles(fold).getOrElse(stratum, 0)) -
                BigInt(stratumTotals(stratum))
            val after = before + k * BigInt(count)
            delta += after.pow(2) - before.pow(2)
          }
          if bestDelta.forall(delta < _) then
            bestDelta = Some(delta)
            bestFold = fold
            chosenDelta = delta
        }
        groupFold(currentGroup) = bestFold
        foldSizes(bestFold) += groupSize
        profiles(currentGroup).foreach { (stratum, count) =>
          foldProfiles(bestFold).updateWith(stratum) {
            case Some(current) => Some(current + count)
            case None          => Some(count)
          }
        }
        objective += chosenDelta
      }

      assignmentsFromGroups(groups, groupFold, folds).map { partition =>
        val maxDeviation =
          maxStratumDeviation(foldProfiles, stratumTotals, folds)
        val pureGroups = profiles.count(_.length == 1)
        val maximum = foldSizes.max
        val minimum = foldSizes.min
        val diagnostics =
          PlanDiagnostics.unsafe(
            (
              DiagnosticMetric.GroupPurityDenominator,
              BigInt(groups.cardinality)
            ),
            (
              DiagnosticMetric.GroupPurityNumerator,
              BigInt(pureGroups)
            ),
            (DiagnosticMetric.MaxFoldSize, BigInt(maximum)),
            (
              DiagnosticMetric.MaxStratumDeviation,
              BigInt(maxDeviation)
            ),
            (DiagnosticMetric.MinFoldSize, BigInt(minimum)),
            (DiagnosticMetric.Objective, objective),
            (
              DiagnosticMetric.SizeImbalance,
              BigInt(maximum - minimum)
            )
          )
        (partition, diagnostics)
      }

  def labelMembers(labels: Labels): IArray[IArray[Int]] =
    val counts = Array.fill(labels.cardinality)(0)
    var index = 0
    while index < labels.size do
      counts(labels.unsafeAt(index)) += 1
      index += 1
    val members =
      Array.tabulate(labels.cardinality)(code =>
        new Array[Int](counts(code))
      )
    val offsets = Array.fill(labels.cardinality)(0)
    index = 0
    while index < labels.size do
      val code = labels.unsafeAt(index)
      members(code)(offsets(code)) = index
      offsets(code) += 1
      index += 1
    IArray.unsafeFromArray(
      members.map(values => IArray.unsafeFromArray(values))
    )

  private def seededLptOrder(
      context: BuildContext,
      members: IArray[IArray[Int]],
      repeat: Int
  ): Vector[Int] =
    val ordered =
      Vector
        .range(0, members.length)
        .sortBy(group => (-members(group).length, members(group)(0)))
    val result = Vector.newBuilder[Int]
    var start = 0
    var bucket = 0
    while start < ordered.length do
      val size = members(ordered(start)).length
      var end = start + 1
      while end < ordered.length &&
          members(ordered(end)).length == size
      do end += 1
      val bucketValues =
        IArray.unsafeFromArray(ordered.slice(start, end).toArray)
      val seed =
        context.derive(
          childPath(repeat, StreamDomain.GroupSizeBucket, bucket)
        )
      val (_, shuffled) = Rand.fromSeed(seed).shuffle(bucketValues)
      var index = 0
      while index < shuffled.length do
        result += shuffled(index)
        index += 1
      start = end
      bucket += 1
    result.result()

  private def foldPriority(
      context: BuildContext,
      folds: Int,
      repeat: Int
  ): Vector[Int] =
    val seed =
      context.derive(
        childPath(repeat, StreamDomain.FoldPriority, 0)
      )
    val shuffled = shuffledIndices(folds, seed)
    Vector.tabulate(shuffled.length)(shuffled(_))

  private def assignmentsFromGroups(
      groups: Labels,
      groupFold: Array[Int],
      folds: Int
  ): Either[DesignError, FoldPartition] =
    val assignments = new Array[Int](groups.size)
    var row = 0
    while row < groups.size do
      assignments(row) = groupFold(groups.unsafeAt(row))
      row += 1
    FoldPartition.fromAssignments(
      groups.size,
      folds,
      IArray.unsafeFromArray(assignments)
    )

  private def countLabel(labels: Labels, code: Int): Int =
    var count = 0
    var index = 0
    while index < labels.size do
      if labels.unsafeAt(index) == code then count += 1
      index += 1
    count

  private def maxStratumDeviation(
      profiles: Array[mutable.HashMap[Int, Int]],
      totals: Array[Int],
      folds: Int
  ): Int =
    var maximum = if totals.isEmpty then 0 else totals.max
    profiles.foreach { profile =>
      profile.foreach { (stratum, count) =>
        val deviation = math.abs(folds * count - totals(stratum))
        if deviation > maximum then maximum = deviation
      }
    }
    maximum
