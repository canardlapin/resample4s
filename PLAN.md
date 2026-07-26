# Tessera — Execution Plan

Companion to `PRD.md` (v0.9). Phased; every phase ends with a verification gate (evidence, not assertion). Sized for solo development in the alder/gale house style. Alder is under concurrent development by another agent — nothing here touches the alder repo until phase 5, and phase 5 begins with a fresh sync of alder's state.

Revised 2026-07-26 after three independent PRD review passes, the implementation type-discipline pass, Alder integration, the phase-4 fresh-context assurance review, the semantic-parity Python/R benchmark tranche, and exact-equivalent Monte Carlo/RNG kernel profiling (PRD §12 D24–D28).

## Phase 0 — Bootstrap (small)

- `git init`; move `note*.txt` into `docs/notes/`; keep `PRD.md`/`PLAN.md` at root.
- sbt build cloned from alder's settings: Scala 3.3.8, sbt 1.11.x, `crossProject(JVM, JS, Native)` `CrossType.Pure`, strict flags with `-Werror`, org `io.github.canardlapin`, Apache-2.0.
- Modules: `core`, `designs`, `laws` (+ root aggregate, publish-skipped). munit + munit-scalacheck test deps.
- `compileAll` / `testAll` aliases; a trivial cross-platform smoke test per module.

**Gate:** `sbt testAll` green on JVM, JS, and Native locally.

## Phase 1 — Core algebra, RNG, and the design protocol (the heart; do this carefully)

This phase is larger than v0.1's phase 1 by design: `Design` and `Labels` are core types (PRD §7), and putting them here is what makes phases 2 and 3 actually independent.

- **Reindexing lattice.** `IndexSpace` (opaque, validated); `Draw` / `Injection` / `Selection` / `Permutation` with private constructors, validated factories, **defensive copies** on every public factory, `private[tessera] fromOwned` for internal buffers. Total public accessors (`at: Either[OutOfDomain, Int]`, `toIArray`), `private[tessera] unsafeAt`.
- **Composition.** `Compose[F, G] { type Out }` typeclass with instances exactly matching the PRD §4.2 closure table; runtime dimension check returning `Either[DomainMismatch, Out]`. `Injection.factor: (Selection, Permutation)`. Explicit `widen` conversions, no implicits.
- **Set algebra and pullback.** `Selection` complement/intersection/union/difference (codomain-checked, `Either`-returning); `pull(x, ρ)`; `Draw.multiplicity`/`support`/`sameMultiset`; `Permutation` group ops.
- **Selection backings.** `Explicit`, `Block`, `ComplementBlock`, `LabelClasses(labels, classSet)`, and `ComplementOf(base)` behind one extensional type. Equality/hash/digest ignore the backing; double complement returns the base. This makes LOO O(1), delete-*d* analysis O(d), and grouped-bootstrap OOB O(g) view state rather than eagerly expanding rows (PRD §4.2/§4.8).
- **Plans.** Extensional `FoldPartition` with O(n) explicit assignments and O(1) `SingletonIdentity` backing for LOO; `Split[+A <: Reindexing]` smart constructor (disjointness + non-empty analysis); freely constructible lookup `UnitKey` plus private-constructor `PlanShape.of` validating positive axes and an `Int`-sized product; `Coverage`/`Coverage.Exact`; **lazy** `Plan[+A, +Cov <: Coverage]` with total `at`, O(1)-state `keys`/`iterator`, `map`/`zip`, explicit `materialize`, and eager immutable `materialized`. No hidden mutable memoization.
- **RNG.** `Seed`, fixed internal `DesignKey`, splitmix64 `Rand`, Fisher–Yates, rejection-sampled bounded ints, uniform `nextBigIntBounded` ranks, and domain-separated `StreamPath` child derivation with sequential framed mixing. Cover repeat/unit/stratum/group-size-bucket/fold-priority/exchangeability-block/redraw-attempt domains; never XOR an unordered component tuple. `DesignKey` is derived from canonical design bytes and is deliberately separate from policy-selectable receipt digests. No floating point.
- **Design protocol.** `Design[+A, +Cov]` has the single extension member `definition`; core owns `randomizationKey`, compilation, audit fingerprints, and receipts. Implement the owned canonical `DescriptorValue` grammar/`DesignDescriptor`, `DesignDefinition.general` → `Coverage`, `DesignDefinition.exactPartitions` → core-certified `Coverage.Exact`, validated `GeneralPlanSpec`/`ExactPartitionSpec`, `PlanCost`, `CanonicalAssignmentEncoder`, and `Compiled[A, Cov]` (plan + `PlanDiagnostics` + explicit streaming `receipt`). Add `Labels` (validated length/range/cardinality, every code present, defensive copy, canonical recoding by ascending minimum member ordinal); `Fraction` (exact rational, integer round-half-up); the full `DesignError`/`DigestError`/`FingerprintError`/`ReceiptError` ADTs.
- **Audit.** Open `DigestAlgorithm` capability, validated `DigestAlgorithmId`, arbitrary-length owned `DigestValue`, built-in `DigestAlgorithm.fnv1a64`, policy-tagged `Fingerprint`, strongly typed `ContentDigest` receipt fields, versioned/framed canonical bytes, and `PlanReceipt.verify(design, space, population)(using algorithm)`. Add a deterministic test-only 128-bit provider to prove that the extension path is real rather than FNV-shaped. Scaladoc states plainly that FNV-1a is a non-adversarial checksum and that a cryptographic digest still requires trusted storage or a signature for authentication (PRD §4.7).
- **Lazy failure timing.** Bootstrap compilation preflights `OobPolicy.Fail`/`Redraw`, stores only accepted child seeds, and exposes no late `DesignError` through `Plan.at`. `Compiled.receipt` remains a separate canonical streaming traversal; compilation never computes an assignment digest implicitly.
- **Laws** 1, 3, 8, 13, 14, and the general-design half of law 15 plus the RNG/determinism half of law 4; **golden fixtures** for pinned `(seed, n)` pairs on all three platforms.

**Gate:** law suite + golden fixtures green ×3 platforms; a deliberately-broken constructor mutation fails the laws (sanity that laws bite); `-Werror` clean; stream-path fixtures prove domain tags and component order affect derivation while exact paths replay ×3 platforms; aliasing tests prove public factories, descriptor sequences, labels, and `DigestValue` copy their source arrays; explicit versus `SingletonIdentity` partitions and `Explicit`/`Block`/`ComplementBlock`/`LabelClasses`/`ComplementOf` selections with the same ordinals compare, hash, and encode identically, and specialized double complement returns its base; obtaining `keys` for a million-unit shape stays within a fixed allocation budget; `materialized` performs one eager traversal and does not change subsequent source-plan behavior; a test-only consumer implements both public design routes without package-private access, while a compile-time negative test proves `general` cannot produce `Coverage.Exact`; deliberately broken custom unit generators and encoders fail law 15, and an underdeclared `PlanCost` fails counted output/encoding guardrails; canonical-byte fixtures match across platforms before hashing; changing iterator chunk boundaries leaves digest bytes unchanged; switching between FNV and the test 128-bit audit provider changes receipt digest bytes but not `DesignKey`, compiled assignments, or plan digests under a fixed provider; the 128-bit value survives receipt construction/verification without truncation; provider-id and population-fingerprint mismatches report the correct `ReceiptError`; changing only the receipt seed reports assignment mismatch on a fixture whose allocation changes, while LOO and a colliding-assignment fixture still verify unless the consumer separately checks an expected seed.

## Phase 2 — KFold family + repeats

- `Holdout.assessing/analyzing`, `MonteCarlo.assessing/analyzing`, `KFold` (plain, stratified, grouped, groupedStratified), `.repeat(r)` on one-native-repeat designs (preserving per-repeat `Exact` but dropping `ExactOnce`), `LeaveOneOut`, `LeaveOneGroupOut`.
- Implement PRD §§4.6 and 4.10 **exactly as specified**: Fisher–Yates/deal plain K-fold; named-role shuffle-split holdout; canonical label identity from minimum member ordinals; seeded Fisher–Yates order within equal-size LPT buckets; seed-derived fold-priority permutations for equal load/cost; rotating round-robin deal for stratified; and the exact `BigInt` objective `J`/increment `ΔJ` for groupedStratified. Raw label codes never enter ordering or seed derivation. `PlanDiagnostics` records `J`, the exact-oracle optimum where available, additive regret, and the other best-effort diagnostics.
- Typed `DesignError` for every genuinely infeasible configuration — and *not* for oversized groups or small strata, which are legal (PRD §12 D5).
- Laws 2, 4, 5, 6, 9, 10, 11, 12 for these designs; adversarial-config ScalaCheck suite (PRD §6.3).
- **Exhaustive oracles** over small `n`/`k`/label configurations: exact minimum `J*` and zero-safe additive regret for groupedStratified; exact minimum size imbalance for grouped. Thresholds are one-sided so an improvement never fails merely because it changes a pinned output.
- Worked example under `examples/`: nested CV as *composition* — inner design compiled inside an outer analysis `IndexSpace`, embedded via `Selection ∘ Selection`; property that inner allocations never touch outer assessment ordinals.

**Gate:** full law suite green ×3 platforms; law 6's ±1 stratum bound holds on adversarial label configurations; law 12 (recoding invariance) proves bijectively recoded inputs produce equal canonical `Labels`, equal `DesignKey`s, equal label/design fingerprints under the same audit provider, equal derived streams, and equal partitions across the seeded group-order and fold-priority paths; tie-rich grouped and groupedStratified fixtures produce at least two distinct partitions over a fixed seed set while replaying identically for the same seed; repeated-unit collisions remain legal; `BigInt` objective comparisons agree with exhaustive `J*` oracles and cannot overflow; nested-composition property green; golden fixtures extended to every design variant; one-sided additive-regret baselines recorded.

## Phase 3 — Bootstrap, jackknife, permutation

Independent of phase 2 once phase 1 lands (both need only `Design`/`Labels`/`Plan`); the two can interleave.

- `Bootstrap(times)` performs exactly `n` uniform row draws per unit. `Bootstrap.grouped` performs exactly `g` uniform canonical-group draws per unit and emits each chosen group's rows in canonical order, producing variable row length `L` (PRD §§4.6/4.10). Reject `g.toLong * m_max > Int.MaxValue` before plan construction rather than risking a late oversized `Draw` or conditioning the distribution. Both compile to `Plan[Split[Draw], Coverage]` with OOB assessment and explicit `OobPolicy` (default `Redraw(8)`). `Fail`/`Redraw` candidate generation occurs during compilation; accepted child seeds make subsequent lazy access total.
- `Jackknife.delete1`; `Jackknife.deleteD.exhaustive(d, budget)` for `2 ≤ d < n` via lexicographic combinatorial unranking; `Jackknife.deleteD.sampled(d, times)` via uniform `BigInt` ranks into that same ordering. Both use an O(d) assessment plus `ComplementOf`; sampled duplicates are allowed, and `d = 1` is directed to the exactly covered `delete1` constructor.
- `PermutationDesign(times)` uses per-unit Fisher–Yates; `within(blocks, times)` independently shuffles canonical members back into each block's positions. Identity and duplicate permutations are allowed and asserted to be allowed.
- Laws 7, 8, and the built-in-design portion of law 15 (multiset **and order** preservation, OOB complement, block-preserving permutations, total generation/canonical encoding).

**Gate:** law suite green ×3; unit-budget error fires exactly at the boundary and never truncates silently; grouped potential-draw-size rejection fires on the Long-computed boundary before any unit access; lexicographic rank/unrank round trips cover every small `C(n,d)`, and sampled ranks meet a predeclared discrete-uniform goodness-of-fit check without rejecting duplicates; instrumentation proves `Fail` performs one candidate generation per unit and `Redraw(a)` performs at most `a`, grouped OOB preflight visits O(g) sampled group ids without expanding rows, exhaustion is returned by `compile`, and access has no late failure; under `OobPolicy.Allow`, ordinary mean OOB fraction matches `(1 − 1/n)^n` at `n ∈ {5, 10, 50, 1000}`, while grouped row/group fractions match `(1 − 1/g)^g` on equal and unequal group sizes, each over a fixed seed set within a stated Hoeffding bound at declared α; grouped draw lengths obey `g·m_min ≤ L ≤ g·m_max` and their mean matches `E[L] = n` under the normalized Hoeffding check from PRD §6.2 (equal-size groups are exact); `Redraw` separately matches exact small-case conditioned-distribution/exhaustion oracles; seed-sensitive fixtures exercise their declared random degrees of freedom, while seed-independent configurations are asserted invariant without assuming uniformity from output-space cardinality.

## Phase 4 — Hardening + docs + fresh-context review

- **Cost guardrails** (PRD §6.4): allocation, unit-count, candidate-generation, and receipt-work assertions against §4.8. `LeaveOneOut(n = 100_000)`, million-unit `Plan.keys`, and `Jackknife.deleteD.exhaustive` at the budget edge compile within bounded allocation and never materialize n² indices; grouped-bootstrap instrumentation scales with `g + L`; eager `materialized` storage is charged separately; receipt generation streams canonical encodings without retaining assignment vectors and remains visibly separate from compile.
- README with the algebra story (finite reindexing → designs) and the nested-CV composition example.
- Scaladoc pass on the public surface, including the honest-limits statements: digest is not tamper-evident, redraw biases the bootstrap distribution at small `n`, grouped-stratified balance is best-effort.
- `docs/design.md` capturing decisions D-style, seeded from PRD §12.
- CI: GitHub Actions matrix (JVM/JS/Native × law suite + golden fixtures + statistical suite + cost guardrails).
- **Semantic-parity benchmarks:** a non-published JVM runner plus locked
  scikit-learn, splitTools, and rsample environments. Time only complete
  canonical analysis/assessment artifacts after the same fixture and contract
  pass; report grouped-stratified quality beside time. Keep rsample's public
  object workflow distinct from index-kernel comparisons (PRD §6.5).
- **Profile-guided kernel checks:** bounded-`Int` RNG and shuffle-split fast
  paths require differential oracles against their literal definitions,
  unchanged golden fixtures on JVM/JS/Native, and refreshed semantic-parity
  evidence. A timing improvement without seed identity is a failed gate.
- **Independent review pass:** fresh-context subagent review of the whole public surface against PRD principles P1–P7 (plus `/scala-type-discipline` on the diff); fix findings. Do not self-approve.

**Gate:** CI green including cost guardrails and benchmark protocol tests;
review findings resolved or explicitly waived in `docs/design.md`; the smoke
and standard benchmark profiles produce validated raw rows, aggregates,
environment manifests, and interpretation-bounded reports.

## Phase 5 — Alder integration spike (gated on alder-data existing)

- Re-survey alder first (it is moving). Propose the alder PRD amendment: resampling interpretation moves to a tessera-backed module; whitelist `tessera-core` in alder's dependency policy.
- Build the thin adapter in the alder repo: ordinals ↔ RowId, `GroupOf` → `Labels`, alder Seed → tessera Seed, analysis/assessment → `Use.Train`/`Use.Test`, `PlanReceipt`'s tagged fingerprints → alder `Audit` (alder D15).
- **Prove the Alder D19 claim:** the adapter's `CompleteResampler` factory takes `Plan[Split[Selection], Coverage.ExactOnce]` and is total — no runtime coverage check. Assert negatively too: Holdout, Bootstrap, and repeated `Coverage.Exact` plans must fail to compile there. `ExactOnce` was added by the integration spike because `Exact` is per repeat and repeated K-fold is not exactly once over the whole plan (PRD D23).
- Run alder's required resampling laws (coverage, disjointness, determinism, order reconstruction, fingerprints — alder's open priority-1 tracker item) through the adapter; prototype `crossFitExclusion` with instrumented data.
- Feed any boundary friction back into tessera **before** the surface freeze.

**Gate:** Alder's resampling law list passes via the adapter on all Alder platforms; compile-time negative tests for non-exact and repeated-exact plans are in place.

## Phase 6 — Release

Two outcomes, chosen by whether phase 5 completed. This separation is the fix for v0.1's contradictory release policy (PRD §12 D9): phase 5 slipping blocks the *freeze*, not the *tag*.

- **Phase 5 complete → `v0.1.0`.** Freeze the surface; MiMa + TASTy-MiMa baselines; CHANGELOG; tag.
- **Phase 5 incomplete → `v0.1.0-M1`.** No MiMa baseline. README and CHANGELOG state explicitly that the surface is unfrozen and unvalidated against a consumer, and that breaking changes are expected before `0.1.0`. Tagging a milestone is not a soft freeze.

Rolling-origin / time-series designs are **post-v0.1 unconditionally** (PRD §12 D10) — deferred to 0.2 regardless of how phase 5 goes. They are the counterexample the `Coverage` parameter was designed against, not a v0.1 stretch goal.

**Gate:** for `v0.1.0`, MiMa baseline recorded + CHANGELOG + tag pushed. For `v0.1.0-M1`, the unfrozen-surface notice is present in README and CHANGELOG before the tag.

## Sequencing notes

- Phases 0–4 have no external dependencies and can proceed immediately.
- Phases 2 and 3 are independent **after phase 1**, which is true only because `Design` and `Labels` land in phase 1 (PRD §12 D8). If either slips out of phase 1, phase 3 acquires a dependency on phase 2 and the interleaving is off.
- Phase 5 is the only cross-repo phase. It can slip without blocking development, but it gates the surface freeze — see phase 6.
- Throughout: never weaken a law to make a design pass — fix the design. And never promote a distributional check to a law: if it needs a tolerance, it belongs in the statistical suite (PRD §6.2).
