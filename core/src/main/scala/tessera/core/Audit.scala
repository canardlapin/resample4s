package tessera.core

opaque type DigestAlgorithmId = String

object DigestAlgorithmId:
  def of(value: String): Either[DigestError, DigestAlgorithmId] =
    if Identifiers.schema(value) then Right(value)
    else Left(DigestError.InvalidAlgorithmId(value))

  extension (id: DigestAlgorithmId)
    def value: String = id

  private[tessera] def unsafe(value: String): DigestAlgorithmId = value

  given CanEqual[DigestAlgorithmId, DigestAlgorithmId] = CanEqual.derived

final class DigestValue private (private val bytes: IArray[Byte]):
  def length: Int = bytes.length
  def toIArray: IArray[Byte] = bytes

  private[tessera] def unsafeAt(index: Int): Byte = bytes(index)

  override def equals(other: Any): Boolean =
    other match
      case that: DigestValue =>
        if length != that.length then false
        else
          var index = 0
          var same = true
          while index < length && same do
            same = unsafeAt(index) == that.unsafeAt(index)
            index += 1
          same
      case _ => false

  override def hashCode(): Int =
    var hash = 1
    var index = 0
    while index < length do
      hash = 31 * hash + unsafeAt(index).toInt
      index += 1
    hash

object DigestValue:
  def fromBytes(bytes: IArray[Byte]): Either[DigestError, DigestValue] =
    if bytes.isEmpty then Left(DigestError.EmptyDigestValue)
    else Right(new DigestValue(OwnedArrays.copyByte(bytes)))

  private[tessera] def fromOwned(bytes: IArray[Byte]): DigestValue =
    new DigestValue(bytes)

  given CanEqual[DigestValue, DigestValue] = CanEqual.derived

trait DigestAlgorithm:
  def id: DigestAlgorithmId
  def digest(
      chunks: Iterator[IArray[Byte]]
  ): Either[DigestError, DigestValue]

object DigestAlgorithm:
  /** FNV-1a-64 is a non-adversarial checksum for accidental divergence.
    *
    * It is not collision-resistant, does not make a receipt tamper-evident,
    * and provides no authentication. Consumers needing a cryptographic
    * commitment must supply another deterministic `DigestAlgorithm` and still
    * arrange trusted storage or a signature outside Tessera.
    */
  val fnv1a64: DigestAlgorithm =
    new DigestAlgorithm:
      val id: DigestAlgorithmId =
        DigestAlgorithmId.unsafe("fnv1a64/v1")

      def digest(
          chunks: Iterator[IArray[Byte]]
      ): Either[DigestError, DigestValue] =
        var hash = 0xcbf29ce484222325L
        while chunks.hasNext do
          val chunk = chunks.next()
          var index = 0
          while index < chunk.length do
            hash ^= (chunk(index).toInt & 0xff).toLong
            hash *= 0x100000001b3L
            index += 1
        val bytes = new Array[Byte](8)
        var index = 0
        while index < 8 do
          bytes(index) = (hash >>> (56 - 8 * index)).toByte
          index += 1
        Right(
          DigestValue.fromOwned(IArray.unsafeFromArray(bytes))
        )

sealed trait Fingerprint

final class ContentDigest private (
    val algorithm: DigestAlgorithmId,
    val value: DigestValue
) extends Fingerprint:
  override def equals(other: Any): Boolean =
    other match
      case that: ContentDigest =>
        algorithm == that.algorithm && value == that.value
      case _ => false

  override def hashCode(): Int = 31 * algorithm.hashCode() + value.hashCode()

object ContentDigest:
  def of(
      algorithm: DigestAlgorithmId,
      value: DigestValue
  ): ContentDigest =
    new ContentDigest(algorithm, value)

  given CanEqual[ContentDigest, ContentDigest] = CanEqual.derived

final class SourceIdentity private (
    val uri: String,
    val version: String
) extends Fingerprint:
  override def equals(other: Any): Boolean =
    other match
      case that: SourceIdentity =>
        uri == that.uri && version == that.version
      case _ => false

  override def hashCode(): Int = 31 * uri.hashCode() + version.hashCode()

object SourceIdentity:
  def of(
      uri: String,
      version: String
  ): Either[FingerprintError, SourceIdentity] =
    val valid =
      uri.nonEmpty &&
        version.nonEmpty &&
        Utf8.encode(uri).isRight &&
        Utf8.encode(version).isRight
    if valid then Right(new SourceIdentity(uri, version))
    else Left(FingerprintError.InvalidSourceIdentity(uri, version))

  given CanEqual[SourceIdentity, SourceIdentity] = CanEqual.derived

final class Summary private (
    val policyId: String,
    val value: Long
) extends Fingerprint:
  override def equals(other: Any): Boolean =
    other match
      case that: Summary =>
        policyId == that.policyId && value == that.value
      case _ => false

  override def hashCode(): Int = 31 * policyId.hashCode() + value.hashCode()

object Summary:
  def of(
      policyId: String,
      value: Long
  ): Either[FingerprintError, Summary] =
    val valid =
      policyId.nonEmpty &&
        policyId.length <= 128 &&
        policyId.charAt(0) >= 'a' &&
        policyId.charAt(0) <= 'z' &&
        policyId.forall(character =>
          (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') ||
            character == '-' ||
            character == '_' ||
            character == '.' ||
            character == '/'
        )
    if valid then Right(new Summary(policyId, value))
    else Left(FingerprintError.InvalidPolicyId(policyId))

  given CanEqual[Summary, Summary] = CanEqual.derived

private[tessera] object FingerprintEquality:
  def equal(left: Fingerprint, right: Fingerprint): Boolean =
    (left, right) match
      case (first: ContentDigest, second: ContentDigest) =>
        first == second
      case (first: SourceIdentity, second: SourceIdentity) =>
        first == second
      case (first: Summary, second: Summary) =>
        first == second
      case _ => false
