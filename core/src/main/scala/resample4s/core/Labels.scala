package resample4s.core

import scala.collection.mutable

/**
 * Canonical equivalence classes over population ordinals.
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

  /**
   * Proves that every class in these labels is wholly contained in one class
   * of `coarser`.
   */
  def refines(
      coarser: Labels
  ): Either[DesignError, LabelRefinement] =
    LabelRefinement.of(this, coarser)

  private[resample4s] def unsafeAt(index: Int): Int = codes(index)

  /**
   * Projects canonical labels through a non-empty selection whose codomain is
   * this label population.
   *
   * The caller is internal because those two facts have already been
   * established by a validated `Split` and its compiled design. Classes that
   * are absent from the selection are removed and the remainder are
   * canonically recoded.
   */
  private[resample4s] def projectUnsafe(selection: Selection): Labels =
    val selected = new Array[Int](selection.domain)
    var index = 0
    selection.foreachIndex { ordinal =>
      selected(index) = codes(ordinal)
      index += 1
    }
    Labels.canonicalize(IArray.unsafeFromArray(selected), selection.domain)

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
  /** Validates already-dense codes using their known array length. */
  def of(
      codes: IArray[Int],
      cardinality: Int
  ): Either[DesignError, Labels] =
    of(codes, cardinality, codes.length)

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

  /** Canonically recodes arbitrary integer labels using their array length. */
  def dense(codes: IArray[Int]): Either[DesignError, Labels] =
    dense(codes, codes.length)

  def dense(codes: IArray[Int], n: Int): Either[DesignError, Labels] =
    if codes.length != n then Left(DesignError.LengthMismatch(n, codes.length))
    else if n == 0 then Left(DesignError.EmptyPopulation)
    else Right(canonicalize(codes, n))

  /**
   * Validates already-dense codes `0 .. k-1` without remapping class identity.
   *
   * Use for fold-of-row assignments where fold `i` must remain fold `i`. Prefer
   * [[dense]] for group/stratum codes whose raw integers are only labels.
   */
  def retained(codes: IArray[Int]): Either[DesignError, Labels] =
    retained(codes, codes.length)

  def retained(codes: IArray[Int], n: Int): Either[DesignError, Labels] =
    if codes.length != n then Left(DesignError.LengthMismatch(n, codes.length))
    else if n == 0 then Left(DesignError.EmptyPopulation)
    else
      var maximum = -1
      var index = 0
      while index < n do
        val code = codes(index)
        if code > maximum then maximum = code
        index += 1
      if maximum < 0 then Left(DesignError.InvalidCardinality(0))
      else
        val cardinality = maximum + 1
        val present = new Array[Boolean](cardinality)
        val owned = new Array[Int](n)
        index = 0
        var failure: Option[DesignError] = None
        while index < n && failure.isEmpty do
          val code = codes(index)
          if code < 0 || code >= cardinality then
            failure = Some(DesignError.InvalidLabel(index, code, cardinality))
          else
            present(code) = true
            owned(index) = code
          index += 1
        failure match
          case Some(error) => Left(error)
          case None =>
            var code = 0
            var missing: Option[Int] = None
            while code < cardinality && missing.isEmpty do
              if !present(code) then missing = Some(code)
              code += 1
            missing match
              case Some(value) => Left(DesignError.MissingLabel(value))
              case None =>
                Right(new Labels(IArray.unsafeFromArray(owned), cardinality))

  private def canonicalize(codes: IArray[Int], n: Int): Labels =
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
    new Labels(IArray.unsafeFromArray(owned), ordered.size)

  given CanEqual[Labels, Labels] = CanEqual.derived

/**
 * Evidence that `finer` is a refinement of `coarser`.
 *
 * Construction is validating: a finer class may belong to exactly one
 * coarser class, while multiple finer classes may share a coarser class.
 */
final class LabelRefinement private (
    val finer: Labels,
    val coarser: Labels
):
  override def equals(other: Any): Boolean =
    other match
      case that: LabelRefinement =>
        finer == that.finer && coarser == that.coarser
      case _ => false

  override def hashCode(): Int =
    31 * finer.hashCode() + coarser.hashCode()

object LabelRefinement:
  def of(
      finer: Labels,
      coarser: Labels
  ): Either[DesignError, LabelRefinement] =
    if finer.size != coarser.size then
      Left(DesignError.LengthMismatch(finer.size, coarser.size))
    else
      val parent = Array.fill(finer.cardinality)(-1)
      var index = 0
      var error: Option[DesignError] = None
      while index < finer.size && error.isEmpty do
        val child = finer.unsafeAt(index)
        val observedParent = coarser.unsafeAt(index)
        if parent(child) < 0 then parent(child) = observedParent
        else if parent(child) != observedParent then
          error = Some(
            DesignError.LabelRefinementViolation(
              child,
              parent(child),
              observedParent,
              index
            )
          )
        index += 1
      error match
        case Some(value) => Left(value)
        case None => Right(new LabelRefinement(finer, coarser))

  given CanEqual[LabelRefinement, LabelRefinement] = CanEqual.derived
