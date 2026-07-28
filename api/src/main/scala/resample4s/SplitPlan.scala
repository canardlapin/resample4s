package resample4s

import resample4s.core.*

/** Ordinary-user view of a compiled plan. */
final class SplitPlan[+A, +Cov <: Coverage] private[resample4s] (
    val compiled: Compiled[A, Cov]
):
  def shape: PlanShape = compiled.plan.shape
  def size: Int = compiled.plan.shape.unitCount
  def splits: Iterable[A] =
    compiled.plan.iterator.map(_._2).toVector
  def iterator: Iterator[A] =
    compiled.plan.iterator.map(_._2)
  def foreach(f: A => Unit): Unit =
    compiled.plan.iterator.foreach((_, value) => f(value))

object Facade:
  private[resample4s] val DeterministicSeed: Seed = Seed.fromLong(0L)

  def plan[A, Cov <: Coverage](
      design: Design[A, Cov],
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[A, Cov]] =
    for
      space <- IndexSpace.of(samples)
      compiled <- design.compile(space, Seed.fromLong(seed))
    yield new SplitPlan(compiled)

  def plan[A, Cov <: Coverage](
      design: Design[A, Cov],
      samples: Int
  ): Either[DesignError, SplitPlan[A, Cov]] =
    plan(design, samples, DeterministicSeed.value)

  def planWithLabels[A, Cov <: Coverage](
      design: Design[A, Cov],
      samples: Int,
      seed: Long
  ): Either[DesignError, SplitPlan[A, Cov]] =
    plan(design, samples, seed)
