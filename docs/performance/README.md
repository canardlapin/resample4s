# Performance

Resample4s treats allocation-path performance as ongoing work, not a one-time
release checkbox.

## Local gates

```bash
sbt -batch benchmarkCheck
```

That runs the JVM protocol suite and a smoke profile of Resample4s-only cases.
It is part of the in-repo freeze gate and should stay green on every kernel
change that touches RNG, shuffle-split, or reindexing traversal.

## Cross-language evidence

Full semantic-parity comparisons (Resample4s vs scikit-learn / rsample /
splitTools) live under `benchmarks/` and checked-in result directories under
`benchmarks/results/`. Refresh a standard profile when:

- a normative generator changes (even if goldens are regenerated);
- a hot path is optimized (record before/after on the same machine);
- compact-selection traversal or `pull` / `foreachIndex` contracts change.

Do not mix machines when claiming ratios. Each report states its boundaries.

## Written findings

- [Monte Carlo kernel (2026-07-26)](monte-carlo-2026-07-26.md) — partial
  Fisher–Yates + primitive unsigned rejection; JFR and median evidence.

## Optimization posture

Prefer exact-equivalent kernels with differential oracles over approximate
speedups. Preserve seed-to-assignment locks when changing generators; if that
is impossible, mint a new algorithm id (see `compatibility.md`).
