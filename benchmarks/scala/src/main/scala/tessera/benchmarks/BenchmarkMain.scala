package tessera.benchmarks

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Try

import tessera.core.*
import tessera.designs.*

private val Protocol = "tessera-benchmark/v1"
private val Modulus = 2147483647L
private val BenchmarkSeed = 20260726L

enum Family derives CanEqual:
  case KFold
  case Stratified
  case Grouped
  case GroupedStratified
  case MonteCarlo
  case Bootstrap
  case LeaveOneOut

  def id: String =
    this match
      case KFold             => "kfold"
      case Stratified        => "stratified"
      case Grouped           => "grouped"
      case GroupedStratified => "grouped_stratified"
      case MonteCarlo        => "monte_carlo"
      case Bootstrap         => "bootstrap"
      case LeaveOneOut       => "loo"

  def contractId: String =
    this match
      case KFold | Stratified | LeaveOneOut =>
        "exact-partition/v1"
      case Grouped =>
        "group-exact-partition/v1"
      case GroupedStratified =>
        "group-stratified-exact-partition/v1"
      case MonteCarlo =>
        "monte-carlo-complement/v1"
      case Bootstrap =>
        "bootstrap-oob/v1"

object Family:
  def parse(value: String): Either[String, Family] =
    value match
      case "kfold"             => Right(KFold)
      case "stratified"        => Right(Stratified)
      case "grouped"           => Right(Grouped)
      case "grouped_stratified" => Right(GroupedStratified)
      case "monte_carlo"       => Right(MonteCarlo)
      case "bootstrap"         => Right(Bootstrap)
      case "loo"               => Right(LeaveOneOut)
      case other               => Left(s"unknown family: $other")

final case class BenchmarkCase(
    profile: String,
    caseId: String,
    family: Family,
    n: Int,
    folds: Int,
    repeats: Int,
    fractionNum: Int,
    fractionDen: Int,
    groups: String,
    strata: String
) derives CanEqual

final case class Fixture(
    groups: Array[Int],
    strata: Array[Int],
    groupCount: Int,
    stratumCount: Int,
    checksum: Long
)

final case class Observation(
    units: Int,
    analysisOrdinals: Long,
    assessmentOrdinals: Long,
    checksum: Long
)

final case class ContractEvidence(
    qualityPrimary: Long,
    qualitySecondary: Long
)

final class PreparedTessera private[benchmarks] (
    val execute: () => Either[String, Observation],
    val artifacts: () =>
      Either[String, Vector[(Array[Int], Array[Int])]]
)

final case class Cli(
    manifest: Path,
    profile: String,
    warmup: Int,
    measure: Int,
    output: Path
)

private object BenchmarkManifest:
  private val ExpectedHeader =
    "protocol,profile,case_id,family,n,folds,repeats," +
      "fraction_num,fraction_den,groups,strata"

  def read(path: Path): Either[String, Vector[BenchmarkCase]] =
    val lines =
      Files
        .readAllLines(path, StandardCharsets.UTF_8)
        .nn
        .asScala
        .toVector
    lines.headOption match
      case None => Left(s"empty benchmark manifest: $path")
      case Some(header) if header != ExpectedHeader =>
        Left(s"unexpected benchmark manifest header: $header")
      case Some(_) =>
        lines.drop(1).zipWithIndex.foldLeft(
          Right(Vector.empty): Either[String, Vector[BenchmarkCase]]
        ) { case (result, (line, index)) =>
          result.flatMap(cases => parseLine(line, index + 2).map(cases :+ _))
        }

  private def parseLine(
      line: String,
      lineNumber: Int
  ): Either[String, BenchmarkCase] =
    val fields = line.split(",", -1).nn.iterator.map(_.nn).toVector
    if fields.size != 11 then
      Left(s"manifest line $lineNumber has ${fields.size} fields")
    else if fields(0) != Protocol then
      Left(s"manifest line $lineNumber has protocol ${fields(0)}")
    else
      for
        family <- Family.parse(fields(3))
        n <- positiveInt(fields(4), "n", lineNumber)
        folds <- positiveInt(fields(5), "folds", lineNumber)
        repeats <- positiveInt(fields(6), "repeats", lineNumber)
        fractionNum <- nonNegativeInt(
          fields(7),
          "fraction_num",
          lineNumber
        )
        fractionDen <- nonNegativeInt(
          fields(8),
          "fraction_den",
          lineNumber
        )
        result <- validate(
          BenchmarkCase(
            fields(1),
            fields(2),
            family,
            n,
            folds,
            repeats,
            fractionNum,
            fractionDen,
            fields(9),
            fields(10)
          ),
          lineNumber
        )
      yield result

  private def positiveInt(
      value: String,
      field: String,
      line: Int
  ): Either[String, Int] =
    value.toIntOption.filter(_ > 0).toRight(
      s"manifest line $line has invalid $field: $value"
    )

  private def nonNegativeInt(
      value: String,
      field: String,
      line: Int
  ): Either[String, Int] =
    value.toIntOption.filter(_ >= 0).toRight(
      s"manifest line $line has invalid $field: $value"
    )

  private def validate(
      benchmarkCase: BenchmarkCase,
      line: Int
  ): Either[String, BenchmarkCase] =
    val fractionValid =
      benchmarkCase.family != Family.MonteCarlo ||
        (benchmarkCase.fractionNum > 0 &&
          benchmarkCase.fractionNum < benchmarkCase.fractionDen)
    val groupValid =
      (benchmarkCase.family != Family.Grouped &&
        benchmarkCase.family != Family.GroupedStratified) ||
        benchmarkCase.groups != "none"
    val strataValid =
      (benchmarkCase.family != Family.Stratified &&
        benchmarkCase.family != Family.GroupedStratified) ||
        benchmarkCase.strata != "none"
    if !fractionValid then Left(s"manifest line $line has invalid fraction")
    else if !groupValid then Left(s"manifest line $line requires groups")
    else if !strataValid then Left(s"manifest line $line requires strata")
    else Right(benchmarkCase)

private object Fixtures:
  def build(benchmarkCase: BenchmarkCase): Either[String, Fixture] =
    for
      groups <- groupCodes(benchmarkCase.n, benchmarkCase.groups)
      strata <- stratumCodes(
        benchmarkCase.n,
        benchmarkCase.strata,
        groups
      )
    yield
      val groupCount =
        if benchmarkCase.groups == "none" then 0
        else cardinality(groups)
      val stratumCount =
        if benchmarkCase.strata == "none" then 0
        else cardinality(strata)
      Fixture(
        groups,
        strata,
        groupCount,
        stratumCount,
        checksum(groups, strata)
      )

  private def groupCodes(n: Int, pattern: String): Either[String, Array[Int]] =
    pattern match
      case "none" => Right(Array.fill(n)(-1))
      case "balanced" =>
        Right(Array.tabulate(n)(index => index / 8))
      case "skewed" =>
        val values = new Array[Int](n)
        var index = 0
        var group = 0
        while index < n do
          val size = 1 + (group * 17) % 23
          var offset = 0
          while offset < size && index < n do
            values(index) = group
            index += 1
            offset += 1
          group += 1
        Right(values)
      case other => Left(s"unknown group pattern: $other")

  private def stratumCodes(
      n: Int,
      pattern: String,
      groups: Array[Int]
  ): Either[String, Array[Int]] =
    pattern match
      case "none" => Right(Array.fill(n)(-1))
      case "balanced4" =>
        Right(Array.tabulate(n)(index => index % 4))
      case "group_balanced4" =>
        if groups.forall(_ >= 0) then
          Right(Array.tabulate(n)(index => groups(index) % 4))
        else Left("group_balanced4 requires groups")
      case other => Left(s"unknown stratum pattern: $other")

  private def cardinality(values: Array[Int]): Int =
    values.maxOption.fold(0)(_ + 1)

  private def checksum(groups: Array[Int], strata: Array[Int]): Long =
    var result = 17L
    var index = 0
    while index < groups.length do
      result = (result * 31L + groups(index).toLong + 2L) % Modulus
      index += 1
    result = (result * 31L + 97L) % Modulus
    index = 0
    while index < strata.length do
      result = (result * 31L + strata(index).toLong + 2L) % Modulus
      index += 1
    result

private object TesseraRunner:
  private type SelectionDesign =
    Design[Split[Selection], Coverage]

  def prepare(
      benchmarkCase: BenchmarkCase,
      fixture: Fixture
  ): Either[String, PreparedTessera] =
    for
      space <- IndexSpace
        .of(benchmarkCase.n)
        .left
        .map(_.toString)
      result <-
        benchmarkCase.family match
          case Family.Bootstrap =>
            val design =
              Bootstrap(benchmarkCase.repeats, OobPolicy.Allow)
            Right(
              new PreparedTessera(
                () => executeBootstrap(design, space),
                () => artifactsBootstrap(design, space)
              )
            )
          case _ =>
            selectionDesign(benchmarkCase, fixture).map { design =>
              new PreparedTessera(
                () => executeSelection(design, space),
                () => artifactsSelection(design, space)
              )
            }
    yield result

  private def selectionDesign(
      benchmarkCase: BenchmarkCase,
      fixture: Fixture
  ): Either[String, SelectionDesign] =
    def labels(
        values: Array[Int],
        cardinality: Int
    ): Either[String, Labels] =
      Labels
        .of(
          IArray.unsafeFromArray(values.clone()),
          cardinality,
          benchmarkCase.n
        )
        .left
        .map(_.toString)

    def repeated(
        design: RepeatableDesign[Split[Selection], ? <: Coverage.Exact]
    ): Either[String, SelectionDesign] =
      if benchmarkCase.repeats == 1 then
        Right(design: SelectionDesign)
      else
        design
          .repeat(benchmarkCase.repeats)
          .left
          .map(_.toString)
          .map(value => value: SelectionDesign)

    benchmarkCase.family match
      case Family.KFold =>
        repeated(KFold(benchmarkCase.folds))
      case Family.Stratified =>
        labels(fixture.strata, fixture.stratumCount).flatMap(value =>
          repeated(KFold.stratified(benchmarkCase.folds, value))
        )
      case Family.Grouped =>
        labels(fixture.groups, fixture.groupCount).flatMap(value =>
          repeated(KFold.grouped(benchmarkCase.folds, value))
        )
      case Family.GroupedStratified =>
        for
          groups <- labels(fixture.groups, fixture.groupCount)
          strata <- labels(fixture.strata, fixture.stratumCount)
          design <- repeated(
            KFold.groupedStratified(
              benchmarkCase.folds,
              groups,
              strata
            )
          )
        yield design
      case Family.MonteCarlo =>
        Fraction
          .of(benchmarkCase.fractionNum, benchmarkCase.fractionDen)
          .left
          .map(_.toString)
          .map { fraction =>
            val design: SelectionDesign =
              MonteCarlo.assessing(fraction, benchmarkCase.repeats)
            design
          }
      case Family.LeaveOneOut =>
        Right(LeaveOneOut(): SelectionDesign)
      case Family.Bootstrap =>
        Left("bootstrap is a draw design")

  private def executeSelection(
      design: SelectionDesign,
      space: IndexSpace
  ): Either[String, Observation] =
    design
      .compile(space, Seed.fromLong(BenchmarkSeed))
      .left
      .map(_.toString)
      .map { compiled =>
        consume(
          compiled.plan.iterator.map { case (_, split) =>
            (
              copyOrdinals(split.analysis.toIArray),
              copyOrdinals(split.assessment.toIArray)
            )
          }
        )
      }

  private def artifactsSelection(
      design: SelectionDesign,
      space: IndexSpace
  ): Either[String, Vector[(Array[Int], Array[Int])]] =
    design
      .compile(space, Seed.fromLong(BenchmarkSeed))
      .left
      .map(_.toString)
      .map { compiled =>
        compiled.plan.iterator.map { case (_, split) =>
          (
            copyOrdinals(split.analysis.toIArray),
            copyOrdinals(split.assessment.toIArray)
          )
        }.toVector
      }

  private def executeBootstrap(
      design: Bootstrap,
      space: IndexSpace
  ): Either[String, Observation] =
    design
      .compile(space, Seed.fromLong(BenchmarkSeed))
      .left
      .map(_.toString)
      .map { compiled =>
        consume(
          compiled.plan.iterator.map { case (_, split) =>
            (
              copyOrdinals(split.analysis.toIArray),
              copyOrdinals(split.assessment.toIArray)
            )
          }
        )
      }

  private def artifactsBootstrap(
      design: Bootstrap,
      space: IndexSpace
  ): Either[String, Vector[(Array[Int], Array[Int])]] =
    design
      .compile(space, Seed.fromLong(BenchmarkSeed))
      .left
      .map(_.toString)
      .map { compiled =>
        compiled.plan.iterator.map { case (_, split) =>
          (
            copyOrdinals(split.analysis.toIArray),
            copyOrdinals(split.assessment.toIArray)
          )
        }.toVector
      }

  private def consume(
      splits: Iterator[(Array[Int], Array[Int])]
  ): Observation =
    var units = 0
    var analysisOrdinals = 0L
    var assessmentOrdinals = 0L
    var checksum = 17L
    while splits.hasNext do
      val (analysis, assessment) = splits.next()
      var analysisSum = 0L
      var index = 0
      while index < analysis.length do
        analysisSum += analysis(index).toLong
        index += 1
      var assessmentSum = 0L
      index = 0
      while index < assessment.length do
        assessmentSum += assessment(index).toLong
        index += 1
      checksum = (
        checksum +
          31L * (analysisSum % Modulus) +
          37L * (assessmentSum % Modulus) +
          41L * analysis.length.toLong +
          43L * assessment.length.toLong
      ) % Modulus
      units += 1
      analysisOrdinals += analysis.length.toLong
      assessmentOrdinals += assessment.length.toLong
    Observation(
      units,
      analysisOrdinals,
      assessmentOrdinals,
      checksum
    )

  private def copyOrdinals(values: IArray[Int]): Array[Int] =
    Array.tabulate(values.length)(values(_))

private object ContractValidator:
  def validate(
      benchmarkCase: BenchmarkCase,
      fixture: Fixture,
      splits: Vector[(Array[Int], Array[Int])]
  ): Either[String, ContractEvidence] =
    val expectedUnits =
      benchmarkCase.family match
        case Family.KFold | Family.Stratified | Family.Grouped |
            Family.GroupedStratified =>
          benchmarkCase.folds * benchmarkCase.repeats
        case Family.LeaveOneOut => benchmarkCase.n
        case Family.MonteCarlo | Family.Bootstrap =>
          benchmarkCase.repeats
    if splits.size != expectedUnits then
      Left(s"expected $expectedUnits units, observed ${splits.size}")
    else
      benchmarkCase.family match
        case Family.Bootstrap =>
          validateBootstrap(benchmarkCase, splits)
        case Family.MonteCarlo =>
          validateMonteCarlo(benchmarkCase, splits)
        case _ =>
          validateExact(benchmarkCase, fixture, splits)

  private def validateExact(
      benchmarkCase: BenchmarkCase,
      fixture: Fixture,
      splits: Vector[(Array[Int], Array[Int])]
  ): Either[String, ContractEvidence] =
    val repeats =
      if benchmarkCase.family == Family.LeaveOneOut then 1
      else benchmarkCase.repeats
    val folds =
      if benchmarkCase.family == Family.LeaveOneOut then benchmarkCase.n
      else benchmarkCase.folds
    val coverage = Array.fill(repeats, benchmarkCase.n)(0)
    val foldSizes = Array.fill(repeats, folds)(0)
    val strataCounts =
      Array.fill(repeats, folds, fixture.stratumCount)(0)
    val groupFold =
      Array.fill(repeats, fixture.groupCount)(-1)
    var unit = 0
    var failure: Option[String] = None
    while unit < splits.size && failure.isEmpty do
      val repeat = unit / folds
      val fold = unit % folds
      val (analysis, assessment) = splits(unit)
      failure = validatePartition(
        benchmarkCase.n,
        analysis,
        assessment
      )
      var index = 0
      while index < assessment.length && failure.isEmpty do
        val row = assessment(index)
        coverage(repeat)(row) += 1
        foldSizes(repeat)(fold) += 1
        if fixture.stratumCount > 0 then
          strataCounts(repeat)(fold)(fixture.strata(row)) += 1
        if fixture.groupCount > 0 then
          val group = fixture.groups(row)
          val previous = groupFold(repeat)(group)
          if previous == -1 then groupFold(repeat)(group) = fold
          else if previous != fold then
            failure = Some(
              s"group $group crosses folds $previous and $fold"
            )
        index += 1
      unit += 1
    if failure.nonEmpty then Left(failure.fold("validation failed")(identity))
    else
      var repeat = 0
      while repeat < repeats && failure.isEmpty do
        var row = 0
        while row < benchmarkCase.n && failure.isEmpty do
          if coverage(repeat)(row) != 1 then
            failure = Some(
              s"repeat $repeat row $row assessed ${coverage(repeat)(row)} times"
            )
          row += 1
        repeat += 1
      failure match
        case Some(value) => Left(value)
        case None =>
          val foldImbalance = maximumFoldImbalance(foldSizes)
          val stratumDeviation =
            maximumStratumDeviation(strataCounts, fixture.stratumCount)
          val primary =
            benchmarkCase.family match
              case Family.Stratified => stratumDeviation
              case Family.Grouped    => foldImbalance
              case Family.GroupedStratified =>
                groupedStratifiedObjective(
                  benchmarkCase,
                  fixture,
                  foldSizes,
                  strataCounts
                )
              case _ => 0L
          Right(ContractEvidence(primary, foldImbalance))

  private def validatePartition(
      n: Int,
      analysis: Array[Int],
      assessment: Array[Int]
  ): Option[String] =
    if analysis.length + assessment.length != n then
      Some(
        s"partition has ${analysis.length + assessment.length} rows, expected $n"
      )
    else
      increasingAndBounded(analysis, n, "analysis").orElse(
        increasingAndBounded(assessment, n, "assessment")
      ).orElse {
        var left = 0
        var right = 0
        var overlap: Option[String] = None
        while left < analysis.length && right < assessment.length &&
            overlap.isEmpty
        do
          if analysis(left) == assessment(right) then
            overlap = Some(s"roles overlap at ${analysis(left)}")
          else if analysis(left) < assessment(right) then left += 1
          else right += 1
        overlap
      }

  private def increasingAndBounded(
      values: Array[Int],
      n: Int,
      role: String
  ): Option[String] =
    var index = 0
    var previous = -1
    var failure: Option[String] = None
    while index < values.length && failure.isEmpty do
      val value = values(index)
      if value < 0 || value >= n then
        failure = Some(s"$role ordinal $value is outside [0,$n)")
      else if value <= previous then
        failure = Some(s"$role is not strictly increasing at $value")
      previous = value
      index += 1
    failure

  private def validateMonteCarlo(
      benchmarkCase: BenchmarkCase,
      splits: Vector[(Array[Int], Array[Int])]
  ): Either[String, ContractEvidence] =
    val expectedAssessment =
      benchmarkCase.n * benchmarkCase.fractionNum /
        benchmarkCase.fractionDen
    splits.zipWithIndex.foldLeft(
      Right(()): Either[String, Unit]
    ) { case (result, ((analysis, assessment), unit)) =>
      result.flatMap { _ =>
        validatePartition(
          benchmarkCase.n,
          analysis,
          assessment
        ).toLeft(()).left.map(error => s"unit $unit: $error").flatMap { _ =>
          if assessment.length == expectedAssessment then Right(())
          else
            Left(
              s"unit $unit assessment size ${assessment.length}, " +
                s"expected $expectedAssessment"
            )
        }
      }
    }.map(_ => ContractEvidence(0L, 0L))

  private def validateBootstrap(
      benchmarkCase: BenchmarkCase,
      splits: Vector[(Array[Int], Array[Int])]
  ): Either[String, ContractEvidence] =
    val marks = Array.fill(benchmarkCase.n)(0)
    var stamp = 0
    var unit = 0
    var failure: Option[String] = None
    while unit < splits.size && failure.isEmpty do
      val (analysis, assessment) = splits(unit)
      stamp += 1
      if analysis.length != benchmarkCase.n then
        failure = Some(
          s"bootstrap unit $unit draw length ${analysis.length}, " +
            s"expected ${benchmarkCase.n}"
        )
      var index = 0
      while index < analysis.length && failure.isEmpty do
        val row = analysis(index)
        if row < 0 || row >= benchmarkCase.n then
          failure = Some(s"bootstrap draw ordinal $row is out of bounds")
        else marks(row) = stamp
        index += 1
      if failure.isEmpty then
        failure = increasingAndBounded(
          assessment,
          benchmarkCase.n,
          "assessment"
        )
      index = 0
      var assessmentIndex = 0
      while index < benchmarkCase.n && failure.isEmpty do
        val expectedOob = marks(index) != stamp
        val observedOob =
          assessmentIndex < assessment.length &&
            assessment(assessmentIndex) == index
        if expectedOob != observedOob then
          failure = Some(s"bootstrap OOB mismatch at row $index")
        if observedOob then assessmentIndex += 1
        index += 1
      unit += 1
    failure match
      case Some(value) => Left(value)
      case None        => Right(ContractEvidence(0L, 0L))

  private def maximumFoldImbalance(
      foldSizes: Array[Array[Int]]
  ): Long =
    foldSizes.iterator.map { values =>
      values.max.toLong - values.min.toLong
    }.maxOption.getOrElse(0L)

  private def maximumStratumDeviation(
      counts: Array[Array[Array[Int]]],
      stratumCount: Int
  ): Long =
    var maximum = 0L
    var repeat = 0
    while repeat < counts.length do
      var stratum = 0
      while stratum < stratumCount do
        var minimum = Int.MaxValue
        var largest = Int.MinValue
        var fold = 0
        while fold < counts(repeat).length do
          val value = counts(repeat)(fold)(stratum)
          if value < minimum then minimum = value
          if value > largest then largest = value
          fold += 1
        val deviation = largest.toLong - minimum.toLong
        if deviation > maximum then maximum = deviation
        stratum += 1
      repeat += 1
    maximum

  private def groupedStratifiedObjective(
      benchmarkCase: BenchmarkCase,
      fixture: Fixture,
      foldSizes: Array[Array[Int]],
      counts: Array[Array[Array[Int]]]
  ): Long =
    val totals = Array.fill(fixture.stratumCount)(0)
    var row = 0
    while row < benchmarkCase.n do
      totals(fixture.strata(row)) += 1
      row += 1
    var maximum = BigInt(0)
    var repeat = 0
    while repeat < foldSizes.length do
      var objective = BigInt(0)
      var fold = 0
      while fold < benchmarkCase.folds do
        var stratum = 0
        while stratum < fixture.stratumCount do
          val delta =
            BigInt(benchmarkCase.folds) *
              BigInt(counts(repeat)(fold)(stratum)) -
              BigInt(totals(stratum))
          objective += delta * delta
          stratum += 1
        val sizeDelta =
          BigInt(benchmarkCase.folds) *
            BigInt(foldSizes(repeat)(fold)) -
            BigInt(benchmarkCase.n)
        objective += sizeDelta * sizeDelta
        fold += 1
      if objective > maximum then maximum = objective
      repeat += 1
    if maximum > BigInt(Long.MaxValue) then Long.MaxValue
    else maximum.toLong

private object CsvOutput:
  val Header =
    "protocol,library,library_version,runtime,case_id,family," +
      "contract_id,n,folds,repeats,measurement,elapsed_ns,units," +
      "analysis_ordinals,assessment_ordinals,fixture_checksum," +
      "semantic_checksum,contract_ok,quality_primary,quality_secondary"

  def row(
      benchmarkCase: BenchmarkCase,
      measurement: Int,
      elapsed: Long,
      observation: Observation,
      fixture: Fixture,
      evidence: ContractEvidence
  ): String =
    Vector(
      Protocol,
      "tessera",
      "0.1.0-SNAPSHOT",
      System.getProperty("java.runtime.version"),
      benchmarkCase.caseId,
      benchmarkCase.family.id,
      benchmarkCase.family.contractId,
      benchmarkCase.n.toString,
      benchmarkCase.folds.toString,
      benchmarkCase.repeats.toString,
      measurement.toString,
      elapsed.toString,
      observation.units.toString,
      observation.analysisOrdinals.toString,
      observation.assessmentOrdinals.toString,
      fixture.checksum.toString,
      observation.checksum.toString,
      "true",
      evidence.qualityPrimary.toString,
      evidence.qualitySecondary.toString
    ).mkString(",")

object BenchmarkMain:
  @volatile private var blackhole = 0L

  def main(args: Array[String]): Unit =
    run(args.toVector) match
      case Left(error) =>
        System.err.nn.println(error)
        sys.exit(2)
      case Right(summary) =>
        println(summary)

  private def run(args: Vector[String]): Either[String, String] =
    for
      cli <- parseCli(args)
      cases <- BenchmarkManifest.read(cli.manifest)
      selected = cases.filter(_.profile == cli.profile)
      _ <-
        Either.cond(
          selected.nonEmpty,
          (),
          s"profile ${cli.profile} has no cases"
        )
      rows <- selected.foldLeft(
        Right(Vector.empty): Either[String, Vector[String]]
      ) { (result, benchmarkCase) =>
        result.flatMap { accumulated =>
          runCase(benchmarkCase, cli).map(accumulated ++ _)
        }
      }
      _ <- write(cli.output, CsvOutput.Header +: rows)
    yield
      s"wrote ${rows.size} Tessera measurements for " +
        s"${selected.size} ${cli.profile} cases to ${cli.output} " +
        s"(consumer=${blackhole & 0xffffL})"

  private def runCase(
      benchmarkCase: BenchmarkCase,
      cli: Cli
  ): Either[String, Vector[String]] =
    for
      fixture <- Fixtures.build(benchmarkCase)
      prepared <- TesseraRunner.prepare(benchmarkCase, fixture)
      artifacts <- prepared.artifacts()
      evidence <- ContractValidator
        .validate(benchmarkCase, fixture, artifacts)
        .left
        .map(error => s"${benchmarkCase.caseId}: $error")
      _ <- runWarmups(prepared, cli.warmup)
      rows <- runMeasurements(
        benchmarkCase,
        fixture,
        prepared,
        evidence,
        cli.measure
      )
    yield rows

  private def runWarmups(
      prepared: PreparedTessera,
      count: Int
  ): Either[String, Unit] =
    var iteration = 0
    var failure: Option[String] = None
    while iteration < count && failure.isEmpty do
      prepared.execute() match
        case Left(error) => failure = Some(error)
        case Right(observation) =>
          blackhole = observation.checksum
      iteration += 1
    failure.toLeft(())

  private def runMeasurements(
      benchmarkCase: BenchmarkCase,
      fixture: Fixture,
      prepared: PreparedTessera,
      evidence: ContractEvidence,
      count: Int
  ): Either[String, Vector[String]] =
    val rows = Vector.newBuilder[String]
    var measurement = 0
    var failure: Option[String] = None
    while measurement < count && failure.isEmpty do
      val start = System.nanoTime()
      prepared.execute() match
        case Left(error) => failure = Some(error)
        case Right(observation) =>
          val elapsed = System.nanoTime() - start
          blackhole = observation.checksum
          rows += CsvOutput.row(
            benchmarkCase,
            measurement,
            elapsed,
            observation,
            fixture,
            evidence
          )
      measurement += 1
    failure match
      case Some(value) => Left(value)
      case None        => Right(rows.result())

  private def parseCli(args: Vector[String]): Either[String, Cli] =
    def value(name: String): Either[String, String] =
      val index = args.indexOf(name)
      if index < 0 || index + 1 >= args.size then
        Left(s"missing required option $name")
      else Right(args(index + 1))

    for
      manifest <- value("--manifest").map(value => Path.of(value).nn)
      profile <- value("--profile")
      warmup <- value("--warmup").flatMap(
        _.toIntOption.filter(_ >= 0).toRight("warmup must be non-negative")
      )
      measure <- value("--measure").flatMap(
        _.toIntOption.filter(_ > 0).toRight("measure must be positive")
      )
      output <- value("--output").map(value => Path.of(value).nn)
    yield Cli(manifest, profile, warmup, measure, output)

  private def write(
      path: Path,
      lines: Vector[String]
  ): Either[String, Unit] =
    Try {
        val parent = path.getParent
        if parent ne null then
          val _ = Files.createDirectories(parent.nn)
        val _ = Files.writeString(
          path,
          lines.mkString("", "\n", "\n"),
          StandardCharsets.UTF_8
        )
      }
      .toEither
      .left
      .map(error => s"cannot write $path: ${error.getMessage}")
