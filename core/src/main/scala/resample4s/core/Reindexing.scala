package resample4s.core

import scala.collection.mutable
import scala.reflect.ClassTag

/** Discriminator for reindexing kinds that participate in [[Split]] equality. */
private[resample4s] enum ReindexingKind derives CanEqual:
  case Selection, Draw, Injection, Permutation

/**
 * A total function from one finite ordinal into another.
 *
 * Public lookup is checked. Implementations expose an unchecked path only
 * inside Resample4s after dimensions have been validated.
 *
 * Ordinary full traversal must use [[foreachIndex]] or [[cursor]], not
 * repeated [[at]] / `unsafeAt` on compact backings.
 */
sealed trait Reindexing extends IterableOnce[Int]:
  def domain: Int
  def codomain: Int

  /** Familiar alias for [[domain]]. */
  final def size: Int = domain

  /** Familiar alias for [[codomain]]. */
  final def populationSize: Int = codomain

  def at(index: Int): Either[OutOfDomain, Int] =
    if index >= 0 && index < domain then Right(unsafeAt(index))
    else Left(OutOfDomain(index, domain))

  def get(position: Int): Option[Int] = at(position).toOption

  def toIArray: IArray[Int]
  def toArray: Array[Int] =
    val values = toIArray
    val copy = new Array[Int](values.length)
    var index = 0
    while index < values.length do
      copy(index) = values(index)
      index += 1
    copy
  def toVector: Vector[Int] = toIArray.toVector
  def support: Selection
  private[resample4s] def unsafeAt(index: Int): Int
  private[resample4s] def cursor: IntCursor
  private[resample4s] def kind: ReindexingKind

  /** Applies `f` to each ordinal in domain order using a linear cursor. */
  final def foreachIndex(f: Int => Unit): Unit =
    val it = cursor
    while it.hasNext do f(it.nextInt())

  final def iterator: Iterator[Int] =
    new Iterator[Int]:
      private val underlying = cursor
      def hasNext: Boolean = underlying.hasNext
      def next(): Int = underlying.nextInt()

  /** Extensional ordinal equality, ignoring reindexing kind. */
  final def sameMapping(that: Reindexing): Boolean =
    ReindexingEquality.equal(this, that)

sealed trait Injective extends Reindexing

private[resample4s] object ReindexingValidation:
  def validateRange(
      values: IArray[Int],
      codomain: Int
  ): Either[DesignError, Unit] =
    var index = 0
    var error: Option[DesignError] = None
    while index < values.length && error.isEmpty do
      val value = values(index)
      if value < 0 || value >= codomain then
        error = Some(DesignError.OutOfRangeIndex(value, codomain))
      index += 1
    error.toLeft(())

  def validateUnique(
      values: IArray[Int],
      codomain: Int
  ): Either[DesignError, Unit] =
    validateRange(values, codomain).flatMap { _ =>
      val seen = Array.fill(codomain)(false)
      var index = 0
      var duplicate: Option[Int] = None
      while index < values.length && duplicate.isEmpty do
        val value = values(index)
        if seen(value) then duplicate = Some(value)
        else seen(value) = true
        index += 1
      duplicate match
        case Some(value) => Left(DesignError.DuplicateIndex(value))
        case None => Right(())
    }

private[resample4s] sealed trait SelectionBacking:
  def size: Int
  def codomain: Int
  def unsafeAt(index: Int): Int
  def materialize: IArray[Int]
  def cursor: IntCursor

private[resample4s] object SelectionBacking:
  final class Explicit(
      val values: IArray[Int],
      val codomain: Int
  ) extends SelectionBacking:
    def size: Int = values.length
    def unsafeAt(index: Int): Int = values(index)
    def materialize: IArray[Int] = values
    def cursor: IntCursor = ArrayIntCursor(values)

  final class Block(
      private[resample4s] val partition: FoldPartition,
      private[resample4s] val blockId: Int
  ) extends SelectionBacking:
    def size: Int = partition.blockSizeUnsafe(blockId)
    def codomain: Int = partition.populationSize
    def unsafeAt(index: Int): Int = partition.blockMemberUnsafe(blockId, index)
    def materialize: IArray[Int] = partition.blockMembersUnsafe(blockId)
    def cursor: IntCursor =
      new IntCursor:
        private var index = 0
        def hasNext: Boolean = index < size
        def nextInt(): Int =
          val value = partition.blockMemberUnsafe(blockId, index)
          index += 1
          value

  final class ComplementBlock(
      private[resample4s] val partition: FoldPartition,
      private[resample4s] val blockId: Int
  ) extends SelectionBacking:
    def size: Int =
      partition.populationSize - partition.blockSizeUnsafe(blockId)
    def codomain: Int = partition.populationSize
    def unsafeAt(index: Int): Int =
      var populationIndex = 0
      var found = 0
      while populationIndex < codomain do
        CompactTraversalProbe.observe()
        if partition.assignmentUnsafe(populationIndex) != blockId then
          if found == index then return populationIndex
          found += 1
        populationIndex += 1
      throw new IndexOutOfBoundsException(index.toString)
    def materialize: IArray[Int] =
      val values = new Array[Int](size)
      var populationIndex = 0
      var output = 0
      while populationIndex < codomain do
        CompactTraversalProbe.observe()
        if partition.assignmentUnsafe(populationIndex) != blockId then
          values(output) = populationIndex
          output += 1
        populationIndex += 1
      IArray.unsafeFromArray(values)
    def cursor: IntCursor =
      new IntCursor:
        private var populationIndex = 0
        private var emitted = 0
        def hasNext: Boolean = emitted < size
        def nextInt(): Int =
          while populationIndex < codomain do
            CompactTraversalProbe.observe()
            val current = populationIndex
            val included =
              partition.assignmentUnsafe(populationIndex) != blockId
            populationIndex += 1
            if included then
              emitted += 1
              return current
          throw new NoSuchElementException("ComplementBlock cursor exhausted")

  final class LabelClasses(
      labels: Labels,
      classSet: IArray[Boolean]
  ) extends SelectionBacking:
    val codomain: Int = labels.size
    val size: Int =
      var count = 0
      var index = 0
      while index < labels.size do
        if classSet(labels.unsafeAt(index)) then count += 1
        index += 1
      count
    def unsafeAt(index: Int): Int =
      var populationIndex = 0
      var found = 0
      while populationIndex < codomain do
        CompactTraversalProbe.observe()
        if classSet(labels.unsafeAt(populationIndex)) then
          if found == index then return populationIndex
          found += 1
        populationIndex += 1
      throw new IndexOutOfBoundsException(index.toString)
    def materialize: IArray[Int] =
      val values = new Array[Int](size)
      var populationIndex = 0
      var output = 0
      while populationIndex < codomain do
        CompactTraversalProbe.observe()
        if classSet(labels.unsafeAt(populationIndex)) then
          values(output) = populationIndex
          output += 1
        populationIndex += 1
      IArray.unsafeFromArray(values)
    def cursor: IntCursor =
      new IntCursor:
        private var populationIndex = 0
        private var emitted = 0
        def hasNext: Boolean = emitted < size
        def nextInt(): Int =
          while populationIndex < codomain do
            CompactTraversalProbe.observe()
            val current = populationIndex
            val included = classSet(labels.unsafeAt(populationIndex))
            populationIndex += 1
            if included then
              emitted += 1
              return current
          throw new NoSuchElementException("LabelClasses cursor exhausted")

  final class ComplementOf(
      private[resample4s] val base: Selection
  ) extends SelectionBacking:
    val size: Int = base.codomain - base.domain
    val codomain: Int = base.codomain
    def unsafeAt(index: Int): Int =
      var populationIndex = 0
      var baseIndex = 0
      var found = 0
      while populationIndex < codomain do
        CompactTraversalProbe.observe()
        val excluded =
          baseIndex < base.domain &&
            base.unsafeAt(baseIndex) == populationIndex
        if excluded then baseIndex += 1
        else
          if found == index then return populationIndex
          found += 1
        populationIndex += 1
      throw new IndexOutOfBoundsException(index.toString)
    def materialize: IArray[Int] =
      val values = new Array[Int](size)
      var populationIndex = 0
      var output = 0
      val baseCursor = base.cursor
      var hasExcluded = baseCursor.hasNext
      var nextExcluded = if hasExcluded then baseCursor.nextInt() else -1
      while populationIndex < codomain do
        CompactTraversalProbe.observe()
        if hasExcluded && populationIndex == nextExcluded then
          if baseCursor.hasNext then nextExcluded = baseCursor.nextInt()
          else hasExcluded = false
        else
          values(output) = populationIndex
          output += 1
        populationIndex += 1
      IArray.unsafeFromArray(values)
    def cursor: IntCursor =
      new IntCursor:
        private val baseCursor = base.cursor
        private var hasExcluded = baseCursor.hasNext
        private var nextExcluded =
          if hasExcluded then baseCursor.nextInt() else -1
        private var populationIndex = 0
        private var emitted = 0
        def hasNext: Boolean = emitted < size
        def nextInt(): Int =
          while populationIndex < codomain do
            CompactTraversalProbe.observe()
            val current = populationIndex
            populationIndex += 1
            if hasExcluded && current == nextExcluded then
              if baseCursor.hasNext then nextExcluded = baseCursor.nextInt()
              else hasExcluded = false
            else
              emitted += 1
              return current
          throw new NoSuchElementException("ComplementOf cursor exhausted")

/**
 * A strictly increasing, injective finite reindexing.
 *
 * Equality and hashing are extensional: compact block and complement
 * backings are not observable.
 */
final class Selection private[resample4s] (
    private[resample4s] val backing: SelectionBacking
) extends Injective:
  def domain: Int = backing.size
  def codomain: Int = backing.codomain
  private[resample4s] def unsafeAt(index: Int): Int = backing.unsafeAt(index)
  private[resample4s] def cursor: IntCursor = backing.cursor
  private[resample4s] def kind: ReindexingKind = ReindexingKind.Selection
  def toIArray: IArray[Int] = backing.materialize
  def support: Selection = this

  /** Explicitly forgets increasing order while retaining injectivity. */
  def widen: Injection =
    Injection.fromOwned(toIArray, codomain)

  def complement: Selection =
    backing match
      case value: SelectionBacking.ComplementOf => value.base
      case value: SelectionBacking.Block =>
        Selection.complementBlock(value.partition, value.blockId)
      case value: SelectionBacking.ComplementBlock =>
        Selection.block(value.partition, value.blockId)
      case _ =>
        new Selection(SelectionBacking.ComplementOf(this))

  def intersection(that: Selection): Either[CodomainMismatch, Selection] =
    merge(that, _ == 0)

  def union(that: Selection): Either[CodomainMismatch, Selection] =
    merge(that, _ <= 0, includeRightOnly = true)

  def difference(that: Selection): Either[CodomainMismatch, Selection] =
    if codomain != that.codomain then
      Left(CodomainMismatch(codomain, that.codomain))
    else
      val result = Vector.newBuilder[Int]
      val leftCursor = cursor
      val rightCursor = that.cursor
      var rightHas = rightCursor.hasNext
      var rightValue = if rightHas then rightCursor.nextInt() else 0
      while leftCursor.hasNext do
        val value = leftCursor.nextInt()
        while rightHas && rightValue < value do
          if rightCursor.hasNext then rightValue = rightCursor.nextInt()
          else rightHas = false
        if !rightHas || rightValue != value then result += value
        else if rightCursor.hasNext then rightValue = rightCursor.nextInt()
        else rightHas = false
      Right(Selection.fromSortedOwned(result.result(), codomain))

  private def merge(
      that: Selection,
      includeComparison: Int => Boolean,
      includeRightOnly: Boolean = false
  ): Either[CodomainMismatch, Selection] =
    if codomain != that.codomain then
      Left(CodomainMismatch(codomain, that.codomain))
    else
      val result = Vector.newBuilder[Int]
      val leftCursor = cursor
      val rightCursor = that.cursor
      var leftHas = leftCursor.hasNext
      var rightHas = rightCursor.hasNext
      var leftValue = if leftHas then leftCursor.nextInt() else 0
      var rightValue = if rightHas then rightCursor.nextInt() else 0
      while leftHas || rightHas do
        if !leftHas then
          if includeRightOnly then result += rightValue
          if rightCursor.hasNext then rightValue = rightCursor.nextInt()
          else rightHas = false
        else if !rightHas then
          if includeRightOnly then result += leftValue
          if leftCursor.hasNext then leftValue = leftCursor.nextInt()
          else leftHas = false
        else
          val comparison = java.lang.Integer.compare(leftValue, rightValue)
          if comparison == 0 then
            if includeComparison(comparison) then result += leftValue
            if leftCursor.hasNext then leftValue = leftCursor.nextInt()
            else leftHas = false
            if rightCursor.hasNext then rightValue = rightCursor.nextInt()
            else rightHas = false
          else if comparison < 0 then
            if includeComparison(comparison) then result += leftValue
            if leftCursor.hasNext then leftValue = leftCursor.nextInt()
            else leftHas = false
          else
            if includeRightOnly then result += rightValue
            if rightCursor.hasNext then rightValue = rightCursor.nextInt()
            else rightHas = false
      Right(Selection.fromSortedOwned(result.result(), codomain))

  override def equals(other: Any): Boolean =
    other match
      case that: Selection =>
        ReindexingEquality.equal(this, that)
      case _ => false

  override def hashCode(): Int = ReindexingEquality.hash(this)

  override def toString: String = Rendering.reindexing(this)

object Selection:
  def from(
      values: IArray[Int],
      space: IndexSpace
  ): Either[DesignError, Selection] =
    ReindexingValidation.validateRange(values, space.size).flatMap { _ =>
      var index = 1
      var error: Option[DesignError] = None
      while index < values.length && error.isEmpty do
        if values(index - 1) >= values(index) then
          error = Some(
            DesignError.NonIncreasingIndex(values(index - 1), values(index))
          )
        index += 1
      error match
        case Some(value) => Left(value)
        case None =>
          val owned = OwnedArrays.copyInt(values)
          Right(
            new Selection(SelectionBacking.Explicit(owned, space.size))
          )
    }

  def empty(space: IndexSpace): Selection =
    fromSortedOwned(Vector.empty, space.size)

  def singleton(index: Int, space: IndexSpace): Either[DesignError, Selection] =
    from(OwnedArrays.ints(index), space)

  private[resample4s] def fromSortedOwned(
      values: Vector[Int],
      codomain: Int
  ): Selection =
    new Selection(
      SelectionBacking.Explicit(OwnedArrays.fromVector(values), codomain)
    )

  private[resample4s] def fromOwned(
      values: IArray[Int],
      codomain: Int
  ): Selection =
    new Selection(SelectionBacking.Explicit(values, codomain))

  private[resample4s] def block(
      partition: FoldPartition,
      blockId: Int
  ): Selection =
    new Selection(SelectionBacking.Block(partition, blockId))

  private[resample4s] def complementBlock(
      partition: FoldPartition,
      blockId: Int
  ): Selection =
    new Selection(SelectionBacking.ComplementBlock(partition, blockId))

  private[resample4s] def labelClasses(
      labels: Labels,
      classSet: IArray[Boolean]
  ): Selection =
    new Selection(SelectionBacking.LabelClasses(labels, classSet))

  given CanEqual[Selection, Selection] = CanEqual.derived

/**
 * An ordered draw sequence in which targets may repeat.
 *
 * Order is semantic and participates in equality and receipt encoding.
 * `sameMultiset` is the explicitly weaker comparison.
 */
final class Draw private (
    private val values: IArray[Int],
    val codomain: Int,
    private val knownSupport: Option[Selection]
) extends Reindexing:
  def domain: Int = values.length
  private[resample4s] def unsafeAt(index: Int): Int = values(index)
  private[resample4s] def cursor: IntCursor = ArrayIntCursor(values)
  private[resample4s] def kind: ReindexingKind = ReindexingKind.Draw
  def toIArray: IArray[Int] = values
  def support: Selection =
    knownSupport.getOrElse {
      val seen = Array.fill(codomain)(false)
      var index = 0
      while index < domain do
        seen(values(index)) = true
        index += 1
      val result = Vector.newBuilder[Int]
      index = 0
      while index < codomain do
        if seen(index) then result += index
        index += 1
      Selection.fromSortedOwned(result.result(), codomain)
    }

  def multiplicity(index: Int): Int =
    var count = 0
    var position = 0
    while position < domain do
      if values(position) == index then count += 1
      position += 1
    count

  def sameMultiset(that: Draw): Boolean =
    if codomain != that.codomain || domain != that.domain then false
    else
      val counts = Array.fill(codomain)(0)
      var index = 0
      while index < domain do
        counts(values(index)) += 1
        counts(that.values(index)) -= 1
        index += 1
      counts.forall(_ == 0)

  override def equals(other: Any): Boolean =
    other match
      case that: Draw => ReindexingEquality.equal(this, that)
      case _ => false

  override def hashCode(): Int = ReindexingEquality.hash(this)

  override def toString: String = Rendering.reindexing(this)

object Draw:
  def from(values: IArray[Int], space: IndexSpace): Either[DesignError, Draw] =
    ReindexingValidation.validateRange(values, space.size).map { _ =>
      new Draw(OwnedArrays.copyInt(values), space.size, None)
    }

  private[resample4s] def fromOwned(
      values: IArray[Int],
      codomain: Int,
      support: Option[Selection] = None
  ): Draw =
    new Draw(values, codomain, support)

  given CanEqual[Draw, Draw] = CanEqual.derived

/** An injective finite reindexing with arbitrary output order. */
final class Injection private (
    private val values: IArray[Int],
    val codomain: Int
) extends Injective:
  def domain: Int = values.length
  private[resample4s] def unsafeAt(index: Int): Int = values(index)
  private[resample4s] def cursor: IntCursor = ArrayIntCursor(values)
  private[resample4s] def kind: ReindexingKind = ReindexingKind.Injection
  def toIArray: IArray[Int] = values
  lazy val support: Selection =
    val sorted = Array.tabulate(domain)(values(_))
    scala.util.Sorting.quickSort(sorted)
    Selection.fromOwned(IArray.unsafeFromArray(sorted), codomain)

  def factor: (Selection, Permutation) =
    val selected = support
    val positions = mutable.HashMap.empty[Int, Int]
    var index = 0
    selected.foreachIndex { ordinal =>
      positions.update(ordinal, index)
      index += 1
    }
    val permutation = new Array[Int](domain)
    index = 0
    while index < domain do
      permutation(index) = positions(values(index))
      index += 1
    (
      selected,
      Permutation.fromOwned(IArray.unsafeFromArray(permutation))
    )

  def widen: Draw = Draw.fromOwned(values, codomain, Some(support))

  override def equals(other: Any): Boolean =
    other match
      case that: Injection => ReindexingEquality.equal(this, that)
      case _ => false

  override def hashCode(): Int = ReindexingEquality.hash(this)

  override def toString: String = Rendering.reindexing(this)

object Injection:
  def from(
      values: IArray[Int],
      space: IndexSpace
  ): Either[DesignError, Injection] =
    ReindexingValidation.validateUnique(values, space.size).map { _ =>
      new Injection(OwnedArrays.copyInt(values), space.size)
    }

  private[resample4s] def fromOwned(
      values: IArray[Int],
      codomain: Int
  ): Injection =
    new Injection(values, codomain)

  given CanEqual[Injection, Injection] = CanEqual.derived

/** A bijection of a finite ordinal. */
final class Permutation private (private val values: IArray[Int])
    extends Injective:
  val domain: Int = values.length
  val codomain: Int = values.length
  private[resample4s] def unsafeAt(index: Int): Int = values(index)
  private[resample4s] def cursor: IntCursor = ArrayIntCursor(values)
  private[resample4s] def kind: ReindexingKind = ReindexingKind.Permutation
  def toIArray: IArray[Int] = values
  lazy val support: Selection =
    val identity = new Array[Int](domain)
    var index = 0
    while index < domain do
      identity(index) = index
      index += 1
    Selection.fromOwned(IArray.unsafeFromArray(identity), domain)

  def inverse: Permutation =
    val inverted = new Array[Int](domain)
    var index = 0
    while index < domain do
      inverted(values(index)) = index
      index += 1
    Permutation.fromOwned(IArray.unsafeFromArray(inverted))

  def andThen(
      that: Permutation
  ): Either[DomainMismatch, Permutation] =
    that.after(this)

  def widen: Injection = Injection.fromOwned(values, codomain)

  override def equals(other: Any): Boolean =
    other match
      case that: Permutation => ReindexingEquality.equal(this, that)
      case _ => false

  override def hashCode(): Int = ReindexingEquality.hash(this)

  override def toString: String = Rendering.reindexing(this)

object Permutation:
  def from(
      values: IArray[Int]
  ): Either[DesignError, Permutation] =
    val size = values.length
    ReindexingValidation.validateUnique(values, size).map { _ =>
      new Permutation(OwnedArrays.copyInt(values))
    }

  def identity(size: Int): Either[DesignError, Permutation] =
    if size < 0 then Left(DesignError.NegativePopulation(size))
    else
      val values = new Array[Int](size)
      var index = 0
      while index < size do
        values(index) = index
        index += 1
      Right(fromOwned(IArray.unsafeFromArray(values)))

  private[resample4s] def fromOwned(values: IArray[Int]): Permutation =
    new Permutation(values)

  given CanEqual[Permutation, Permutation] = CanEqual.derived

private[resample4s] object ReindexingEquality:
  def equal(left: Reindexing, right: Reindexing): Boolean =
    if left.domain != right.domain || left.codomain != right.codomain then false
    else
      val leftCursor = left.cursor
      val rightCursor = right.cursor
      var same = true
      while same && leftCursor.hasNext do
        same = leftCursor.nextInt() == rightCursor.nextInt()
      same

  def hash(value: Reindexing): Int =
    var hash = 31 + value.codomain
    val it = value.cursor
    while it.hasNext do hash = 31 * hash + it.nextInt()
    hash

def pull[A: ClassTag](
    values: IArray[A],
    reindexing: Reindexing
): Either[CodomainMismatch, IArray[A]] =
  if values.length != reindexing.codomain then
    Left(CodomainMismatch(values.length, reindexing.codomain))
  else
    val result = new Array[A](reindexing.domain)
    val it = reindexing.cursor
    var index = 0
    while it.hasNext do
      result(index) = values(it.nextInt())
      index += 1
    Right(IArray.unsafeFromArray(result))

extension (reindexing: Reindexing)
  def pullFrom[A: ClassTag](
      values: IArray[A]
  ): Either[CodomainMismatch, IArray[A]] =
    pull(values, reindexing)

  @scala.annotation.targetName("pullFromArray")
  def pullFrom[A: ClassTag](
      values: Array[A]
  ): Either[CodomainMismatch, IArray[A]] =
    pull(IArray.unsafeFromArray(values.clone()), reindexing)

  @scala.annotation.targetName("pullFromIndexedSeq")
  def pullFrom[A: ClassTag](
      values: IndexedSeq[A]
  ): Either[CodomainMismatch, IArray[A]] =
    val owned = Array.ofDim[A](values.length)
    var index = 0
    while index < values.length do
      owned(index) = values(index)
      index += 1
    pull(IArray.unsafeFromArray(owned), reindexing)
