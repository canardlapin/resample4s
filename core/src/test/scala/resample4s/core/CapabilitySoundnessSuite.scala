package resample4s.core

import scala.compiletime.testing.typeCheckErrors

/** Regression gate for Phase A coverage honesty and Phase B kernel freeze. */
final class CapabilitySoundnessSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private def exactOncePlan: Plan[Split[Selection], Coverage.ExactOnce] =
    right(SplitPlans.fromAssignments(ints(0, 1, 0, 1)))._2

  test("public map forgets ExactOnce at the type boundary") {
    val exact = exactOncePlan
    val errors = typeCheckErrors(
      """import resample4s.core.*
def forged(
  exact: Plan[Split[Selection], Coverage.ExactOnce]
): Plan[Split[Selection], Coverage.ExactOnce] =
  exact.map(identity)
"""
    )
    assert(errors.nonEmpty)
    val message = errors.map(_.message).mkString("\n")
    assert(
      message.contains("Coverage.ExactOnce") || message.contains("Coverage")
    )
  }

  test("public zip forgets Exact and ExactOnce") {
    val shape = right(PlanShape.of(1, 2))
    val left =
      Plan.fromGenerator[Int, Coverage.ExactOnce](shape, _.fold)
    val rightPlan =
      Plan.fromGenerator[String, Coverage.Exact](
        shape,
        key => key.fold.toString
      )
    val zipped: Plan[(Int, String), Coverage] = right(left.zip(rightPlan))
    assertEquals(zipped.first, (0, "0"))
    val errors = typeCheckErrors(
      """import resample4s.core.*
def keepExact(
  left: Plan[Int, Coverage.ExactOnce],
  right: Plan[String, Coverage.Exact]
): Plan[(Int, String), Coverage.Exact] =
  left.zip(right).toOption.get
"""
    )
    assert(errors.nonEmpty)
  }

  test("Plan.sameUnits compares generated values without CanEqual on Plan") {
    val shape = right(PlanShape.of(2, 2))
    val first =
      Plan.fromGenerator[Int, Coverage](
        shape,
        key => key.repeat * 10 + key.fold
      )
    val second =
      Plan.fromGenerator[Int, Coverage](
        shape,
        key => key.repeat * 10 + key.fold
      )
    val third =
      Plan.fromGenerator[Int, Coverage](shape, key => key.fold)
    assert(Plan.sameUnits(first, second))
    assert(!Plan.sameUnits(first, third))
  }

  test("CompletePerRepeat retains fold witnesses across repeats") {
    val (complete, plan) = right(
      SplitPlans.fromRepeatedAssignments(
        IArray(
          ints(0, 1, 0, 1),
          ints(1, 0, 1, 0)
        )
      )
    )
    assertEquals(complete.repeats, 2)
    assertEquals(right(complete.assessmentFold(0, 0)), 0)
    assertEquals(right(complete.assessmentFold(1, 0)), 1)
    assertEquals(
      complete.planOnce,
      Left(DesignError.ExpectedSingleRepeat(2))
    )
    assertEquals(plan.shape.repeats, 2)
    assertEquals(plan.materialize.length, 4)
  }

  test("CompleteOnce.foldOfRow reconstructs the authority") {
    val complete = right(CompleteOnce.fromAssignments(ints(0, 0, 1, 1, 2, 2)))
    assertEquals(complete.foldOfRow.toList, List(0, 0, 1, 1, 2, 2))
    assertEquals(right(complete.assessmentFold(5)), 2)
    assertEquals(complete.assessmentFold(6), Left(OutOfDomain(6, 6)))
  }

  test("SplitPlans.validate accepts a coherent partial plan") {
    val space = right(IndexSpace.of(4))
    val shape = right(PlanShape.of(1, 2))
    val units = IArray(
      right(
        Split.of(
          right(Selection.from(ints(0, 1, 2), space)),
          right(Selection.from(ints(3), space))
        )
      ),
      right(
        Split.of(
          right(Selection.from(ints(0, 1, 3), space)),
          right(Selection.from(ints(2), space))
        )
      )
    )
    val plan = right(SplitPlans.validate(4, shape, units))
    assertEquals(plan.shape, shape)
    assertEquals(
      plan.materialize.map(_._2.assessment.toIArray.toList),
      Vector(List(3), List(2))
    )
  }

  test("custom MetricId and StreamTag round-trip through public factories") {
    assertEquals(
      MetricId.fromString(""),
      Left(DesignError.InvalidMetricId(""))
    )
    assertEquals(
      MetricId.fromString("bad id"),
      Left(DesignError.InvalidMetricId("bad id"))
    )
    val tag = right(StreamDomain.StreamTag.fromString("vendor-block"))
    val first = right(StreamDomain.fromTag(tag))
    val second = right(StreamDomain.fromTag(tag))
    assertEquals(first.tag, second.tag)
    assert(first.tag >= 100)
  }

  test("NestedInnerFailure preserves cause code and message") {
    val cause = DesignError.TooFewFolds(1, 2)
    val nested = DesignError.NestedInnerFailure(UnitKey(0, 1), cause)
    assertEquals(nested.code, ErrorCodes.nestedInnerFailure)
    assert(nested.message.contains(cause.message))
    assertEquals(cause.code, ErrorCodes.tooFewFolds)
  }
