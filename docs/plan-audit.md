# PLAN completion audit

Audit date: 2026-07-26

This record maps each execution phase to current authoritative evidence. A
green aggregate count is not treated as proof unless the named gate is covered.

## Phase 0 — complete

- The root build defines the core, designs, and laws cross-projects for JVM,
  Scala.js, and Scala Native with Scala 3.3.8, strict warnings, explicit nulls,
  strict equality, Apache-2.0, and a publish-skipped aggregate.
- `compileAll` and `testAll` cover all nine projects.
- Each module has an executing smoke suite on every platform.

## Phase 1 — complete

- Core implements the closed reindexing lattice, typed composition, explicit
  widening, set algebra, compact selection/partition backings, lazy plans,
  SplitMix64 streams, the public design SPI, diagnostics/costs, labels, and
  verification receipts.
- Property and fixture evidence covers pullback, injection factorization,
  permutation laws, defensive ownership, constructor failures, backing
  equality/hash/encoding, laziness/materialization, exact-capability negative
  compilation, canonical bytes, provider-width/chunk invariance, and
  component-specific receipt failures.
- The published conformance suite contains deliberately broken generators,
  encoders, grouping, and cost declarations and confirms that the laws fail.

## Phase 2 — complete

- Every cross-validation family and repeat capability in PLAN is implemented.
- Generated adversarial properties cover exactness, floor/ceiling
  stratification, oversized-group atomicity, nested composition, and typed
  totality.
- Exhaustive allocation oracles cover 974 grouped and 4,866
  grouped-stratified canonical small configurations with one-sided regret
  baselines, plus a 65,536-allocation frontier fixture.
- Seeded-priority grouped LPT uses a min-heap. Both the heap and the production
  allocator have deterministic O(g log k) comparison-count guardrails.
- `examples/NestedCrossValidation.scala` is compiled as a designs test source
  on all three platforms and its exclusion check executes in the suite.

## Phase 3 — complete

- Ordinary/grouped bootstrap, delete-one/exhaustive/sampled jackknife, and
  free/within-block permutation designs implement the normative algorithms.
- Direct observers prove `Fail` evaluates one candidate per successful unit,
  `Redraw(a)` evaluates at most `a`, grouped preflight samples exactly g group
  ids per candidate without emitting rows, and lazy access has no late failure.
- Calibrated fixed-seed statistics use exact finite-n expectations and declared
  alpha/Hoeffding or chi-square bounds. Redraw has an exact n=2 conditional and
  exhaustion oracle; grouped OOB covers equal and unequal group sizes; grouped
  length uses its normalized bound and exact equal-size case.
- Rank/unrank exhaustively covers small combination spaces and sampled ranks
  retain duplicates. Repeated identity permutations prove duplicates are legal.

## Phase 4 — partially complete

- Cost guardrails, README, honest-limit Scaladoc, design decisions, CI workflow,
  compatibility policy, type-discipline review, and release-readiness evidence
  exist.
- The local CI-equivalent test gate passes 35 core, 48 designs, and 8 laws tests
  on each platform, 273 total.
- The user-authorized fresh-context review of `3dc2d77` is complete. Its
  receipt, oracle/diagnostic, and public-law findings were fixed rather than
  waived; the review and resolutions are recorded under `docs/reviews/`.
- The semantic-parity benchmark harness validates deterministic shared
  fixtures and family-specific public contracts before timing. Its smoke
  protocol covers 24 supported library/case cells; the standard profile
  preserves 120 raw rows and 24 comparable aggregates with grouped-stratified
  quality reported beside runtime.
- Profile-guided Monte Carlo/RNG kernels remain exact against the BigInt
  rejection and complete-shuffle differential oracles within the canonical
  Resample4s protocol. The name cutover intentionally changed design keys; new
  cross-platform goldens lock them. The standard 100,000-row/100-unit median is
  94.025 ms versus the 1,248.301 ms pre-optimization evidence, and the refreshed
  cross-library artifact contract remains green.
- Public usability probes prove named bootstrap policies expand into the same
  keys, failures, assignments, cost, diagnostics, fingerprints, and receipts;
  coverage and abstract-composition compiler diagnostics name the relevant
  domain types. The primary README path retains `Either`; the focused
  type-discipline reconciliation is recorded in
  `docs/reviews/scala-type-discipline-usability-2026-07-26.md`.
- The D30 identity cutover covers packages, artifacts, canonical framing,
  benchmark protocols, workflows, and documentation. Regenerated golden
  fixtures pass on all three platforms; refreshed smoke and standard
  cross-language profiles each validate 120 raw rows and 24 aggregates.
  Published JVM jars contain only `resample4s/` classes, and a clean external
  consumer resolves `resample4s-laws` on JVM, Scala.js, and Scala Native.
- Open: hosted CI cannot run until a Git remote exists. Local evidence is not
  mislabeled as hosted-CI evidence.

## Phase 5 — complete

- Alder commit `648ac3b` contains the typed `ExactOnce` adapter, receipt
  retention, cross-fit exclusion evidence, and negative compilation fixtures.
- Its 114-test module set passed separately on JVM, Scala.js, and Scala Native
  (342 total). Resample4s records the resulting `ExactOnce` correction in PRD D23.

## Phase 6 — open

- MiMa and TASTy-MiMa are configured with `0.1.0` as the first compatibility
  baseline, and the snapshot compatibility tasks are green.
- The changelog remains intentionally `Unreleased`; the build remains
  `0.1.0-SNAPSHOT`; no tag exists.
- Stable finalization requires the Phase 4 review and hosted-CI gates, followed
  by the final full gate, changelog date/version freeze, `v0.1.0` tag, and push
  to a configured remote.
