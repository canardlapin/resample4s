package resample4s.core

opaque type Seed = Long

object Seed:
  def fromLong(value: Long): Seed = value

  extension (seed: Seed)
    def value: Long = seed

opaque type DesignKey = Long

object DesignKey:
  private[resample4s] def fromLong(value: Long): DesignKey = value

  extension (key: DesignKey)
    def value: Long = key

enum StreamDomain(val tag: Int):
  case Repeat extends StreamDomain(1)
  case Unit extends StreamDomain(2)
  case Stratum extends StreamDomain(3)
  case GroupSizeBucket extends StreamDomain(4)
  case FoldPriority extends StreamDomain(5)
  case ExchangeabilityBlock extends StreamDomain(6)
  case RedrawAttempt extends StreamDomain(7)

final case class StreamSegment private[resample4s] (
    domain: StreamDomain,
    ordinal: Int
) derives CanEqual

final class StreamPath private (
    private val segments: Vector[StreamSegment]
):
  def length: Int = segments.length

  def at(index: Int): Either[OutOfDomain, StreamSegment] =
    if index >= 0 && index < segments.length then Right(segments(index))
    else Left(OutOfDomain(index, segments.length))

  def append(
      domain: StreamDomain,
      ordinal: Int
  ): Either[DesignError, StreamPath] =
    if ordinal < 0 then Left(DesignError.InvalidStreamOrdinal(ordinal))
    else Right(new StreamPath(segments :+ StreamSegment(domain, ordinal)))

  private[resample4s] def unsafeAt(index: Int): StreamSegment = segments(index)

  private[resample4s] def appendUnchecked(
      domain: StreamDomain,
      ordinal: Int
  ): StreamPath =
    new StreamPath(segments :+ StreamSegment(domain, ordinal))

  override def equals(other: Any): Boolean =
    other match
      case that: StreamPath => segments == that.segments
      case _                => false

  override def hashCode(): Int = segments.hashCode()

object StreamPath:
  def of(
      domain: StreamDomain,
      ordinal: Int
  ): Either[DesignError, StreamPath] =
    if ordinal < 0 then Left(DesignError.InvalidStreamOrdinal(ordinal))
    else Right(new StreamPath(Vector(StreamSegment(domain, ordinal))))

  private[resample4s] def unsafe(
      domain: StreamDomain,
      ordinal: Int
  ): StreamPath =
    new StreamPath(Vector(StreamSegment(domain, ordinal)))

  given CanEqual[StreamPath, StreamPath] = CanEqual.derived

/** Pure, platform-stable SplitMix64 state.
  *
  * Bounded draws use unsigned rejection sampling. No floating-point arithmetic
  * participates in generation.
  */
final class Rand private (private val state: Long):
  def nextLong: (Rand, Long) =
    val nextState = state + Rand.Gamma
    (new Rand(nextState), Rand.mix64(nextState))

  def nextIntBounded(
      upperExclusive: Int
  ): Either[DesignError, (Rand, Int)] =
    if upperExclusive <= 0 then
      Left(DesignError.InvalidBound(BigInt(upperExclusive)))
    else Right(nextIntBoundedUnsafe(upperExclusive))

  def nextBigIntBounded(
      upperExclusive: BigInt
  ): Either[DesignError, (Rand, BigInt)] =
    if upperExclusive <= 0 then
      Left(DesignError.InvalidBound(upperExclusive))
    else Right(nextBigIntBoundedUnsafe(upperExclusive))

  def shuffle(
      values: IArray[Int]
  ): (Rand, IArray[Int]) =
    val shuffled = new Array[Int](values.length)
    var index = 0
    while index < values.length do
      shuffled(index) = values(index)
      index += 1
    val next = shuffleOwnedPrefix(shuffled, stopAt = 1)
    (next, IArray.unsafeFromArray(shuffled))

  def shuffleIndices(
      size: Int
  ): Either[DesignError, (Rand, IArray[Int])] =
    if size < 0 then Left(DesignError.NegativePopulation(size))
    else
      val values = new Array[Int](size)
      var index = 0
      while index < size do
        values(index) = index
        index += 1
      Right(shuffle(IArray.unsafeFromArray(values)))

  /** Initializes an owned identity array and fixes its prefix membership.
    *
    * The caller must provide `0 <= prefixSize <= size`. The returned array is
    * newly allocated and may be transferred to an immutable owner.
    */
  private[resample4s] def shufflePrefixIndicesUnsafe(
      size: Int,
      prefixSize: Int
  ): IArray[Int] =
    val values = new Array[Int](size)
    var index = 0
    while index < size do
      values(index) = index
      index += 1
    shuffleOwnedPrefix(values, stopAt = prefixSize)
    IArray.unsafeFromArray(values)

  /** Mutates an owned identity/value array with the Fisher-Yates steps needed
    * to fix the set in `[0, stopAt)`.
    *
    * Keeping the SplitMix state in a primitive local avoids one `Rand` and two
    * boxed tuple values per swap. The returned state is exactly the state that
    * repeated `nextIntBoundedUnsafe` calls would produce.
    */
  private def shuffleOwnedPrefix(
      values: Array[Int],
      stopAt: Int
  ): Rand =
    var currentState = state
    var drew = false
    var index = values.length - 1
    while index >= stopAt do
      val bound = index.toLong + 1L
      val threshold =
        java.lang.Long.remainderUnsigned(-bound, bound)
      var accepted = false
      var selected = 0
      while !accepted do
        currentState += Rand.Gamma
        val word = Rand.mix64(currentState)
        if java.lang.Long.compareUnsigned(word, threshold) >= 0 then
          selected =
            java.lang.Long.remainderUnsigned(word, bound).toInt
          accepted = true
      val held = values(index)
      values(index) = values(selected)
      values(selected) = held
      drew = true
      index -= 1
    if drew then new Rand(currentState) else this

  private[resample4s] def nextIntBoundedUnsafe(
      upperExclusive: Int
  ): (Rand, Int) =
    val bound = upperExclusive.toLong
    val threshold =
      java.lang.Long.remainderUnsigned(-bound, bound)
    var current = this
    var accepted = false
    var result = 0
    while !accepted do
      val (next, word) = current.nextLong
      current = next
      if java.lang.Long.compareUnsigned(word, threshold) >= 0 then
        result = java.lang.Long.remainderUnsigned(word, bound).toInt
        accepted = true
    (current, result)

  private[resample4s] def nextBigIntBoundedUnsafe(
      upperExclusive: BigInt
  ): (Rand, BigInt) =
    val bits = (upperExclusive - 1).bitLength
    if bits == 0 then (this, BigInt(0))
    else
      val words = (bits + 63) / 64
      val mask = (BigInt(1) << bits) - 1
      var current = this
      var accepted = false
      var result = BigInt(0)
      while !accepted do
        var candidate = BigInt(0)
        var index = 0
        while index < words do
          val (next, word) = current.nextLong
          current = next
          candidate = (candidate << 64) | Rand.unsigned(word)
          index += 1
        candidate &= mask
        if candidate < upperExclusive then
          result = candidate
          accepted = true
      (current, result)

object Rand:
  private val Gamma = 0x9e3779b97f4a7c15L
  private val Mix1 = 0xbf58476d1ce4e5b9L
  private val Mix2 = 0x94d049bb133111ebL
  private val PathFrame = 0x632be59bd9b4e019L
  private val DomainFrame = 0x8cb92ba72f3d8dd7L
  private val OrdinalFrame = 0x9e3779b185ebca87L
  def fromSeed(seed: Seed): Rand = new Rand(seed.value)

  def derive(
      seed: Seed,
      designKey: DesignKey,
      path: StreamPath
  ): Seed =
    var mixed = mix64(seed.value ^ designKey.value)
    mixed = mix64(mixed + PathFrame + path.length.toLong)
    var index = 0
    while index < path.length do
      val segment = path.unsafeAt(index)
      mixed = mix64(mixed + DomainFrame + segment.domain.tag.toLong)
      mixed = mix64(mixed + OrdinalFrame + segment.ordinal.toLong)
      index += 1
    Seed.fromLong(mixed)

  private[resample4s] def mix64(value: Long): Long =
    var mixed = value
    mixed = (mixed ^ (mixed >>> 30)) * Mix1
    mixed = (mixed ^ (mixed >>> 27)) * Mix2
    mixed ^ (mixed >>> 31)

  private[resample4s] def unsigned(value: Long): BigInt =
    if value >= 0L then BigInt(value)
    else BigInt(value & Long.MaxValue) + (BigInt(1) << 63)

  given CanEqual[Rand, Rand] = CanEqual.derived
