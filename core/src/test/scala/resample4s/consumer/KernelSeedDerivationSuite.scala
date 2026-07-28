package resample4s.consumer

import resample4s.kernel.*

final class KernelSeedDerivationSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  test("kernel ring exposes typed replicate-local seed derivation") {
    val root = Seed.fromLong(90210L)
    val path =
      right(StreamPath.of(StreamDomain.Repeat, 17))
        .append(StreamDomain.Unit, 3)
    val child: Seed = root.derive(right(path))
    val algorithm: AlgorithmId = Seed.derivationAlgorithm

    assertEquals(child.value, -3497511708555549203L)
    assertEquals(algorithm.value, "seed-path/v1")
  }
