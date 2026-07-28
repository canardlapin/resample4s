package resample4s.core

final class FoldPartition private (
    private val assignments: Option[IArray[Int]],
    private val packedMembers: Option[IArray[IArray[Int]]],
    val populationSize: Int,
    val folds: Int
):
  def assignmentAt(index: Int): Either[OutOfDomain, Int] =
    if index >= 0 && index < populationSize then Right(assignmentUnsafe(index))
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
      case None => index

  private[resample4s] def blockSizeUnsafe(fold: Int): Int =
    packedMembers match
      case Some(values) => values(fold).length
      case None => 1

  private[resample4s] def blockMemberUnsafe(fold: Int, index: Int): Int =
    packedMembers match
      case Some(values) => values(fold)(index)
      case None => fold

  private[resample4s] def blockMembersUnsafe(fold: Int): IArray[Int] =
    packedMembers match
      case Some(values) => values(fold)
      case None => OwnedArrays.ints(fold)

  private[resample4s] def residentElementsUpperBound: Long =
    assignments match
      case Some(_) => 2L * populationSize.toLong
      case None => 0L

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
    else if k < 1 || k > n then Left(DesignError.InvalidFoldCount(k, n))
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
  /** Familiar alias for [[analysis]] (training / fitting rows). */
  def train: A = analysis

  /** Familiar alias for [[assessment]] (test / out-of-fold rows). */
  def test: Selection = assessment

  override def equals(other: Any): Boolean =
    other match
      case that: Split[?] =>
        analysis.kind == that.analysis.kind &&
        ReindexingEquality.equal(analysis, that.analysis) &&
        ReindexingEquality.equal(assessment, that.assessment)
      case _ => false

  /** Ordinal equality of analysis and assessment, ignoring analysis kind. */
  def sameMapping(that: Split[?]): Boolean =
    ReindexingEquality.equal(analysis, that.analysis) &&
      ReindexingEquality.equal(assessment, that.assessment)

  override def hashCode(): Int =
    31 * (31 * analysis.kind.ordinal + analysis.hashCode()) +
      assessment.hashCode()

  override def toString: String =
    s"Split(train=${Rendering.reindexing(analysis)}, test=${Rendering.reindexing(assessment)})"

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
      val supportCursor = support.cursor
      val assessmentCursor = assessment.cursor
      var supportHas = supportCursor.hasNext
      var assessmentHas = assessmentCursor.hasNext
      var leftValue = if supportHas then supportCursor.nextInt() else 0
      var rightValue = if assessmentHas then assessmentCursor.nextInt() else 0
      var overlap: Option[Int] = None
      while supportHas && assessmentHas && overlap.isEmpty do
        if leftValue == rightValue then overlap = Some(leftValue)
        else if leftValue < rightValue then
          if supportCursor.hasNext then leftValue = supportCursor.nextInt()
          else supportHas = false
        else if assessmentCursor.hasNext then
          rightValue = assessmentCursor.nextInt()
        else assessmentHas = false
      overlap match
        case Some(value) => Left(DesignError.OverlappingRoles(value))
        case None => Right(new Split(analysis, assessment))

  private[resample4s] def unsafe[A <: Reindexing](
      analysis: A,
      assessment: Selection
  ): Split[A] =
    new Split(analysis, assessment)

  given [A <: Reindexing]: CanEqual[Split[A], Split[A]] = CanEqual.derived
