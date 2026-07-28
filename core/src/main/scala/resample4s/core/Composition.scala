package resample4s.core

import scala.annotation.implicitNotFound

@implicitNotFound(
  "Cannot preserve the concrete result of composing ${F} after ${G}. " +
    "Keep both values typed as Draw, Injection, Selection, or Permutation."
)
trait Compose[F <: Reindexing, G <: Reindexing]:
  type Out <: Reindexing
  private[resample4s] def compose(left: F, right: G): Out

object Compose:
  type Aux[F <: Reindexing, G <: Reindexing, O <: Reindexing] =
    Compose[F, G] { type Out = O }

  private def values(
      left: Reindexing,
      right: Reindexing
  ): IArray[Int] =
    val leftValues = left.toIArray
    val result = new Array[Int](right.domain)
    val rightCursor = right.cursor
    var index = 0
    while rightCursor.hasNext do
      result(index) = leftValues(rightCursor.nextInt())
      index += 1
    IArray.unsafeFromArray(result)

  private def draw[F <: Reindexing, G <: Reindexing]: Compose.Aux[F, G, Draw] =
    new Compose[F, G]:
      type Out = Draw
      private[resample4s] def compose(left: F, right: G): Draw =
        Draw.fromOwned(values(left, right), left.codomain)

  private def injection[F <: Reindexing, G <: Reindexing]
      : Compose.Aux[F, G, Injection] =
    new Compose[F, G]:
      type Out = Injection
      private[resample4s] def compose(left: F, right: G): Injection =
        Injection.fromOwned(values(left, right), left.codomain)

  private def selection[F <: Reindexing, G <: Reindexing]
      : Compose.Aux[F, G, Selection] =
    new Compose[F, G]:
      type Out = Selection
      private[resample4s] def compose(left: F, right: G): Selection =
        Selection.fromOwned(values(left, right), left.codomain)

  private def permutation[F <: Reindexing, G <: Reindexing]
      : Compose.Aux[F, G, Permutation] =
    new Compose[F, G]:
      type Out = Permutation
      private[resample4s] def compose(left: F, right: G): Permutation =
        Permutation.fromOwned(values(left, right))

  given drawDraw: Compose.Aux[Draw, Draw, Draw] = draw
  given drawInjection: Compose.Aux[Draw, Injection, Draw] = draw
  given drawSelection: Compose.Aux[Draw, Selection, Draw] = draw
  given drawPermutation: Compose.Aux[Draw, Permutation, Draw] = draw

  given injectionDraw: Compose.Aux[Injection, Draw, Draw] = draw
  given injectionInjection: Compose.Aux[Injection, Injection, Injection] =
    injection
  given injectionSelection: Compose.Aux[Injection, Selection, Injection] =
    injection
  given injectionPermutation: Compose.Aux[Injection, Permutation, Injection] =
    injection

  given selectionDraw: Compose.Aux[Selection, Draw, Draw] = draw
  given selectionInjection: Compose.Aux[Selection, Injection, Injection] =
    injection
  given selectionSelection: Compose.Aux[Selection, Selection, Selection] =
    selection
  given selectionPermutation: Compose.Aux[Selection, Permutation, Injection] =
    injection

  given permutationDraw: Compose.Aux[Permutation, Draw, Draw] = draw
  given permutationInjection: Compose.Aux[Permutation, Injection, Injection] =
    injection
  given permutationSelection: Compose.Aux[Permutation, Selection, Injection] =
    injection
  given permutationPermutation
      : Compose.Aux[Permutation, Permutation, Permutation] =
    permutation

extension [F <: Reindexing](left: F)
  def after[G <: Reindexing](
      right: G
  )(using compose: Compose[F, G]): Either[DomainMismatch, compose.Out] =
    if left.domain != right.codomain then
      Left(DomainMismatch(left.domain, right.codomain))
    else Right(compose.compose(left, right))
