# Design decisions

This is the implementation-facing record for Resample4s 0.1. The normative
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
   named `private[resample4s]` unchecked paths accept only buffers and values whose
   invariants Resample4s just established.
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
    retain their standard library partial contracts; Resample4s's domain lookups
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
29. `resample4s-laws` publishes the consumer-relevant universal laws directly.
    Tolerance-bearing redraw, OOB, draw-length, and sampled-rank checks remain
    in the statistical test suite and are not promoted to laws.
30. Digest providers expose one incremental accumulator per invocation.
    Assignment, design, and label canonical bytes are pushed synchronously;
    buffered canonical writers remain a separate internal type used only for
    byte fixtures. Provider failure is a typed `DigestError`.
31. Exact grouped quality diagnostics have a declared bounded frontier:
    `n <= 32` and `k^g <= 100000`. Within it, compilation records exact
    `Optimum` and additive `Regret`; outside it, those metrics are absent.
    Repeated designs retain worst-case quality instead of discarding all
    diagnostics except the repeat count.
32. The small grouped oracle is a real exhaustive lattice, not a handful of
    examples: grouped configurations cover every canonical label partition
    through `n = 6`, and grouped-stratified covers the cross-product of every
    group/stratum partition through `n = 5`, for every legal `k <= 3`.
    Published laws separately cover full label-recoding identity and bootstrap
    sequence/multiplicity preservation through composition.
33. Cross-language benchmarks compare one canonical public artifact. Every
    runner receives the same deterministic fixture, proves the same
    family-specific contract, materializes increasing non-bootstrap roles, and
    performs one linear reduction inside the timer. Different RNGs and
    heuristics may choose different legal assignments. Grouped-stratified
    quality is reported beside time, and rsample's public data-frame workflow
    is labeled separately from scikit-learn/splitTools index kernels.
34. Performance kernels may exploit representation-transparent equivalences,
    but cannot redefine seeded behavior. `Int` rejection implements the same
    unsigned threshold/modulo rule without `BigInt`; shuffle-split stops when
    the named prefix set is fixed and emits its two sorted roles by membership
    scan. BigInt-oracle, complete-shuffle differential, golden-fixture, and
    semantic-parity tests jointly guard the change.
35. Public convenience APIs are admitted only as exact expansions of the core
    algebra. Bootstrap policy is named at the call site; no bare constructor
    silently selects redraw. Descriptor, no-label definition, general-plan, and
    label-size conveniences remove only proof already carried by validated
    values. Expansion tests compare keys, errors, assignments, cost,
    diagnostics, fingerprints, and receipts, while compiler probes keep
    capability failures expressed in domain terms.
36. Resample4s is the final pre-publication name. The package namespace,
    artifact ids, canonical receipt framing, benchmark protocol, and repository
    move together. No compatibility alias preserves the discarded `tessera`
    working name because no external artifact or consumer namespace exists.
37. Nested cross-validation is a built-in, data-blind design rather than a
    test-only recipe. The convenience constructors expand into the existing
    K-fold allocators and selection composition. The top-level plan preserves
    outer `ExactOnce`; every `NestedFold` contains an embedded inner
    `ExactOnce` plan plus its derived seed, diagnostics, and cost. Compilation
    performs every fallible inner build up front, and the normal design
    fingerprint and receipt cover the complete nested allocation. Fitting,
    tuning, predictions, and scores remain consumer responsibilities.

## Evidence policy

Universal statements belong in `resample4s-laws`. Small finite algorithms also
receive exhaustive oracles. Distributional statements use fixed seed sets,
predeclared confidence thresholds, and exact finite-sample expectations. Cost
tests distinguish resident state, unit generation, candidate preflight, eager
materialization, and receipt traversal. A snapshot may lock compatibility but
cannot establish correctness. Benchmark ratios are machine-specific evidence;
they do not replace laws or asymptotic cost guardrails.

## Open release gate

The implementation-context type-discipline review is recorded under
`docs/reviews/`. The user-authorized fresh-context review examined commit
`3dc2d77` and found receipt-streaming/cost, exhaustive-oracle/diagnostic, and
published-law gaps. Decisions 30–32 are the remediation; the independent
review is not self-approved or waived. The public-usability reconciliation is
recorded in
`docs/reviews/scala-type-discipline-usability-2026-07-26.md`. Hosted CI still
cannot be green until the repository has a remote, so no stable release tag
may yet be created.
