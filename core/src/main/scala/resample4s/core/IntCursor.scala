package resample4s.core

/**
 * Sequential ordinal traversal that does not restart random access.
 *
 * Compact selection backings must implement this with one population pass so
 * ordinary iteration, pull, equality, hashing, and set algebra stay linear in
 * population size plus selection size.
 */
private[resample4s] trait IntCursor:
  def hasNext: Boolean
  def nextInt(): Int

private[resample4s] final class ArrayIntCursor(values: IArray[Int])
    extends IntCursor:
  private var index = 0
  def hasNext: Boolean = index < values.length
  def nextInt(): Int =
    val value = values(index)
    index += 1
    value

/**
 * Counts compact-backing population inspections while a probe is active.
 *
 * Used by complexity guardrails. Production paths call [[observe]] only from
 * compact random-access and cursor implementations. The counter is process-wide
 * and intended for single-threaded tests.
 */
private[resample4s] object CompactTraversalProbe:
  private var depth: Int = 0
  private var inspections: Long = 0L

  def count[A](body: => A): (A, Long) =
    depth += 1
    if depth == 1 then inspections = 0L
    try
      val result = body
      (result, inspections)
    finally depth -= 1

  private[core] def observe(): Unit =
    if depth > 0 then inspections += 1L
