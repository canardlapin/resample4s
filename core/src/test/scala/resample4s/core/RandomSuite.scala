package resample4s.core

final class RandomSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private def vector(values: IArray[Int]): Vector[Int] =
    Vector.tabulate(values.length)(values(_))

  test("SplitMix64 matches its platform-stable golden words") {
    var rand = Rand.fromSeed(Seed.fromLong(0L))
    val observed = Vector.newBuilder[Long]
    var index = 0
    while index < 5 do
      val (next, word) = rand.nextLong
      rand = next
      observed += word
      index += 1
    assertEquals(
      observed.result(),
      Vector(
        -2152535657050944081L, 7960286522194355700L, 487617019471545679L,
        -537132696929009172L, 1961750202426094747L
      )
    )
  }

  test("bounded draws are total, in range, and deterministic") {
    var left = Rand.fromSeed(Seed.fromLong(Long.MinValue))
    var rightRand = Rand.fromSeed(Seed.fromLong(Long.MinValue))
    var index = 0
    while index < 10000 do
      val (leftNext, leftValue) = right(left.nextIntBounded(37))
      val (rightNext, rightValue) = right(rightRand.nextIntBounded(37))
      assert(leftValue >= 0)
      assert(leftValue < 37)
      assertEquals(leftValue, rightValue)
      left = leftNext
      rightRand = rightNext
      index += 1

    assertEquals(
      Rand.fromSeed(Seed.fromLong(1L)).nextIntBounded(0),
      Left(DesignError.InvalidBound(BigInt(0)))
    )
  }

  test("bounded Int draws match the BigInt rejection oracle") {
    def oracle(
        random: Rand,
        bound: Int
    ): (Rand, Int) =
      val bigBound = BigInt(bound)
      val threshold = (BigInt(1) << 64) % bigBound
      var current = random
      var result: Option[Int] = None
      while result.isEmpty do
        val (next, word) = current.nextLong
        current = next
        val unsigned = Rand.unsigned(word)
        if unsigned >= threshold then result = Some((unsigned % bigBound).toInt)
      (current, result.getOrElse(fail("oracle must accept a draw")))

    val bounds =
      Vector(1, 2, 3, 7, 37, 65536, 100000, Int.MaxValue)
    val seeds =
      Vector(0L, 1L, -1L, 42L, Long.MinValue, Long.MaxValue)
    bounds.foreach { bound =>
      seeds.foreach { seed =>
        var optimized = Rand.fromSeed(Seed.fromLong(seed))
        var reference = Rand.fromSeed(Seed.fromLong(seed))
        var index = 0
        while index < 100 do
          val (optimizedNext, optimizedValue) =
            right(optimized.nextIntBounded(bound))
          val (referenceNext, referenceValue) =
            oracle(reference, bound)
          assertEquals(optimizedValue, referenceValue)
          optimized = optimizedNext
          reference = referenceNext
          index += 1
      }
    }
  }

  test("BigInt bounded draws support bounds wider than one word") {
    val upper = (BigInt(1) << 130) + BigInt(12345)
    var rand = Rand.fromSeed(Seed.fromLong(42L))
    var index = 0
    while index < 1000 do
      val (next, value) = right(rand.nextBigIntBounded(upper))
      assert(value >= 0)
      assert(value < upper)
      rand = next
      index += 1

    val unchanged = Rand.fromSeed(Seed.fromLong(99L))
    val (same, zero) = right(unchanged.nextBigIntBounded(BigInt(1)))
    assert(same eq unchanged)
    assertEquals(zero, BigInt(0))
  }

  test("Fisher-Yates has a pinned order and preserves its input") {
    val source = Array.tabulate(10)(identity)
    val rand = Rand.fromSeed(Seed.fromLong(123456789L))
    val (_, shuffled) = rand.shuffle(IArray.unsafeFromArray(source))
    assertEquals(source.toVector, Vector.range(0, 10))
    assertEquals(
      vector(shuffled),
      Vector(8, 3, 9, 2, 4, 6, 1, 5, 0, 7)
    )
    assertEquals(vector(shuffled).sorted, Vector.range(0, 10))
  }

  test("stream derivation is domain- and order-separated") {
    val seed = Seed.fromLong(90210L)
    val key = DesignKey.fromLong(0x1020304050607080L)
    val repeatUnit =
      right(StreamPath.of(StreamDomain.Repeat, 1))
        .append(StreamDomain.Unit, 2)
    val unitRepeat =
      right(StreamPath.of(StreamDomain.Unit, 2))
        .append(StreamDomain.Repeat, 1)
    val changedDomain =
      right(StreamPath.of(StreamDomain.Stratum, 1))
        .append(StreamDomain.Unit, 2)
    val outerUnit =
      right(StreamPath.of(StreamDomain.OuterUnit, 1))
        .append(StreamDomain.Unit, 2)

    val first = Rand.derive(seed, key, right(repeatUnit))
    assertEquals(first, Rand.derive(seed, key, right(repeatUnit)))
    assertNotEquals(first, Rand.derive(seed, key, right(unitRepeat)))
    assertNotEquals(first, Rand.derive(seed, key, right(changedDomain)))
    assertNotEquals(first, Rand.derive(seed, key, right(outerUnit)))
    assertEquals(first.value, 1064180939181588761L)
  }

  test("stream paths reject negative ordinals") {
    assertEquals(
      StreamPath.of(StreamDomain.Unit, -1),
      Left(DesignError.InvalidStreamOrdinal(-1))
    )
  }
