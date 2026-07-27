package resample4s.core

final class FoldPartition private (
    private val assignments: Option[IArray[Int]],
    private val packedMembers: Option[IArray[IArray[Int]]],
    val populationSize: Int,
    val folds: Int
):
  def assignmentAt(index: Int): Either[OutOfDomain, Int] =
    if index >= 0 && index < populationSize then
      Right(assignmentUnsafe(index))
    else Left(OutOfDomain(index, populationSize))

  def block(fold: Int): Either[UnknownFold, Selection] =
    if fold >= 0 && fold < folds then Right(Selection.block(this, fold))
    else Left(UnknownFold(fold, folds))

  def complementBlock(fold: Int): Either[UnknownFold, Selection] =
    if fold >= 0 && fold < folds then
      Right(Selection.complementBlock(this, fold))
    else Left(UnknownFold(fold, folds))

  private[resample4s] def assignmentUnsafe(index: Int): Int =
    assignments match
      case Some(values) => values(index)
      case None         => index

  private[resample4s] def blockSizeUnsafe(fold: Int): Int =
    packedMembers match
      case Some(values) => values(fold).length
      case None         => 1

  private[resample4s] def blockMemberUnsafe(fold: Int, index: Int): Int =
    packedMembers match
      case Some(values) => values(fold)(index)
      case None         => fold

  private[resample4s] def blockMembersUnsafe(fold: Int): IArray[Int] =
    packedMembers match
      case Some(values) => values(fold)
      case None         => OwnedArrays.ints(fold)

  private[resample4s] def residentElementsUpperBound: Long =
    assignments match
      case Some(_) => 2L * populationSize.toLong
      case None    => 0L

  override def equals(other: Any): Boolean =
    other match
      case that: FoldPartition =>
        if populationSize != that.populationSize || folds != that.folds then
          false
        else
          var index = 0
          var same = true
          while index < populationSize && same do
            same = assignmentUnsafe(index) == that.assignmentUnsafe(index)
            index += 1
          same
      case _ => false

  override def hashCode(): Int =
    var hash = 31 + folds
    var index = 0
    while index < populationSize do
      hash = 31 * hash + assignmentUnsafe(index)
      index += 1
    hash

object FoldPartition:
  def fromAssignments(
      n: Int,
      k: Int,
      assign: IArray[Int]
  ): Either[DesignError, FoldPartition] =
    if n < 1 then Left(DesignError.EmptyPopulation)
    else if k < 1 || k > n then
      Left(DesignError.InvalidFoldCount(k, n))
    else if assign.length != n then
      Left(DesignError.LengthMismatch(n, assign.length))
    else
      val counts = Array.fill(k)(0)
      var index = 0
      var error: Option[DesignError] = None
      while index < n && error.isEmpty do
        val fold = assign(index)
        if fold < 0 || fold >= k then
          error = Some(DesignError.InvalidFoldAssignment(index, fold, k))
        else counts(fold) += 1
        index += 1
      error match
        case Some(value) => Left(value)
        case None =>
          var fold = 0
          var empty: Option[Int] = None
          while fold < k && empty.isEmpty do
            if counts(fold) == 0 then empty = Some(fold)
            fold += 1
          empty match
            case Some(value) => Left(DesignError.EmptyFold(value))
            case None =>
              val members = Array.tabulate(k)(f => new Array[Int](counts(f)))
              val offsets = Array.fill(k)(0)
              index = 0
              while index < n do
                fold = assign(index)
                members(fold)(offsets(fold)) = index
                offsets(fold) += 1
                index += 1
              val packed = new Array[IArray[Int]](k)
              fold = 0
              while fold < k do
                packed(fold) = IArray.unsafeFromArray(members(fold))
                fold += 1
              Right(
                new FoldPartition(
                  Some(OwnedArrays.copyInt(assign)),
                  Some(IArray.unsafeFromArray(packed)),
                  n,
                  k
                )
              )

  private[resample4s] def singletonIdentity(n: Int): FoldPartition =
    new FoldPartition(None, None, n, n)

  given CanEqual[FoldPartition, FoldPartition] = CanEqual.derived

final class Split[+A <: Reindexing] private (
    val analysis: A,
    val assessment: Selection
):
  override def equals(other: Any): Boolean =
    other match
      case that: Split[?] =>
        ReindexingEquality.equal(analysis, that.analysis) &&
          ReindexingEquality.equal(assessment, that.assessment)
      case _ => false

  override def hashCode(): Int = 31 * analysis.hashCode() + assessment.hashCode()

object Split:
  def of[A <: Reindexing](
      analysis: A,
      assessment: Selection
  ): Either[DesignError | CodomainMismatch, Split[A]] =
    if analysis.codomain != assessment.codomain then
      Left(CodomainMismatch(analysis.codomain, assessment.codomain))
    else if analysis.domain == 0 then Left(DesignError.EmptyAnalysis)
    else
      val support = analysis.support
      var left = 0
      var right = 0
      var overlap: Option[Int] = None
      while left < support.domain && right < assessment.domain &&
          overlap.isEmpty
      do
        val leftValue = support.unsafeAt(left)
        val rightValue = assessment.unsafeAt(right)
        if leftValue == rightValue then overlap = Some(leftValue)
        else if leftValue < rightValue then left += 1
        else right += 1
      overlap match
        case Some(value) => Left(DesignError.OverlappingRoles(value))
        case None        => Right(new Split(analysis, assessment))

  private[resample4s] def unsafe[A <: Reindexing](
      analysis: A,
      assessment: Selection
  ): Split[A] =
    new Split(analysis, assessment)

  given [A <: Reindexing]: CanEqual[Split[A], Split[A]] = CanEqual.derived
