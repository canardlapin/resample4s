# Fresh-context release review

Date: 2026-07-26

Reviewed commit: `3dc2d77`

Method: independent, read-only review with no conversation history, against
`PLAN.md`, `PRD.md`, and the applicability-aware Typelevel-grade assurance
checklist

## Verdict at review

Not ready to freeze. The algebra, coverage capabilities, deterministic
randomization, catalogue semantics, and three-platform tests were strong, but
two contract gaps blocked PLAN phase 4 and one public-law claim was incomplete.

## Findings and resolution

1. **Receipt streaming and work accounting — resolved.**
   `CanonicalWriter` buffered every general-plan unit before hashing, and
   delete-d charged only `d` for receipt work. Digest providers now create an
   independent `DigestAccumulator`; design, label, and assignment bytes are
   consumed synchronously as they are generated. Buffered fixture writing is a
   distinct internal type. A regression test asserts provider updates occur
   during every encoder call, provider failure is exercised directly, and
   delete-d charges unranking plus encoding.
2. **Grouped diagnostics and exhaustive oracles — resolved.**
   The former fixtures sampled only a few configurations and repeated
   grouped-stratified plans discarded quality observations. Compilation now
   reports exact `Optimum`/`Regret` on the declared bounded frontier and
   aggregates worst achieved diagnostics across repeats. Independent tests
   exhaust 974 grouped configurations and 4,866 grouped-stratified
   configurations, plus a 65,536-allocation frontier fixture.
3. **Published-law scope — resolved.**
   `tessera-laws` now exposes full label-recoding equivalence across owned
   labels, randomization keys, fingerprints, and compiled assignments. It also
   exposes bootstrap order, multiplicity, and assessment preservation through
   composition. A deliberately non-equivalent recoding fails the public law.
4. **Hosted CI — open external gate.**
   Workflow source is configured, but no Git remote exists and no hosted run
   can yet be inspected.

No defect was found in the coverage lattice, deterministic replay,
grouped/stratified absolute guarantees, OOB preflight, rank/unrank, or
permutation behavior.

## Assurance scorecard after remediation

| Dimension | Rating | Evidence or boundary |
|---|---|---|
| ScalaCheck generators | Strong | Generated valid, boundary, degenerate, and invalid catalogue/algebra domains; exhaustive grouped lattices are separate deterministic oracles. |
| Reusable laws | Strong | Public cross-built laws cover algebra, exact plans, groups, strata, bootstrap composition, permutations, recoding, receipts, costs, and perturbation. |
| Test framework / Discipline | Strong | MUnit and MUnit-ScalaCheck execute on all targets; Discipline is not applicable because Tessera exposes no standard Cats instances. |
| Typeclass coherence | Strong | The closed `Compose` table has one coherent instance per cell and executable laws. |
| Provider conformance | Strong | FNV and a 128-bit provider cover chunk invariance, width, provider identity, verification, and typed incremental failure. |
| Cross-platform CI | Present but incomplete | Local JVM, Scala.js, and Scala Native gates pass; hosted execution remains unavailable. |
| Computational assurance | Strong | Universal laws, exact finite oracles, metamorphic checks, adversarial inputs, calibrated statistics, and work counters are distinct families. |
| Independent oracles | Strong | Rank/unrank, redraw, grouped imbalance, and grouped-stratified objective use independently implemented finite or analytic references. |
| Failure/resource contracts | Strong | Construction, OOB exhaustion, digest failure, and verification mismatch remain typed; valid plans have no late design failure. |
| Work/allocation accounting | Strong | Heap, candidate, preflight, plan-state, materialization, incremental receipt, and delete-d work paths are directly instrumented. |
| Compiler discipline | Strong | Fatal unused/value-discard/non-unit warnings, explicit nulls, and strict equality; no new cast or suppression. |
| Formatting / rewrites | Missing | No Scalafmt/Scalafix gate. This is an optional tooling gap, not a PLAN requirement. |
| Binary/source compatibility | Strong | MiMa/TASTy-MiMa execute for every artifact; the previous set is intentionally empty until the first baseline. |
| Coverage/mutation signal | Missing | No line-coverage or mutation framework. Deliberately broken fixtures prove specific laws bite but are not a coverage metric. |
| Performance evidence | Present but incomplete | Deterministic complexity/work frontiers execute; no JMH or allocation-profiler receipt is claimed. |
| Documentation/release evidence | Present but incomplete | Contracts and local artifacts are verified; hosted CI, final version freeze, and pushed tag remain open. |

Cats, Cats Effect, Discipline, `sbt-typelevel`, coverage, mutation, and
formatting plugins are not added merely to fill checklist rows. Their future
adoption should solve a named risk and remain separate from the v0.1 law and
release gates.

## Verification boundary

The independent reviewer executed the full local test, compatibility,
Scaladoc, and publication gates at `3dc2d77`. The parent agent then reran the
same test scope after remediation and also exercised the isolated artifact
consumer. Hosted Actions, optimized release linking, external publication, and
tag push are not represented as completed evidence.
