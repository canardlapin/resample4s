package resample4s.designs

import resample4s.core.*

/**
 * Compose compatible ExactOnce outer/inner split designs into nested folds.
 *
 * For each outer unit, `innerFor` receives that unit's analysis selection and
 * must return an ExactOnce design for the analysis subpopulation. Compiled
 * inner selections are embedded back into the original population.
 *
 * Label-aware inners must project labels inside `innerFor` (see
 * [[NestedCrossValidation]]). Label-free designs may use [[Nested.of]].
 */
final class Nested private (
    private val outer: Design[Split[Selection], Coverage.ExactOnce],
    private val innerFor: Selection => Design[
      Split[Selection],
      Coverage.ExactOnce
    ],
    private val algorithm: String,
    private val ownedLabels: Vector[Labels]
) extends Design[NestedFold, Coverage.ExactOnce]:
  private val descriptor =
    DesignSupport.descriptor(algorithm)

  val definition: DesignDefinition[NestedFold, Coverage.ExactOnce] =
    DesignDefinition.derived(
      descriptor,
      IArray.unsafeFromArray(ownedLabels.toArray)
    )(context =>
      NestedCompose.compile(
        context,
        descriptor,
        ownedLabels,
        outer,
        innerFor
      )
    )

object Nested:
  /** Factory form: build an inner ExactOnce design for each outer analysis. */
  def combine(
      outer: Design[Split[Selection], Coverage.ExactOnce],
      innerFor: Selection => Design[Split[Selection], Coverage.ExactOnce]
  ): Nested =
    new Nested(
      outer,
      innerFor,
      "nested/v1",
      outer.definition.labelValues
    )

  /**
   * Fixed label-free (or already-population-sized) inner design.
   *
   * Prefer [[combine]] when the inner design carries labels that must be
   * projected onto each outer analysis selection.
   */
  def of(
      outer: Design[Split[Selection], Coverage.ExactOnce],
      inner: Design[Split[Selection], Coverage.ExactOnce]
  ): Nested =
    combine(outer, _ => inner)

private[designs] object NestedCompose:
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
      labels: Vector[Labels],
      outer: Design[Split[Selection], Coverage.ExactOnce],
      innerFor: Selection => Design[Split[Selection], Coverage.ExactOnce]
  ): Either[DesignError, Compiled[NestedFold, Coverage.ExactOnce]] =
    outer.compile(context.space, context.seed).flatMap { compiledOuter =>
      val shape = compiledOuter.plan.shape
      val nested = new Array[NestedFold](shape.unitCount)
      val outerUnits = compiledOuter.plan.iterator
      var index = 0
      var resident =
        BigInt(compiledOuter.cost.residentElementsUpperBound) +
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
        innerFor(outerSplit.analysis).compile(innerSpace, innerSeed) match
          case Left(error) =>
            failure = Some(DesignError.NestedInnerFailure(outerKey, error))
          case Right(inner) =>
            val embedded =
              inner.plan.mapPreservingCoverage(split =>
                embed(outerSplit.analysis, split)
              )
            nested(index) = new NestedFold(
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
            if unitReceiptWork > receiptWork then receiptWork = unitReceiptWork
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
              receiptWorkPerUnitUpperBound = cappedLong(receiptWork)
            )
          Right(
            Compiled.general(
              plan,
              compiledOuter.diagnostics,
              cost,
              descriptor,
              labels,
              context.space,
              context.seed,
              encoder
            )
          )
    }

  private[designs] def embed(
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
    value.foreachIndex { ordinal =>
      if out.error.isEmpty then out.int(ordinal)
    }

  private def linear(key: UnitKey, shape: PlanShape): Int =
    key.repeat * shape.foldsPerRepeat + key.fold

  private def cappedLong(value: BigInt): Long =
    if value > Long.MaxValue then Long.MaxValue
    else value.toLong
