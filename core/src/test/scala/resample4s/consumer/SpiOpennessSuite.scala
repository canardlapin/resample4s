package resample4s.consumer

import resample4s.core.*

/** External-package regression: SPI extension points require no private access. */
final class SpiOpennessSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private final class VendorTooSparse(
      val required: Int,
      val observed: Int
  ) extends DesignError:
    val code: ErrorCode = right(ErrorCode.fromString("vendor-too-sparse"))
    val message: String =
      s"vendor strata need at least $required rows, observed $observed"

  test("external DesignError, MetricId, and StreamTag compile outside core") {
    val error: DesignError = VendorTooSparse(5, 2)
    assertEquals(error.code.value, "vendor-too-sparse")
    assert(error.message.contains("observed 2"))

    val metric = right(MetricId.fromString("vendor-balance"))
    val diagnostics = right(
      PlanDiagnostics.of(
        IArray((metric, BigInt(3)), (Metrics.sizeImbalance, BigInt(1)))
      )
    )
    assertEquals(diagnostics.value(metric), Some(BigInt(3)))

    val tag = right(StreamDomain.StreamTag.fromString("vendor-stream"))
    val domain = right(StreamDomain.fromTag(tag))
    assert(domain.tag >= 100)

    val path = right(StreamPath.of(domain, 0))
    val seed = Seed.fromLong(1L)
    val derived = Rand.derive(seed, DesignKey.fromLong(2L), path)
    assert(derived.value != seed.value)
  }

  test("external exact plan construction uses only public SplitPlans") {
    val assignments = IArray.unsafeFromArray(Array(0, 1, 0, 1, 0, 1))
    val (complete, plan) = right(SplitPlans.fromAssignments(assignments))
    val _: Plan[Split[Selection], Coverage.ExactOnce] = plan
    assertEquals(complete.folds, 2)
    assertEquals(plan.shape.unitCount, 2)
  }
