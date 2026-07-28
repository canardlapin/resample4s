# Concepts

Resample4s builds reproducible train/test index plans. This guide explains the
ideas behind the ordinary-user façade without requiring the authoring SPI.

## Plan versus data

A design is a pure description of how to allocate ordinals `{0, …, n−1}`.
Compiling it with a population size and seed yields a plan: a finite family of
splits. The library never stores your rows. Callers interpret ordinals as row
ids, matrix indices, or dataframe positions.

```scala
import resample4s.*

val plan =
  KFold(folds = 5, shuffle = true)
    .plan(samples = 120, seed = 42L)
    .toOption
    .get
```

## Train / test and analysis / assessment

Every split has two roles:

- `train` / `analysis` — rows used to fit or tune
- `test` / `assessment` — rows held out for scoring

The façade prefers `train`/`test`. The kernel keeps `analysis`/`assessment`
because those terms match the statistical literature Alder and similar
consumers use.

## Selection versus Draw

- `Selection` is an ordered set: no repeats.
- `Draw` may repeat rows and preserves draw order.
- `Injection` preserves order without repeats.
- `Permutation` is a bijection.

Cross-validation training sets are selections. Bootstrap training sets are
draws. Silently converting a draw into unique indices would change the
estimator.

## Exact once versus partial assessment

`Coverage.Exact` means assessments partition the population within each
repeat. `Coverage.ExactOnce` additionally proves the plan has one repeat, so
each row is assessed exactly once over the whole plan.

Public `Plan.map` and `zip` drop to ordinary `Coverage`. Exactness is not a
property of an arbitrary payload. Constructive witnesses live on
`CompleteOnce` / `CompletePerRepeat` and on designs that mint exact partitions.

## Seed stability

Randomized designs use a fixed SplitMix64 generator, unbiased bounded draws,
Fisher–Yates, and ordered domain-separated stream paths. The same design,
population size, and seed produce the same assignments on JVM, Scala.js, and
Scala Native.

Changing an algorithm id, stream path, or descriptor field is a semantic break
even when MiMa reports no binary change. See
[compatibility.md](compatibility.md).

## Grouped and stratified semantics

- Stratified designs allocate within label classes.
- Grouped designs keep whole groups atomic.
- Grouped-stratified designs keep groups atomic while optimizing a stated
  stratum/size objective; balance quality is diagnosed, not typed as a
  guarantee.

For grouped holdout / shuffle-split, `SplitSize.count` is a **group** count and
`SplitSize.percent` is a fraction of **groups**. Plain and stratified paths
interpret sizes relative to rows.

Fold-of-row imports (`PredefinedSplit.fromAssignments`) keep literal fold ids
(`Labels.retained`). Group and stratum factories still use canonical
`Labels.dense` recoding so raw class integers are interchangeable under
bijection.

## Compact backings and iteration

Some selections store complements or label classes instead of full index
arrays. Random access may still scan; ordinary iteration uses a linear cursor
through `foreachIndex` / `iterator`. Prefer those primitives when gathering
rows from large populations.
