package resample4s.designs

import resample4s.core.*

/** Label-aware variants of holdout / Monte Carlo (shuffle-split) designs. */
private[designs] enum ShuffleVariant derives CanEqual:
  case Plain
  case Stratified(strata: Labels)
  case Grouped(groups: Labels)

  def holdoutAlgorithm: String =
    this match
      case Plain => "holdout/v1"
      case Stratified(_) => "holdout-stratified/v1"
      case Grouped(_) => "holdout-grouped/v1"

  def monteCarloAlgorithm: String =
    this match
      case Plain => "monte-carlo/v1"
      case Stratified(_) => "monte-carlo-stratified/v1"
      case Grouped(_) => "monte-carlo-grouped/v1"

  def labels: Vector[Labels] =
    this match
      case Plain => Vector.empty
      case Stratified(value) => Vector(value)
      case Grouped(value) => Vector(value)

private[designs] object ShuffleSplitSupport:
  def spec(
      context: BuildContext,
      fraction: Fraction,
      role: NamedRole,
      times: Int,
      variant: ShuffleVariant = ShuffleVariant.Plain
  ): Either[DesignError, GeneralPlanSpec[Split[Selection]]] =
    val n = context.space.size
    val namedSize = fraction.sizeOf(n)
    val assessmentSize =
      role match
        case NamedRole.Assessing => namedSize
        case NamedRole.Analyzing => n - namedSize
    if times < 1 then Left(DesignError.InvalidTimes(times))
    else
      variant match
        case ShuffleVariant.Plain if namedSize <= 0 || namedSize >= n =>
          Left(DesignError.DegenerateSplit(n, assessmentSize))
        case _ =>
          for
            shape <- PlanShape.of(times, 1)
            cost <- PlanCost.of(times.toLong, n.toLong, n.toLong)
            _ <- validateVariant(context, fraction, variant)
          yield
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
            GeneralPlanSpec(
              shape,
              PlanDiagnostics.empty,
              cost
            )(
              key => split(context, fraction, role, seeds(key.repeat), variant),
              CanonicalAssignmentEncoder.selectionSplit
            )

  private def validateVariant(
      context: BuildContext,
      fraction: Fraction,
      variant: ShuffleVariant
  ): Either[DesignError, Unit] =
    variant match
      case ShuffleVariant.Plain => Right(())
      case ShuffleVariant.Stratified(strata) =>
        if strata.size != context.space.size then
          Left(DesignError.LengthMismatch(context.space.size, strata.size))
        else
          val members = DesignSupport.labelMembers(strata)
          var ordinal = 0
          var failure: Option[DesignError] = None
          while ordinal < members.length && failure.isEmpty do
            val size = members(ordinal).length
            val named = fraction.sizeOf(size)
            if named <= 0 || named >= size then
              failure = Some(DesignError.DegenerateSplit(size, named))
            ordinal += 1
          failure.toLeft(())
      case ShuffleVariant.Grouped(groups) =>
        if groups.size != context.space.size then
          Left(DesignError.LengthMismatch(context.space.size, groups.size))
        else
          val groupCount = groups.cardinality
          val namedGroups = fraction.sizeOf(groupCount)
          if namedGroups <= 0 || namedGroups >= groupCount then
            Left(DesignError.DegenerateSplit(groupCount, namedGroups))
          else Right(())

  private def split(
      context: BuildContext,
      fraction: Fraction,
      role: NamedRole,
      seed: Seed,
      variant: ShuffleVariant
  ): Split[Selection] =
    val n = context.space.size
    val (named, other) =
      variant match
        case ShuffleVariant.Plain =>
          val namedSize = fraction.sizeOf(n)
          sampledRoles(n, namedSize, seed)
        case ShuffleVariant.Stratified(strata) =>
          stratifiedRoles(context.designKey, strata, fraction, seed)
        case ShuffleVariant.Grouped(groups) =>
          groupedRoles(groups, fraction, seed)
    val namedSelection = Selection.fromOwned(named, n)
    val otherSelection = Selection.fromOwned(other, n)
    role match
      case NamedRole.Assessing =>
        Split.unsafe(otherSelection, namedSelection)
      case NamedRole.Analyzing =>
        Split.unsafe(namedSelection, otherSelection)

  /**
   * Produces the same sorted role selections as a complete Fisher-Yates
   * shuffle followed by sorting both sides.
   *
   * Once the shuffle has processed position `namedSize`, later swaps only
   * permute the named prefix. Because `Selection` discards that order, a
   * membership scan can emit both roles directly in canonical order.
   */
  private[designs] def sampledRoles(
      n: Int,
      namedSize: Int,
      seed: Seed
  ): (IArray[Int], IArray[Int]) =
    val shuffled =
      Rand
        .fromSeed(seed)
        .shufflePrefixIndicesUnsafe(n, namedSize)

    val isNamed = new Array[Boolean](n)
    var index = 0
    while index < namedSize do
      isNamed(shuffled(index)) = true
      index += 1
    emitRoles(isNamed)

  private[designs] def stratifiedRoles(
      designKey: DesignKey,
      strata: Labels,
      fraction: Fraction,
      seed: Seed
  ): (IArray[Int], IArray[Int]) =
    val n = strata.size
    val members = DesignSupport.labelMembers(strata)
    val isNamed = new Array[Boolean](n)
    var ordinal = 0
    while ordinal < members.length do
      val bucket = members(ordinal)
      val namedSize = fraction.sizeOf(bucket.length)
      val stratumSeed =
        Rand.derive(
          seed,
          designKey,
          StreamPath.unsafe(StreamDomain.Stratum, ordinal)
        )
      val order =
        DesignSupport.shuffledIndices(bucket.length, stratumSeed)
      var position = 0
      while position < namedSize do
        isNamed(bucket(order(position))) = true
        position += 1
      ordinal += 1
    emitRoles(isNamed)

  private[designs] def groupedRoles(
      groups: Labels,
      fraction: Fraction,
      seed: Seed
  ): (IArray[Int], IArray[Int]) =
    val n = groups.size
    val groupCount = groups.cardinality
    val namedGroups = fraction.sizeOf(groupCount)
    val order = DesignSupport.shuffledIndices(groupCount, seed)
    val selected = new Array[Boolean](groupCount)
    var index = 0
    while index < namedGroups do
      selected(order(index)) = true
      index += 1
    val isNamed = new Array[Boolean](n)
    index = 0
    while index < n do
      isNamed(index) = selected(groups.unsafeAt(index))
      index += 1
    emitRoles(isNamed)

  private def emitRoles(isNamed: Array[Boolean]): (IArray[Int], IArray[Int]) =
    var namedCount = 0
    var index = 0
    while index < isNamed.length do
      if isNamed(index) then namedCount += 1
      index += 1
    val named = new Array[Int](namedCount)
    val other = new Array[Int](isNamed.length - namedCount)
    var namedIndex = 0
    var otherIndex = 0
    index = 0
    while index < isNamed.length do
      if isNamed(index) then
        named(namedIndex) = index
        namedIndex += 1
      else
        other(otherIndex) = index
        otherIndex += 1
      index += 1
    (IArray.unsafeFromArray(named), IArray.unsafeFromArray(other))
