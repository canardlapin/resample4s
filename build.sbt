import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / homepage := Some(url("https://github.com/canardlapin/resample4s"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/canardlapin/resample4s"),
    "scm:git:https://github.com/canardlapin/resample4s.git",
    "scm:git:git@github.com:canardlapin/resample4s.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "canardlapin",
    name = "Brian Buchsbaum",
    email = "bbuchsbaum@users.noreply.github.com",
    url = url("https://github.com/canardlapin")
  )
)
ThisBuild / pomIncludeRepository := { _ => false }

val munitV = "1.3.4"
val munitScalacheckV = "1.1.0"
val scalacheckV = "1.18.1"
val compatibilityBaseline = "0.1.0"

lazy val strictSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Wvalue-discard",
    "-Yexplicit-nulls",
    "-language:strictEquality",
    "-Werror"
  )
)

lazy val testSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % munitV % Test,
    "org.scalameta" %%% "munit-scalacheck" % munitScalacheckV % Test
  )
)

lazy val compatibilitySettings = Seq(
  mimaPreviousArtifacts := {
    val current = version.value
    if (
      current == compatibilityBaseline ||
      current.startsWith(s"$compatibilityBaseline-")
    ) Set.empty
    else
      Set(
        organization.value %%% moduleName.value % compatibilityBaseline
      )
  },
  mimaFailOnNoPrevious := {
    val current = version.value
    current != compatibilityBaseline &&
    !current.startsWith(s"$compatibilityBaseline-")
  },
  tastyMiMaPreviousArtifacts := {
    val current = version.value
    if (
      current == compatibilityBaseline ||
      current.startsWith(s"$compatibilityBaseline-")
    ) Set.empty
    else
      Set(
        organization.value %%% moduleName.value % compatibilityBaseline
      )
  }
)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(strictSettings)
  .settings(testSettings)
  .settings(compatibilitySettings)
  .settings(
    name := "resample4s-core"
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val coreNative = core.native

lazy val designs = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("designs"))
  .dependsOn(core)
  .settings(strictSettings)
  .settings(testSettings)
  .settings(compatibilitySettings)
  .settings(
    name := "resample4s-designs",
    Test / unmanagedSources +=
      file("examples/NestedCrossValidation.scala")
  )

lazy val designsJVM = designs.jvm
lazy val designsJS = designs.js
lazy val designsNative = designs.native

lazy val api = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("api"))
  .dependsOn(designs)
  .settings(strictSettings)
  .settings(testSettings)
  .settings(compatibilitySettings)
  .settings(
    name := "resample4s"
  )

lazy val apiJVM = api.jvm
lazy val apiJS = api.js
lazy val apiNative = api.native

lazy val laws = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("laws"))
  .dependsOn(core, designs)
  .settings(strictSettings)
  .settings(testSettings)
  .settings(compatibilitySettings)
  .settings(
    name := "resample4s-laws",
    libraryDependencies +=
      "org.scalacheck" %%% "scalacheck" % scalacheckV
  )

lazy val lawsJVM = laws.jvm
lazy val lawsJS = laws.js
lazy val lawsNative = laws.native

lazy val benchmarks = project
  .in(file("benchmarks/scala"))
  .dependsOn(designsJVM)
  .settings(strictSettings)
  .settings(
    name := "resample4s-benchmarks",
    publish / skip := true,
    libraryDependencies +=
      "org.scalameta" %% "munit" % munitV % Test
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    coreJVM,
    coreJS,
    coreNative,
    designsJVM,
    designsJS,
    designsNative,
    apiJVM,
    apiJS,
    apiNative,
    lawsJVM,
    lawsJS,
    lawsNative,
    benchmarks
  )
  .settings(
    name := "resample4s-build",
    publish / skip := true
  )

addCommandAlias(
  "compileAll",
  ";coreJVM/compile;coreJS/compile;coreNative/compile;" +
    "designsJVM/compile;designsJS/compile;designsNative/compile;" +
    "apiJVM/compile;apiJS/compile;apiNative/compile;" +
    "lawsJVM/compile;lawsJS/compile;lawsNative/compile"
)

addCommandAlias(
  "testAll",
  ";coreJVM/test;coreJS/test;coreNative/test;" +
    "designsJVM/test;designsJS/test;designsNative/test;" +
    "apiJVM/test;apiJS/test;apiNative/test;" +
    "lawsJVM/test;lawsJS/test;lawsNative/test"
)

addCommandAlias(
  "compatibilityAll",
  ";coreJVM/mimaReportBinaryIssues;coreJVM/tastyMiMaReportIssues;" +
    "coreJS/mimaReportBinaryIssues;coreJS/tastyMiMaReportIssues;" +
    "coreNative/mimaReportBinaryIssues;coreNative/tastyMiMaReportIssues;" +
    "designsJVM/mimaReportBinaryIssues;designsJVM/tastyMiMaReportIssues;" +
    "designsJS/mimaReportBinaryIssues;designsJS/tastyMiMaReportIssues;" +
    "designsNative/mimaReportBinaryIssues;designsNative/tastyMiMaReportIssues;" +
    "apiJVM/mimaReportBinaryIssues;apiJVM/tastyMiMaReportIssues;" +
    "apiJS/mimaReportBinaryIssues;apiJS/tastyMiMaReportIssues;" +
    "apiNative/mimaReportBinaryIssues;apiNative/tastyMiMaReportIssues;" +
    "lawsJVM/mimaReportBinaryIssues;lawsJVM/tastyMiMaReportIssues;" +
    "lawsJS/mimaReportBinaryIssues;lawsJS/tastyMiMaReportIssues;" +
    "lawsNative/mimaReportBinaryIssues;lawsNative/tastyMiMaReportIssues"
)

addCommandAlias(
  "publishLocalAll",
  ";coreJVM/publishLocal;coreJS/publishLocal;coreNative/publishLocal;" +
    "designsJVM/publishLocal;designsJS/publishLocal;" +
    "designsNative/publishLocal;" +
    "apiJVM/publishLocal;apiJS/publishLocal;apiNative/publishLocal;" +
    "lawsJVM/publishLocal;lawsJS/publishLocal;lawsNative/publishLocal"
)

addCommandAlias(
  "benchmarkCheck",
  ";benchmarks/test;benchmarks/runMain " +
    "resample4s.benchmarks.BenchmarkMain " +
    "--manifest benchmarks/cases.csv --profile smoke " +
    "--warmup 1 --measure 1 " +
    "--output benchmarks/scala/target/scala-smoke.csv"
)

addCommandAlias(
  "fmtCheck",
  ";scalafmtCheckAll;scalafmtSbtCheck"
)

addCommandAlias(
  "fmt",
  ";scalafmtAll;scalafmtSbt"
)
