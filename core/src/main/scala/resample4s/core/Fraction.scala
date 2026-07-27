package resample4s.core

final class Fraction private (val num: Int, val den: Int):
  def sizeOf(n: Int): Int =
    ((n.toLong * num.toLong + den.toLong / 2L) / den.toLong).toInt

  override def equals(other: Any): Boolean =
    other match
      case that: Fraction => num == that.num && den == that.den
      case _              => false

  override def hashCode(): Int = 31 * num + den

object Fraction:
  def of(num: Int, den: Int): Either[DesignError, Fraction] =
    if num <= 0 || den <= 0 || num >= den then
      Left(DesignError.InvalidFraction(num, den))
    else
      val divisor = gcd(num, den)
      Right(new Fraction(num / divisor, den / divisor))

  private def gcd(left: Int, right: Int): Int =
    var a = left
    var b = right
    while b != 0 do
      val remainder = a % b
      a = b
      b = remainder
    a

  given CanEqual[Fraction, Fraction] = CanEqual.derived
