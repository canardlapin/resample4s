package resample4s.core

final class ApiRingSuite extends munit.FunSuite:
  test("kernel, spi, and audit rings re-export the freeze surface") {
    val space: resample4s.kernel.IndexSpace =
      IndexSpace.of(4) match
        case Right(value) => value
        case Left(error) => fail(error.message)
    val _: resample4s.kernel.Selection =
      Selection.empty(space)
    val _: resample4s.kernel.CompleteOnce =
      right(CompleteOnce.fromAssignments(IArray(0, 1, 0, 1)))
    val _: resample4s.spi.MetricId = Metrics.sizeImbalance
    val _: resample4s.spi.DesignError = DesignError.EmptyPopulation
    val _: resample4s.spi.ErrorCode = ErrorCodes.emptyPopulation
    val _: resample4s.audit.DigestAlgorithmId =
      DigestAlgorithmId.unsafe("fnv1a-64")
  }

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")
