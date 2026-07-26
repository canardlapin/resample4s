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
equality. `sbt -batch testAll` passes 32 core, 32 designs, and 5 laws tests on
each of JVM, Scala.js, and Scala Native. The suite includes capability-negative
compilation, aliasing, algebraic laws, exact oracles, statistical calibration,
golden compatibility locks, and cost guardrails.

## Independent-review gate

This pass applied the type-discipline checklist in the implementation context.
It is not the PLAN phase-4 fresh-context independent review. Current
orchestration rules prohibit delegation unless the user explicitly requests
it, so that separate gate remains open and is not waived. The stable release
tag must not be created until it is completed or explicitly waived.
