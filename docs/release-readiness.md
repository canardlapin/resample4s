# Release readiness

Last verified: 2026-07-27

## Completed evidence

- `sbt -batch testAll`: 36 core, 64 designs, and 10 laws tests passed
  independently on JVM, Scala.js, and Scala Native (330 total), including the
  fixed-allocation tranche.
- `sbt -batch compatibilityAll`: all nine TASTy-MiMa tasks passed. The nine
  binary MiMa tasks correctly reported an empty previous-artifact set for the
  first `0.1.0` baseline; later versions compare against `0.1.0`.
- Cross-platform Scaladoc generated successfully for all nine published
  projects. Scala Native reports only the toolchain's known unsupported
  `-Xplugin` option.
- `sbt -batch publishLocalAll`: the core, designs, and laws binaries, source
  archives, API-documentation archives, POMs, and Ivy descriptors published
  locally for all three platforms. This completed before a documentation-only
  link correction in `Fixed.scala`; the managed environment denied the
  requested cache re-publish after that correction.
- A clean temporary consumer, containing no source-project dependency, resolved
  `resample4s-laws` from the local artifact repository and passed its published
  exact-coverage law on JVM, Scala.js, and Scala Native.
- The PLAN phase-4 fresh-context review inspected commit `3dc2d77` without
  conversation history. Its receipt-streaming/cost, oracle/diagnostic, and
  public-law findings are resolved and recorded in
  `docs/reviews/fresh-context-2026-07-26.md`.
- Alder commit `648ac3b` passed its 114-test module set on each platform and
  retains the complete Resample4s receipt in cross-fit lineage.
- The locked cross-language benchmark protocol passed 7 Resample4s, 6
  scikit-learn, 7 rsample, and 4 splitTools smoke contract cells. The standard
  profile produced 120 accepted raw measurements and 24 validated aggregates
  over identical fixtures and contracts. Raw rows, runtimes, quality metrics,
  and interpretation boundaries are preserved under
  `benchmarks/results/2026-07-26-standard/`.
- The Monte Carlo kernel is differentially identical to the literal `BigInt`
  rejection and complete Fisher-Yates definitions across JVM, Scala.js, and
  Scala Native within the canonical Resample4s protocol. The name cutover
  intentionally changed design keys, and regenerated goldens lock the new
  assignments. The refreshed 100,000-row/100-unit standard median is 94.025 ms
  versus the pre-change 1,248.301 ms on the same machine; JFR evidence and
  claim boundaries are recorded in
  `docs/performance/monte-carlo-2026-07-26.md`.
- Public usability gates compare named bootstrap presets with their explicit
  policy expansions through receipt production, exercise an external custom
  design using only the concise public SPI, and lock domain-facing compiler
  diagnostics for coverage and abstract composition. The reconciliation is
  recorded in
  `docs/reviews/scala-type-discipline-usability-2026-07-26.md`.
- The pre-publication identity cutover is complete in source and local
  artifacts. Canonical framing and benchmark protocols use `resample4s`;
  regenerated goldens pass on all three platforms; refreshed smoke and standard
  benchmark profiles each validate 120 raw rows and 24 aggregates. The JVM
  core, designs, and laws jars contain 343, 83, and 20 `resample4s/` entries
  respectively and no `tessera/` entries.
- Hosted CI run `30256827936` passed the JVM, Scala.js, Scala Native, and
  compatibility jobs on commit `81e4a22`.
- Fixed allocations have platform-identical canonical byte and digest
  fixtures. Behavioral tests cover partial and overlapping external splits,
  omitted rows, repeated exact partitions, defensive ownership, eager typed
  failures, seed-invariant assignment receipts, component-specific mismatch
  precedence, negative `ExactOnce` compilation, and declared cost bounds.
- `sbt -batch benchmarkCheck` passed its three protocol tests and emitted all
  seven smoke measurements after the fixed-allocation implementation.

## Published dependency graph

- `resample4s-core`: Scala standard library only.
- `resample4s-designs`: `resample4s-core` plus the Scala standard library.
- `resample4s-laws`: `resample4s-core`, `resample4s-designs`, ScalaCheck, and the Scala
  standard library.
- MUnit and MUnit-ScalaCheck appear only with test scope in generated POMs.
- scikit-learn, rsample, splitTools, bench, and MUnit for the benchmark module
  are non-published benchmark/test dependencies and do not enter any Resample4s
  artifact.

Every artifact has the expected platform suffix:

```text
_3
_sjs1_3
_native0.5_3
```

## Open gates

1. The ScalaFIM consumer rehearsal is open; it is intentionally outside this
   repository's writable scope.
2. The changed public surface still needs hosted CI and an independent review;
   `publishLocalAll` should also be repeated once to refresh the source and
   Scaladoc archives after the documentation-only link correction.
3. `CHANGELOG.md` remains `Unreleased`, and the build remains
   `0.1.0-SNAPSHOT`, until those gates close.

The stable tag must not be created while any item above remains open.
