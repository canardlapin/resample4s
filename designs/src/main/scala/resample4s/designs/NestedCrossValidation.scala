package resample4s.designs

import resample4s.core.*

/** One outer cross-validation fold and its inner plan.
  *
  * `inner` is compiled within `outer.analysis`, then embedded back into the
  * original population. Its analysis and assessment selections therefore use
  * the same codomain as `outer` and cannot contain an ordinal from
  * `outer.assessment`.
  */
final class NestedFold private[designs] (
    val outer: Split[Selection],
    val inner: Plan[Split[Selection], Coverage.ExactOnce],
    val innerSeed: Seed,
    val innerDiagnostics: PlanDiagnostics,
    val innerCost: PlanCost
)

private enum NestedVariant derives CanEqual:
  case Plain
  case Stratified(strata: Labels)
  case Grouped(groups: Labels)
  case GroupedStratified(groups: Labels, strata: Labels)

  def algorithm: String =
    this match
      case Plain               => "nested-kfold/v1"
      case Stratified(_)       => "nested-kfold-stratified/v1"
      case Grouped(_)          => "nested-kfold-grouped/v1"
      case GroupedStratified(_, _) =>
        "nested-kfold-grouped-stratified/v1"

  def labels: Vector[Labels] =
    this match
      case Plain                        => Vector.empty
      case Stratified(strata)           => Vector(strata)
      case Grouped(groups)              => Vector(groups)
      case GroupedStratified(groups, strata) =>
        Vector(groups, strata)

  def groupLabels: Option[Labels] =
    this match
      case Grouped(value)              => Some(value)
      case GroupedStratified(value, _) => Some(value)
      case _                           => None

  def stratumLabels: Option[Labels] =
    this match
      case Stratified(value)              => Some(value)
      case GroupedStratified(_, value)    => Some(value)
      case _                              => None

  def kfold(
      folds: Int,
      analysis: Option[Selection]
  ): Design[Split[Selection], Coverage.ExactOnce] =
    this match
      case Plain => KFold(folds)
      case Stratified(source) =>
        KFold.stratified(folds, projected(source, analysis))
      case Grouped(source) =>
        KFold.grouped(folds, projected(source, analysis))
      case GroupedStratified(groupSource, stratumSource) =>
        KFold.groupedStratified(
          folds,
          projected(groupSource, analysis),
          projected(stratumSource, analysis)
        )

  private def projected(
      source: Labels,
      analysis: Option[Selection]
  ): Labels =
    analysis match
      case Some(selection) => source.projectUnsafe(selection)
      case None            => source

/** A complete, data-blind nested K-fold design.
  *
  * Compile it once to obtain an exactly-covered outer plan. Every outer unit
  * contains its outer split and a separately exactly-covered inner plan whose
  * selections are already expressed in the original population.
  *
  * The plain constructor is:
  *
  * {{{
  * NestedCrossValidation(outerFolds = 5, innerFolds = 4)
  *   .compile(space, Seed.fromLong(42L))
  * }}}
  *
  * The label-aware constructors apply the same K-fold variant at both levels.
  * Fitting, tuning, and scoring remain the caller's responsibility.
  */
final class NestedCrossValidation private (
    val outerFolds: Int,
    val innerFolds: Int,
    private val variant: NestedVariant
) extends Design[NestedFold, Coverage.ExactOnce]:
  private val descriptor =
    DesignSupport.descriptor(
      variant.algorithm,
      "inner-folds" -> DescriptorValue.int(innerFolds),
      "outer-folds" -> DescriptorValue.int(outerFolds)
    )

  def groups: Option[Labels] = variant.groupLabels
  def strata: Option[Labels] = variant.stratumLabels

  val definition: DesignDefinition[NestedFold, Coverage.ExactOnce] =
    DesignDefinition.derived(
      descriptor,
      IArray.unsafeFromArray(variant.labels.toArray)
    )(context =>
      NestedCrossValidationSupport.compile(
        context,
        descriptor,
        variant,
        outerFolds,
        innerFolds
      )
    )

object NestedCrossValidation:
  def apply(
      outerFolds: Int,
      innerFolds: Int
  ): NestedCrossValidation =
    new NestedCrossValidation(
      outerFolds,
      innerFolds,
      NestedVariant.Plain
    )

  def stratified(
      outerFolds: Int,
      innerFolds: Int,
      strata: Labels
  ): NestedCrossValidation =
    new NestedCrossValidation(
      outerFolds,
      innerFolds,
      NestedVariant.Stratified(strata)
    )

  def grouped(
      outerFolds: Int,
      innerFolds: Int,
      groups: Labels
  ): NestedCrossValidation =
    new NestedCrossValidation(
      outerFolds,
      innerFolds,
      NestedVariant.Grouped(groups)
    )

  def groupedStratified(
      outerFolds: Int,
      innerFolds: Int,
      groups: Labels,
      strata: Labels
  ): NestedCrossValidation =
    new NestedCrossValidation(
      outerFolds,
      innerFolds,
      NestedVariant.GroupedStratified(groups, strata)
    )

private object NestedCrossValidationSupport:
  private val encoder: CanonicalAssignmentEncoder[NestedFold] =
    new CanonicalAssignmentEncoder[NestedFold]:
      def encode(
          value: NestedFold,
          out: CanonicalWriter
      ): Either[DigestError, Unit] =
        out.variantUnchecked("nested-fold")
        out.variantUnchecked("outer")
        writeSplit(value.outer, out)
        out.variantUnchecked("inner")
        out.beginSequenceUnchecked(value.inner.shape.unitCount)
        val units = value.inner.iterator
        while units.hasNext && out.error.isEmpty do
          val (key, split) = units.next()
          out.variantUnchecked("inner-unit")
          out.int(key.repeat)
          out.int(key.fold)
          writeSplit(split, out)
        Right(())

  def compile(
      context: BuildContext,
      descriptor: DesignDescriptor,
      variant: NestedVariant,
      outerFolds: Int,
      innerFolds: Int
  ): Either[
    DesignError,
    Compiled[NestedFold, Coverage.ExactOnce]
  ] =
    variant
      .kfold(outerFolds, None)
      .compile(context.space, context.seed)
      .flatMap { outer =>
        val shape = outer.plan.shape
        val nested = new Array[NestedFold](shape.unitCount)
        val outerUnits = outer.plan.iterator
        var index = 0
        var resident =
          BigInt(outer.cost.residentElementsUpperBound) +
            BigInt(2L) * shape.unitCount
        var receiptWork = BigInt(0)
        var failure: Option[DesignError] = None

        while outerUnits.hasNext && failure.isEmpty do
          val (outerKey, outerSplit) = outerUnits.next()
          val innerSeed =
            context.derive(
              StreamPath.unsafe(
                StreamDomain.OuterUnit,
                linear(outerKey, shape)
              )
            )
          val innerSpace = IndexSpace.unsafe(outerSplit.analysis.domain)
          variant
            .kfold(innerFolds, Some(outerSplit.analysis))
            .compile(innerSpace, innerSeed) match
            case Left(error) =>
              failure =
                Some(DesignError.NestedInnerFailure(outerKey, error))
            case Right(inner) =>
              val embedded =
                inner.plan.map(split => embed(outerSplit.analysis, split))
              nested(index) =
                new NestedFold(
                  outerSplit,
                  embedded,
                  innerSeed,
                  inner.diagnostics,
                  inner.cost
                )
              resident += inner.cost.residentElementsUpperBound
              val unitReceiptWork =
                BigInt(outerSplit.analysis.domain) +
                  outerSplit.assessment.domain +
                  BigInt(inner.plan.shape.unitCount) *
                    outerSplit.analysis.domain
              if unitReceiptWork > receiptWork then
                receiptWork = unitReceiptWork
          index += 1

        failure match
          case Some(error) => Left(error)
          case None =>
            val values = IArray.unsafeFromArray(nested)
            val plan =
              Plan.fromGenerator[NestedFold, Coverage.ExactOnce](
                shape,
                key => values(linear(key, shape))
              )
            val cost =
              PlanCost.unsafe(
                residentElementsUpperBound = cappedLong(resident),
                workPerUnitUpperBound = 1L,
                receiptWorkPerUnitUpperBound =
                  cappedLong(receiptWork)
              )
            Right(
              Compiled.general(
                plan,
                outer.diagnostics,
                cost,
                descriptor,
                variant.labels,
                context.space,
                context.seed,
                encoder
              )
            )
      }

  private def embed(
      outerAnalysis: Selection,
      local: Split[Selection]
  ): Split[Selection] =
    Split.unsafe(
      Compose.selectionSelection.compose(
        outerAnalysis,
        local.analysis
      ),
      Compose.selectionSelection.compose(
        outerAnalysis,
        local.assessment
      )
    )

  private def writeSplit(
      value: Split[Selection],
      out: CanonicalWriter
  ): Unit =
    out.variantUnchecked("split")
    out.variantUnchecked("analysis")
    writeSelection(value.analysis, out)
    out.variantUnchecked("assessment")
    writeSelection(value.assessment, out)

  private def writeSelection(
      value: Selection,
      out: CanonicalWriter
  ): Unit =
    out.beginSequenceUnchecked(value.domain)
    var index = 0
    while index < value.domain && out.error.isEmpty do
      out.int(value.unsafeAt(index))
      index += 1

  private def linear(key: UnitKey, shape: PlanShape): Int =
    key.repeat * shape.foldsPerRepeat + key.fold

  private def cappedLong(value: BigInt): Long =
    if value > Long.MaxValue then Long.MaxValue
    else value.toLong
