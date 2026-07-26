package tessera.core

enum DiagnosticMetric derives CanEqual:
  case MaxFoldSize
  case MinFoldSize
  case SizeImbalance
  case Objective
  case Optimum
  case Regret
  case MaxStratumDeviation
  case GroupPurityNumerator
  case GroupPurityDenominator
  case Repeats

/** Exact, typed observations about best-effort allocation quality. */
final class PlanDiagnostics private (
    private val entries: Vector[(DiagnosticMetric, BigInt)]
):
  def size: Int = entries.length

  def metric(index: Int): Either[OutOfDomain, DiagnosticMetric] =
    if index >= 0 && index < entries.length then Right(entries(index)._1)
    else Left(OutOfDomain(index, entries.length))

  def value(metric: DiagnosticMetric): Option[BigInt] =
    entries.find(_._1 == metric).map(_._2)

  override def equals(other: Any): Boolean =
    other match
      case that: PlanDiagnostics => entries == that.entries
      case _                     => false

  override def hashCode(): Int = entries.hashCode()

object PlanDiagnostics:
  val empty: PlanDiagnostics = new PlanDiagnostics(Vector.empty)

  def of(
      values: IArray[(DiagnosticMetric, BigInt)]
  ): Either[DesignError, PlanDiagnostics] =
    val result = Vector.newBuilder[(DiagnosticMetric, BigInt)]
    val seen = scala.collection.mutable.HashSet.empty[DiagnosticMetric]
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
      case None        => Right(new PlanDiagnostics(result.result()))

  private[tessera] def unsafe(
      values: (DiagnosticMetric, BigInt)*
  ): PlanDiagnostics =
    new PlanDiagnostics(values.toVector)

  given CanEqual[PlanDiagnostics, PlanDiagnostics] = CanEqual.derived

/** Declared upper bounds used by published design-conformance laws. */
final class PlanCost private (
    val residentElementsUpperBound: Long,
    val workPerUnitUpperBound: Long,
    val receiptWorkPerUnitUpperBound: Long
)

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

  private[tessera] def unsafe(
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
final class BuildContext private[tessera] (
    val space: IndexSpace,
    private val ownedLabels: Vector[Labels],
    val seed: Seed,
    val designKey: DesignKey
):
  def labels: Option[Labels] = ownedLabels.headOption
  def labelCount: Int = ownedLabels.length
  def labelAt(index: Int): Either[OutOfDomain, Labels] =
    if index >= 0 && index < ownedLabels.length then
      Right(ownedLabels(index))
    else Left(OutOfDomain(index, ownedLabels.length))

  def derive(path: StreamPath): Seed =
    Rand.derive(seed, designKey, path)

/** Canonical semantic encoding for a general design's public unit value.
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
        var index = 0
        while index < value.domain do
          out.int(value.unsafeAt(index))
          index += 1
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
    private[tessera] val unit: UnitKey => A,
    private[tessera] val encoder: CanonicalAssignmentEncoder[A]
)

object GeneralPlanSpec:
  def of[A](
      shape: PlanShape,
      diagnostics: PlanDiagnostics,
      cost: PlanCost
  )(
      unit: UnitKey => A,
      encoder: CanonicalAssignmentEncoder[A]
  ): Either[DesignError, GeneralPlanSpec[A]] =
    Right(new GeneralPlanSpec(shape, diagnostics, cost, unit, encoder))

final class ExactPartitionSpec private (
    private[tessera] val partitions: IArray[FoldPartition],
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
    if partitions.isEmpty then
      Left(DesignError.InvalidPlanShape(0, 0))
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

/** Framework-owned compilation route for a design.
  *
  * `general` always yields ordinary `Coverage`; only the partition routes can
  * produce exact-coverage evidence. `exactOncePartitions` additionally proves
  * that the plan contains one repeat.
  */
final class DesignDefinition[+A, +Cov <: Coverage] private (
    val descriptor: DesignDescriptor,
    private val ownedLabels: Vector[Labels],
    private val compileValidated:
      (IndexSpace, Seed, BuildContext) => Either[
        DesignError,
        Compiled[A, Cov]
      ]
):
  def labels: Option[Labels] = ownedLabels.headOption
  def labelCount: Int = ownedLabels.length
  def labelAt(index: Int): Either[OutOfDomain, Labels] =
    if index >= 0 && index < ownedLabels.length then
      Right(ownedLabels(index))
    else Left(OutOfDomain(index, ownedLabels.length))

  private[tessera] def labelValues: Vector[Labels] = ownedLabels

  private[tessera] def compile(
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

/** A self-contained, reproducible design.
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

  final def fingerprint(
      using algorithm: DigestAlgorithm
  ): Either[DigestError, ContentDigest] =
    CanonicalDesign
      .fingerprint(definition.descriptor, definition.labelValues)

  final def labelsFingerprint(
      using algorithm: DigestAlgorithm
  ): Either[DigestError, Option[ContentDigest]] =
    val labels = definition.labelValues
    if labels.nonEmpty then
        algorithm
          .digest(CanonicalDesign.labelChunks(labels).iterator)
          .map(result => Some(ContentDigest.of(algorithm.id, result)))
    else Right(None)

  final def compile(
      space: IndexSpace,
      seed: Seed
  ): Either[DesignError, Compiled[A, Cov]] =
    if space.size == 0 then Left(DesignError.EmptyPopulation)
    else definition.compile(space, seed)

private[tessera] trait DigestStream:
  def iterator: Iterator[IArray[Byte]]
  def error: Option[DigestError]

private[tessera] final class GeneralDigestStream[A](
    space: IndexSpace,
    plan: Plan[A, ? <: Coverage],
    encoder: CanonicalAssignmentEncoder[A]
) extends DigestStream:
  private var failure: Option[DigestError] = None
  private val units = plan.iterator
  private var pending =
    CanonicalAssignment.header(space, plan.shape).iterator

  val iterator: Iterator[IArray[Byte]] =
    new Iterator[IArray[Byte]]:
      def hasNext: Boolean =
        prepare()
        pending.hasNext

      def next(): IArray[Byte] =
        if !hasNext then throw new NoSuchElementException("next on empty iterator")
        pending.next()

      private def prepare(): Unit =
        while !pending.hasNext && units.hasNext && failure.isEmpty do
          val (key, value) = units.next()
          val writer = new CanonicalWriter()
          val unit = writer.variant("unit")
          if unit.isLeft then failure = unit.left.toOption
          else
            writer.int(key.repeat)
            writer.int(key.fold)
            encoder.encode(value, writer) match
              case Left(error) => failure = Some(error)
              case Right(_)    => pending = writer.chunks.iterator

  def error: Option[DigestError] = failure

private[tessera] final class ExactDigestStream(
    space: IndexSpace,
    shape: PlanShape,
    partitions: IArray[FoldPartition]
) extends DigestStream:
  val iterator: Iterator[IArray[Byte]] =
    CanonicalAssignment.header(space, shape).iterator ++
      partitions.iterator.zipWithIndex.flatMap { (partition, repeat) =>
        val repeatWriter = new CanonicalWriter()
        repeatWriter.variantUnchecked("partition")
        repeatWriter.int(repeat)
        repeatWriter.beginSequenceUnchecked(partition.populationSize)
        repeatWriter.chunks.iterator ++
          (0 until partition.populationSize).iterator.flatMap { index =>
            val value = new CanonicalWriter()
            value.int(partition.assignmentUnsafe(index))
            value.chunks.iterator
          }
      }

  val error: Option[DigestError] = None

private[tessera] object CanonicalAssignment:
  def header(
      space: IndexSpace,
      shape: PlanShape
  ): Vector[IArray[Byte]] =
    val writer = new CanonicalWriter()
    writer.textUnchecked("tessera/assignment/v1")
    writer.int(space.size)
    writer.int(shape.repeats)
    writer.int(shape.foldsPerRepeat)
    writer.chunks

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
          algorithm
            .digest(CanonicalDesign.labelChunks(labels).iterator)
            .map(result => Some(ContentDigest.of(algorithm.id, result)))
        else Right(None)
      assignmentDigest <-
        val stream = streamFactory()
        algorithm.digest(stream.iterator).flatMap { result =>
          stream.error match
            case Some(error) => Left(error)
            case None =>
              Right(ContentDigest.of(algorithm.id, result))
        }
    yield new PlanReceipt(
      descriptor.algorithm,
      designDigest,
      population,
      labelsDigest,
      seedValue,
      assignmentDigest
    )

object Compiled:
  private[tessera] def general[A, Cov <: Coverage](
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

  private[tessera] def exact[Cov <: Coverage.Exact](
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

/** A verification artifact for a compiled plan.
  *
  * A receipt cannot reconstruct a design. Verification requires the caller to
  * supply the design, index space, population fingerprint, and matching digest
  * provider. Even a cryptographic provider does not authenticate a receipt
  * without trusted storage or a signature outside Tessera.
  */
final class PlanReceipt private[tessera] (
    val algorithm: AlgorithmId,
    val design: ContentDigest,
    val population: Fingerprint,
    val labels: Option[ContentDigest],
    val seed: Seed,
    val assignment: ContentDigest
):
  private[tessera] def withSeed(value: Seed): PlanReceipt =
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

private[tessera] def sameOptionalDigest(
    left: Option[ContentDigest],
    right: Option[ContentDigest]
): Boolean =
  (left, right) match
    case (Some(first), Some(second)) => first == second
    case (None, None)                => true
    case _                           => false
