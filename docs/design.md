# Design decisions

This is the implementation-facing record for Tessera 0.1. The normative
requirements remain in `PRD.md`; this file records the decisions reviewers and
maintainers need when changing code.

## Core commitments

1. A receipt verifies a caller-supplied design and population; it never stores
   enough payload to reconstruct them.
2. Exact assessment coverage is a type capability. `Coverage.Exact` means once
   per repeat; `Coverage.ExactOnce` additionally proves one repeat. Only
   core-derived partition definitions can create either proof.
3. Plans are lazy, immutable generators. Eager retention is explicit through
   `materialized`; there is no mutable memoization.
4. Exhaustive and sampled delete-d are distinct constructors. Exhaustive
   enumeration has a typed, exact unit-budget check.
5. Group atomicity and plain-stratified floor/ceiling balance are laws.
   Grouped and grouped-stratified balance are diagnosed best-effort properties.
6. `Injection` closes the reindexing composition lattice and uniquely factors
   into a `Selection` followed by a `Permutation`.
7. Public accessors are total. Public array-taking factories copy; explicitly
   named `private[tessera]` unchecked paths accept only buffers and values whose
   invariants Tessera just established.
8. `Design`, `Labels`, compilation, randomization keys, canonical assignment
   framing, costs, and receipts are core-owned.
9. Alder integration gates the stable `0.1.0` surface. Without it, only an
   explicitly unfrozen `0.1.0-M1` may be tagged.
10. Rolling-origin designs are post-0.1.

## Randomization and allocation

11. Statistical sensitivity checks are not universal laws. Golden outputs are
    compatibility locks, never sole correctness evidence.
12. Fractions use reduced integer rationals and round-half-up. Holdout names its
    selected role. Bootstrap OOB behavior is an explicit policy.
13. Audit digests are an open capability with validated provider ids and owned,
    arbitrary-length byte values.
14. Fallible OOB policy work occurs during compilation; a valid lazy plan has no
    late design failure.
15. Label codes are canonicalized by minimum member ordinal before descriptor
    bytes or streams are derived. Equal-size group order and fold ties receive
    distinct ordered stream domains.
16. Grouped-stratified allocation compares the exact `BigInt` increment of the
    stated global objective.
17. `Selection` and `FoldPartition` backings are representation details.
    Equality, hash, and assignment encoding are extensional.
18. `PlanShape` validates both axes and their product. `keys` is a mixed-radix
    constant-state view.
19. Grouped bootstrap draws exactly the number of canonical groups, with
    replacement, and emits every chosen group in ascending member order.
20. Consumer designs use a closed descriptor grammar plus either a general plan
    or exact partitions; arbitrary generators cannot mint exact coverage.
21. Every built-in family owns one normative generator and stream-path scheme.

## Implementation clarifications

22. `PlanDiagnostics` uses the closed `DiagnosticMetric` ADT. String-keyed
    metrics were rejected during the type-discipline review because they would
    weaken observable quality claims into an untyped bag.
23. A definition may own a defensively copied sequence of `Labels`. The
    single-label factories remain the common API; multi-label grouped-stratified
    designs commit groups and strata together in their randomization key,
    design fingerprint, and one framed labels fingerprint.
24. The design route stores a typed compilation closure. No cast or
    `@uncheckedVariance` is used to recover `Coverage.Exact`.
25. Internal unchecked canonical-writing helpers are centralized and named.
    They accept only hard-coded schema identifiers or values already validated
    by public smart constructors. Iterator `next` and indexed-sequence `apply`
    retain their standard library partial contracts; Tessera's domain lookups
    remain `Either`-returning.
26. The Alder integration spike separated `ExactOnce` from per-repeat `Exact`.
    Repeating a partitioning design drops the stronger proof, and Alder accepts
    only `ExactOnce`; otherwise repeated K-fold could unsoundly produce multiple
    OOF values per row while satisfying the old type.
27. Grouped LPT allocation selects the least-loaded fold through a
    seeded-priority min-heap. This preserves the normative tie-break while
    satisfying the O(g log k) compilation contract; a production-path
    comparison counter guards the bound.
28. Bootstrap preflight and grouped-fold work observers are package-private
    test capabilities. Default catalogue compilation captures only no-op
    observers, so no mutable instrumentation enters the public design or plan
    surface.
29. `tessera-laws` publishes the consumer-relevant universal laws directly.
    Tolerance-bearing redraw, OOB, draw-length, and sampled-rank checks remain
    in the statistical test suite and are not promoted to laws.

## Evidence policy

Universal statements belong in `tessera-laws`. Small finite algorithms also
receive exhaustive oracles. Distributional statements use fixed seed sets,
predeclared confidence thresholds, and exact finite-sample expectations. Cost
tests distinguish resident state, unit generation, candidate preflight, eager
materialization, and receipt traversal. A snapshot may lock compatibility but
cannot establish correctness.

## Open release gate

The implementation-context type-discipline review is recorded under
`docs/reviews/`. The distinct fresh-context independent review required by PLAN
phase 4 remains open because the current orchestration policy does not permit
delegation without explicit user authorization. It has not been self-approved
or silently waived. Hosted CI also cannot be green until the repository has a
remote. No stable release tag may be created while either gate is open.
