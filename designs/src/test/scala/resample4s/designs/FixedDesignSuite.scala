package resample4s.designs

import scala.collection.mutable.ArrayBuffer
import scala.compiletime.testing.typeCheckErrors
import resample4s.core.*

final class FixedDesignSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(s"expected Right, obtained $error")

  private def labels(values: Int*): Labels =
    right(Labels.dense(ints(values*)))

  private def selection(
      space: IndexSpace,
      values: Int*
  ): Selection =
    right(Selection.from(ints(values*), space))

  private def split(
      space: IndexSpace,
      analysis: Seq[Int],
      assessment: Seq[Int]
  ): Split[Selection] =
    right(
      Split.of(
        selection(space, analysis*),
        selection(space, assessment*)
      )
    )

  private def values(value: Reindexing): Vector[Int] =
    Vector.tabulate(value.domain)(index => right(value.at(index)))

  private def planValues(
      plan: Plan[Split[Selection], ? <: Coverage]
  ): Vector[(UnitKey, Vector[Int], Vector[Int])] =
    plan.materialize.map { (key, value) =>
      (key, values(value.analysis), values(value.assessment))
    }

  private def summary(size: Int): Summary =
    right(Summary.of("resample4s/size", size.toLong))

  private def hex(bytes: IArray[Byte]): String =
    bytes.iterator
      .map(value => f"${value.toInt & 0xff}%02x")
      .mkString

  private final class RecordingAlgorithm extends DigestAlgorithm:
    private val observed = ArrayBuffer.empty[Byte]
    val id: DigestAlgorithmId =
      right(DigestAlgorithmId.of("recording/v1"))

    def bytes: IArray[Byte] =
      IArray.unsafeFromArray(observed.toArray)

    def newAccumulator(): Either[DigestError, DigestAccumulator] =
      observed.clear()
      Right(
        new DigestAccumulator:
          def update(
              chunk: IArray[Byte]
          ): Either[DigestError, Unit] =
            observed ++= chunk.iterator
            Right(())

          def finish(): Either[DigestError, DigestValue] =
            DigestValue.fromBytes(
              IArray.unsafeFromArray(Array(1.toByte))
            )
      )

  test("fixed splits preserve arbitrary repeat-major units") {
    val space = right(IndexSpace.of(6))
    val first = split(space, Seq(0, 1), Seq(2))
    val second = split(space, Seq(0, 3), Seq(2, 4))
    val shape = right(PlanShape.of(1, 2))
    val design =
      right(
        FixedSplits.of(
          shape,
          IArray.unsafeFromArray(Array(first, second))
        )
      )
    val compiled =
      right(design.compile(space, Seed.fromLong(9L)))

    assertEquals(compiled.plan.shape, shape)
    assertEquals(
      planValues(compiled.plan),
      Vector(
        (UnitKey(0, 0), Vector(0, 1), Vector(2)),
        (UnitKey(0, 1), Vector(0, 3), Vector(2, 4))
      )
    )
    assertEquals(compiled.cost.residentElementsUpperBound, 9L)
    assertEquals(compiled.cost.workPerUnitUpperBound, 1L)
    assertEquals(compiled.cost.receiptWorkPerUnitUpperBound, 4L)
  }

  test("fixed split validation is eager and typed") {
    val six = right(IndexSpace.of(6))
    val five = right(IndexSpace.of(5))
    val first = split(six, Seq(0, 1), Seq(2))
    val otherPopulation = split(five, Seq(0, 1), Seq(2))
    val shape = right(PlanShape.of(1, 2))

    assertEquals(
      FixedSplits.of(
        shape,
        IArray.unsafeFromArray(Array(first))
      ),
      Left(DesignError.FixedUnitCountMismatch(2, 1))
    )
    assertEquals(
      FixedSplits.of(
        shape,
        IArray.unsafeFromArray(Array(first, otherPopulation))
      ),
      Left(
        DesignError.FixedUnitPopulationMismatch(
          UnitKey(0, 1),
          6,
          5
        )
      )
    )
    assertEquals(
      FixedSplits
        .once(first)
        .compile(
          five,
          Seed.fromLong(0L)
        ),
      Left(DesignError.LengthMismatch(5, 6))
    )
  }

  test("fixed constructors copy outer arrays and have no late unit failure") {
    val space = right(IndexSpace.of(6))
    val first = split(space, Seq(0, 1), Seq(2))
    val second = split(space, Seq(0, 3), Seq(2, 4))
    val splitSource = Array(first, second)
    val fixedSplits =
      right(
        FixedSplits.of(
          right(PlanShape.of(1, 2)),
          IArray.unsafeFromArray(splitSource)
        )
      )
    splitSource(0) = second
    val splitPlan =
      right(
        fixedSplits.compile(space, Seed.fromLong(1L))
      ).plan
    assertEquals(splitPlan.first, first)
    assert(splitPlan.keys.forall(splitPlan.at(_).isRight))

    val firstLabels = labels(10, 10, 20, 20, 30, 30)
    val secondLabels = labels(10, 20, 10, 20, 30, 30)
    val labelSource = Array(firstLabels, secondLabels)
    val fixedPartitions =
      right(
        FixedPartitions.repeated(
          IArray.unsafeFromArray(labelSource)
        )
      )
    labelSource(0) = secondLabels
    assertEquals(
      fixedPartitions.definition.labelAt(0),
      Right(firstLabels)
    )
    val partitionPlan =
      right(
        fixedPartitions.compile(space, Seed.fromLong(1L))
      ).plan
    assert(partitionPlan.keys.forall(partitionPlan.at(_).isRight))
  }

  test("fixed partitions mint only their proven exact capabilities") {
    val space = right(IndexSpace.of(6))
    val first = labels(10, 10, 20, 20, 30, 30)
    val second = labels(10, 20, 10, 20, 30, 30)
    val once: Compiled[
      Split[Selection],
      Coverage.ExactOnce
    ] =
      right(
        right(FixedPartitions.once(first))
          .compile(space, Seed.fromLong(2L))
      )
    val repeated: Compiled[
      Split[Selection],
      Coverage.Exact
    ] =
      right(
        right(
          FixedPartitions.repeated(
            IArray.unsafeFromArray(Array(first, second))
          )
        ).compile(space, Seed.fromLong(2L))
      )

    assertEquals(once.plan.shape, right(PlanShape.of(1, 3)))
    assertEquals(repeated.plan.shape, right(PlanShape.of(2, 3)))
    Vector(once.plan, repeated.plan).foreach { plan =>
      plan.iterator.foreach { (_, value) =>
        assertEquals(
          right(
            value.analysis.intersection(value.assessment)
          ).domain,
          0
        )
      }
    }

    val constructive = right(FixedPartitions.completeOnce(first))
    assertEquals(constructive.folds, 3)
    assertEquals(constructive.populationSize, 6)
    assertEquals(right(constructive.assessmentFold(0)), 0)
    assertEquals(right(constructive.assessmentFold(2)), 1)
    assert(Plan.sameUnits(constructive.plan, once.plan))

    val predefined =
      PredefinedSplit.once(split(space, Seq(0, 1, 2), Seq(3, 4, 5)))
    val fromAlias = right(
      predefined.compile(space, Seed.fromLong(9L))
    )
    assertEquals(fromAlias.plan.shape.unitCount, 1)

    val errors = typeCheckErrors(
      """import resample4s.core.*
import resample4s.designs.*
def needsOnce(
  design: Design[Split[Selection], Coverage.ExactOnce]
): Unit = ()
val arbitrary: FixedSplits = ???
val repeated: FixedPartitions[Coverage.Exact] = ???
needsOnce(arbitrary)
needsOnce(repeated)
"""
    )
    assertEquals(errors.length, 2)
    assert(
      errors
        .map(_.message)
        .mkString("\n")
        .contains("Coverage.ExactOnce")
    )
  }

  test("fixed partition validation rejects invalid repeated authorities") {
    val first = labels(0, 0, 1, 1, 2, 2)
    val differentPopulation = labels(0, 0, 1, 1)
    val differentFolds = labels(0, 0, 0, 1, 1, 1)
    val oneFold = labels(0, 0, 0)

    assertEquals(
      FixedPartitions.repeated(
        IArray.unsafeFromArray(Array.empty[Labels])
      ),
      Left(DesignError.InvalidPlanShape(0, 0))
    )
    assertEquals(
      FixedPartitions.repeated(
        IArray.unsafeFromArray(Array(first, differentPopulation))
      ),
      Left(DesignError.PartitionPopulationMismatch(6, 4))
    )
    assertEquals(
      FixedPartitions.repeated(
        IArray.unsafeFromArray(Array(first, differentFolds))
      ),
      Left(DesignError.PartitionFoldMismatch(3, 2))
    )
    assertEquals(
      FixedPartitions.once(oneFold),
      Left(DesignError.InvalidFoldCount(1, 3))
    )
  }

  test("canonical labels make fixed partitions recoding-invariant") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val first = labels(10, 10, 20, 20, 30, 30)
    val recoded = labels(-7, -7, 99, 99, 3, 3)
    val firstDesign = right(FixedPartitions.once(first))
    val recodedDesign = right(FixedPartitions.once(recoded))
    val space = right(IndexSpace.of(6))
    val seed = Seed.fromLong(4L)

    assertEquals(first, recoded)
    assertEquals(
      right(firstDesign.fingerprint),
      right(recodedDesign.fingerprint)
    )
    assertEquals(
      right(firstDesign.labelsFingerprint),
      right(recodedDesign.labelsFingerprint)
    )
    assertEquals(
      planValues(right(firstDesign.compile(space, seed)).plan),
      planValues(right(recodedDesign.compile(space, seed)).plan)
    )
  }

  test("fixed receipts are seed-invariant and diagnose semantic changes") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val space = right(IndexSpace.of(6))
    val population = summary(6)
    val first = split(space, Seq(0, 1), Seq(2))
    val second = split(space, Seq(0, 3), Seq(2, 4))
    val swapped = split(space, Seq(2), Seq(0, 1))
    val horizontal =
      right(
        FixedSplits.of(
          right(PlanShape.of(1, 2)),
          IArray.unsafeFromArray(Array(first, second))
        )
      )
    val reordered =
      right(
        FixedSplits.of(
          right(PlanShape.of(1, 2)),
          IArray.unsafeFromArray(Array(second, first))
        )
      )
    val reshaped =
      right(
        FixedSplits.of(
          right(PlanShape.of(2, 1)),
          IArray.unsafeFromArray(Array(first, second))
        )
      )
    val changedRole =
      right(
        FixedSplits.of(
          right(PlanShape.of(1, 2)),
          IArray.unsafeFromArray(Array(swapped, second))
        )
      )
    val receipt =
      right(
        right(horizontal.compile(space, Seed.fromLong(1L)))
          .receipt(population)
      )
    val otherSeedReceipt =
      right(
        right(horizontal.compile(space, Seed.fromLong(99L)))
          .receipt(population)
      )

    assertEquals(receipt.assignment, otherSeedReceipt.assignment)
    Vector(reordered, reshaped, changedRole).foreach { changed =>
      assertEquals(
        receipt.verify(changed, space, population),
        Left(ReceiptError.Mismatch(ReceiptComponent.Design))
      )
      val changedReceipt =
        right(
          right(changed.compile(space, Seed.fromLong(1L)))
            .receipt(population)
        )
      assertNotEquals(receipt.assignment, changedReceipt.assignment)
    }

    val firstLabels = labels(0, 0, 1, 1, 2, 2)
    val changedLabels = labels(0, 1, 0, 1, 2, 2)
    val firstPartition = right(FixedPartitions.once(firstLabels))
    val changedPartition = right(FixedPartitions.once(changedLabels))
    val partitionReceipt =
      right(
        right(firstPartition.compile(space, Seed.fromLong(1L)))
          .receipt(population)
      )
    val changedPartitionReceipt =
      right(
        right(changedPartition.compile(space, Seed.fromLong(1L)))
          .receipt(population)
      )
    assertEquals(
      partitionReceipt.verify(changedPartition, space, population),
      Left(ReceiptError.Mismatch(ReceiptComponent.Labels))
    )
    assertNotEquals(
      partitionReceipt.assignment,
      changedPartitionReceipt.assignment
    )
  }

  test("fixed design canonical bytes and digests are platform-identical") {
    val space = right(IndexSpace.of(5))
    val fixedSplit =
      FixedSplits.once(
        split(space, Seq(0, 2, 4), Seq(1, 3))
      )
    val fixedPartition =
      right(
        FixedPartitions.once(
          labels(10, 10, 20, 20, 30)
        )
      )

    val splitRecorder = new RecordingAlgorithm()
    right(fixedSplit.fingerprint(using splitRecorder))
    val partitionRecorder = new RecordingAlgorithm()
    right(fixedPartition.fingerprint(using partitionRecorder))

    assertEquals(
      hex(splitRecorder.bytes),
      "0400000014726573616d706c6534732f64657369676e2f763107040000000664657369676e040000000f66697865642d73706c6974732f763106000000040400000005666f6c64730100000001040000000a706f70756c6174696f6e010000000504000000077265706561747301000000010400000005756e697473060000000107040000000f73656c656374696f6e2d73706c6974070400000008616e616c797369730100000005060000000301000000000100000002010000000407040000000a6173736573736d656e7401000000050600000002010000000101000000030300"
    )
    assertEquals(
      hex(partitionRecorder.bytes),
      "0400000014726573616d706c6534732f64657369676e2f763107040000000664657369676e040000001366697865642d706172746974696f6e732f763106000000020400000005666f6c647301000000030400000007726570656174730100000001030101000000050100000003060000000501000000000100000000010000000101000000010100000002"
    )

    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    assertEquals(
      hex(right(fixedSplit.fingerprint).value.toIArray),
      "9b4ca587771ecdce"
    )
    assertEquals(
      hex(right(fixedPartition.fingerprint).value.toIArray),
      "a812d6c58ae7465e"
    )
  }
