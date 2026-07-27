package resample4s.laws

import org.scalacheck.Prop
import scala.reflect.ClassTag
import resample4s.core.*
import resample4s.designs.NestedFold

/** Universal algebraic laws for finite reindexings. */
object ReindexingLaws:
  def pullbackFunctoriality[
      A: ClassTag,
      F <: Reindexing,
      G <: Reindexing
  ](
      values: IArray[A],
      outer: F,
      inner: G
  )(using compose: Compose[F, G]): Prop =
    Prop.secure {
      val direct =
        outer.after(inner).flatMap(composed => pull(values, composed))
      val staged =
        pull(values, outer).flatMap(selected => pull(selected, inner))
      (direct, staged) match
        case (Right(first), Right(second)) =>
          iarrayEqual(first, second)
        case (Left(_), Left(_)) => true
        case _                  => false
    }

  def identity[A: ClassTag](
      values: IArray[A]
  ): Prop =
    Prop.secure {
      Permutation.identity(values.length) match
        case Left(_) => false
        case Right(identity) =>
          pull(values, identity) match
            case Left(_)         => false
            case Right(observed) => iarrayEqual(values, observed)
    }

  def injectionFactorization(injection: Injection): Prop =
    Prop.secure {
      val (selection, permutation) = injection.factor
      selection.after(permutation) match
        case Right(observed) => observed == injection
        case Left(_)         => false
    }

  def permutationGroup(
      first: Permutation,
      second: Permutation,
      third: Permutation
  ): Prop =
    Prop.secure {
      if first.domain != second.domain ||
          first.domain != third.domain
      then false
      else
        Permutation.identity(first.domain) match
          case Left(_) => false
          case Right(identity) =>
            val inverse =
              first.after(first.inverse).toOption.contains(identity) &&
                first.inverse.after(first).toOption.contains(identity)
            val neutral =
              first.after(identity).toOption.contains(first) &&
                identity.after(first).toOption.contains(first)
            val associative =
              for
                firstSecond <- first.after(second)
                left <- firstSecond.after(third)
                secondThird <- second.after(third)
                right <- first.after(secondThird)
              yield left == right
            inverse && neutral && associative.toOption.contains(true)
    }

  private def iarrayEqual[A](
      left: IArray[A],
      right: IArray[A]
  ): Boolean =
    if left.length != right.length then false
    else
      var index = 0
      var same = true
      while index < left.length && same do
        same = left(index).equals(right(index))
        index += 1
      same

/** Universal split, exact-coverage, reconstruction, and label laws. */
object PlanLaws:
  def disjointness[A <: Reindexing, Cov <: Coverage](
      plan: Plan[Split[A], Cov]
  ): Prop =
    Prop.secure {
      plan.iterator.forall { (_, split) =>
        split.analysis.support
          .intersection(split.assessment)
          .toOption
          .exists(_.domain == 0)
      }
    }

  def exactCoverage(
      plan: Plan[Split[Selection], Coverage.Exact],
      populationSize: Int
  ): Prop =
    Prop.secure {
      var repeat = 0
      var valid = populationSize >= 0
      while repeat < plan.shape.repeats && valid do
        val counts = Array.fill(populationSize)(0)
        var fold = 0
        while fold < plan.shape.foldsPerRepeat && valid do
          plan.at(UnitKey(repeat, fold)) match
            case Left(_) => valid = false
            case Right(split) =>
              var index = 0
              while index < split.assessment.domain && valid do
                split.assessment.at(index) match
                  case Left(_) => valid = false
                  case Right(ordinal) =>
                    if ordinal < 0 || ordinal >= populationSize then
                      valid = false
                    else counts(ordinal) += 1
                index += 1
          fold += 1
        valid = valid && counts.forall(_ == 1)
        repeat += 1
      valid
    }

  def reconstruction(
      plan: Plan[Split[Selection], Coverage.Exact],
      populationSize: Int
  ): Prop =
    Prop.secure {
      var repeat = 0
      var valid = populationSize >= 0
      while repeat < plan.shape.repeats && valid do
        val reconstructed = Array.fill(populationSize)(-1)
        var fold = 0
        while fold < plan.shape.foldsPerRepeat && valid do
          plan.at(UnitKey(repeat, fold)) match
            case Left(_) => valid = false
            case Right(split) =>
              var index = 0
              while index < split.assessment.domain && valid do
                split.assessment.at(index) match
                  case Left(_) => valid = false
                  case Right(ordinal) =>
                    if ordinal < 0 || ordinal >= populationSize ||
                        reconstructed(ordinal) >= 0
                    then valid = false
                    else reconstructed(ordinal) = ordinal
                index += 1
          fold += 1
        var ordinal = 0
        while ordinal < populationSize && valid do
          valid = reconstructed(ordinal) == ordinal
          ordinal += 1
        repeat += 1
      valid
    }

  def groupAtomicity[A <: Reindexing, Cov <: Coverage](
      plan: Plan[Split[A], Cov],
      groups: Labels
  ): Prop =
    Prop.secure {
      plan.iterator.forall { (_, split) =>
        if split.analysis.codomain != groups.size ||
            split.assessment.codomain != groups.size
        then false
        else
          val analysis = membership(split.analysis.support, groups.size)
          val assessment = membership(split.assessment, groups.size)
          (analysis, assessment) match
            case (Some(inAnalysis), Some(inAssessment)) =>
              val groupRole = Array.fill(groups.cardinality)(-1)
              var row = 0
              var valid = true
              while row < groups.size && valid do
                val role =
                  if inAnalysis(row) && !inAssessment(row) then 0
                  else if !inAnalysis(row) && inAssessment(row) then 1
                  else -1
                groups.at(row) match
                  case Left(_) => valid = false
                  case Right(group) =>
                    if role < 0 then valid = false
                    else if groupRole(group) < 0 then
                      groupRole(group) = role
                    else if groupRole(group) != role then valid = false
                row += 1
              valid
            case _ => false
      }
    }

  def stratificationBalance[Cov <: Coverage.Exact](
      plan: Plan[Split[Selection], Cov],
      strata: Labels
  ): Prop =
    Prop.secure {
      val totals = Array.fill(strata.cardinality)(0)
      var row = 0
      var valid = true
      while row < strata.size && valid do
        strata.at(row) match
          case Left(_)        => valid = false
          case Right(stratum) => totals(stratum) += 1
        row += 1

      var repeat = 0
      while repeat < plan.shape.repeats && valid do
        var fold = 0
        while fold < plan.shape.foldsPerRepeat && valid do
          plan.at(UnitKey(repeat, fold)) match
            case Left(_) => valid = false
            case Right(split) =>
              if split.assessment.codomain != strata.size then valid = false
              else
                val counts = Array.fill(strata.cardinality)(0)
                var index = 0
                while index < split.assessment.domain && valid do
                  split.assessment.at(index).flatMap(strata.at) match
                    case Left(_)        => valid = false
                    case Right(stratum) => counts(stratum) += 1
                  index += 1
                var stratum = 0
                while stratum < strata.cardinality && valid do
                  val lower = totals(stratum) / plan.shape.foldsPerRepeat
                  val upper =
                    (totals(stratum) + plan.shape.foldsPerRepeat - 1) /
                      plan.shape.foldsPerRepeat
                  valid =
                    counts(stratum) == lower || counts(stratum) == upper
                  stratum += 1
          fold += 1
        repeat += 1
      valid
    }

  private def membership(
      selection: Selection,
      populationSize: Int
  ): Option[Array[Boolean]] =
    if selection.codomain != populationSize then None
    else
      val result = Array.fill(populationSize)(false)
      var index = 0
      var valid = true
      while index < selection.domain && valid do
        selection.at(index) match
          case Left(_) => valid = false
          case Right(ordinal) =>
            if ordinal < 0 || ordinal >= populationSize then valid = false
            else result(ordinal) = true
        index += 1
      if valid then Some(result) else None

/** Universal laws for embedded nested cross-validation plans. */
object NestedCrossValidationLaws:
  /** Every inner role stays inside its outer analysis selection. */
  def exclusion[Cov <: Coverage](
      plan: Plan[NestedFold, Cov]
  ): Prop =
    Prop.secure {
      plan.iterator.forall { (_, nested) =>
        nested.inner.iterator.forall { (_, inner) =>
          (
            inner.analysis.intersection(nested.outer.assessment),
            inner.assessment.intersection(nested.outer.assessment)
          ) match
            case (Right(analysisOverlap), Right(assessmentOverlap)) =>
              analysisOverlap.domain == 0 &&
                assessmentOverlap.domain == 0
            case _ => false
        }
      }
    }

  /** Inner assessments partition the outer analysis exactly once, and every
    * inner split reconstructs that outer analysis.
    */
  def innerCoverage[Cov <: Coverage](
      plan: Plan[NestedFold, Cov],
      populationSize: Int
  ): Prop =
    Prop.secure {
      plan.iterator.forall { (_, nested) =>
        if nested.outer.analysis.codomain != populationSize ||
            nested.outer.assessment.codomain != populationSize
        then false
        else
          val expected = membership(nested.outer.analysis, populationSize)
          val counts = Array.fill(populationSize)(0)
          var valid = expected.nonEmpty
          val innerUnits = nested.inner.iterator
          while innerUnits.hasNext && valid do
            val (_, inner) = innerUnits.next()
            valid =
              inner.analysis.codomain == populationSize &&
                inner.assessment.codomain == populationSize &&
                (
                  inner.analysis.union(inner.assessment) match
                    case Right(reconstructed) =>
                      reconstructed == nested.outer.analysis
                    case Left(_) => false
                )
            var index = 0
            while index < inner.assessment.domain && valid do
              inner.assessment.at(index) match
                case Left(_) => valid = false
                case Right(ordinal) =>
                  if ordinal < 0 || ordinal >= populationSize then
                    valid = false
                  else counts(ordinal) += 1
              index += 1
          var ordinal = 0
          while ordinal < populationSize && valid do
            valid =
              counts(ordinal) ==
                (if expected.exists(_(ordinal)) then 1 else 0)
            ordinal += 1
          valid
      }
    }

  private def membership(
      selection: Selection,
      populationSize: Int
  ): Option[Array[Boolean]] =
    if selection.codomain != populationSize then None
    else
      val result = Array.fill(populationSize)(false)
      var index = 0
      var valid = true
      while index < selection.domain && valid do
        selection.at(index) match
          case Left(_) => valid = false
          case Right(ordinal) =>
            if ordinal < 0 || ordinal >= populationSize then valid = false
            else result(ordinal) = true
        index += 1
      if valid then Some(result) else None

/** Universal bootstrap semantics; distributional checks are deliberately not
  * included here.
  */
object ResamplingLaws:
  def bootstrapSplit(
      split: Split[Draw],
      populationSize: Int,
      expectedDraws: Int
  ): Prop =
    Prop.secure {
      if populationSize < 0 ||
          split.analysis.codomain != populationSize ||
          split.assessment.codomain != populationSize ||
          split.analysis.domain != expectedDraws
      then false
      else
        val expectedOob = split.analysis.support.complement
        val multiplicityTotal =
          Vector
            .range(0, populationSize)
            .map(split.analysis.multiplicity)
            .sum
        val ordinals =
          IArray.unsafeFromArray(
            Array.tabulate(populationSize)(index => index)
          )
        pull(ordinals, split.analysis) match
          case Left(_) => false
          case Right(observed) =>
            var index = 0
            var orderPreserved =
              observed.length == split.analysis.domain
            while index < observed.length && orderPreserved do
              split.analysis.at(index) match
                case Left(_) => orderPreserved = false
                case Right(value) =>
                  orderPreserved = observed(index) == value
              index += 1
            split.assessment == expectedOob &&
            multiplicityTotal == expectedDraws &&
            orderPreserved
    }

  def bootstrapComposition(
      split: Split[Draw],
      embedding: Selection
  ): Prop =
    Prop.secure {
      if split.analysis.codomain != embedding.domain ||
          split.assessment.codomain != embedding.domain
      then false
      else
        (
          embedding.after(split.analysis),
          embedding.after(split.assessment)
        ) match
          case (Right(composedDraw), Right(composedAssessment)) =>
            var index = 0
            var orderPreserved =
              composedDraw.domain == split.analysis.domain
            while index < composedDraw.domain && orderPreserved do
              val expected =
                split.analysis.at(index).flatMap(embedding.at)
              orderPreserved =
                expected == composedDraw.at(index)
              index += 1
            var ordinal = 0
            var multiplicitiesPreserved = true
            while ordinal < embedding.domain && multiplicitiesPreserved do
              embedding.at(ordinal) match
                case Left(_) => multiplicitiesPreserved = false
                case Right(embeddedOrdinal) =>
                  multiplicitiesPreserved =
                    composedDraw.multiplicity(embeddedOrdinal) ==
                      split.analysis.multiplicity(ordinal)
              ordinal += 1
            index = 0
            var assessmentPreserved =
              composedAssessment.domain == split.assessment.domain
            while index < composedAssessment.domain &&
                assessmentPreserved
            do
              assessmentPreserved =
                split.assessment
                  .at(index)
                  .flatMap(embedding.at) ==
                  composedAssessment.at(index)
              index += 1
            orderPreserved &&
            multiplicitiesPreserved &&
            assessmentPreserved
          case _ => false
    }

/** Universal bijection and exchangeability-block laws. */
object PermutationLaws:
  def bijection(permutation: Permutation): Prop =
    Prop.secure(isBijection(permutation))

  def withinBlocks(
      permutation: Permutation,
      blocks: Labels
  ): Prop =
    Prop.secure {
      if permutation.domain != blocks.size then false
      else
        var index = 0
        var preserved = isBijection(permutation)
        while index < permutation.domain && preserved do
          permutation.at(index).flatMap(blocks.at) match
            case Left(_) => preserved = false
            case Right(targetBlock) =>
              preserved = blocks.at(index).toOption.contains(targetBlock)
          index += 1
        preserved
    }

  private def isBijection(permutation: Permutation): Boolean =
    val seen = Array.fill(permutation.codomain)(false)
    var index = 0
    var valid = permutation.domain == permutation.codomain
    while index < permutation.domain && valid do
      permutation.at(index) match
        case Left(_) => valid = false
        case Right(value) =>
          if value < 0 || value >= permutation.codomain || seen(value) then
            valid = false
          else seen(value) = true
      index += 1
    valid && seen.forall(identity)

final class WorkMeasurement[-A] private (
    val residentElements: Long,
    val unitWork: A => Long,
    val receiptWork: A => Long
)

object WorkMeasurement:
  def of[A](
      residentElements: Long
  )(
      unitWork: A => Long,
      receiptWork: A => Long
  ): WorkMeasurement[A] =
    new WorkMeasurement(residentElements, unitWork, receiptWork)

/** Conformance laws for built-in and consumer-defined designs. */
object DesignLaws:
  def deterministic[A, Cov <: Coverage](
      design: Design[A, Cov],
      space: IndexSpace,
      seed: Seed
  )(
      equivalent: (A, A) => Boolean
  ): Prop =
    Prop.secure {
      (design.compile(space, seed), design.compile(space, seed)) match
        case (Right(first), Right(second)) =>
          first.plan.shape.repeats == second.plan.shape.repeats &&
          first.plan.shape.foldsPerRepeat ==
            second.plan.shape.foldsPerRepeat &&
          first.plan.keys.forall { key =>
            (first.plan.at(key), second.plan.at(key)) match
              case (Right(left), Right(right)) => equivalent(left, right)
              case _                          => false
          }
        case (Left(first), Left(second)) => first == second
        case _                          => false
    }

  def totalUnits[A, Cov <: Coverage](
      design: Design[A, Cov],
      space: IndexSpace,
      seed: Seed
  ): Prop =
    Prop.secure {
      design.compile(space, seed) match
        case Left(_) => true
        case Right(compiled) =>
          compiled.plan.keys.forall(key => compiled.plan.at(key).isRight)
    }

  def costConformance[A, Cov <: Coverage](
      design: Design[A, Cov],
      space: IndexSpace,
      seed: Seed,
      measurement: WorkMeasurement[A]
  ): Prop =
    Prop.secure {
      design.compile(space, seed) match
        case Left(_) => false
        case Right(compiled) =>
          measurement.residentElements <=
            compiled.cost.residentElementsUpperBound &&
          compiled.plan.iterator.forall { (_, value) =>
            measurement.unitWork(value) <=
              compiled.cost.workPerUnitUpperBound &&
            measurement.receiptWork(value) <=
              compiled.cost.receiptWorkPerUnitUpperBound
          }
    }

  def receiptReplay[A, Cov <: Coverage](
      design: Design[A, Cov],
      space: IndexSpace,
      seed: Seed,
      population: Fingerprint
  )(using algorithm: DigestAlgorithm): Prop =
    Prop.secure {
      design
        .compile(space, seed)
        .flatMap(_.receipt(population))
        .toOption
        .exists(
          _.verify(design, space, population).toOption.contains(())
        )
    }

  def equivalentCompilations[
      A,
      Cov1 <: Coverage,
      Cov2 <: Coverage
  ](
      first: Design[A, Cov1],
      second: Design[A, Cov2],
      space: IndexSpace,
      seed: Seed
  )(
      equivalent: (A, A) => Boolean
  ): Prop =
    Prop.secure(
      compilationsEquivalent(first, second, space, seed)(equivalent)
    )

  def labelRecoding[
      A,
      Cov1 <: Coverage,
      Cov2 <: Coverage
  ](
      first: Design[A, Cov1],
      second: Design[A, Cov2],
      labelPairs: IArray[(Labels, Labels)],
      space: IndexSpace,
      seed: Seed
  )(
      equivalent: (A, A) => Boolean
  )(using algorithm: DigestAlgorithm): Prop =
    Prop.secure {
      val labelsEquivalent =
        first.definition.labelCount == labelPairs.length &&
          second.definition.labelCount == labelPairs.length &&
          Vector.range(0, labelPairs.length).forall { index =>
            val (firstLabels, secondLabels) = labelPairs(index)
            firstLabels == secondLabels &&
            first.definition.labelAt(index).toOption.contains(firstLabels) &&
            second.definition.labelAt(index).toOption.contains(secondLabels)
          }
      val identityEquivalent =
        first.randomizationKey.value == second.randomizationKey.value &&
          first.fingerprint == second.fingerprint &&
          first.labelsFingerprint == second.labelsFingerprint
      val assignmentsEquivalent =
        compilationsEquivalent(first, second, space, seed)(equivalent)
      labelsEquivalent &&
      identityEquivalent &&
      assignmentsEquivalent
    }

  def assignmentPerturbation[
      A,
      Cov1 <: Coverage,
      Cov2 <: Coverage
  ](
      first: Design[A, Cov1],
      second: Design[A, Cov2],
      space: IndexSpace,
      seed: Seed,
      population: Fingerprint,
      key: UnitKey
  )(
      differs: (A, A) => Boolean
  )(using algorithm: DigestAlgorithm): Prop =
    Prop.secure {
      (first.compile(space, seed), second.compile(space, seed)) match
        case (Right(left), Right(right)) =>
          val unitDiffers =
            (left.plan.at(key), right.plan.at(key)) match
              case (Right(firstValue), Right(secondValue)) =>
                differs(firstValue, secondValue)
              case _ => false
          val digestDiffers =
            (left.receipt(population), right.receipt(population)) match
              case (Right(firstReceipt), Right(secondReceipt)) =>
                firstReceipt.assignment != secondReceipt.assignment
              case _ => false
          unitDiffers && digestDiffers
        case _ => false
    }

  private def compilationsEquivalent[
      A,
      Cov1 <: Coverage,
      Cov2 <: Coverage
  ](
      first: Design[A, Cov1],
      second: Design[A, Cov2],
      space: IndexSpace,
      seed: Seed
  )(
      equivalent: (A, A) => Boolean
  ): Boolean =
    (first.compile(space, seed), second.compile(space, seed)) match
      case (Right(left), Right(right)) =>
        left.plan.shape.repeats == right.plan.shape.repeats &&
        left.plan.shape.foldsPerRepeat ==
          right.plan.shape.foldsPerRepeat &&
        left.plan.keys.forall { key =>
          (left.plan.at(key), right.plan.at(key)) match
            case (Right(firstValue), Right(secondValue)) =>
              equivalent(firstValue, secondValue)
            case _ => false
        }
      case (Left(firstError), Left(secondError)) =>
        firstError == secondError
      case _ => false
