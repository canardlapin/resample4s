import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / licenses     := Seq(License.Apache2)

val munitV           = "1.3.4"
val munitScalacheckV = "1.1.0"
val scalacheckV      = "1.18.1"
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
    "org.scalameta" %%% "munit"            % munitV % Test,
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
    name := "tessera-core"
  )

lazy val coreJVM    = core.jvm
lazy val coreJS     = core.js
lazy val coreNative = core.native

lazy val designs = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("designs"))
  .dependsOn(core)
  .settings(strictSettings)
  .settings(testSettings)
  .settings(compatibilitySettings)
  .settings(
    name := "tessera-designs"
  )

lazy val designsJVM    = designs.jvm
lazy val designsJS     = designs.js
lazy val designsNative = designs.native

lazy val laws = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("laws"))
  .dependsOn(core, designs)
  .settings(strictSettings)
  .settings(testSettings)
  .settings(compatibilitySettings)
  .settings(
    name := "tessera-laws",
    libraryDependencies +=
      "org.scalacheck" %%% "scalacheck" % scalacheckV
  )

lazy val lawsJVM    = laws.jvm
lazy val lawsJS     = laws.js
lazy val lawsNative = laws.native

lazy val root = project
  .in(file("."))
  .aggregate(
    coreJVM,
    coreJS,
    coreNative,
    designsJVM,
    designsJS,
    designsNative,
    lawsJVM,
    lawsJS,
    lawsNative
  )
  .settings(
    name           := "tessera",
    publish / skip := true
  )

addCommandAlias(
  "compileAll",
  ";coreJVM/compile;coreJS/compile;coreNative/compile;" +
    "designsJVM/compile;designsJS/compile;designsNative/compile;" +
    "lawsJVM/compile;lawsJS/compile;lawsNative/compile"
)

addCommandAlias(
  "testAll",
  ";coreJVM/test;coreJS/test;coreNative/test;" +
    "designsJVM/test;designsJS/test;designsNative/test;" +
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
    "lawsJVM/mimaReportBinaryIssues;lawsJVM/tastyMiMaReportIssues;" +
    "lawsJS/mimaReportBinaryIssues;lawsJS/tastyMiMaReportIssues;" +
    "lawsNative/mimaReportBinaryIssues;lawsNative/tastyMiMaReportIssues"
)

addCommandAlias(
  "publishLocalAll",
  ";coreJVM/publishLocal;coreJS/publishLocal;coreNative/publishLocal;" +
    "designsJVM/publishLocal;designsJS/publishLocal;" +
    "designsNative/publishLocal;" +
    "lawsJVM/publishLocal;lawsJS/publishLocal;lawsNative/publishLocal"
)
