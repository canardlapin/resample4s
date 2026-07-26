# Scala type-discipline review

Date: 2026-07-25
Scope: all public and package-private Scala sources in core, designs, and laws

## Verdict

Clean after fixes. The implementation does not erase coverage evidence, use
unchecked variance, suppress warnings, stuff closed concepts into strings, or
expose mutable array ownership.

## Findings resolved

1. The first design compiler recovered generic coverage with an
   `asInstanceOf`. It was replaced by a typed compilation closure owned by
   `DesignDefinition`.
2. `PlanDiagnostics` initially accepted string keys. `DiagnosticMetric` is now
   a closed ADT, so misspelled quality claims cannot enter a receipt or test.
3. A definition initially owned one optional label vector. It now owns a
   defensively copied sequence, allowing grouped-stratified designs to commit
   both groups and strata.
4. Integration showed that per-repeat `Coverage.Exact` was too weak for
   Alder's one-OOF-value capability. `Coverage.ExactOnce` now carries that
   stronger proof, and `.repeat` drops it statically.
5. Internal partial operations are centralized in named
   `private[tessera]` paths whose invariants are established immediately
   beforehand. Public domain lookup remains total through `Either`.

## Surface reconciliation

- Public mutable-array inputs are copied before storage.
- Frozen-surface records are final classes with accessors, not case classes.
- General consumer designs cannot mint either exact capability.
- `exactPartitions` mints per-repeat `Exact`; `exactOncePartitions` additionally
  checks for one repeat before minting `ExactOnce`.
- `map` preserves the capability; `zip` widens; repeated catalogue designs
  expose only per-repeat `Exact`.
- Error paths remain typed. Built-in invariant failures are package-private and
  named; no warning suppression or erased cast remains.

## Evidence

The strict build uses `-Werror`, `-Wunused:all`, explicit nulls, and strict
equality. `sbt -batch testAll` passes 32 core, 41 designs, and 8 laws tests on
each of JVM, Scala.js, and Scala Native: 243 tests in total. The suite includes
capability-negative compilation, aliasing, algebraic laws, exact oracles,
statistical calibration, golden compatibility locks, and cost guardrails.

## Post-audit recheck — 2026-07-26

Verdict: clean after two precision fixes.

- `Selection.widen` now completes the explicit typed widening path promised by
  the reindexing lattice; it returns `Injection`, never a loose supertype.
- `UnitKey` and `PlanShape`, the two intentionally structural case classes,
  derive `CanEqual` explicitly under strict equality.
- A temporary `-1` fallback in a published bootstrap law was replaced with
  total `Either` matching.
- `BootstrapWorkObserver` and `FoldLoadQueue` remain `private[designs]`.
  Observer mutation exists only in tests; catalogue designs use the no-op
  observer, and heap mutation is confined to eager compilation.
- No cast, unchecked variance, warning suppression, stringly ADT, mutable public
  array, or weakened coverage type was introduced.

Should-have-changed audit: every new law API has an executing catalogue fixture,
the production grouped allocator is measured rather than only its heap helper,
all selection and partition backings reach the receipt-encoding test, and no
stale match over a changed public ADT was found.

## Independent-review closure — 2026-07-26

The user authorized a distinct fresh-context review of commit `3dc2d77`; its
report is `fresh-context-2026-07-26.md`. The remediation diff was rechecked
against this type-discipline checklist:

- incremental digest state is a named per-invocation capability with typed
  provider failure, not a global mutable cache;
- buffered and streaming canonical writers are distinct internal types;
- exact diagnostic absence outside the bounded oracle frontier remains
  `Option`, with no sentinel or fabricated zero regret;
- repeated diagnostics retain typed metrics rather than stuffing a summary
  string;
- the public recoding and bootstrap-composition laws preserve their precise
  `Design`, `Labels`, `Selection`, and `Draw` types.

No cast, unchecked variance, warning suppression, stringly error, partial
public accessor, weakened coverage capability, or stale digest-provider
implementation was introduced. The independent-review gate is closed; hosted
CI remains the release blocker.
