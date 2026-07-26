# Scala type-discipline usability review

Date: 2026-07-26
Scope: public construction APIs, consumer SPI, composition diagnostics, and
quick-start documentation

## Verdict

Clean after resolving one policy-visibility finding and four ergonomic
findings. The revised surface makes behavior-changing choices visible while
removing proof and validation steps that could not fail. It does not weaken
coverage evidence, typed error channels, ownership, or immutable public
representations.

## Findings resolved

1. A bare `Bootstrap(times)` silently selected bounded redraw behavior.
   Callers now choose `unconditional`, `redrawing`, or `failOnEmptyOob`, or
   pass an `OobPolicy` to the general constructor. `GroupedBootstrap` exposes
   the same policy vocabulary.
2. The README's primary nested-cross-validation example erased typed errors
   with `.toOption.get`. It now retains
   `Either[DesignError | DomainMismatch, Selection]` end to end.
3. External design authors had to spell an empty label option and unwrap an
   `Either` that `GeneralPlanSpec` could never fail to construct. No-label
   `DesignDefinition` overloads and a total `GeneralPlanSpec.apply` remove
   that bookkeeping.
4. Common immutable inputs required callers to repeat facts already present
   in the values. `Labels.dense(codes)`, `Labels.of(codes, cardinality)`, and
   `DesignDescriptor.named` provide exact delegating conveniences.
5. Abstract `Reindexing` composition failed correctly but with an incidental
   implicit-search message. `Compose` now supplies a stable diagnostic that
   tells callers which precise operand types preserve the result.

## Surface reconciliation

- Named Bootstrap routes expand to the explicit-policy route; they do not
  introduce a second implementation.
- Bootstrap expansion tests compare randomization keys, compile failures,
  assignments, cost, diagnostics, fingerprints, and receipts for ordinary and
  grouped designs.
- The external-consumer fixture uses only public APIs, has private
  constructors, and needs neither package-private access nor unsafe extraction.
- Convenience constructors delegate to the existing validated constructors,
  preserving defensive copies and typed validation failures.
- `GeneralPlanSpec.apply` is total only because all its inputs are already
  validated values and its function fields have no construction invariant.
- Coverage-negative compilation still prevents a general consumer design from
  minting `Coverage.Exact` or `Coverage.ExactOnce`.
- The README retains the typed channel, and its linked complete example is
  compiled as a cross-platform unmanaged test source.

## Should-have-changed audit

All built-in general designs use the no-label overload where appropriate. The
old `GeneralPlanSpec.of` and bare Bootstrap policy default are absent. Grouped
and ordinary Bootstrap expose symmetric presets, and all presets have
equivalence coverage. Label call sites use inferred size where the size is not
an independent test variable. The concrete `Selection`, `Permutation`,
`Injection`, and `Draw` composition paths remain unchanged.

## Evidence

The strict build passes 35 core, 48 designs, and 8 laws tests on each of JVM,
Scala.js, and Scala Native: 273 tests in total. The new tests cover exact preset
expansion, stable abstract-composition diagnostics, public-SPI construction,
convenience-constructor equivalence, and both exact coverage compiler errors.

The benchmark protocol tests, MiMa/TASTy-MiMa tasks, JVM Scaladoc, and local
publication for all nine platform artifacts also pass. Scala Native reports
only the repository's known old-clang and unsupported-Scaladoc-plugin
capability warnings.
