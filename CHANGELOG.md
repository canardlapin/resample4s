# Changelog

All notable changes to Tessera are recorded here.

## 0.1.0 — Unreleased

Initial cross-platform release candidate.

### Added

- A closed algebra of `Draw`, `Injection`, `Selection`, and `Permutation`, with
  typed composition and extensional equality across compact backings.
- Lazy, immutable plans with explicit materialization, exact cost accounting,
  total public lookup, and `Coverage`, `Coverage.Exact`, and
  `Coverage.ExactOnce` capabilities.
- Plain, stratified, grouped, and grouped-stratified K-fold; holdout and Monte
  Carlo; LOO and LOGO; ordinary and grouped bootstrap; delete-one, exhaustive,
  and sampled jackknife; and free and within-block permutations.
- Platform-stable SplitMix64 randomization with domain-separated streams.
- Verification receipts with canonical assignment encoding and an open digest
  provider. Built-in FNV-1a-64 is explicitly a non-adversarial checksum.
- Published law bundles, exhaustive oracles, calibrated statistical checks,
  cross-platform golden fixtures, and complexity guardrails.
- Consumer law bundles for group atomicity, stratification, bootstrap
  multiplicity/order/OOB semantics, permutation bijectivity and block
  preservation, reconstruction, recoding equivalence, and assignment
  perturbation.
- A cross-platform-compiled nested-cross-validation composition example.
- MiMa and TASTy-MiMa configuration with `0.1.0` recorded as the first
  compatibility baseline for subsequent releases.

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
