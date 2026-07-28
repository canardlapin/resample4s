package resample4s.core

final class PhaseBKernelSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  test("Split.equals distinguishes analysis kinds with the same ordinals") {
    val space = right(IndexSpace.of(3))
    val selection = right(Selection.from(ints(0, 2), space))
    val draw = right(Draw.from(ints(0, 2), space))
    val assessment = right(Selection.from(ints(1), space))
    val asSelection = Split.unsafe(selection, assessment)
    val asDraw = Split.unsafe(draw, assessment)
    assert(!asSelection.equals(asDraw))
    assert(asSelection.sameMapping(asDraw))
  }

  test("PlanCost compares by bounds; Plan has no CanEqual") {
    val left = right(PlanCost.of(1, 2, 3))
    val rightCost = right(PlanCost.of(1, 2, 3))
    val different = right(PlanCost.of(1, 2, 4))
    assertEquals(left, rightCost)
    assert(!left.equals(different))
  }

  test("CompleteOnce.fromAssignments builds a sound exact-once plan") {
    val (complete, plan) = right(SplitPlans.fromAssignments(ints(0, 1, 0, 1)))
    assertEquals(complete.folds, 2)
    assertEquals(right(complete.assessmentFold(0)), 0)
    assertEquals(right(complete.assessmentFold(1)), 1)
    assertEquals(plan.shape.repeats, 1)
    assertEquals(plan.shape.foldsPerRepeat, 2)
    val assessments =
      plan.materialize.map(_._2.assessment.toIArray.toList).toSet
    assertEquals(assessments, Set(List(0, 2), List(1, 3)))
  }

  test("SplitPlans.validate rejects population mismatches") {
    val space = right(IndexSpace.of(2))
    val other = right(IndexSpace.of(3))
    val split = right(
      Split.of(
        right(Selection.from(ints(0), space)),
        right(Selection.from(ints(1), space))
      )
    )
    val bad = Split.unsafe(
      right(Selection.from(ints(0, 1), other)),
      right(Selection.from(ints(2), other))
    )
    val shape = right(PlanShape.of(1, 2))
    assertEquals(
      SplitPlans.validate(2, shape, IArray(split, bad)),
      Left(
        DesignError.FixedUnitPopulationMismatch(UnitKey(0, 1), 2, 3)
      )
    )
  }

  test("DesignError is open and carries stable codes") {
    val custom =
      new DesignError:
        val code = ErrorCodes.tooFewFolds
        val message = "custom too few folds"
    assertEquals(custom.code, ErrorCodes.tooFewFolds)
    assertEquals(
      DesignError.TooFewFolds(1, 2).code,
      ErrorCodes.tooFewFolds
    )
  }

  test("MetricId and custom StreamDomain are extensible") {
    val metric = right(MetricId.fromString("custom-balance"))
    assertEquals(metric.value, "custom-balance")
    val domain = right(StreamDomain.custom(100))
    assertEquals(domain.tag, 100)
    assertEquals(
      StreamDomain.custom(8),
      Left(DesignError.InvalidStreamTag(8))
    )
    val fromTag = right(StreamDomain.fromTag(StreamDomain.StreamTags.stratum))
    assertEquals(fromTag.tag, StreamDomain.Stratum.tag)
  }

  test("FoldLayout maps between UnitKey and UnitId") {
    val layout = right(FoldLayout.of(2, 3))
    assertEquals(right(layout.unit(1, 2)), UnitKey(1, 2))
    assertEquals(right(layout.unitId(UnitKey(1, 2))).toInt, 5)
    assertEquals(right(layout.unitKey(UnitId.unsafe(5))), UnitKey(1, 2))
    assertEquals(
      layout.unit(2, 0),
      Left(UnknownUnit(UnitKey(2, 0), layout.shape))
    )
    assertEquals(
      layout.unitKey(UnitId.unsafe(6)),
      Left(UnknownUnit(UnitKey(6, 0), layout.shape))
    )
  }

  test("same-kind Splits with identical ordinals compare equal") {
    val space = right(IndexSpace.of(4))
    val analysis = right(Selection.from(ints(0, 1, 3), space))
    val assessment = right(Selection.from(ints(2), space))
    val left = Split.unsafe(analysis, assessment)
    val rightSplit = Split.unsafe(
      right(Selection.from(ints(0, 1, 3), space)),
      right(Selection.from(ints(2), space))
    )
    assertEquals(left, rightSplit)
    assertEquals(left.hashCode(), rightSplit.hashCode())
  }

  test("Injection and Selection with the same ordinals are not Split-equal") {
    val space = right(IndexSpace.of(3))
    val selection = right(Selection.from(ints(0, 2), space))
    val injection = right(Injection.from(ints(0, 2), space))
    val assessment = right(Selection.from(ints(1), space))
    assert(
      !Split
        .unsafe(selection, assessment)
        .equals(
          Split.unsafe(injection, assessment)
        )
    )
    assert(
      Split
        .unsafe(selection, assessment)
        .sameMapping(
          Split.unsafe(injection, assessment)
        )
    )
  }
