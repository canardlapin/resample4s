package tessera.laws

import org.scalacheck.Prop
import scala.reflect.ClassTag
import tessera.core.*

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
    exactCoverage(plan, populationSize)

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
