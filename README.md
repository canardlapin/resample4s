# Resample4s

Resample4s builds reproducible train/test index plans for Scala 3. It
distinguishes sampling with replacement, selection without replacement, and
permutation in the type system, while keeping ordinary cross-validation
concise. It targets JVM, Scala.js, and Scala Native from one pure source tree
with zero external runtime dependencies.

## Status

This repository is currently `0.1.0-SNAPSHOT`. Freeze-readiness work for the
kernel, façade, and catalogue is in tree. In-repo gates still open before a
stable tag:

- hosted CI green on the current surface (including façade tests and Scalafmt);
- an independent review of the changed public surface, with findings resolved.

Sonatype publication and external consumer rehearsals (Alder follow-ups,
ScalaFIM) are deferred; local `publishLocalAll` is enough for early development.
Rolling-origin / time-series designs remain deferred (PRD D10). Performance
benchmarks and allocation-path optimizations stay active work — see
`docs/performance/` and `benchmarks/`.

## Installation

```scala
libraryDependencies +=
  "io.github.canardlapin" %%% "resample4s" % "0.1.0-SNAPSHOT"
```

Specialist artifacts remain available as `resample4s-core`,
`resample4s-designs`, and `resample4s-laws`.

## Sixty-second K-fold

```scala
import resample4s.*

val result =
  KFold(
    folds = 5,
    shuffle = true
  ).plan(
    samples = 120,
    seed = 42L
  )

result.foreach { plan =>
  for split <- plan.splits do
    val train: Selection = split.train
    val test: Selection  = split.test
    // gather rows with train.foreachIndex / test.foreachIndex
}
```

Randomization is explicit. Use `KFold.ordered(5)` when fold assignment must
follow population order without shuffling.

## Stratified and grouped

```scala
import resample4s.*

val classLabels = Vector(0, 0, 1, 1, 0, 1)
val subjectIds  = Vector(0, 0, 1, 1, 2, 2)

val stratified =
  KFold.stratified(folds = 2, strata = classLabels).flatMap(_.plan(seed = 42L))

val grouped =
  KFold.grouped(folds = 2, groups = subjectIds).flatMap(_.plan(seed = 42L))
```

Typed wrappers prevent exchanging groups and strata:

```scala
val groups: Groups = Groups.from(subjectIds).toOption.get
val strata: Strata = Strata.from(classLabels).toOption.get
```

When two label systems have a containment relationship, validate it once and
carry typed evidence:

```scala
val nesting: Either[DesignError, LabelRefinement] =
  groups.labels.refines(strata.labels)
```

## Bootstrap reveals Draw

```scala
import resample4s.*

val result =
  Bootstrap
    .unconditional(resamples = 1000)
    .plan(samples = 120, seed = 42L)

result.foreach { plan =>
  for split <- plan.splits do
    val train: Draw     = split.train  // may repeat rows
    val test: Selection = split.test   // out-of-bag rows
}
```

That is where a scikit-learn user becomes intrigued: the API looks familiar, but
Scala tells them that bootstrap training rows can repeat.

## Holdout and shuffle split

```scala
import resample4s.*

val holdout =
  Holdout(test = SplitSize.percent(20).toOption.get)
    .plan(samples = 120, seed = 42L)

val repeated =
  ShuffleSplit(
    test = SplitSize.count(24),
    resamples = 100
  ).plan(samples = 120, seed = 42L)

val stratified =
  ShuffleSplit
    .stratified(
      test = SplitSize.percent(25).toOption.get,
      resamples = 10,
      strata = Vector(0, 0, 1, 1, 0, 1, 0, 1)
    )
    .flatMap(_.plan(seed = 42L))

// Grouped shuffle-split sizes are in groups, not rows:
// SplitSize.count(2) holds out two groups; percent(25) holds out ~25% of groups.
```

## Predefined and nested

```scala
import resample4s.*

val imported =
  PredefinedSplit.fromAssignments(Array(0, 0, 1, 1, 2, 2))

val nested =
  Nested.plan(outerFolds = 5, innerFolds = 3, samples = 120, seed = 42L)
```

Compatible ExactOnce designs can also be composed with `Nested.of(outer, inner)`
or `Nested.combine(outer, analysis => …)` when inner labels must be projected.

## What the types buy you

- `Draw` may repeat rows and preserves draw order.
- `Injection` preserves order but cannot repeat.
- `Selection` is an ordered set of rows.
- `Permutation` is a bijection.

Bootstrap, cross-validation, jackknife, and permutation therefore have
genuinely different static meanings. Coverage capabilities such as
`Coverage.ExactOnce` (and constructive `CompleteOnce`) let downstream libraries
require one out-of-fold value per row without a runtime coverage check.

## Catalogue

The façade exposes ordinary constructors for:

- plain, ordered, stratified, grouped, and grouped-stratified K-fold;
- holdout and shuffle split, including stratified and grouped variants;
- predefined splits from assignments or imported train/test selections;
- unconditional / redrawing / fail-on-empty-OOB bootstrap;
- leave-one-out and delete-one jackknife;
- free and within-block permutation tests;
- equal-sized whole-group permutations via `PermutationTest.wholeGroups`,
  preserving row order within each group;
- nested cross-validation via `Nested.kFold` / `Nested.plan`, plus the
  general `Nested.of` / `Nested.combine` combinator.

Rolling-origin / time-series designs remain deferred past v0.1 (PRD D10).

Import rings for specialists:

- `import resample4s.kernel.*` — reindexing algebra and plan capabilities;
- `import resample4s.spi.*` — design authoring, descriptors, stream tags;
- `import resample4s.audit.*` — digests and receipts.

Guides:

- [Concepts](docs/concepts.md) — plan vs data, Selection vs Draw, coverage
- [Integrator guide](docs/integrator.md) — consuming plans and ordinals
- [Author guide](docs/author.md) — writing auditable designs
- [Compatibility](docs/compatibility.md) — MiMa plus seed-to-assignment policy
- [Performance](docs/performance/README.md) — benchmarks and optimization posture

## Reproducibility and audit

Randomized designs use a fixed SplitMix64 generator, unbiased bounded draws,
Fisher-Yates, and ordered domain-separated stream paths. Golden fixtures lock
outputs on all three platforms; laws and exhaustive oracles provide the
correctness evidence.

Integrators that need schedule-independent replicate streams can address them
directly through the stable kernel ring:

```scala
import resample4s.kernel.*

val path = StreamPath.of(StreamDomain.Repeat, replicate)
val childSeed = path.map(rootSeed.derive)
```

`Seed.derivationAlgorithm` identifies this mapping as `seed-path/v1`.

`PlanReceipt` verifies a recompiled plan against design, labels, population, and
assignment fingerprints. It does not reconstruct a design. The built-in
FNV-1a-64 provider is only a checksum for accidental divergence. It is not
collision-resistant, tamper-evident, or authenticated.

## Honest limits

- `OobPolicy.Redraw` / façade `Bootstrap.redrawing` conditions bootstrap on
  non-empty OOB and therefore biases the distribution, especially for small
  populations. Use unconditional bootstrap for the unbiased distribution.
- Grouped K-fold guarantees group atomicity, not balanced fold sizes.
- Grouped-stratified K-fold uses an exact `BigInt` objective and reports its
  result, but balance is best-effort and has no approximation guarantee.
- Plans are lazy and recompute randomized units on repeated access. Call
  `compiled.plan.materialized` when retaining all generated units is intended.

## Modules and verification

- `resample4s` — ordinary-user façade (this artifact).
- `resample4s-core`: algebra, RNG, design SPI, plans, and receipts.
- `resample4s-designs`: built-in design catalogue.
- `resample4s-laws`: published ScalaCheck law bundles.
- `resample4s-benchmarks`: non-published JVM harness with locked scikit-learn,
  splitTools, and rsample comparators.

Run the full local gate with:

```bash
sbt testAll
```
