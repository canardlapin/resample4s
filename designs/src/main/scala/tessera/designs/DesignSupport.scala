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
        diagnostics => diagnostics.headOption.getOrElse(PlanDiagnostics.empty)
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
      repeat: Int
  ): Either[DesignError, FoldPartition] =
    if groups.cardinality < folds then
      Left(DesignError.TooFewGroups(groups.cardinality, folds))
    else
      val members = labelMembers(groups)
      val groupOrder = seededLptOrder(context, members, repeat)
      val priority = foldPriority(context, folds, repeat)
      val loads = Array.fill(folds)(0)
      val groupFold = Array.fill(groups.cardinality)(-1)
      groupOrder.foreach { group =>
        val minimum = loads.min
        var priorityIndex = 0
        while loads(priority(priorityIndex)) != minimum do
          priorityIndex += 1
        val fold = priority(priorityIndex)
        groupFold(group) = fold
        loads(fold) += members(group).length
      }
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
