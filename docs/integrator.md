# Integrator guide

This guide is for libraries that consume Resample4s plans: dataframe adapters,
model frameworks, neuroimaging pipelines, and similar.

## Depend on the façade

```scala
libraryDependencies +=
  "io.github.canardlapin" %%% "resample4s" % "0.1.0-SNAPSHOT"
```

Specialist modules remain available as `resample4s-core`, `resample4s-designs`,
and `resample4s-laws`.

## Consume a SplitPlan

```scala
import resample4s.*

val result =
  KFold(folds = 5, shuffle = true).plan(samples = n, seed = seed)

result.foreach { plan =>
  plan.foreach { split =>
    gather(split.train)  // Selection
    score(split.test)    // Selection
  }
}
```

Bootstrap yields `Draw` training rows:

```scala
Bootstrap.unconditional(1000).plan(samples = n, seed = seed).foreach { plan =>
  plan.foreach { split =>
    val train: Draw = split.train
    val test: Selection = split.test
  }
}
```

## Efficient ordinal traversal

Avoid materializing every selection unless you need an array. Use:

```scala
split.train.foreachIndex { row =>
  // touch one ordinal
}
```

`pullFrom` gathers from `IArray`, `Array`, or `IndexedSeq` when that is the
natural host representation. Compact complement backings are linear under
cursor traversal; do not loop `unsafeAt` yourself.

## Complete-plan capabilities

When a consumer needs one out-of-fold value per row, accept
`CompleteOnce` or `Plan[Split[Selection], Coverage.ExactOnce]` from catalogue
designs that mint that capability (one-repeat K-fold, LOO, fixed partitions,
nested outer/inner plans).

Do not recover exactness from an ordinary `Plan[A, Coverage]` by casting.
Public `map`/`zip` deliberately forget coverage.

Validated imports:

```scala
PredefinedSplit.fromAssignments(foldOfRow)
CompleteOnce.fromAssignments(...) // via kernel / SplitPlans
```

## Ordinals as row ids

Resample4s never sees your row identity type. Map `0 until n` onto your stable
row ids before gathering. Population fingerprints in receipts are caller-owned:
supply a digest of the ordered population you actually bound.

## Receipts

`PlanReceipt` verifies recompilation. It does not reconstruct a design. Retain
the design, labels, population fingerprint, seed, and digest algorithm id
beside the receipt. Built-in FNV-1a-64 is a checksum only.

## Import rings

| Import | Audience |
| --- | --- |
| `resample4s.*` | Ordinary users and most integrators |
| `resample4s.kernel.*` | Reindexing algebra, schedules, labels |
| `resample4s.spi.*` | Design authoring |
| `resample4s.audit.*` | Digests and receipts |
