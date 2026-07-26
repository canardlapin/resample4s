# Release readiness

Last verified: 2026-07-25

## Completed evidence

- `sbt -batch testAll`: 32 core, 32 designs, and 5 laws tests passed
  independently on JVM, Scala.js, and Scala Native.
- `sbt -batch compatibilityAll`: all nine MiMa and TASTy-MiMa tasks passed.
  The previous-artifact set is intentionally empty for the first `0.1.0`
  baseline and becomes `0.1.0` for later versions.
- `sbt -batch 'coreJVM/doc' 'designsJVM/doc' 'lawsJVM/doc'`: all public API
  documentation generated successfully.
- `sbt -batch publishLocalAll`: the core, designs, and laws binaries, source
  archives, API-documentation archives, POMs, and Ivy descriptors published
  locally for all three platforms.
- A clean temporary consumer, containing no source-project dependency, resolved
  `tessera-laws` from the local artifact repository and passed
  `PlanLaws.exactCoverage` over a catalogue K-fold on JVM, Scala.js, and Scala
  Native.
- Alder commit `648ac3b` passed its 114-test module set on each platform and
  retains the complete Tessera receipt in cross-fit lineage.

## Published dependency graph

- `tessera-core`: Scala standard library only.
- `tessera-designs`: `tessera-core` plus the Scala standard library.
- `tessera-laws`: `tessera-core`, `tessera-designs`, ScalaCheck, and the Scala
  standard library.
- MUnit and MUnit-ScalaCheck appear only with test scope in generated POMs.

Every artifact has the expected platform suffix:

```text
_3
_sjs1_3
_native0.5_3
```

## Open gates

1. The fresh-context independent review required by PLAN phase 4 is neither
   completed nor waived.
2. `CHANGELOG.md` remains `Unreleased`, and the build remains
   `0.1.0-SNAPSHOT`, until that review closes.
3. No Git remote is configured, so a release tag cannot be pushed.

The stable tag must not be created while any item above remains open.
