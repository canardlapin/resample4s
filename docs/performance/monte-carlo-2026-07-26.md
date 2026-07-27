# Monte Carlo kernel optimization

Date: 2026-07-26

This change optimizes the seeded shuffle-split operation used by `Holdout` and
`MonteCarlo`. It also improves every design that uses bounded `Int` draws.
Public assignments, stream separation, and canonical ordering do not change.

## Benchmark contract

The standard Monte Carlo case uses 100,000 rows and 100 independent units. Each
unit returns an increasing 80,000-row analysis selection and an increasing
20,000-row assessment selection. The timed operation compiles the design,
generates and materializes every role ordinal, and performs one linear
reduction. Contract validation runs before timing.

The comparison rejects a result unless every library agrees on the fixture,
configuration, unit count, and role sizes. Random assignments may differ across
libraries. See [`../../benchmarks/README.md`](../../benchmarks/README.md) for
the complete cross-language protocol.

## Profile findings

Java Flight Recorder sampled the standard Scala profile before the change.
`BigInteger` addition and one-word division accounted for 32.68% of execution
samples. The two main integer-sort kernels accounted for another 22.26%.
Allocation samples were likewise dominated by the `BigInt` rejection path.

The baseline profiled Monte Carlo median was 972.715 ms. The checked-in
pre-change standard evidence recorded 1,248.301 ms. These values are separate
runs on the same machine, so they establish the scale of the problem rather
than one interchangeable baseline.

## Exact-equivalent kernels

Bounded `Int` draws now compute the same rejection threshold and remainder with
unsigned 64-bit operations. A literal `BigInt` implementation remains in the
test suite as the differential oracle. It checks edge bounds, multiple seeds,
and successive draws, so it also detects incorrect rejection-state advances.

Shuffle-split still uses the first `q` members of the descending Fisher–Yates
shuffle. Once the loop has processed position `q`, later swaps only permute
that prefix. Because a `Selection` is increasing, those swaps cannot change the
public result. A membership scan emits the named role and its complement
directly in increasing order.

An exhaustive small differential test compares this shortcut with the complete
shuffle-and-sort definition for every `2 <= n <= 48`, every
`1 <= q < n`, and five adversarial seeds. Existing golden fixtures separately
lock the seed-to-assignment mapping on JVM, Scala.js, and Scala Native. The
later pre-publication rename changed the canonical design framing from the
discarded working name to `resample4s`; that identity change intentionally
created new design keys, and the regenerated goldens lock the new protocol.

## Result

The refreshed standard run records the final result in
[`../../benchmarks/results/2026-07-26-standard/report.md`](../../benchmarks/results/2026-07-26-standard/report.md).
The checked-in pre-change Resample4s median was 1,248.301 ms. The original
post-optimization run recorded 82.417 ms. After the canonical name cutover, the
same standard profile recorded 94.025 ms, a 13.28x reduction from the
pre-change evidence on this machine. In that current run, scikit-learn recorded
290.519 ms and rsample recorded 2,178.829 ms for the matching artifact
contract.

The primitive bounded-draw kernel also reduced Resample4s's bootstrap median
from 272.565 ms to 110.390 ms in the current run. That is a shared-kernel
consequence, not a change to bootstrap sampling or OOB policy.

Interpret its ratios as directional evidence for this machine, not as a
universal performance guarantee. The deterministic complexity and
differential tests remain the release guardrails.
