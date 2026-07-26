# Tessera

Tessera is a zero-runtime-dependency Scala 3 library for finite reindexings,
partitions, and reproducible resampling designs. It targets JVM, Scala.js, and
Scala Native from one pure source tree.

The core idea is small: a dataset has ordinal positions `0` through `n - 1`, and
a resample is a function from a new finite ordinal into those positions. Tessera
keeps the important kinds of that function distinct:

- `Draw` is ordered and may repeat positions.
- `Injection` is ordered and never repeats.
- `Selection` is strictly increasing.
- `Permutation` is a bijection.

Composition preserves the strongest valid result type. In particular,
`Selection.after(Selection)` is still a `Selection`, which is the algebraic
reason nested cross-validation cannot reach an outer assessment fold.

## Status

This repository is currently `0.1.0-SNAPSHOT`. The fresh-context review and
Alder integration gates are complete; the public surface is not frozen until
the hosted-CI gate in `PLAN.md` passes. The implementation follows `PRD.md` v0.9;
rolling-origin/time-series designs are explicitly deferred.

## Example

```scala
import tessera.core.*
import tessera.designs.*

val space = IndexSpace.of(120).toOption.get
val outer = KFold(5).compile(space, Seed.fromLong(42L)).toOption.get.plan

outer.iterator.foreach { (_, outerSplit) =>
  val innerSpace = IndexSpace.of(outerSplit.analysis.domain).toOption.get
  val inner =
    KFold(4).compile(innerSpace, Seed.fromLong(99L)).toOption.get.plan

  inner.iterator.foreach { (_, innerSplit) =>
    val embeddedAssessment =
      outerSplit.analysis.after(innerSplit.assessment).toOption.get
    // embeddedAssessment is statically a Selection over the original space.
  }
}
```

Production code should retain the `Either` error channel rather than use the
compact `.toOption.get` presentation style above.

The complete, cross-platform-compiled version is
[`examples/NestedCrossValidation.scala`](examples/NestedCrossValidation.scala).

## Catalogue

The `tessera-designs` module includes:

- named-role holdout and Monte Carlo splits;
- plain, stratified, grouped, and grouped-stratified K-fold;
- leave-one-out and leave-one-group-out;
- ordinary and whole-group bootstrap with explicit OOB policy;
- delete-one, exhaustive delete-d, and sampled delete-d jackknife;
- free and within-block permutation designs.

One-repeat partitioning designs return
`Plan[Split[Selection], Coverage.ExactOnce]`. Repeating them preserves the
weaker `Coverage.Exact` proof—once per repeat—but deliberately drops
`ExactOnce`. Partial designs return `Coverage`, so a consumer cannot pass
Holdout, Bootstrap, or repeated K-fold to an API that requires one OOF value per
row.

## Reproducibility and audit

Randomized designs use a fixed SplitMix64 generator, unbiased bounded draws,
Fisher-Yates, and ordered domain-separated stream paths. Golden fixtures lock
outputs on all three platforms; laws and exhaustive oracles provide the
correctness evidence.

`PlanReceipt` verifies a recompiled plan against design, labels, population, and
assignment fingerprints. It does not reconstruct a design. The built-in
FNV-1a-64 provider is only a checksum for accidental divergence. It is not
collision-resistant, tamper-evident, or authenticated. Consumers may supply an
arbitrary-length digest provider through a per-invocation incremental
accumulator; authentication still requires trusted storage or a signature
outside Tessera.

## Honest limits

- `OobPolicy.Redraw` conditions bootstrap on non-empty OOB and therefore biases
  the distribution, especially for small populations. Use `Allow` for the
  unconditional bootstrap distribution.
- Grouped K-fold guarantees group atomicity, not balanced fold sizes.
- Grouped-stratified K-fold uses an exact `BigInt` objective and reports its
  result, but balance is best-effort and has no approximation guarantee.
- Plans are lazy and recompute randomized units on repeated access. Call
  `materialized` when retaining all generated units is the intended tradeoff.

## Modules and verification

- `tessera-core`: algebra, RNG, design SPI, plans, and receipts.
- `tessera-designs`: built-in design catalogue.
- `tessera-laws`: published ScalaCheck law bundles.
- `tessera-benchmarks`: non-published JVM harness with locked scikit-learn,
  splitTools, and rsample comparators.

Run the full local gate with:

```text
sbt testAll
sbt compatibilityAll
sbt publishLocalAll
sbt benchmarkCheck
```

The build uses Scala 3.3.8 with fatal warnings, strict equality, explicit nulls,
and no runtime library dependencies.

The cross-language benchmark compares complete canonical split artifacts rather
than constructor names. Every timing cell first proves the same fixture and
semantic contract. The checked-in standard run and its limits are recorded in
[`benchmarks/results/2026-07-26-standard/report.md`](benchmarks/results/2026-07-26-standard/report.md);
the reproducible protocol is in
[`benchmarks/README.md`](benchmarks/README.md).
