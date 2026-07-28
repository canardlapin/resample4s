package resample4s.benchmarks

import java.nio.file.Path

class BenchmarkProtocolSuite extends munit.FunSuite:
  private val manifest =
    Path
      .of("benchmarks", "cases.csv")
      .nn
      .toAbsolutePath
      .nn
      .normalize()
      .nn

  test("every smoke Resample4s artifact satisfies its declared contract") {
    val cases = right(BenchmarkManifest.read(manifest))
      .filter(_.profile == "smoke")
    assertEquals(cases.size, 7)
    cases.foreach { benchmarkCase =>
      val fixture = right(Fixtures.build(benchmarkCase))
      val prepared = right(Resample4sRunner.prepare(benchmarkCase, fixture))
      val artifacts = right(prepared.artifacts())
      val evidence =
        right(ContractValidator.validate(benchmarkCase, fixture, artifacts))
      assert(evidence.qualityPrimary >= 0L)
      assert(evidence.qualitySecondary >= 0L)
    }
  }

  test("group-homogeneous strata are identical within each group") {
    val benchmarkCase = right(BenchmarkManifest.read(manifest))
      .find(_.caseId == "grouped-stratified-smoke")
      .getOrElse(fail("missing grouped-stratified smoke case"))
    val fixture = right(Fixtures.build(benchmarkCase))
    val observed = Array.fill(fixture.groupCount)(-1)
    var row = 0
    while row < benchmarkCase.n do
      val group = fixture.groups(row)
      val stratum = fixture.strata(row)
      if observed(group) == -1 then observed(group) = stratum
      else assertEquals(observed(group), stratum)
      row += 1
  }

  test("fixture checksum is stable") {
    val benchmarkCase = right(BenchmarkManifest.read(manifest))
      .find(_.caseId == "grouped-stratified-smoke")
      .getOrElse(fail("missing grouped-stratified smoke case"))
    assertEquals(
      right(Fixtures.build(benchmarkCase)).checksum,
      128298438L
    )
  }

  private def right[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error) => fail(error.toString)
