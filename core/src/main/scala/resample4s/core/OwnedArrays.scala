package resample4s.core

import scala.reflect.ClassTag

private[resample4s] object OwnedArrays:
  def copyInt(values: IArray[Int]): IArray[Int] =
    val copied = new Array[Int](values.length)
    var index = 0
    while index < values.length do
      copied(index) = values(index)
      index += 1
    IArray.unsafeFromArray(copied)

  def copyByte(values: IArray[Byte]): IArray[Byte] =
    val copied = new Array[Byte](values.length)
    var index = 0
    while index < values.length do
      copied(index) = values(index)
      index += 1
    IArray.unsafeFromArray(copied)

  def fromVector[A: ClassTag](values: Vector[A]): IArray[A] =
    val copied = new Array[A](values.size)
    var index = 0
    while index < values.size do
      copied(index) = values(index)
      index += 1
    IArray.unsafeFromArray(copied)

  def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)
