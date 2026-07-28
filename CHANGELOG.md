# Changelog

All notable changes to Resample4s are recorded here.

## 0.1.0 — Unreleased

Initial cross-platform release candidate.

### Added

- A closed algebra of `Draw`, `Injection`, `Selection`, and `Permutation`, with
  typed composition and extensional equality across compact backings.
- Lazy, immutable plans with explicit materialization, exact cost accounting,
  total public lookup, and `Coverage`, `Coverage.Exact`, and
  `Coverage.ExactOnce` capabilities. Constructive `CompleteOnce` /
  `CompletePerRepeat` schedules retain fold witnesses.
- Published façade artifact `resample4s` with `import resample4s.*`, primitive
  `plan(samples, seed)`, `train`/`test` aliases, `Groups`/`Strata`/`Blocks`,
  `SplitSize`, and kernel / spi / audit import rings.
- Plain, ordered, stratified, grouped, and grouped-stratified K-fold; holdout
  and Monte Carlo (including stratified and grouped shuffle-split); LOO and
  LOGO; ordinary and grouped bootstrap; delete-one, exhaustive, and sampled
  jackknife; free and within-block permutations; predefined splits; and a
  general `Nested` combinator beside nested K-fold.
- Platform-stable SplitMix64 randomization with domain-separated streams.
- Verification receipts with canonical assignment encoding and an open digest
  provider whose per-invocation accumulator consumes bytes incrementally.
  Built-in FNV-1a-64 is explicitly a non-adversarial checksum.
- Published law bundles, exhaustive oracles, calibrated statistical checks,
  cross-platform golden fixtures, and complexity guardrails for allocation
  cost and compact-selection traversal.
- Bounded exact `Optimum`/`Regret` diagnostics for small grouped allocations,
  with worst-case aggregation across repeats and exhaustive regression lattices.
- Consumer law bundles for group atomicity, stratification, bootstrap
  multiplicity/order/OOB semantics, permutation bijectivity and block
  preservation, reconstruction, recoding equivalence, and assignment
  perturbation.
- Primitive nested cross-validation with plain, stratified, grouped, and
  grouped-stratified constructors. Each outer fold carries an embedded,
  exactly-covered inner plan with deterministic seed derivation, diagnostics,
  cost accounting, and receipt coverage.
- Fixed external allocation designs: arbitrary repeat-major `FixedSplits` with
  ordinary coverage, and canonical-label `FixedPartitions` with one-repeat
  `ExactOnce` or repeated `Exact` evidence. Façade fold imports use
  `Labels.retained` so fold identity is preserved.
- Open SPI identifiers: `DesignError` as an extensible trait, `MetricId`,
  custom `StreamDomain` tags, and validated plan constructors
  (`SplitPlans.*`, `PredefinedSplit`).
- Linear `IntCursor` / `foreachIndex` traversal for compact selection backings.
- MiMa and TASTy-MiMa configuration with `0.1.0` recorded as the first
  compatibility baseline for subsequent releases.
- A semantic-parity benchmark harness comparing complete canonical split
  artifacts with scikit-learn, splitTools, and rsample. Locked environments,
  contract tests, raw evidence, allocation-quality diagnostics, and
  interpretation-bounded reports are included. Benchmark and hot-path
  optimization work remains active (`docs/performance/`).
- Concepts, integrator, and author guides under `docs/`, plus an explicit
  seed-to-assignment compatibility policy.
- Scalafmt configuration and a CI format gate.

### Changed

- The pre-publication working name `tessera` was replaced by `resample4s`.
  Packages, artifacts, canonical receipt framing, benchmark protocols, and
  documentation use the new name. No compatibility alias is published because
  no artifact existed under the working name.
- Public `Plan.map` / `zip` drop coverage capabilities; only
  `mapPreservingCoverage` retains `Cov` for proven library transforms.
- Bootstrap no longer chooses a redraw policy through a bare constructor.
  Ordinary and grouped bootstrap expose named unconditional, redrawing, and
  fail-on-empty-OOB presets plus an explicit-policy route. Public descriptor,
  no-label design-definition, general-plan, and label conveniences remove
  redundant proof plumbing while expanding exactly into the same typed algebra.
- Plain K-fold shuffle is explicit. Ordered K-fold uses `kfold-ordered/v1`;
  shuffled K-fold retains `kfold/v1`.
- Grouped holdout / shuffle-split interpret `SplitSize` relative to groups.
- The README leads with installation and ordinary K-fold rather than nested CV.
- Monte Carlo and holdout now use an exact-equivalent partial Fisher–Yates
  kernel plus a linear canonical-role scan. Bounded `Int` draws use primitive
  unsigned rejection arithmetic instead of allocating `BigInt` operands.
  Differential oracles and cross-platform golden fixtures lock the previous
  seed-to-artifact mapping.

### Integration

- Alder integration validates ordinal-to-`RowId` interpretation, canonical
  `GroupOf` labels, receipt retention, and leakage-safe cross-fitting on JVM,
  Scala.js, and Scala Native.
- The integration spike introduced `Coverage.ExactOnce`. Per-repeat `Exact`
  alone is insufficient for one OOF value per row when a plan has multiple
  repeats; repeated exact plans therefore cannot mint Alder
  `CompleteResampler`.

### Deferred

- Rolling-origin and other time-series designs are deferred to 0.2.
- Hosted Sonatype / automated GitHub release credentials remain outside the
  in-repo freeze gate; local `publishLocalAll` is the publication rehearsal.
