package tessera.core

import scala.collection.mutable.ArrayBuffer

private[tessera] object Utf8:
  def encode(value: String): Either[String, IArray[Byte]] =
    val result = ArrayBuffer.empty[Byte]
    var index = 0
    var error: Option[String] = None
    while index < value.length && error.isEmpty do
      val first = value.charAt(index).toInt
      val (codePoint, consumed) =
        if first >= 0xd800 && first <= 0xdbff then
          if index + 1 >= value.length then
            error = Some("unpaired high surrogate")
            (0, 1)
          else
            val second = value.charAt(index + 1).toInt
            if second < 0xdc00 || second > 0xdfff then
              error = Some("unpaired high surrogate")
              (0, 1)
            else
              (
                0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00),
                2
              )
        else if first >= 0xdc00 && first <= 0xdfff then
          error = Some("unpaired low surrogate")
          (0, 1)
        else (first, 1)

      if error.isEmpty then
        if codePoint <= 0x7f then result += codePoint.toByte
        else if codePoint <= 0x7ff then
          result += (0xc0 | (codePoint >>> 6)).toByte
          result += (0x80 | (codePoint & 0x3f)).toByte
        else if codePoint <= 0xffff then
          result += (0xe0 | (codePoint >>> 12)).toByte
          result += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
          result += (0x80 | (codePoint & 0x3f)).toByte
        else
          result += (0xf0 | (codePoint >>> 18)).toByte
          result += (0x80 | ((codePoint >>> 12) & 0x3f)).toByte
          result += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
          result += (0x80 | (codePoint & 0x3f)).toByte
        index += consumed
    error match
      case Some(reason) => Left(reason)
      case None =>
        Right(IArray.unsafeFromArray(result.toArray))

private[tessera] object Identifiers:
  def schema(value: String): Boolean =
    val marker = value.lastIndexOf("/v")
    marker > 0 &&
    field(value.substring(0, marker).nn) &&
    version(value.substring(marker + 2).nn)

  def field(value: String): Boolean =
    value.nonEmpty &&
    value.length <= 128 &&
    isLower(value.charAt(0)) &&
    value.forall(character =>
      isLower(character) ||
        (character >= '0' && character <= '9') ||
        character == '-' ||
        character == '_' ||
        character == '.'
    )

  private def version(value: String): Boolean =
    value.nonEmpty &&
    value.charAt(0) >= '1' &&
    value.charAt(0) <= '9' &&
    value.forall(_.isDigit)

  private def isLower(value: Char): Boolean =
    value >= 'a' && value <= 'z'

/** Writer for the closed, versioned canonical value grammar.
  *
  * Each operation emits a type tag and a length-framed value. Consumers cannot
  * inject unframed bytes.
  */
final class CanonicalWriter private[tessera] (
    sink: IArray[Byte] => Either[DigestError, Unit]
):
  private var failure: Option[DigestError] = None

  def int(value: Int): Unit =
    rawByte(1)
    rawInt(value)

  def long(value: Long): Unit =
    rawByte(2)
    rawLong(value)

  def bool(value: Boolean): Unit =
    rawByte(3)
    rawByte(if value then 1 else 0)

  def text(value: String): Either[DigestError, Unit] =
    Utf8.encode(value) match
      case Left(reason) =>
        Left(DigestError.InvalidCanonicalText(reason))
      case Right(bytes) =>
        rawByte(4)
        rawInt(bytes.length)
        emit(bytes)
        Right(())

  def fraction(value: Fraction): Unit =
    rawByte(5)
    rawInt(value.num)
    rawInt(value.den)

  def beginSequence(length: Int): Either[DigestError, Unit] =
    if length < 0 then
      Left(DigestError.ProviderFailure(s"negative sequence length: $length"))
    else
      rawByte(6)
      rawInt(length)
      Right(())

  def variant(tag: String): Either[DigestError, Unit] =
    if !Identifiers.field(tag) then
      Left(DigestError.InvalidCanonicalText(s"invalid variant tag: $tag"))
    else
      rawByte(7)
      text(tag)

  /** Internal path for constants or values already validated by a smart
    * constructor. A failure here means a Tessera invariant was broken, never
    * malformed public input.
    */
  private[tessera] def textUnchecked(value: String): Unit =
    text(value) match
      case Right(_) => ()
      case Left(error) =>
        throw new IllegalStateException(s"invalid owned text: $error")

  private[tessera] def beginSequenceUnchecked(length: Int): Unit =
    beginSequence(length) match
      case Right(_) => ()
      case Left(error) =>
        throw new IllegalStateException(s"invalid owned sequence: $error")

  private[tessera] def variantUnchecked(tag: String): Unit =
    variant(tag) match
      case Right(_) => ()
      case Left(error) =>
        throw new IllegalStateException(s"invalid owned variant: $error")

  private[tessera] def error: Option[DigestError] = failure

  private[tessera] def rawByte(value: Int): Unit =
    emit(IArray.unsafeFromArray(Array(value.toByte)))

  private[tessera] def rawInt(value: Int): Unit =
    emit(
      IArray.unsafeFromArray(
        Array(
          (value >>> 24).toByte,
          (value >>> 16).toByte,
          (value >>> 8).toByte,
          value.toByte
        )
      )
    )

  private[tessera] def rawLong(value: Long): Unit =
    emit(
      IArray.unsafeFromArray(
        Array(
          (value >>> 56).toByte,
          (value >>> 48).toByte,
          (value >>> 40).toByte,
          (value >>> 32).toByte,
          (value >>> 24).toByte,
          (value >>> 16).toByte,
          (value >>> 8).toByte,
          value.toByte
        )
      )
    )

  private def emit(value: IArray[Byte]): Unit =
    if failure.isEmpty then
      sink(value) match
        case Left(error) => failure = Some(error)
        case Right(_)    => ()

private[tessera] final class CanonicalBuffer private[tessera] ():
  private val output = ArrayBuffer.empty[IArray[Byte]]
  val writer: CanonicalWriter =
    new CanonicalWriter(chunk =>
      output += chunk
      Right(())
    )
  def chunks: Vector[IArray[Byte]] = output.toVector

private[tessera] object CanonicalWriter:
  def buffered(): CanonicalBuffer = new CanonicalBuffer()

  def streaming(
      sink: IArray[Byte] => Either[DigestError, Unit]
  ): CanonicalWriter =
    new CanonicalWriter(sink)

sealed trait DescriptorValue:
  private[tessera] def write(out: CanonicalWriter): Unit

object DescriptorValue:
  private final class IntValue(value: Int) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit = out.int(value)

  private final class LongValue(value: Long) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit = out.long(value)

  private final class BoolValue(value: Boolean) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit = out.bool(value)

  private final class TextValue(value: String) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit =
      out.textUnchecked(value)

  private final class FractionValue(value: Fraction) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit = out.fraction(value)

  private final class SequenceValue(
      values: IArray[DescriptorValue]
  ) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit =
      out.beginSequenceUnchecked(values.length)
      var index = 0
      while index < values.length do
        values(index).write(out)
        index += 1

  private final class VariantValue(
      tag: String,
      value: DescriptorValue
  ) extends DescriptorValue:
    private[tessera] def write(out: CanonicalWriter): Unit =
      out.variantUnchecked(tag)
      value.write(out)

  def int(value: Int): DescriptorValue = new IntValue(value)
  def long(value: Long): DescriptorValue = new LongValue(value)
  def bool(value: Boolean): DescriptorValue = new BoolValue(value)

  def text(value: String): Either[DesignError, DescriptorValue] =
    Utf8.encode(value) match
      case Left(reason) => Left(DesignError.InvalidText(reason))
      case Right(_)     => Right(new TextValue(value))

  def fraction(value: Fraction): DescriptorValue =
    new FractionValue(value)

  def sequence(values: IArray[DescriptorValue]): DescriptorValue =
    val owned = new Array[DescriptorValue](values.length)
    var index = 0
    while index < values.length do
      owned(index) = values(index)
      index += 1
    new SequenceValue(IArray.unsafeFromArray(owned))

  def variant(
      tag: String,
      value: DescriptorValue
  ): Either[DesignError, DescriptorValue] =
    if !Identifiers.field(tag) then
      Left(DesignError.InvalidIdentifier("variant", tag))
    else Right(new VariantValue(tag, value))

  private[tessera] def variantUnchecked(
      tag: String,
      value: DescriptorValue
  ): DescriptorValue =
    new VariantValue(tag, value)

opaque type AlgorithmId = String

object AlgorithmId:
  def of(value: String): Either[DesignError, AlgorithmId] =
    if Identifiers.schema(value) then Right(value)
    else Left(DesignError.InvalidIdentifier("algorithm", value))

  extension (id: AlgorithmId)
    def value: String = id

  private[tessera] def unsafe(value: String): AlgorithmId = value

  given CanEqual[AlgorithmId, AlgorithmId] = CanEqual.derived

final class DesignDescriptor private (
    val algorithm: AlgorithmId,
    private val sortedFields: Vector[(String, DescriptorValue)]
):
  def fieldCount: Int = sortedFields.length

  def fieldName(index: Int): Either[OutOfDomain, String] =
    if index >= 0 && index < sortedFields.length then
      Right(sortedFields(index)._1)
    else Left(OutOfDomain(index, sortedFields.length))

  private[tessera] def write(out: CanonicalWriter): Unit =
    out.variantUnchecked("design")
    out.textUnchecked(algorithm.value)
    out.beginSequenceUnchecked(sortedFields.length)
    sortedFields.foreach { (name, value) =>
      out.textUnchecked(name)
      value.write(out)
    }

object DesignDescriptor:
  /** Validates a schema id and its fields in one typed construction step. */
  def named(
      algorithm: String,
      fields: (String, DescriptorValue)*
  ): Either[DesignError, DesignDescriptor] =
    AlgorithmId.of(algorithm).flatMap { id =>
      val values = new Array[(String, DescriptorValue)](fields.length)
      var index = 0
      while index < fields.length do
        values(index) = fields(index)
        index += 1
      of(id, IArray.unsafeFromArray(values))
    }

  def of(
      algorithm: AlgorithmId,
      fields: IArray[(String, DescriptorValue)]
  ): Either[DesignError, DesignDescriptor] =
    val owned = Vector.newBuilder[(String, DescriptorValue)]
    val seen = scala.collection.mutable.HashSet.empty[String]
    var index = 0
    var error: Option[DesignError] = None
    while index < fields.length && error.isEmpty do
      val (name, value) = fields(index)
      if !Identifiers.field(name) then
        error = Some(DesignError.InvalidIdentifier("field", name))
      else if seen.contains(name) then
        error = Some(DesignError.DuplicateField(name))
      else
        seen += name
        owned += ((name, value))
      index += 1
    error match
      case Some(value) => Left(value)
      case None =>
        Right(new DesignDescriptor(algorithm, owned.result().sortBy(_._1)))

  private[tessera] def unsafe(
      algorithm: AlgorithmId,
      fields: IArray[(String, DescriptorValue)]
  ): DesignDescriptor =
    of(algorithm, fields) match
      case Right(value) => value
      case Left(error) =>
        throw new IllegalStateException(s"invalid built-in descriptor: $error")

private[tessera] object CanonicalDesign:
  def designChunks(
      descriptor: DesignDescriptor,
      labels: Vector[Labels]
  ): Vector[IArray[Byte]] =
    val buffer = CanonicalWriter.buffered()
    writeDesign(descriptor, labels, buffer.writer)
    buffer.chunks

  private def writeDesign(
      descriptor: DesignDescriptor,
      labels: Vector[Labels],
      out: CanonicalWriter
  ): Unit =
    out.textUnchecked("tessera/design/v1")
    descriptor.write(out)
    out.bool(labels.nonEmpty)
    labels match
      case Vector(single) => writeLabels(single, out)
      case multiple if multiple.nonEmpty =>
        out.variantUnchecked("label-set")
        out.beginSequenceUnchecked(multiple.length)
        multiple.foreach(writeLabels(_, out))
      case _ => ()

  def designChunks(
      descriptor: DesignDescriptor,
      labels: Option[Labels]
  ): Vector[IArray[Byte]] =
    designChunks(descriptor, labels.toVector)

  def labelChunks(labels: Labels): Vector[IArray[Byte]] =
    val buffer = CanonicalWriter.buffered()
    writeLabelSet(Vector(labels), buffer.writer)
    buffer.chunks

  def labelChunks(labels: Vector[Labels]): Vector[IArray[Byte]] =
    labels match
      case Vector(single) => labelChunks(single)
      case multiple =>
        val buffer = CanonicalWriter.buffered()
        writeLabelSet(multiple, buffer.writer)
        buffer.chunks

  private def writeLabelSet(
      labels: Vector[Labels],
      out: CanonicalWriter
  ): Unit =
    labels match
      case Vector(single) =>
        out.textUnchecked("tessera/labels/v1")
        writeLabels(single, out)
      case multiple =>
        out.textUnchecked("tessera/label-set/v1")
        out.beginSequenceUnchecked(multiple.length)
        multiple.foreach(writeLabels(_, out))

  def randomizationKey(
      descriptor: DesignDescriptor,
      labels: Vector[Labels]
  ): DesignKey =
    val digest =
      digestDesign(descriptor, labels, DigestAlgorithm.fnv1a64) match
        case Right(value) => value
        case Left(error) =>
          throw new IllegalStateException(s"built-in FNV failed: $error")
    var value = 0L
    var index = 0
    while index < digest.length do
      value = (value << 8) | (digest.unsafeAt(index).toLong & 0xffL)
      index += 1
    DesignKey.fromLong(value)

  def fingerprint(
      descriptor: DesignDescriptor,
      labels: Vector[Labels]
  )(using algorithm: DigestAlgorithm): Either[DigestError, ContentDigest] =
    digestDesign(descriptor, labels, algorithm)
      .map(value => ContentDigest.of(algorithm.id, value))

  def labelsFingerprint(
      labels: Vector[Labels]
  )(using algorithm: DigestAlgorithm): Either[DigestError, ContentDigest] =
    digestLabels(labels, algorithm)
      .map(value => ContentDigest.of(algorithm.id, value))

  private def digestDesign(
      descriptor: DesignDescriptor,
      labels: Vector[Labels],
      algorithm: DigestAlgorithm
  ): Either[DigestError, DigestValue] =
    digestStreaming(algorithm)(writeDesign(descriptor, labels, _))

  private def digestLabels(
      labels: Vector[Labels],
      algorithm: DigestAlgorithm
  ): Either[DigestError, DigestValue] =
    digestStreaming(algorithm)(writeLabelSet(labels, _))

  private def digestStreaming(
      algorithm: DigestAlgorithm
  )(
      write: CanonicalWriter => Unit
  ): Either[DigestError, DigestValue] =
    algorithm.newAccumulator().flatMap { accumulator =>
      val out = CanonicalWriter.streaming(accumulator.update)
      write(out)
      out.error match
        case Some(error) => Left(error)
        case None        => accumulator.finish()
    }

  private def writeLabels(labels: Labels, out: CanonicalWriter): Unit =
    out.int(labels.size)
    out.int(labels.cardinality)
    out.beginSequenceUnchecked(labels.size)
    var index = 0
    while index < labels.size do
      out.int(labels.unsafeAt(index))
      index += 1
