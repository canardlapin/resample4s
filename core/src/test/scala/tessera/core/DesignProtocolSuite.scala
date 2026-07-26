package tessera.core

import tessera.consumer.{PublicExactDesign, PublicGeneralDesign}

final class DesignProtocolSuite extends munit.FunSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def bytes(values: Int*): IArray[Byte] =
    IArray.unsafeFromArray(values.map(_.toByte).toArray)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  private def summary(value: Long): Summary =
    right(Summary.of("tessera/size", value))

  private val test128: DigestAlgorithm =
    new DigestAlgorithm:
      val id: DigestAlgorithmId =
        right(DigestAlgorithmId.of("test128/v1"))

      def digest(
          chunks: Iterator[IArray[Byte]]
      ): Either[DigestError, DigestValue] =
        var first = 0x6a09e667f3bcc909L
        var second = 0xbb67ae8584caa73bL
        while chunks.hasNext do
          val chunk = chunks.next()
          var index = 0
          while index < chunk.length do
            val value = (chunk(index).toInt & 0xff).toLong
            first = Rand.mix64(first ^ value)
            second = Rand.mix64(second + value + 0x9e3779b97f4a7c15L)
            index += 1
        val result = new Array[Byte](16)
        var index = 0
        while index < 8 do
          result(index) = (first >>> (56 - 8 * index)).toByte
          result(index + 8) = (second >>> (56 - 8 * index)).toByte
          index += 1
        DigestValue.fromBytes(IArray.unsafeFromArray(result))

  test("FNV-1a is chunk-boundary invariant and matches a known fixture") {
    val whole = bytes(104, 101, 108, 108, 111)
    val split = Iterator(bytes(104, 101), bytes(108), bytes(108, 111))
    val first = right(DigestAlgorithm.fnv1a64.digest(Iterator(whole)))
    val second = right(DigestAlgorithm.fnv1a64.digest(split))
    assertEquals(first, second)
    assertEquals(
      Vector.tabulate(first.length)(index =>
        first.unsafeAt(index).toInt & 0xff
      ),
      Vector(164, 48, 216, 70, 128, 170, 189, 11)
    )
  }

  test("digest values and descriptor containers own aliased inputs") {
    val digestSource = Array[Byte](1, 2, 3, 4)
    val digest =
      right(DigestValue.fromBytes(IArray.unsafeFromArray(digestSource)))
    digestSource(0) = 9
    assertEquals(digest.unsafeAt(0), 1.toByte)

    val sequenceSource =
      Array[DescriptorValue](
        DescriptorValue.int(1),
        DescriptorValue.int(2)
      )
    val sequence =
      DescriptorValue.sequence(IArray.unsafeFromArray(sequenceSource))
    sequenceSource(0) = DescriptorValue.int(99)

    val fieldSource =
      Array[(String, DescriptorValue)](("values", sequence))
    val descriptor =
      right(
        DesignDescriptor.of(
          right(AlgorithmId.of("alias-test/v1")),
          IArray.unsafeFromArray(fieldSource)
        )
      )
    val before =
      right(
        DigestAlgorithm.fnv1a64.digest(
          CanonicalDesign.designChunks(descriptor, None).iterator
        )
      )
    fieldSource(0) = ("changed", DescriptorValue.int(0))
    val after =
      right(
        DigestAlgorithm.fnv1a64.digest(
          CanonicalDesign.designChunks(descriptor, None).iterator
        )
      )
    assertEquals(before, after)
  }

  test("the public SPI supports general and core-certified exact designs") {
    val space = right(IndexSpace.of(6))
    val seed = Seed.fromLong(17L)

    val general: Compiled[Int, Coverage] =
      right(new PublicGeneralDesign(4).compile(space, seed))
    assertEquals(general.plan.shape, right(PlanShape.of(1, 3)))
    assertEquals(right(general.plan.at(UnitKey(0, 2))), 23)

    val exact: Compiled[Split[Selection], Coverage.Exact] =
      right(new PublicExactDesign().compile(space, seed))
    val assessments =
      exact.plan.iterator.map(_._2.assessment).toVector
    val seen = Array.fill(space.size)(0)
    assessments.foreach { assessment =>
      var index = 0
      while index < assessment.domain do
        seen(assessment.unsafeAt(index)) += 1
        index += 1
    }
    assertEquals(seen.toVector, Vector.fill(space.size)(1))
    exact.plan.iterator.foreach { (_, split) =>
      assertEquals(
        right(split.analysis.intersection(split.assessment)).domain,
        0
      )
    }
  }

  test("the general SPI cannot forge Coverage.Exact") {
    val errors = compileErrors(
      """
import tessera.core.*
val descriptor =
  DesignDescriptor.of(
    AlgorithmId.of("forgery/v1").toOption.get,
    IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
  ).toOption.get
val forged: DesignDefinition[Int, Coverage.Exact] =
  DesignDefinition.general[Int](descriptor, None)(_ =>
    throw new RuntimeException("not evaluated")
  )
"""
    )
    assert(errors.nonEmpty)
    assert(errors.contains("Coverage"))
  }

  test("the ExactOnce SPI rejects a repeated partition specification") {
    val descriptor =
      right(
        DesignDescriptor.of(
          right(AlgorithmId.of("repeated-once/v1")),
          IArray.unsafeFromArray(
            Array.empty[(String, DescriptorValue)]
          )
        )
      )
    val exactDefinition =
      DesignDefinition.exactOncePartitions(descriptor, None) { _ =>
        val assignments = ints(0, 1, 0, 1)
        val partition =
          right(FoldPartition.fromAssignments(4, 2, assignments))
        ExactPartitionSpec.of(
          IArray.unsafeFromArray(Array(partition, partition)),
          PlanDiagnostics.empty
        )
      }
    val design =
      new Design[Split[Selection], Coverage.ExactOnce]:
        val definition
            : DesignDefinition[
              Split[Selection],
              Coverage.ExactOnce
            ] = exactDefinition
    assertEquals(
      design.compile(right(IndexSpace.of(4)), Seed.fromLong(1L)),
      Left(DesignError.ExpectedSingleRepeat(2))
    )
  }

  test("receipts replay and name population, design, and assignment drift") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val space = right(IndexSpace.of(5))
    val population = summary(5)
    val originalDesign = new PublicGeneralDesign(10)
    val compiled = right(originalDesign.compile(space, Seed.fromLong(3L)))
    val receipt = right(compiled.receipt(population))

    assertEquals(receipt.verify(originalDesign, space, population), Right(()))
    assertEquals(
      receipt.verify(originalDesign, space, summary(6)),
      Left(ReceiptError.Mismatch(ReceiptComponent.Population))
    )
    assertEquals(
      receipt.verify(new PublicGeneralDesign(11), space, population),
      Left(ReceiptError.Mismatch(ReceiptComponent.Design))
    )
    assertEquals(
      receipt
        .withSeed(Seed.fromLong(4L))
        .verify(originalDesign, space, population),
      Left(ReceiptError.Mismatch(ReceiptComponent.Assignment))
    )
  }

  test("label drift is diagnosed before its duplicate design commitment") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val space = right(IndexSpace.of(4))
    val firstLabels = right(Labels.dense(ints(0, 1, 0, 1), 4))
    val secondLabels = right(Labels.dense(ints(0, 0, 1, 1), 4))
    val firstDesign = new PublicExactDesign(Some(firstLabels))
    val secondDesign = new PublicExactDesign(Some(secondLabels))
    val receipt =
      right(
        right(firstDesign.compile(space, Seed.fromLong(1L)))
          .receipt(summary(4))
      )
    assertEquals(
      receipt.verify(secondDesign, space, summary(4)),
      Left(ReceiptError.Mismatch(ReceiptComponent.Labels))
    )
  }

  test("seed-independent receipts verify when only the stored seed changes") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val space = right(IndexSpace.of(4))
    val design = new PublicExactDesign()
    val receipt =
      right(
        right(design.compile(space, Seed.fromLong(1L))).receipt(summary(4))
      )
    assertEquals(
      receipt
        .withSeed(Seed.fromLong(999L))
        .verify(design, space, summary(4)),
      Right(())
    )
  }

  test("an open 128-bit provider changes audit bytes, not randomization") {
    val space = right(IndexSpace.of(5))
    val design = new PublicGeneralDesign(2)
    val firstCompiled = right(design.compile(space, Seed.fromLong(8L)))
    val keyBefore = design.randomizationKey.value
    val planBefore = firstCompiled.plan.materialize.map(_._2)

    val fnvReceipt =
      right(
        firstCompiled.receipt(summary(5))(using DigestAlgorithm.fnv1a64)
      )
    val wideReceipt =
      right(firstCompiled.receipt(summary(5))(using test128))
    val secondCompiled = right(design.compile(space, Seed.fromLong(8L)))

    assertEquals(design.randomizationKey.value, keyBefore)
    assertEquals(secondCompiled.plan.materialize.map(_._2), planBefore)
    assertEquals(fnvReceipt.assignment.value.length, 8)
    assertEquals(wideReceipt.assignment.value.length, 16)
    assertNotEquals(
      fnvReceipt.assignment.algorithm.value,
      wideReceipt.assignment.algorithm.value
    )
    assertEquals(
      fnvReceipt.verify(design, space, summary(5))(using test128),
      Left(
        ReceiptError.ProviderMismatch(
          DigestAlgorithm.fnv1a64.id,
          test128.id
        )
      )
    )
  }

  test("semantic assignment encoding ignores selection backings") {
    given DigestAlgorithm = DigestAlgorithm.fnv1a64
    val space = right(IndexSpace.of(4))
    val descriptor =
      right(
        DesignDescriptor.of(
          right(AlgorithmId.of("backing-transparent/v1")),
          IArray.unsafeFromArray(Array.empty[(String, DescriptorValue)])
        )
      )

    def design(useBlock: Boolean): Design[Split[Selection], Coverage] =
      new Design[Split[Selection], Coverage]:
        val definition =
          DesignDefinition.general(descriptor, None) { _ =>
            val partition =
              right(FoldPartition.fromAssignments(4, 2, ints(0, 1, 0, 1)))
            val analysis =
              if useBlock then right(partition.block(0))
              else right(Selection.from(ints(0, 2), space))
            val assessment =
              if useBlock then right(partition.block(1))
              else right(Selection.from(ints(1, 3), space))
            val split = right(Split.of(analysis, assessment))
            for
              shape <- PlanShape.of(1, 1)
              cost <- PlanCost.of(4, 1, 4)
              spec <- GeneralPlanSpec.of(
                shape,
                PlanDiagnostics.empty,
                cost
              )(_ => split, CanonicalAssignmentEncoder.selectionSplit)
            yield spec
          }

    val explicit =
      right(right(design(false).compile(space, Seed.fromLong(0L)))
        .receipt(summary(4)))
    val backed =
      right(right(design(true).compile(space, Seed.fromLong(0L)))
        .receipt(summary(4)))
    assertEquals(explicit.assignment, backed.assignment)
  }

  test("canonical design bytes have a pre-hash compatibility fixture") {
    val descriptor =
      right(
        DesignDescriptor.of(
          right(AlgorithmId.of("byte-fixture/v1")),
          IArray.unsafeFromArray(
            Array[(String, DescriptorValue)](
              ("enabled", DescriptorValue.bool(true)),
              ("size", DescriptorValue.int(7)),
              ("title", right(DescriptorValue.text("tessera λ")))
            )
          )
        )
      )
    val labels = right(Labels.dense(ints(9, 4, 9), 3))
    val observed =
      CanonicalDesign
        .designChunks(descriptor, Some(labels))
        .iterator
        .flatMap(chunk =>
          Vector.tabulate(chunk.length)(index =>
            f"${chunk(index).toInt & 0xff}%02x"
          )
        )
        .mkString
    assertEquals(
      observed,
      "0400000011746573736572612f64657369676e2f763107040000000664657369676e" +
        "040000000f627974652d666978747572652f763106000000030400000007656e6162" +
        "6c65640301040000000473697a65010000000704000000057469746c65040000000a" +
        "7465737365726120cebb030101000000030100000002060000000301000000000100" +
        "0000010100000000"
    )
  }
