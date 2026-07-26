# Cross-language benchmarks

These benchmarks compare Tessera with scikit-learn, rsample, and splitTools.
Every timed cell first passes the same semantic contract outside the timer.
The benchmark then generates, canonicalizes, materializes, and consumes the
analysis and assessment row ordinals.

The comparison allows different random-number generators, allocation
heuristics, and internal data structures. It requires the same observable
resampling artifact.

## Comparable contracts

| Family | Required artifact |
|---|---|
| K-fold and LOO | The assessment sets partition every row exactly once per repeat. Each analysis set is the increasing complement of its assessment set. |
| Stratified K-fold | The exact-partition contract plus observed per-stratum fold deviation. |
| Grouped K-fold | The exact-partition contract plus group atomicity. |
| Grouped-stratified K-fold | Exact partitioning, group atomicity, and the common integer allocation objective recorded as primary quality. |
| Monte Carlo | The requested assessment size, increasing disjoint roles, and complete analysis/assessment coverage in every unit. |
| Bootstrap | An ordered, length-*n* draw with replacement and an increasing OOB complement. |

Tessera uses `OobPolicy.Allow` in these benchmarks. This matches rsample's
unconditional bootstrap distribution. Tessera's named `redrawing` route would
be a different statistical operation.

Non-bootstrap comparator outputs are sorted inside the timed region. This is
necessary because Tessera's `Selection` promises increasing ordinals, while
some comparator APIs return an unordered index set.

## Comparator coverage

- scikit-learn covers K-fold, repeated K-fold, stratified, grouped,
  grouped-stratified, Monte Carlo, and LOO. It has no bootstrap CV splitter with
  Tessera's draw-plus-OOB artifact, so no Python bootstrap ratio is reported.
- splitTools covers basic, repeated, stratified, grouped, and LOO index
  generation. It is the closest R index-kernel comparator.
- rsample covers every benchmark family through its public data-frame API.
  Timings therefore include construction and extraction of its public split
  objects. They are not presented as low-level index-kernel timings.

Repeated grouped cases are not sent to scikit-learn because `GroupKFold` does
not define repeated independent group partitions. Grouped-stratified fixtures
assign one stratum to each whole group, so rsample, scikit-learn, and Tessera
receive the same grouping and stratification problem.

## Profiles

`cases.csv` defines three profiles:

- `smoke`: small contract and harness verification.
- `standard`: representative workloads used for checked-in directional
  evidence.
- `stress`: opt-in scaling workloads. Do not run it in ordinary pull-request
  CI.

Inputs are deterministic formulas rather than language-specific random
generators. `fixture_checksum` must match across every library for a case.

## Environment setup

Python dependencies are locked by `python/uv.lock`:

```bash
cd benchmarks/python
uv sync --frozen
```

R dependencies are locked by `r/renv.lock`. Automatic renv activation is
disabled because it can contend with unrelated long-lived R sessions. Restore
the environment once; the benchmark runner adds the restored project library
directly:

```bash
cd benchmarks/r
R_PROFILE_USER=/dev/null Rscript -e 'renv::restore(project = ".", prompt = FALSE)'
```

## Running

Run the smoke contracts:

```bash
sbt -batch benchmarks/test
cd benchmarks/python && uv run --frozen python -m unittest -v test_protocol.py
cd ../r && R_PROFILE_USER=/dev/null Rscript test_protocol.R
```

Run the complete smoke comparison with one warmup and five measurements:

```bash
./benchmarks/run.sh smoke 1 5 benchmarks/results/smoke-local
```

Run the standard profile with two warmups and seven measurements:

```bash
./benchmarks/run.sh standard 2 7 benchmarks/results/standard-local
```

Each run writes raw CSV files, an aggregate CSV, a Markdown report, and an
environment manifest. The timer excludes process startup, dependency loading,
contract validation, model fitting, scoring, and feature-data copying.

## Reading results

`relative_to_tessera` is the comparator median divided by the Tessera median.
A value above one means the comparator took longer on that machine and run.
It is not a universal speed claim.

Read grouped-stratified timings together with `quality_primary`. Two algorithms
that satisfy group atomicity may produce allocations of different quality.
`quality_primary` is the shared squared allocation-error objective, so lower
values indicate closer joint fold-size and stratum balance.
Bootstrap OOB counts can differ because the libraries use different random
streams; the draw length and OOB-complement contract still match.

Use repeated runs on an otherwise idle machine before making a performance
decision. Checked-in results are provenance-bearing directional evidence, not a
release guarantee or a substitute for Tessera's deterministic complexity
guardrails.

The profile, exact-equivalence argument, and differential evidence for the
Monte Carlo kernel are recorded in
[`../docs/performance/monte-carlo-2026-07-26.md`](../docs/performance/monte-carlo-2026-07-26.md).
