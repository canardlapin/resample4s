# Release readiness

Last verified: 2026-07-26

## Completed evidence

- `sbt -batch testAll`: 35 core, 48 designs, and 8 laws tests passed
  independently on JVM, Scala.js, and Scala Native (273 total).
- `sbt -batch compatibilityAll`: all nine MiMa and TASTy-MiMa tasks passed.
  The previous-artifact set is intentionally empty for the first `0.1.0`
  baseline and becomes `0.1.0` for later versions.
- `sbt -batch 'coreJVM/doc' 'designsJVM/doc' 'lawsJVM/doc'`: all public API
  documentation generated successfully.
- `sbt -batch publishLocalAll`: the core, designs, and laws binaries, source
  archives, API-documentation archives, POMs, and Ivy descriptors published
  locally for all three platforms.
- A clean temporary consumer, containing no source-project dependency, resolved
  `tessera-laws` from the local artifact repository and passed exact coverage,
  reconstruction, group atomicity, stratification, bootstrap semantics, and
  composition, within-block permutation, and complete recoding laws on JVM,
  Scala.js, and Scala Native.
- The PLAN phase-4 fresh-context review inspected commit `3dc2d77` without
  conversation history. Its receipt-streaming/cost, oracle/diagnostic, and
  public-law findings are resolved and recorded in
  `docs/reviews/fresh-context-2026-07-26.md`.
- Alder commit `648ac3b` passed its 114-test module set on each platform and
  retains the complete Tessera receipt in cross-fit lineage.
- The locked cross-language benchmark protocol passed 7 Tessera, 6
  scikit-learn, 7 rsample, and 4 splitTools smoke contract cells. The standard
  profile produced 120 accepted raw measurements and 24 validated aggregates
  over identical fixtures and contracts. Raw rows, runtimes, quality metrics,
  and interpretation boundaries are preserved under
  `benchmarks/results/2026-07-26-standard/`.
- The Monte Carlo kernel is differentially identical to the literal `BigInt`
  rejection and complete Fisher-Yates definitions across JVM, Scala.js, and
  Scala Native. The refreshed 100,000-row/100-unit standard median is 82.417 ms
  versus the pre-change 1,248.301 ms on the same machine; JFR evidence and claim
  boundaries are recorded in
  `docs/performance/monte-carlo-2026-07-26.md`.
- Public usability gates compare named bootstrap presets with their explicit
  policy expansions through receipt production, exercise an external custom
  design using only the concise public SPI, and lock domain-facing compiler
  diagnostics for coverage and abstract composition. The reconciliation is
  recorded in
  `docs/reviews/scala-type-discipline-usability-2026-07-26.md`.

## Published dependency graph

- `tessera-core`: Scala standard library only.
- `tessera-designs`: `tessera-core` plus the Scala standard library.
- `tessera-laws`: `tessera-core`, `tessera-designs`, ScalaCheck, and the Scala
  standard library.
- MUnit and MUnit-ScalaCheck appear only with test scope in generated POMs.
- scikit-learn, rsample, splitTools, bench, and MUnit for the benchmark module
  are non-published benchmark/test dependencies and do not enter any Tessera
  artifact.

Every artifact has the expected platform suffix:

```text
_3
_sjs1_3
_native0.5_3
```

## Open gates

1. Hosted GitHub Actions CI has not run because the repository has no remote;
   the equivalent local JVM, Scala.js, Scala Native, and compatibility tasks
   are green, but local evidence is not represented as hosted-CI evidence.
2. `CHANGELOG.md` remains `Unreleased`, and the build remains
   `0.1.0-SNAPSHOT`, until hosted CI closes.
3. No Git remote is configured, so a release tag cannot be pushed.

The stable tag must not be created while any item above remains open.
