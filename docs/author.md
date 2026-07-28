# Author guide

Use this when writing a new design that must recompile, fingerprint, and emit
receipts like the catalogue.

## Two extension routes

### Route A — validated plans

If your algorithm already produces fold assignments, train/test selections,
draws, or permutations, construct a plan directly:

- `SplitPlans.fromAssignments` / `fromRepeatedAssignments`
- `SplitPlans.validate`
- `FixedSplits` / `FixedPartitions` / façade `PredefinedSplit`

This path validates dimensions and coverage, then mints the strongest sound
capability. Prefer it when you do not need domain-separated RNG inside
Resample4s.

### Route B — auditable `Design`

Implement `Design` by owning a `DesignDefinition`:

1. Build a versioned `DesignDescriptor` (`algorithm/vN` plus typed fields).
2. Choose `DesignDefinition.general`, `exactPartitions`, or
   `exactOncePartitions`.
3. Derive child seeds only through `BuildContext.derive` and ordered
   `StreamPath`s.
4. Supply a `CanonicalAssignmentEncoder` for receipt bytes.
5. Declare honest `PlanCost` upper bounds.

Exact coverage cannot be asserted from an arbitrary generator. Only the exact
partition routes mint `Coverage.Exact` / `ExactOnce`.

## Open identifiers

External authors may mint:

- `ErrorCode` / custom `DesignError` values
- `MetricId` diagnostics
- `StreamDomain.custom(tag ≥ 100)` or `StreamTag` strings

Built-in stream tags 1–8 stay fixed for seed compatibility.

## Descriptors and algorithm identity

The descriptor is part of the randomization key and the receipt. Changing a
field name, algorithm id, or included label set changes assignments and fails
receipt verification. Treat algorithm ids as semvered contracts
(`kfold/v1`, `holdout-stratified/v1`).

## Stream domains

Separate every independent RNG use:

- repeats, units, strata, group-size buckets, fold priority
- exchangeability blocks, redraw attempts, outer nested units
- custom tags for vendor-specific substreams

Never reuse a parent seed for two semantically different draws without a path
segment.

## Cost laws

Compilation must preflight fallible policy (for example OOB redraw) so a
returned plan has no late design failure. Cost bounds must dominate resident
elements, per-unit work, and receipt work. Guardrail suites treat under-stated
costs as defects.

## Diagnostics

Report best-effort quality with `MetricId` values. Do not encode soft balance
claims as type capabilities.

## Testing expectations

Authors should provide:

- golden fixtures when seed-to-assignment must lock
- property or oracle checks for the family invariants
- SPI openness tests that compile from an external package name

See `docs/compatibility.md` for what counts as a semantic break.
