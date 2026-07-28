package resample4s.core

opaque type MetricId = String

object MetricId:
  def fromString(value: String): Either[DesignError, MetricId] =
    if value.isEmpty || value.exists(ch =>
        !(ch.isLetterOrDigit || ch == '-' || ch == '_')
      )
    then Left(DesignError.InvalidMetricId(value))
    else Right(value)

  private[resample4s] def unsafe(value: String): MetricId = value

  extension (id: MetricId) def value: String = id

  given CanEqual[MetricId, MetricId] = CanEqual.derived

/**
 * Built-in diagnostic metric identifiers. External authors may mint their own
 * [[MetricId]] values with [[MetricId.fromString]].
 */
object Metrics:
  val maxFoldSize: MetricId = MetricId.unsafe("max-fold-size")
  val minFoldSize: MetricId = MetricId.unsafe("min-fold-size")
  val sizeImbalance: MetricId = MetricId.unsafe("size-imbalance")
  val objective: MetricId = MetricId.unsafe("objective")
  val optimum: MetricId = MetricId.unsafe("optimum")
  val regret: MetricId = MetricId.unsafe("regret")
  val maxStratumDeviation: MetricId =
    MetricId.unsafe("max-stratum-deviation")
  val groupPurityNumerator: MetricId =
    MetricId.unsafe("group-purity-numerator")
  val groupPurityDenominator: MetricId =
    MetricId.unsafe("group-purity-denominator")
  val repeats: MetricId = MetricId.unsafe("repeats")

/** @deprecated Use [[MetricId]] / [[Metrics]]. */
type DiagnosticMetric = MetricId

/** @deprecated Use [[Metrics]]. */
object DiagnosticMetric:
  val MaxFoldSize: MetricId = Metrics.maxFoldSize
  val MinFoldSize: MetricId = Metrics.minFoldSize
  val SizeImbalance: MetricId = Metrics.sizeImbalance
  val Objective: MetricId = Metrics.objective
  val Optimum: MetricId = Metrics.optimum
  val Regret: MetricId = Metrics.regret
  val MaxStratumDeviation: MetricId = Metrics.maxStratumDeviation
  val GroupPurityNumerator: MetricId = Metrics.groupPurityNumerator
  val GroupPurityDenominator: MetricId = Metrics.groupPurityDenominator
  val Repeats: MetricId = Metrics.repeats

/** Exact, typed observations about best-effort allocation quality. */
final class PlanDiagnostics private (
    private val entries: Vector[(MetricId, BigInt)]
):
  def size: Int = entries.length

  def metric(index: Int): Either[OutOfDomain, MetricId] =
    if index >= 0 && index < entries.length then Right(entries(index)._1)
    else Left(OutOfDomain(index, entries.length))

  def value(metric: MetricId): Option[BigInt] =
    entries.find(_._1 == metric).map(_._2)

  override def equals(other: Any): Boolean =
    other match
      case that: PlanDiagnostics => entries == that.entries
      case _ => false

  override def hashCode(): Int = entries.hashCode()

object PlanDiagnostics:
  val empty: PlanDiagnostics = new PlanDiagnostics(Vector.empty)

  def of(
      values: IArray[(MetricId, BigInt)]
  ): Either[DesignError, PlanDiagnostics] =
    val result = Vector.newBuilder[(MetricId, BigInt)]
    val seen = scala.collection.mutable.HashSet.empty[MetricId]
    var index = 0
    var error: Option[DesignError] = None
    while index < values.length && error.isEmpty do
      val (metric, value) = values(index)
      if seen.contains(metric) then
        error = Some(DesignError.DuplicateDiagnostic(metric))
      else
        seen += metric
        result += ((metric, value))
      index += 1
    error match
      case Some(value) => Left(value)
      case None => Right(new PlanDiagnostics(result.result()))

  private[resample4s] def unsafe(
      values: (MetricId, BigInt)*
  ): PlanDiagnostics =
    new PlanDiagnostics(values.toVector)

  given CanEqual[PlanDiagnostics, PlanDiagnostics] = CanEqual.derived

/** Declared upper bounds used by published design-conformance laws. */
final class PlanCost private (
    val residentElementsUpperBound: Long,
    val workPerUnitUpperBound: Long,
    val receiptWorkPerUnitUpperBound: Long
):
  override def equals(other: Any): Boolean =
    other match
      case that: PlanCost =>
        residentElementsUpperBound == that.residentElementsUpperBound &&
        workPerUnitUpperBound == that.workPerUnitUpperBound &&
        receiptWorkPerUnitUpperBound == that.receiptWorkPerUnitUpperBound
      case _ => false

  override def hashCode(): Int =
    var hash = 31 + residentElementsUpperBound.##
    hash = 31 * hash + workPerUnitUpperBound.##
    31 * hash + receiptWorkPerUnitUpperBound.##

object PlanCost:
  def of(
      residentElementsUpperBound: Long,
      workPerUnitUpperBound: Long,
      receiptWorkPerUnitUpperBound: Long
  ): Either[DesignError, PlanCost] =
    if residentElementsUpperBound < 0 ||
      workPerUnitUpperBound < 0 ||
      receiptWorkPerUnitUpperBound < 0
    then
      Left(
        DesignError.InvalidPlanCost(
          residentElementsUpperBound,
          workPerUnitUpperBound,
          receiptWorkPerUnitUpperBound
        )
      )
    else
      Right(
        new PlanCost(
          residentElementsUpperBound,
          workPerUnitUpperBound,
          receiptWorkPerUnitUpperBound
        )
      )

  private[resample4s] def unsafe(
      residentElementsUpperBound: Long,
      workPerUnitUpperBound: Long,
      receiptWorkPerUnitUpperBound: Long
  ): PlanCost =
    new PlanCost(
      residentElementsUpperBound,
      workPerUnitUpperBound,
      receiptWorkPerUnitUpperBound
    )

  given CanEqual[PlanCost, PlanCost] = CanEqual.derived

/** The complete pure input available to a consumer-defined design builder. */
final class BuildContext private[resample4s] (
    val space: IndexSpace,
    private val ownedLabels: Vector[Labels],
    val seed: Seed,
    val designKey: DesignKey
):
  def labels: Option[Labels] = ownedLabels.headOption
  def labelCount: Int = ownedLabels.length
  def labelAt(index: Int): Either[OutOfDomain, Labels] =
    if index >= 0 && index < ownedLabels.length then Right(ownedLabels(index))
    else Left(OutOfDomain(index, ownedLabels.length))

  def derive(path: StreamPath): Seed =
    Rand.derive(seed, designKey, path)

/**
 * Canonical semantic encoding for a general design's public unit value.
 *
 * The writer exposes framed typed primitives only; raw byte injection is not
 * part of the public extension protocol.
 */
trait CanonicalAssignmentEncoder[-A]:
  def encode(
      value: A,
      out: CanonicalWriter
  ): Either[DigestError, Unit]

object CanonicalAssignmentEncoder:
  private def writeReindexing(
      value: Reindexing,
      tag: String,
      out: CanonicalWriter
  ): Either[DigestError, Unit] =
    out.variant(tag).flatMap { _ =>
      out.beginSequence(value.domain).map { _ =>
        value.foreachIndex(out.int)
      }
    }

  val selection: CanonicalAssignmentEncoder[Selection] =
    new CanonicalAssignmentEncoder[Selection]:
      def encode(
          value: Selection,
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        writeReindexing(value, "selection", out)

  val draw: CanonicalAssignmentEncoder[Draw] =
    new CanonicalAssignmentEncoder[Draw]:
      def encode(
          value: Draw,
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        writeReindexing(value, "draw", out)

  val permutation: CanonicalAssignmentEncoder[Permutation] =
    new CanonicalAssignmentEncoder[Permutation]:
      def encode(
          value: Permutation,
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        writeReindexing(value, "permutation", out)

  val selectionSplit: CanonicalAssignmentEncoder[Split[Selection]] =
    new CanonicalAssignmentEncoder[Split[Selection]]:
      def encode(
          value: Split[Selection],
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        out.variant("split").flatMap { _ =>
          writeReindexing(value.analysis, "selection", out).flatMap { _ =>
            writeReindexing(value.assessment, "assessment", out)
          }
        }

  val assessmentOnlySplit: CanonicalAssignmentEncoder[Split[Selection]] =
    new CanonicalAssignmentEncoder[Split[Selection]]:
      def encode(
          value: Split[Selection],
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        writeReindexing(value.assessment, "assessment", out)

  val drawSplit: CanonicalAssignmentEncoder[Split[Draw]] =
    new CanonicalAssignmentEncoder[Split[Draw]]:
      def encode(
          value: Split[Draw],
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        out.variant("split").flatMap { _ =>
          writeReindexing(value.analysis, "draw", out).flatMap { _ =>
            writeReindexing(value.assessment, "assessment", out)
          }
        }

final class GeneralPlanSpec[A] private (
    val shape: PlanShape,
    val diagnostics: PlanDiagnostics,
    val cost: PlanCost,
    private[resample4s] val unit: UnitKey => A,
    private[resample4s] val encoder: CanonicalAssignmentEncoder[A]
)

object GeneralPlanSpec:
  /** Builds a general plan specification from already-validated components. */
  def apply[A](
      shape: PlanShape,
      diagnostics: PlanDiagnostics,
      cost: PlanCost
  )(
      unit: UnitKey => A,
      encoder: CanonicalAssignmentEncoder[A]
  ): GeneralPlanSpec[A] =
    new GeneralPlanSpec(shape, diagnostics, cost, unit, encoder)

final class ExactPartitionSpec private (
    private[resample4s] val partitions: IArray[FoldPartition],
    val diagnostics: PlanDiagnostics
):
  def repeats: Int = partitions.length
  def populationSize: Int = partitions(0).populationSize
  def foldsPerRepeat: Int = partitions(0).folds

object ExactPartitionSpec:
  def of(
      partitions: IArray[FoldPartition],
      diagnostics: PlanDiagnostics
  ): Either[DesignError, ExactPartitionSpec] =
    if partitions.isEmpty then Left(DesignError.InvalidPlanShape(0, 0))
    else
      val expectedPopulation = partitions(0).populationSize
      val expectedFolds = partitions(0).folds
      if expectedPopulation < 2 || expectedFolds < 2 then
        Left(
          DesignError.InvalidFoldCount(
            expectedFolds,
            expectedPopulation
          )
        )
      else
        val owned = new Array[FoldPartition](partitions.length)
        var index = 0
        var error: Option[DesignError] = None
        while index < partitions.length && error.isEmpty do
          val partition = partitions(index)
          if partition.populationSize != expectedPopulation then
            error = Some(
              DesignError.PartitionPopulationMismatch(
                expectedPopulation,
                partition.populationSize
              )
            )
          else if partition.folds != expectedFolds then
            error = Some(
              DesignError.PartitionFoldMismatch(
                expectedFolds,
                partition.folds
              )
            )
          else owned(index) = partition
          index += 1
        error match
          case Some(value) => Left(value)
          case None =>
            Right(
              new ExactPartitionSpec(
                IArray.unsafeFromArray(owned),
                diagnostics
              )
            )

/**
 * Framework-owned compilation route for a design.
 *
 * `general` always yields ordinary `Coverage`; only the partition routes can
 * produce exact-coverage evidence. `exactOncePartitions` additionally proves
 * that the plan contains one repeat.
 */
final class DesignDefinition[+A, +Cov <: Coverage] private (
    val descriptor: DesignDescriptor,
    private val ownedLabels: Vector[Labels],
    private val compileValidated: (IndexSpace, Seed, BuildContext) => Either[
      DesignError,
      Compiled[A, Cov]
    ]
):
  def labels: Option[Labels] = ownedLabels.headOption
  def labelCount: Int = ownedLabels.length
  def labelAt(index: Int): Either[OutOfDomain, Labels] =
    if index >= 0 && index < ownedLabels.length then Right(ownedLabels(index))
    else Left(OutOfDomain(index, ownedLabels.length))

  private[resample4s] def labelValues: Vector[Labels] = ownedLabels

  private[resample4s] def compile(
      space: IndexSpace,
      seed: Seed
  ): Either[DesignError, Compiled[A, Cov]] =
    ownedLabels.find(_.size != space.size) match
      case Some(value) =>
        Left(DesignError.LengthMismatch(space.size, value.size))
      case None =>
        val key = CanonicalDesign.randomizationKey(descriptor, ownedLabels)
        val context = new BuildContext(space, ownedLabels, seed, key)
        compileValidated(space, seed, context)

object DesignDefinition:
  /**
   * Internal route for a design combinator whose coverage capability is
   * inherited from an already-validated source plan.
   *
   * Public consumer definitions still use `general`, `exactPartitions`, or
   * `exactOncePartitions`; this route does not let an arbitrary generator
   * assert exact coverage.
   */
  private[resample4s] def derived[A, Cov <: Coverage](
      descriptor: DesignDescriptor,
      labels: IArray[Labels]
  )(
      build: BuildContext => Either[DesignError, Compiled[A, Cov]]
  ): DesignDefinition[A, Cov] =
    val owned =
      Vector.tabulate(labels.length)(labels(_))
    new DesignDefinition(
      descriptor,
      owned,
      (_, _, context) => build(context)
    )

  def general[A](
      descriptor: DesignDescriptor
  )(
      build: BuildContext => Either[DesignError, GeneralPlanSpec[A]]
  ): DesignDefinition[A, Coverage] =
    general(descriptor, None)(build)

  def general[A](
      descriptor: DesignDescriptor,
      labels: Option[Labels]
  )(
      build: BuildContext => Either[DesignError, GeneralPlanSpec[A]]
  ): DesignDefinition[A, Coverage] =
    generalMany(descriptor, labels.toVector)(build)

  def general[A](
      descriptor: DesignDescriptor,
      labels: IArray[Labels]
  )(
      build: BuildContext => Either[DesignError, GeneralPlanSpec[A]]
  ): DesignDefinition[A, Coverage] =
    generalMany(
      descriptor,
      Vector.tabulate(labels.length)(labels(_))
    )(build)

  private def generalMany[A](
      descriptor: DesignDescriptor,
      labels: Vector[Labels]
  )(
      build: BuildContext => Either[DesignError, GeneralPlanSpec[A]]
  ): DesignDefinition[A, Coverage] =
    new DesignDefinition(
      descriptor,
      labels,
      (space, seed, context) =>
        build(context).map { spec =>
          val plan =
            Plan.fromGenerator[A, Coverage](spec.shape, spec.unit)
          Compiled.general(
            plan,
            spec.diagnostics,
            spec.cost,
            descriptor,
            labels,
            space,
            seed,
            spec.encoder
          )
        }
    )

  def exactPartitions(
      descriptor: DesignDescriptor
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Coverage.Exact] =
    exactPartitions(descriptor, None)(build)

  def exactPartitions(
      descriptor: DesignDescriptor,
      labels: Option[Labels]
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Coverage.Exact] =
    exactMany[Coverage.Exact](
      descriptor,
      labels.toVector,
      requireSingleRepeat = false
    )(build)

  def exactPartitions(
      descriptor: DesignDescriptor,
      labels: IArray[Labels]
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Coverage.Exact] =
    exactMany[Coverage.Exact](
      descriptor,
      Vector.tabulate(labels.length)(labels(_)),
      requireSingleRepeat = false
    )(build)

  def exactOncePartitions(
      descriptor: DesignDescriptor
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Coverage.ExactOnce] =
    exactOncePartitions(descriptor, None)(build)

  def exactOncePartitions(
      descriptor: DesignDescriptor,
      labels: Option[Labels]
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Coverage.ExactOnce] =
    exactMany[Coverage.ExactOnce](
      descriptor,
      labels.toVector,
      requireSingleRepeat = true
    )(build)

  def exactOncePartitions(
      descriptor: DesignDescriptor,
      labels: IArray[Labels]
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Coverage.ExactOnce] =
    exactMany[Coverage.ExactOnce](
      descriptor,
      Vector.tabulate(labels.length)(labels(_)),
      requireSingleRepeat = true
    )(build)

  private def exactMany[Cov <: Coverage.Exact](
      descriptor: DesignDescriptor,
      labels: Vector[Labels],
      requireSingleRepeat: Boolean
  )(
      build: BuildContext => Either[DesignError, ExactPartitionSpec]
  ): DesignDefinition[Split[Selection], Cov] =
    new DesignDefinition(
      descriptor,
      labels,
      (space, seed, context) =>
        build(context).flatMap { spec =>
          if spec.populationSize != space.size then
            Left(
              DesignError.PartitionPopulationMismatch(
                space.size,
                spec.populationSize
              )
            )
          else if requireSingleRepeat && spec.repeats != 1 then
            Left(DesignError.ExpectedSingleRepeat(spec.repeats))
          else
            PlanShape
              .of(spec.repeats, spec.foldsPerRepeat)
              .map { shape =>
                val plan =
                  Plan.fromGenerator[
                    Split[Selection],
                    Cov
                  ](
                    shape,
                    key =>
                      Split.unsafe(
                        Selection.complementBlock(
                          spec.partitions(key.repeat),
                          key.fold
                        ),
                        Selection.block(
                          spec.partitions(key.repeat),
                          key.fold
                        )
                      )
                  )
                Compiled.exact(
                  plan,
                  spec.diagnostics,
                  descriptor,
                  labels,
                  space,
                  seed,
                  spec.partitions
                )
              }
        }
    )

/**
 * A self-contained, reproducible design.
 *
 * `definition` is the sole extension member. Randomization keys, compilation,
 * fingerprints, and receipt plumbing remain final and core-owned.
 */
trait Design[+A, +Cov <: Coverage]:
  def definition: DesignDefinition[A, Cov]

  final def randomizationKey: DesignKey =
    CanonicalDesign.randomizationKey(
      definition.descriptor,
      definition.labelValues
    )

  final def fingerprint(using
      algorithm: DigestAlgorithm
  ): Either[DigestError, ContentDigest] =
    CanonicalDesign
      .fingerprint(definition.descriptor, definition.labelValues)

  final def labelsFingerprint(using
      algorithm: DigestAlgorithm
  ): Either[DigestError, Option[ContentDigest]] =
    val labels = definition.labelValues
    if labels.nonEmpty then
      CanonicalDesign
        .labelsFingerprint(labels)
        .map(result => Some(result))
    else Right(None)

  final def compile(
      space: IndexSpace,
      seed: Seed
  ): Either[DesignError, Compiled[A, Cov]] =
    if space.size == 0 then Left(DesignError.EmptyPopulation)
    else definition.compile(space, seed)

private[resample4s] trait DigestStream:
  def digest(
      algorithm: DigestAlgorithm
  ): Either[DigestError, DigestValue]

private[resample4s] final class GeneralDigestStream[A](
    space: IndexSpace,
    plan: Plan[A, ? <: Coverage],
    encoder: CanonicalAssignmentEncoder[A]
) extends DigestStream:
  def digest(
      algorithm: DigestAlgorithm
  ): Either[DigestError, DigestValue] =
    algorithm.newAccumulator().flatMap { accumulator =>
      val writer = CanonicalWriter.streaming(accumulator.update)
      CanonicalAssignment.writeHeader(space, plan.shape, writer)
      val units = plan.iterator
      var failure = writer.error
      while units.hasNext && failure.isEmpty do
        val (key, value) = units.next()
        writer.variantUnchecked("unit")
        writer.int(key.repeat)
        writer.int(key.fold)
        encoder.encode(value, writer) match
          case Left(error) => failure = Some(error)
          case Right(_) => failure = writer.error
      failure match
        case Some(error) => Left(error)
        case None => accumulator.finish()
    }

private[resample4s] final class ExactDigestStream(
    space: IndexSpace,
    shape: PlanShape,
    partitions: IArray[FoldPartition]
) extends DigestStream:
  def digest(
      algorithm: DigestAlgorithm
  ): Either[DigestError, DigestValue] =
    algorithm.newAccumulator().flatMap { accumulator =>
      val writer = CanonicalWriter.streaming(accumulator.update)
      CanonicalAssignment.writeHeader(space, shape, writer)
      var repeat = 0
      while repeat < partitions.length && writer.error.isEmpty do
        val partition = partitions(repeat)
        writer.variantUnchecked("partition")
        writer.int(repeat)
        writer.beginSequenceUnchecked(partition.populationSize)
        var index = 0
        while index < partition.populationSize && writer.error.isEmpty do
          writer.int(partition.assignmentUnsafe(index))
          index += 1
        repeat += 1
      writer.error match
        case Some(error) => Left(error)
        case None => accumulator.finish()
    }

private[resample4s] object CanonicalAssignment:
  def header(
      space: IndexSpace,
      shape: PlanShape
  ): Vector[IArray[Byte]] =
    val buffer = CanonicalWriter.buffered()
    writeHeader(space, shape, buffer.writer)
    buffer.chunks

  def writeHeader(
      space: IndexSpace,
      shape: PlanShape,
      writer: CanonicalWriter
  ): Unit =
    writer.textUnchecked("resample4s/assignment/v1")
    writer.int(space.size)
    writer.int(shape.repeats)
    writer.int(shape.foldsPerRepeat)

/** A validated lazy plan plus diagnostics and explicit work accounting. */
final class Compiled[+A, +Cov <: Coverage] private (
    val plan: Plan[A, Cov],
    val diagnostics: PlanDiagnostics,
    val cost: PlanCost,
    private val descriptor: DesignDescriptor,
    private val labels: Vector[Labels],
    private val seedValue: Seed,
    private val streamFactory: () => DigestStream
):
  def receipt(
      population: Fingerprint
  )(using algorithm: DigestAlgorithm): Either[DigestError, PlanReceipt] =
    for
      designDigest <- CanonicalDesign.fingerprint(descriptor, labels)
      labelsDigest <-
        if labels.nonEmpty then
          CanonicalDesign
            .labelsFingerprint(labels)
            .map(result => Some(result))
        else Right(None)
      assignmentDigest <-
        val stream = streamFactory()
        stream
          .digest(algorithm)
          .map(result => ContentDigest.of(algorithm.id, result))
    yield new PlanReceipt(
      descriptor.algorithm,
      designDigest,
      population,
      labelsDigest,
      seedValue,
      assignmentDigest
    )

object Compiled:
  private[resample4s] def general[A, Cov <: Coverage](
      plan: Plan[A, Cov],
      diagnostics: PlanDiagnostics,
      cost: PlanCost,
      descriptor: DesignDescriptor,
      labels: Vector[Labels],
      space: IndexSpace,
      seed: Seed,
      encoder: CanonicalAssignmentEncoder[A]
  ): Compiled[A, Cov] =
    new Compiled(
      plan,
      diagnostics,
      cost,
      descriptor,
      labels,
      seed,
      () => new GeneralDigestStream(space, plan, encoder)
    )

  private[resample4s] def exact[Cov <: Coverage.Exact](
      plan: Plan[Split[Selection], Cov],
      diagnostics: PlanDiagnostics,
      descriptor: DesignDescriptor,
      labels: Vector[Labels],
      space: IndexSpace,
      seed: Seed,
      partitions: IArray[FoldPartition]
  ): Compiled[Split[Selection], Cov] =
    val n = space.size.toLong
    val resident =
      partitions.iterator.map(_.residentElementsUpperBound).sum
    val cost =
      PlanCost.unsafe(
        residentElementsUpperBound = resident,
        workPerUnitUpperBound = 1L,
        receiptWorkPerUnitUpperBound = n
      )
    new Compiled(
      plan,
      diagnostics,
      cost,
      descriptor,
      labels,
      seed,
      () => new ExactDigestStream(space, plan.shape, partitions)
    )

/**
 * A verification artifact for a compiled plan.
 *
 * A receipt cannot reconstruct a design. Verification requires the caller to
 * supply the design, index space, population fingerprint, and matching digest
 * provider. Even a cryptographic provider does not authenticate a receipt
 * without trusted storage or a signature outside Resample4s.
 */
final class PlanReceipt private[resample4s] (
    val algorithm: AlgorithmId,
    val design: ContentDigest,
    val population: Fingerprint,
    val labels: Option[ContentDigest],
    val seed: Seed,
    val assignment: ContentDigest
):
  private[resample4s] def withSeed(value: Seed): PlanReceipt =
    new PlanReceipt(
      algorithm,
      design,
      population,
      labels,
      value,
      assignment
    )

  override def equals(other: Any): Boolean =
    other match
      case that: PlanReceipt =>
        algorithm == that.algorithm &&
        design == that.design &&
        FingerprintEquality.equal(population, that.population) &&
        sameOptionalDigest(labels, that.labels) &&
        seed.value == that.seed.value &&
        assignment == that.assignment
      case _ => false

  override def hashCode(): Int =
    var hash = algorithm.hashCode()
    hash = 31 * hash + design.hashCode()
    hash = 31 * hash + population.hashCode()
    hash = 31 * hash + labels.fold(0)(_.hashCode())
    hash = 31 * hash + seed.value.hashCode()
    31 * hash + assignment.hashCode()

object PlanReceipt:
  given CanEqual[PlanReceipt, PlanReceipt] = CanEqual.derived

extension (receipt: PlanReceipt)
  def verify(
      design: Design[?, ?],
      space: IndexSpace,
      population: Fingerprint
  )(using algorithm: DigestAlgorithm): Either[ReceiptError, Unit] =
    val expectedProvider = receipt.design.algorithm
    if expectedProvider != algorithm.id then
      Left(
        ReceiptError.ProviderMismatch(expectedProvider, algorithm.id)
      )
    else if receipt.assignment.algorithm != algorithm.id then
      Left(
        ReceiptError.ProviderMismatch(
          receipt.assignment.algorithm,
          algorithm.id
        )
      )
    else
      receipt.labels match
        case Some(value) if value.algorithm != algorithm.id =>
          Left(
            ReceiptError.ProviderMismatch(value.algorithm, algorithm.id)
          )
        case _ =>
          design.compile(space, receipt.seed) match
            case Left(error) =>
              Left(ReceiptError.CompilationFailure(error))
            case Right(compiled) =>
              compiled.receipt(population) match
                case Left(error) =>
                  Left(ReceiptError.DigestFailure(error))
                case Right(fresh) =>
                  if !FingerprintEquality.equal(
                      receipt.population,
                      fresh.population
                    )
                  then
                    Left(
                      ReceiptError.Mismatch(ReceiptComponent.Population)
                    )
                  else if !sameOptionalDigest(
                      receipt.labels,
                      fresh.labels
                    )
                  then Left(ReceiptError.Mismatch(ReceiptComponent.Labels))
                  else if receipt.algorithm != fresh.algorithm ||
                    receipt.design != fresh.design
                  then Left(ReceiptError.Mismatch(ReceiptComponent.Design))
                  else if receipt.assignment != fresh.assignment then
                    Left(
                      ReceiptError.Mismatch(ReceiptComponent.Assignment)
                    )
                  else Right(())

private[resample4s] def sameOptionalDigest(
    left: Option[ContentDigest],
    right: Option[ContentDigest]
): Boolean =
  (left, right) match
    case (Some(first), Some(second)) => first == second
    case (None, None) => true
    case _ => false
