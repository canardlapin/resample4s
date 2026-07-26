package tessera.core

import scala.collection.mutable

/** Canonical equivalence classes over population ordinals.
  *
  * Factories defensively copy and recode classes by ascending minimum member
  * ordinal, making bijective raw-code changes observationally irrelevant.
  */
final class Labels private (
    private val codes: IArray[Int],
    val cardinality: Int
):
  val size: Int = codes.length

  def at(index: Int): Either[OutOfDomain, Int] =
    if index >= 0 && index < size then Right(codes(index))
    else Left(OutOfDomain(index, size))

  def toIArray: IArray[Int] = codes

  private[tessera] def unsafeAt(index: Int): Int = codes(index)

  override def equals(other: Any): Boolean =
    other match
      case that: Labels =>
        if cardinality != that.cardinality || size != that.size then false
        else
          var index = 0
          var equal = true
          while index < size && equal do
            equal = codes(index) == that.codes(index)
            index += 1
          equal
      case _ => false

  override def hashCode(): Int =
    var hash = 31 + cardinality
    var index = 0
    while index < size do
      hash = 31 * hash + codes(index)
      index += 1
    hash

object Labels:
  def of(
      codes: IArray[Int],
      cardinality: Int,
      n: Int
  ): Either[DesignError, Labels] =
    if cardinality < 1 then Left(DesignError.InvalidCardinality(cardinality))
    else if codes.length != n then
      Left(DesignError.LengthMismatch(n, codes.length))
    else
      val minima = Array.fill(cardinality)(Int.MaxValue)
      var index = 0
      var error: Option[DesignError] = None
      while index < n && error.isEmpty do
        val code = codes(index)
        if code < 0 || code >= cardinality then
          error = Some(DesignError.InvalidLabel(index, code, cardinality))
        else if index < minima(code) then minima(code) = index
        index += 1
      error match
        case Some(value) => Left(value)
        case None =>
          var code = 0
          var missing: Option[Int] = None
          while code < cardinality && missing.isEmpty do
            if minima(code) == Int.MaxValue then missing = Some(code)
            code += 1
          missing match
            case Some(value) => Left(DesignError.MissingLabel(value))
            case None =>
              val ordered =
                (0 until cardinality).toVector.sortBy(raw => minima(raw))
              val recode = new Array[Int](cardinality)
              var canonical = 0
              while canonical < ordered.size do
                recode(ordered(canonical)) = canonical
                canonical += 1
              val owned = new Array[Int](n)
              index = 0
              while index < n do
                owned(index) = recode(codes(index))
                index += 1
              Right(new Labels(IArray.unsafeFromArray(owned), cardinality))

  def dense(codes: IArray[Int], n: Int): Either[DesignError, Labels] =
    if codes.length != n then Left(DesignError.LengthMismatch(n, codes.length))
    else if n == 0 then Left(DesignError.EmptyPopulation)
    else
      val minima = mutable.HashMap.empty[Int, Int]
      var index = 0
      while index < n do
        val code = codes(index)
        minima.get(code) match
          case Some(previous) =>
            if index < previous then minima.update(code, index)
          case None => minima.update(code, index)
        index += 1
      val ordered = minima.toVector.sortBy(_._2).map(_._1)
      val recode = mutable.HashMap.empty[Int, Int]
      index = 0
      while index < ordered.size do
        recode.update(ordered(index), index)
        index += 1
      val owned = new Array[Int](n)
      index = 0
      while index < n do
        owned(index) = recode(codes(index))
        index += 1
      Right(new Labels(IArray.unsafeFromArray(owned), ordered.size))

  given CanEqual[Labels, Labels] = CanEqual.derived
