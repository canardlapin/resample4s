package tessera.designs

import tessera.core.*

/** Empty out-of-bag handling.
  *
  * `Redraw` conditions the bootstrap distribution on non-empty OOB and thus
  * introduces bias, particularly for small populations. `Allow` preserves the
  * unconditional distribution.
  */
enum OobPolicy derives CanEqual:
  case Allow
  case Redraw(maxAttempts: Int)
  case Fail

final class Bootstrap private[designs] (
    val times: Int,
    val policy: OobPolicy
) extends Design[Split[Draw], Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "bootstrap/v1",
      "policy" -> BootstrapSupport.policyValue(policy),
      "times" -> DescriptorValue.int(times)
    )

  val definition: DesignDefinition[Split[Draw], Coverage] =
    DesignDefinition.general(descriptor) { context =>
      BootstrapSupport.ordinary(context, times, policy)
    }

/** Whole-group bootstrap with exactly one draw per canonical group.
  *
  * Emitted row length is variable when group sizes differ.
  */
final class GroupedBootstrap private[designs] (
    val times: Int,
    val groups: Labels,
    val policy: OobPolicy
) extends Design[Split[Draw], Coverage]:
  private val descriptor =
    DesignSupport.descriptor(
      "bootstrap-grouped/v1",
      "policy" -> BootstrapSupport.policyValue(policy),
      "times" -> DescriptorValue.int(times)
    )

  val definition: DesignDefinition[Split[Draw], Coverage] =
    DesignDefinition.general(descriptor, Some(groups)) { context =>
      BootstrapSupport.grouped(context, times, groups, policy)
    }

object Bootstrap:
  /** General constructor with an explicit empty-OOB policy. */
  def apply(
      times: Int,
      policy: OobPolicy
  ): Bootstrap =
    new Bootstrap(times, policy)

  /** Unconditional bootstrap; an empty OOB assessment is permitted. */
  def unconditional(times: Int): Bootstrap =
    apply(times, OobPolicy.Allow)

  /** Bootstrap conditioned on finding a non-empty OOB assessment. */
  def redrawing(
      times: Int,
      maxAttempts: Int = 8
  ): Bootstrap =
    apply(times, OobPolicy.Redraw(maxAttempts))

  /** Bootstrap that fails compilation when a draw has empty OOB. */
  def failOnEmptyOob(times: Int): Bootstrap =
    apply(times, OobPolicy.Fail)

  /** General whole-group constructor with an explicit empty-OOB policy. */
  def grouped(
      times: Int,
      groups: Labels,
      policy: OobPolicy
  ): GroupedBootstrap =
    GroupedBootstrap(times, groups, policy)

object GroupedBootstrap:
  /** General constructor with an explicit empty-OOB policy. */
  def apply(
      times: Int,
      groups: Labels,
      policy: OobPolicy
  ): GroupedBootstrap =
    new GroupedBootstrap(times, groups, policy)

  /** Unconditional whole-group bootstrap; empty OOB is permitted. */
  def unconditional(times: Int, groups: Labels): GroupedBootstrap =
    apply(times, groups, OobPolicy.Allow)

  /** Whole-group bootstrap conditioned on finding non-empty OOB. */
  def redrawing(
      times: Int,
      groups: Labels,
      maxAttempts: Int = 8
  ): GroupedBootstrap =
    apply(times, groups, OobPolicy.Redraw(maxAttempts))

  /** Whole-group bootstrap that fails compilation on empty OOB. */
  def failOnEmptyOob(times: Int, groups: Labels): GroupedBootstrap =
    apply(times, groups, OobPolicy.Fail)

private[designs] trait BootstrapWorkObserver:
  def candidate(unit: UnitKey): Unit
  def preflightGroupId(unit: UnitKey): Unit
  def emittedRow(unit: UnitKey): Unit

private[designs] object BootstrapWorkObserver:
  val noop: BootstrapWorkObserver =
    new BootstrapWorkObserver:
      def candidate(unit: UnitKey): Unit = ()
      def preflightGroupId(unit: UnitKey): Unit = ()
      def emittedRow(unit: UnitKey): Unit = ()

private[designs] object BootstrapSupport:
  def policyValue(policy: OobPolicy): DescriptorValue =
    policy match
      case OobPolicy.Allow =>
        DescriptorValue.variantUnchecked(
          "allow",
          DescriptorValue.bool(true)
        )
      case OobPolicy.Fail =>
        DescriptorValue.variantUnchecked(
          "fail",
          DescriptorValue.bool(true)
        )
      case OobPolicy.Redraw(attempts) =>
        DescriptorValue.variantUnchecked(
          "redraw",
          DescriptorValue.int(attempts)
        )

  def ordinary(
      context: BuildContext,
      times: Int,
      policy: OobPolicy,
      observer: BootstrapWorkObserver = BootstrapWorkObserver.noop
  ): Either[DesignError, GeneralPlanSpec[Split[Draw]]] =
    val n = context.space.size
    validatePolicy(times, policy).flatMap { maxAttempts =>
      acceptedSeeds(
        context,
        times,
        policy,
        maxAttempts,
        observer,
        (seed, _) => ordinarySupportSize(n, seed) < n
      ).flatMap { seeds =>
        for
          shape <- PlanShape.of(times, 1)
          cost <- PlanCost.of(
            times.toLong,
            n.toLong,
            n.toLong
          )
        yield GeneralPlanSpec(
          shape,
          PlanDiagnostics.empty,
          cost
        )(
          key => ordinarySplit(n, seeds(key.repeat)),
          CanonicalAssignmentEncoder.drawSplit
        )
      }
    }

  def grouped(
      context: BuildContext,
      times: Int,
      groups: Labels,
      policy: OobPolicy,
      observer: BootstrapWorkObserver = BootstrapWorkObserver.noop
  ): Either[DesignError, GeneralPlanSpec[Split[Draw]]] =
    val members = DesignSupport.labelMembers(groups)
    val groupCount = groups.cardinality
    var maximum = 0
    var group = 0
    while group < groupCount do
      if members(group).length > maximum then
        maximum = members(group).length
      group += 1
    validatePotentialDrawSize(groupCount, maximum).flatMap { _ =>
      validatePolicy(times, policy).flatMap { maxAttempts =>
        acceptedSeeds(
          context,
          times,
          policy,
          maxAttempts,
          observer,
          (seed, key) =>
            groupedSupportSize(
              groupCount,
              seed,
              key,
              observer
            ) < groupCount
        ).flatMap { seeds =>
          for
            shape <- PlanShape.of(times, 1)
            cost <- PlanCost.of(
              groups.size.toLong + times.toLong,
              groupCount.toLong + groupCount.toLong * maximum.toLong,
              groupCount.toLong + groupCount.toLong * maximum.toLong
            )
          yield GeneralPlanSpec(
            shape,
            PlanDiagnostics.empty,
            cost
          )(
            key =>
              groupedSplit(
                groups,
                members,
                key,
                seeds(key.repeat),
                observer
              ),
            CanonicalAssignmentEncoder.drawSplit
          )
        }
      }
    }

  private[designs] def validatePotentialDrawSize(
      groupCount: Int,
      maximum: Int
  ): Either[DesignError, Unit] =
    if groupCount.toLong * maximum.toLong > Int.MaxValue.toLong then
      Left(
        DesignError.PotentialDrawSizeExceeded(groupCount, maximum)
      )
    else Right(())

  private def validatePolicy(
      times: Int,
      policy: OobPolicy
  ): Either[DesignError, Int] =
    if times < 1 then Left(DesignError.InvalidTimes(times))
    else
      policy match
        case OobPolicy.Redraw(attempts) if attempts < 1 =>
          Left(DesignError.InvalidRedrawAttempts(attempts))
        case OobPolicy.Redraw(attempts) => Right(attempts)
        case _                          => Right(1)

  private def acceptedSeeds(
      context: BuildContext,
      times: Int,
      policy: OobPolicy,
      maxAttempts: Int,
      observer: BootstrapWorkObserver,
      acceptable: (Seed, UnitKey) => Boolean
  ): Either[DesignError, IArray[Seed]] =
    val seeds = new Array[Seed](times)
    var unit = 0
    var error: Option[DesignError] = None
    while unit < times && error.isEmpty do
      val key = UnitKey(unit, 0)
      val basePath =
        DesignSupport.childPath(unit, StreamDomain.Unit, unit)
      val baseSeed = context.derive(basePath)
      policy match
        case OobPolicy.Allow => seeds(unit) = baseSeed
        case OobPolicy.Fail =>
          observer.candidate(key)
          if acceptable(baseSeed, key) then seeds(unit) = baseSeed
          else error = Some(DesignError.EmptyOutOfBag(key, 1))
        case OobPolicy.Redraw(_) =>
          var attempt = 0
          var accepted: Option[Seed] = None
          while attempt < maxAttempts && accepted.isEmpty do
            val candidate =
              if attempt == 0 then baseSeed
              else
                context.derive(
                  basePath
                    .appendUnchecked(
                      StreamDomain.RedrawAttempt,
                      attempt - 1
                    )
                )
            observer.candidate(key)
            if acceptable(candidate, key) then accepted = Some(candidate)
            attempt += 1
          accepted match
            case Some(seed) => seeds(unit) = seed
            case None =>
              error = Some(
                DesignError.EmptyOutOfBag(key, maxAttempts)
              )
      unit += 1
    error match
      case Some(value) => Left(value)
      case None        => Right(IArray.unsafeFromArray(seeds))

  private def ordinarySupportSize(n: Int, seed: Seed): Int =
    val seen = Array.fill(n)(false)
    var rand = Rand.fromSeed(seed)
    var index = 0
    var count = 0
    while index < n do
      val (next, value) = rand.nextIntBoundedUnsafe(n)
      rand = next
      if !seen(value) then
        seen(value) = true
        count += 1
      index += 1
    count

  private def ordinarySplit(n: Int, seed: Seed): Split[Draw] =
    val values = new Array[Int](n)
    var rand = Rand.fromSeed(seed)
    var index = 0
    while index < n do
      val (next, value) = rand.nextIntBoundedUnsafe(n)
      rand = next
      values(index) = value
      index += 1
    val draw = Draw.fromOwned(IArray.unsafeFromArray(values), n)
    Split.unsafe(draw, draw.support.complement)

  private def groupedSupportSize(
      groupCount: Int,
      seed: Seed,
      unit: UnitKey,
      observer: BootstrapWorkObserver
  ): Int =
    val seen = Array.fill(groupCount)(false)
    var rand = Rand.fromSeed(seed)
    var index = 0
    var count = 0
    while index < groupCount do
      observer.preflightGroupId(unit)
      val (next, value) =
        rand.nextIntBoundedUnsafe(groupCount)
      rand = next
      if !seen(value) then
        seen(value) = true
        count += 1
      index += 1
    count

  private def groupedSplit(
      groups: Labels,
      members: IArray[IArray[Int]],
      unit: UnitKey,
      seed: Seed,
      observer: BootstrapWorkObserver
  ): Split[Draw] =
    val groupCount = groups.cardinality
    val drawnGroups = new Array[Int](groupCount)
    val selected = Array.fill(groupCount)(false)
    var length = 0
    var rand = Rand.fromSeed(seed)
    var index = 0
    while index < groupCount do
      val (next, group) =
        rand.nextIntBoundedUnsafe(groupCount)
      rand = next
      drawnGroups(index) = group
      selected(group) = true
      length += members(group).length
      index += 1

    val values = new Array[Int](length)
    var output = 0
    index = 0
    while index < drawnGroups.length do
      val rows = members(drawnGroups(index))
      var member = 0
      while member < rows.length do
        observer.emittedRow(unit)
        values(output) = rows(member)
        output += 1
        member += 1
      index += 1
    val support =
      Selection.labelClasses(
        groups,
        IArray.unsafeFromArray(selected)
      )
    val draw =
      Draw.fromOwned(
        IArray.unsafeFromArray(values),
        groups.size,
        Some(support)
      )
    Split.unsafe(draw, support.complement)
