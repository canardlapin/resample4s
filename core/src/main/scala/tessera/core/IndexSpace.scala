package tessera.core

opaque type IndexSpace = Int

object IndexSpace:
  def of(size: Int): Either[DesignError, IndexSpace] =
    if size < 0 then Left(DesignError.NegativePopulation(size))
    else Right(size)

  private[tessera] def unsafe(size: Int): IndexSpace = size

  extension (space: IndexSpace) def size: Int = space
