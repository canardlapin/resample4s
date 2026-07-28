package resample4s.core

final case class UnitKey(repeat: Int, fold: Int) derives CanEqual

final case class PlanShape private (
    repeats: Int,
    foldsPerRepeat: Int
) derives CanEqual:
  val unitCount: Int = repeats * foldsPerRepeat

object PlanShape:
  def of(
      repeats: Int,
      foldsPerRepeat: Int
  ): Either[DesignError, PlanShape] =
    val count = repeats.toLong * foldsPerRepeat.toLong
    if repeats < 1 || foldsPerRepeat < 1 || count > Int.MaxValue then
      Left(DesignError.InvalidPlanShape(repeats, foldsPerRepeat))
    else Right(new PlanShape(repeats, foldsPerRepeat))

sealed trait Coverage

object Coverage:
  /**
   * Evidence that assessment selections partition the population exactly once
   * within each repeat.
   */
  sealed trait Exact extends Coverage

  /**
   * Evidence that the plan has exactly one repeat and its assessment
   * selections partition the population exactly once.
   *
   * This is the stronger capability required by consumers that reconstruct
   * one out-of-fold value per input row. A repeated exact plan remains
   * [[Exact]], but is not [[ExactOnce]] because it assesses every row once per
   * repeat.
   */
  sealed trait ExactOnce extends Exact

private final class UnitKeyRange(shape: PlanShape) extends IndexedSeq[UnitKey]:
  def length: Int = shape.unitCount
  def apply(index: Int): UnitKey =
    if index < 0 || index >= length then
      throw new IndexOutOfBoundsException(index.toString)
    UnitKey(
      repeat = index / shape.foldsPerRepeat,
      fold = index % shape.foldsPerRepeat
    )

/**
 * An immutable, lazy plan backed by a pure unit generator.
 *
 * Repeated access recomputes a unit. `materialized` performs one explicit
 * eager traversal and returns a separate vector-backed plan; it never installs
 * a cache in this value.
 */
final class Plan[+A, +Cov <: Coverage] private (
    val shape: PlanShape,
    private val generate: UnitKey => A
):
  /**
   * The first unit. Every validated `PlanShape` has at least one repeat and
   * fold, so this accessor is total.
   */
  def first: A = generate(UnitKey(0, 0))

  def at(key: UnitKey): Either[UnknownUnit, A] =
    if key.repeat >= 0 && key.repeat < shape.repeats &&
      key.fold >= 0 && key.fold < shape.foldsPerRepeat
    then Right(generate(key))
    else Left(UnknownUnit(key, shape))

  def keys: IndexedSeq[UnitKey] = new UnitKeyRange(shape)

  def iterator: Iterator[(UnitKey, A)] =
    keys.iterator.map(key => (key, generate(key)))

  /**
   * Maps unit values and forgets coverage.
   *
   * Coverage is a property of a split schedule, not of an arbitrary payload.
   * Public mapping therefore returns [[Coverage]] only. Library code that has
   * established preservation may use [[mapPreservingCoverage]].
   */
  def map[B](function: A => B): Plan[B, Coverage] =
    Plan.fromGenerator(shape, key => function(generate(key)))

  /**
   * Maps unit values while retaining [[Cov]].
   *
   * Only for transformations the library has proven preserve the coverage
   * witness, such as NestedCrossValidation's outer-analysis embedding.
   */
  private[resample4s] def mapPreservingCoverage[B](
      function: A => B
  ): Plan[B, Cov] =
    Plan.fromGenerator(shape, key => function(generate(key)))

  def zip[B, C2 <: Coverage](
      that: Plan[B, C2]
  ): Either[ShapeMismatch, Plan[(A, B), Coverage]] =
    if shape.repeats != that.shape.repeats ||
      shape.foldsPerRepeat != that.shape.foldsPerRepeat
    then Left(ShapeMismatch(shape, that.shape))
    else
      Right(
        Plan.fromGenerator(
          shape,
          key => (generate(key), that.generate(key))
        )
      )

  def materialize: Vector[(UnitKey, A)] = iterator.toVector

  def materialized: Plan[A, Cov] =
    val values = materialize.map(_._2)
    Plan.fromGenerator(
      shape,
      key =>
        val index = key.repeat * shape.foldsPerRepeat + key.fold
        values(index)
    )

object Plan:
  private[resample4s] def fromGenerator[A, Cov <: Coverage](
      shape: PlanShape,
      generate: UnitKey => A
  ): Plan[A, Cov] =
    new Plan(shape, generate)

  /**
   * Finite comparison of generated units. Plans themselves are not equal by
   * reference under [[CanEqual]]; use this when an explicit unit-wise check is
   * required.
   */
  def sameUnits[A](
      left: Plan[A, ?],
      right: Plan[A, ?]
  )(using CanEqual[A, A]): Boolean =
    left.shape == right.shape &&
      left.keys.iterator.forall { key =>
        (left.at(key), right.at(key)) match
          case (Right(leftValue), Right(rightValue)) =>
            leftValue == rightValue
          case _ => false
      }

type AnyPlan[+A] = Plan[A, Coverage]
