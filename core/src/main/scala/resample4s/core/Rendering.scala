package resample4s.core

/** Bounded, non-explosive string forms for REPL and notebook use. */
private[resample4s] object Rendering:
  private val MaxShown = 12

  def indices(values: IArray[Int]): String =
    if values.length <= MaxShown then values.mkString("[", ", ", "]")
    else
      values.iterator
        .take(MaxShown)
        .mkString("[", ", ", s", … +${values.length - MaxShown}]")

  def reindexing(value: Reindexing): String =
    val kind = value.kind match
      case ReindexingKind.Selection => "Selection"
      case ReindexingKind.Draw => "Draw"
      case ReindexingKind.Injection => "Injection"
      case ReindexingKind.Permutation => "Permutation"
    s"$kind(size=${value.domain}, population=${value.codomain}, indices=${indices(value.toIArray)})"

  def fraction(value: Fraction): String =
    s"Fraction(${value.num}/${value.den})"
