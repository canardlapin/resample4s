package resample4s.core

/**
 * One-dimensional address of a plan unit.
 *
 * Cross-validation layouts still expose [[UnitKey]] / [[FoldLayout]]; generic
 * traversal can use [[UnitId]] without pretending every design has folds.
 */
opaque type UnitId = Int

object UnitId:
  def fromOrdinal(value: Int): Either[DesignError, UnitId] =
    if value < 0 then Left(DesignError.OutOfRangeIndex(value, Int.MaxValue))
    else Right(value)

  private[resample4s] def unsafe(value: Int): UnitId = value

  extension (id: UnitId) def toInt: Int = id

/** Cartesian layout of repeats × folds for partitioning designs. */
final class FoldLayout private (
    val repeats: Int,
    val foldsPerRepeat: Int
) derives CanEqual:
  val unitCount: Long = repeats.toLong * foldsPerRepeat.toLong
  val shape: PlanShape =
    PlanShape.of(repeats, foldsPerRepeat) match
      case Right(value) => value
      case Left(error) =>
        throw new IllegalStateException(s"invalid fold layout: $error")

  def unit(repeat: Int, fold: Int): Either[UnknownUnit, UnitKey] =
    if repeat >= 0 && repeat < repeats && fold >= 0 && fold < foldsPerRepeat
    then Right(UnitKey(repeat, fold))
    else Left(UnknownUnit(UnitKey(repeat, fold), shape))

  def unitId(key: UnitKey): Either[UnknownUnit, UnitId] =
    unit(key.repeat, key.fold).map(resolved =>
      UnitId.unsafe(resolved.repeat * foldsPerRepeat + resolved.fold)
    )

  def unitKey(id: UnitId): Either[UnknownUnit, UnitKey] =
    val ordinal = id.toInt
    if ordinal < 0 || ordinal >= shape.unitCount then
      Left(UnknownUnit(UnitKey(ordinal, 0), shape))
    else Right(UnitKey(ordinal / foldsPerRepeat, ordinal % foldsPerRepeat))

object FoldLayout:
  def of(
      repeats: Int,
      foldsPerRepeat: Int
  ): Either[DesignError, FoldLayout] =
    PlanShape
      .of(repeats, foldsPerRepeat)
      .map(shape => new FoldLayout(shape.repeats, shape.foldsPerRepeat))

  private[resample4s] def unsafe(
      repeats: Int,
      foldsPerRepeat: Int
  ): FoldLayout =
    new FoldLayout(repeats, foldsPerRepeat)

extension (shape: PlanShape)
  def foldLayout: FoldLayout =
    FoldLayout.unsafe(shape.repeats, shape.foldsPerRepeat)

  def unitId(key: UnitKey): UnitId =
    UnitId.unsafe(key.repeat * shape.foldsPerRepeat + key.fold)

  def unitKey(id: UnitId): Either[UnknownUnit, UnitKey] =
    val ordinal = id.toInt
    if ordinal < 0 || ordinal >= shape.unitCount then
      Left(UnknownUnit(UnitKey(ordinal, 0), shape))
    else
      Right(
        UnitKey(ordinal / shape.foldsPerRepeat, ordinal % shape.foldsPerRepeat)
      )
