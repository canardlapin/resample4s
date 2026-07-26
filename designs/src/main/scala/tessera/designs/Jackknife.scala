package tessera.designs

import tessera.core.*

final class DeleteOneJackknife private[designs] ()
    extends Design[Split[Selection], Coverage.ExactOnce]:
  private val descriptor =
    DesignSupport.descriptor("jackknife-delete-one/v1")

  val definition
      : DesignDefinition[Split[Selection], Coverage.ExactOnce] =
    DesignDefinition.exactOncePartitions(descriptor, None) { context =>
      val n = context.space.size
      if n < 2 then Left(DesignError.DegenerateSplit(n, 1))
      else
        ExactPartitionSpec.of(
          IArray.unsafeFromArray(
            Array(FoldPartition.singletonIdentity(n))
          ),
          PlanDiagnostics.empty
        )
    }

final class ExhaustiveDeleteD private[designs] (
    val delete: Int,
    val budget: Long
) extends Design[Split[Selection], Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "jackknife-delete-d-exhaustive/v1",
      "budget" -> DescriptorValue.long(budget),
      "delete" -> DescriptorValue.int(delete)
    )

  val definition: DesignDefinition[Split[Selection], Coverage] =
    DesignDefinition.general(descriptor, None) { context =>
      DeleteDSupport.exhaustive(context, delete, budget)
    }

final class SampledDeleteD private[designs] (
    val delete: Int,
    val times: Int
) extends Design[Split[Selection], Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "jackknife-delete-d-sampled/v1",
      "delete" -> DescriptorValue.int(delete),
      "times" -> DescriptorValue.int(times)
    )

  val definition: DesignDefinition[Split[Selection], Coverage] =
    DesignDefinition.general(descriptor, None) { context =>
      DeleteDSupport.sampled(context, delete, times)
    }

object Jackknife:
  val delete1: DeleteOneJackknife = new DeleteOneJackknife()

  object deleteD:
    def exhaustive(
        delete: Int,
        budget: Long = 10000000L
    ): ExhaustiveDeleteD =
      new ExhaustiveDeleteD(delete, budget)

    def sampled(delete: Int, times: Int): SampledDeleteD =
      new SampledDeleteD(delete, times)

private[designs] object DeleteDSupport:
  def exhaustive(
      context: BuildContext,
      delete: Int,
      budget: Long
  ): Either[DesignError, GeneralPlanSpec[Split[Selection]]] =
    val n = context.space.size
    validateDelete(delete, n).flatMap { _ =>
      val count = Combinations.choose(n, delete)
      val effectiveBudget =
        BigInt(budget).min(BigInt(Int.MaxValue))
      if budget < 0 || count > effectiveBudget then
        Left(DesignError.UnitCountExceeded(count, budget))
      else
        for
          shape <- PlanShape.of(1, count.toInt)
          cost <- PlanCost.of(
            0L,
            workBound(n, delete),
            delete.toLong
          )
          spec <- GeneralPlanSpec.of(
            shape,
            PlanDiagnostics.empty,
            cost
          )(
            key => split(n, delete, BigInt(key.fold)),
            CanonicalAssignmentEncoder.assessmentOnlySplit
          )
        yield spec
    }

  def sampled(
      context: BuildContext,
      delete: Int,
      times: Int
  ): Either[DesignError, GeneralPlanSpec[Split[Selection]]] =
    val n = context.space.size
    validateDelete(delete, n).flatMap { _ =>
      if times < 1 then Left(DesignError.InvalidTimes(times))
      else
        val count = Combinations.choose(n, delete)
        val seeds =
          Array.tabulate(times)(repeat =>
            context.derive(
              DesignSupport.childPath(
                repeat,
                StreamDomain.Unit,
                repeat
              )
            )
          )
        for
          shape <- PlanShape.of(times, 1)
          cost <- PlanCost.of(
            times.toLong,
            workBound(n, delete),
            delete.toLong
          )
          spec <- GeneralPlanSpec.of(
            shape,
            PlanDiagnostics.empty,
            cost
          )(
            key =>
              val rank =
                Rand
                  .fromSeed(seeds(key.repeat))
                  .nextBigIntBoundedUnsafe(count)
                  ._2
              split(n, delete, rank),
            CanonicalAssignmentEncoder.assessmentOnlySplit
          )
        yield spec
    }

  private def validateDelete(
      delete: Int,
      n: Int
  ): Either[DesignError, Unit] =
    if delete < 2 || delete >= n then
      Left(DesignError.InvalidDeleteCount(delete, n))
    else Right(())

  private def split(
      n: Int,
      delete: Int,
      rank: BigInt
  ): Split[Selection] =
    val deleted = Combinations.unrank(n, delete, rank)
    val assessment =
      Selection.fromOwned(deleted, n)
    Split.unsafe(assessment.complement, assessment)

  private def workBound(n: Int, delete: Int): Long =
    val value =
      BigInt(delete) * BigInt(delete) *
        BigInt(math.max(1, BigInt(n).bitLength))
    value.min(BigInt(Long.MaxValue)).toLong

private[designs] object Combinations:
  def choose(n: Int, k: Int): BigInt =
    if k < 0 || k > n then BigInt(0)
    else
      val selected = math.min(k, n - k)
      var result = BigInt(1)
      var index = 1
      while index <= selected do
        result = result * BigInt(n - selected + index) / BigInt(index)
        index += 1
      result

  def unrank(n: Int, k: Int, rank: BigInt): IArray[Int] =
    val result = new Array[Int](k)
    var remainingRank = rank
    var start = 0
    var remaining = k
    var output = 0
    while remaining > 0 do
      val total = choose(n - start, remaining)
      var low = start
      var high = n - remaining
      while low < high do
        val middle = low + (high - low + 1) / 2
        val skipped =
          total - choose(n - middle, remaining)
        if skipped <= remainingRank then low = middle
        else high = middle - 1
      val selected = low
      val skipped =
        total - choose(n - selected, remaining)
      remainingRank -= skipped
      result(output) = selected
      start = selected + 1
      remaining -= 1
      output += 1
    IArray.unsafeFromArray(result)
