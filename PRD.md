# Tessera — Product Requirements Document

**A pure, typed algebra of finite reindexings, partitions, and reproducible randomized designs.**

- Status: proposal v0.9 (performance-hardened release candidate), 2026-07-26 — revised after three independent design passes, the implementation type-discipline pass, the Alder integration spike, the phase-4 fresh-context assurance review, semantic-parity Python/R benchmarks, and exact-equivalent Monte Carlo/RNG kernel profiling (see §12 decision log)
- Working name: `tessera` (a tessera is one tile of a mosaic — the library partitions a population into tiles). Treated as settled unless vetoed.
- Repo: `~/code/scala/tessera`, sibling to `alder` (consumer) and `gale` (unrelated at the dependency level)
- Supersedes: `~/code/scala/resample4s/resample4s.md` (2026-07-23 design doc — broader, Frame4s-coupled conception; tessera is its data-agnostic core, extracted)
- Source notes: `notes2.txt` (= `note1.txt`, the algebraic center), `notes3.txt` (flagship nested-CV usage sketch), `notes4.txt` (module boundary: tessera vs. alder)

---

## 1. Problem and thesis

Every resampling construct in statistical learning — cross-validation, bootstrap, subsampling, jackknife, permutation tests, rolling-origin evaluation — is an instance of one substrate: **reindexings of the finite ordinal** `I_n = {0, …, n−1}`. A dataset is an indexed family `x : I_n → A`; a reindexing `ρ : I_m → I_n` produces the resampled family `x^ρ = x ∘ ρ`.

The wrong unification is a catalogue:

```scala
enum Resampling:
  case KFold(...); case Bootstrap(...); case Permutation(...)
```

That erases the mathematical distinctions from which the useful laws follow (a bootstrap draw is an *ordered sequence with multiplicity*; a permutation is a *bijection*; a fold is an *injective, order-preserving selection*). Tessera keeps those distinctions in the types, derives each family of laws from its type, and ships the laws as a publishable test-kit so downstream libraries can verify their own use.

Tessera deliberately knows **nothing** about data. It never sees rows, outcomes, features, groups-as-row-functions, or learners. It answers exactly one question: *which population indices occupy which roles, under which reproducible randomization*. Interpretation of those allocations over real datasets belongs to consumers (first consumer: `alder-resampling`).

## 2. Non-goals

- No dataframes, feature pipelines, learners, metrics, or tuning (alder's job; the `Workflow`/`NestedCrossValidation` surface in `notes3.txt` is alder API, out of scope here — see §9).
- No I/O, no effects, no concurrency. Everything is a pure value.
- No general-purpose RNG library. Tessera ships exactly the splittable, platform-stable generator its designs need, and exposes it only as far as reproducibility requires.
- No per-row identity. Tessera speaks ordinals; row identity (`RowId`) is the consumer's concern (forced anyway: alder's `RowId` constructor is `private[alder]`).
- **No authenticated audit.** Tessera's built-in digest is a non-adversarial accidental-drift checksum, not a cryptographic commitment (§4.7). Consumers may supply a cryptographic digest implementation through the open digest capability, but signatures, trusted storage, and key management remain consumer concerns.

## 3. Design principles

- **P1 — Algebra, not catalogue.** Distinct reindexing classes get distinct types with distinct laws. Higher-level designs (KFold, Bootstrap…) remain distinct artifacts built on the shared substrate.
- **P2 — Data-blind core.** The core's only inputs are: a population size, optional per-index label vectors (integer-coded), and a seed. Nothing else crosses the boundary.
- **P3 — Determinism as a contract.** Same design + same seed + same population ⇒ bit-identical plan, on JVM, JS, and Native. No `scala.util.Random`, no platform `String.hashCode`, no iteration-order dependence, **no floating point anywhere in index or size computation** (sizes come from exact rational arithmetic, §4.9).
- **P4 — Laws are deliverables.** Coverage, disjointness, group-atomicity, multiplicity preservation, bijectivity, reconstruction — shipped as a `tessera-laws` module usable by consumers, in the style of `gale-laws`/`alder-laws`. Laws are *deterministic universal statements*; distributional claims live in a separate, explicitly-calibrated statistical suite (§6.2).
- **P5 — Smart constructors, total functions.** Illegal states (overlapping split roles, out-of-range indices, empty folds, aliased backing arrays) are unrepresentable or rejected at construction with typed errors (`Either[DesignError, _]`). Every *public* accessor is total; unchecked accessors exist only as `private[tessera]` hot paths. No exceptions on the happy path, no partial functions.
- **P6 — Verifiable audit without row lists.** A compiled plan can explicitly produce a `PlanReceipt` of *policy-tagged digests* (algorithm, design, population, labels, assignment) plus the seed. The receipt **verifies** a recompilation; it does not, by itself, reconstruct one. Verification requires the caller to re-supply the self-contained design (including any labels), the `IndexSpace`, the population fingerprint, and an implementation of the recorded digest algorithm. Receipt production is an explicit, costed traversal (§4.8), not hidden work performed by `compile`. This is what makes the receipt safe to store (aligns with alder D15).
- **P7 — Cost is part of the contract.** Every design publishes stored-state, compilation, per-unit access, and receipt-production work bounds (§4.8), and those bounds are guarded by tests, not prose.

## 4. Core algebra (`tessera-core`)

### 4.1 Population

```scala
opaque type IndexSpace = Int          // I_n
object IndexSpace:
  def of(size: Int): Either[DesignError, IndexSpace]   // size >= 0
extension (s: IndexSpace) def size: Int
```

`IndexSpace` is the whole of what tessera knows about a population. `Population` (the notes4 term) = an `IndexSpace` plus a consumer-supplied, policy-tagged identity `Fingerprint` that the consumer attaches at receipt time (§4.7); tessera never holds ids themselves and never derives an identity it cannot honestly tag.

`IndexSpace.of(0)` is legal as a *value*; every design rejects it with `DesignError.EmptyPopulation`. Keeping the empty space representable is what makes the error typed rather than a constructor failure at an unrelated call site.

### 4.2 The reindexing lattice

```scala
sealed trait Reindexing:              // ρ : I_m → I_n
  def domain: Int                     // m
  def codomain: Int                   // n
  def at(i: Int): Either[OutOfDomain, Int]      // total, checked — public
  def toIArray: IArray[Int]                      // total, bulk — the ergonomic fast path
  private[tessera] def unsafeAt(i: Int): Int     // hot loops only

sealed trait Injective extends Reindexing        // marker: no repeated targets

final class Draw        extends Reindexing       // arbitrary; repetition allowed
final class Injection   extends Injective        // injective, arbitrary order
final class Selection   extends Injective        // injective AND strictly increasing
final class Permutation extends Injective        // bijective, domain == codomain
```

- Private constructors and validated factories throughout. `IArray` can still be backed by an aliased `Array`, so every public factory **defensively copies**; `private[tessera] fromOwned` skips the copy where tessera itself produced the buffer.
- **`Draw` is an ordered draw sequence.** Its multiplicities form a multiset, but the sequence itself is ordered and that order is observable — pullback preserves it and the assignment digest commits to it. Equality is sequence equality. Multiset equality is a separate, explicitly named `sameMultiset(that): Boolean`. Bootstrap multiplicity must never collapse to a set (top design trap inherited from resample4s). `Draw` exposes `multiplicity(i: Int): Int` and `support: Selection`; an internal grouped factory may retain a certified `LabelClasses` support view produced from the same group draws, but that backing never changes `Draw` equality or encoding.
- `Selection` supports complement, intersection, union, and difference — all requiring equal codomains and returning `Either[CodomainMismatch, Selection]`. The canonical strictly-increasing form makes equality and digesting well-defined.
- `Permutation` forms a group: `identity`, `andThen`, `inverse`, with laws.
- **Factorization law.** Every `Injection` decomposes uniquely as `Selection ∘ Permutation`: `injection.factor: (Selection, Permutation)` with `sel.after(perm) == injection`. This is what keeps `Injection` from being a junk drawer — it is exactly "a subset plus an order on it".
- **Selection backings** (invisible in the type, decisive for cost — §4.8):

  | Backing | State | `toIArray` |
  |---|---|---|
  | `Explicit(IArray[Int])` | O(size) | O(1) |
  | `Block(partition, blockId)` | O(1) over a shared partition | O(size) |
  | `ComplementBlock(partition, blockId)` | O(1) over a shared partition | O(n) |
  | `LabelClasses(labels, classSet)` | O(cardinality) bitset over shared labels | O(n) |
  | `ComplementOf(base: Selection)` | O(1) wrapper + base state | O(n) |

  `toIArray` complexity includes discovering members, not merely writing the output; complement views therefore state the honest O(n) scan bound. A leave-one-out analysis set is a `ComplementBlock` view: O(1) stored, materialized only when asked. Delete-*d* uses an explicit size-*d* assessment plus `ComplementOf(assessment)`, so producing the split stores O(d), not O(n). Grouped bootstrap represents drawn support as `LabelClasses` and OOB as its complement, so constructing a lazy split does not expand all unselected rows. Taking the complement of a `ComplementOf` returns its base; the double-complement law is representation-preserving.

- Equality, hashing, assignment encoding, and `CanEqual` are **extensional across backings**. An `Explicit`, `Block`, or complement view denoting the same ordinal sequence compares and digests identically; backing tags never enter semantic equality or receipts.

- **Pullback**, the algebra's keystone, property-tested: for any indexed family `x : IArray[A]`, `pull(pull(x, ρ), σ) == pull(x, ρ.after(σ))`, and `pull(x, identity) == x`.

**Composition closure table.** Composition is realized by a `Compose[F, G] { type Out }` typeclass whose instances are exactly this table; the dimension check (`ρ.domain == σ.codomain`) remains a runtime `Either[DomainMismatch, Out]`.

| `ρ.after(σ)` ↓ρ \ →σ | `Draw` | `Injection` | `Selection` | `Permutation` |
|---|---|---|---|---|
| **`Draw`** | Draw | Draw | Draw | Draw |
| **`Injection`** | Draw | Injection | Injection | Injection |
| **`Selection`** | Draw | Injection | **Selection** | Injection |
| **`Permutation`** | Draw | Injection | Injection | **Permutation** |

The bolded cells carry the weight: `Selection ∘ Selection = Selection` is exactly the closure that makes nested CV work by composition (§5), and `Permutation ∘ Permutation = Permutation` is the group law. Widening is explicit (`selection.widen: Injection`, `injection.widen: Draw`); there are no implicit conversions.

### 4.3 Partitions, splits, plans

```scala
final class FoldPartition private (...)     // total partition of I_n into k non-empty blocks
object FoldPartition:
  def fromAssignments(n: Int, k: Int, assign: IArray[Int]): Either[DesignError, FoldPartition]
  // internal backings: ExplicitAssignments (O(n)) and SingletonIdentity(n) (O(1))

final class Split[+A <: Reindexing] private (val analysis: A, val assessment: Selection)
// smart ctor enforces: assessment ∩ analysis.support = ∅  (leakage boundary)
//                      analysis.domain > 0                (no empty analysis)

final case class UnitKey(repeat: Int, fold: Int)
final case class PlanShape private (repeats: Int, foldsPerRepeat: Int)
object PlanShape:
  def of(repeats: Int, foldsPerRepeat: Int): Either[DesignError, PlanShape]
  // both axes >= 1 and repeats.toLong * foldsPerRepeat <= Int.MaxValue

sealed trait Coverage
object Coverage:
  sealed trait Exact extends Coverage       // assessment blocks partition I_n exactly, per repeat
  sealed trait ExactOnce extends Exact      // one repeat, hence exactly once over the whole plan

final class Plan[+A, +Cov <: Coverage] private (shape: PlanShape, at: UnitKey => A):
  def shape: PlanShape
  def first: A                              // total: PlanShape is structurally non-empty
  def at(key: UnitKey): Either[UnknownUnit, A]
  def keys: IndexedSeq[UnitKey]              // immutable O(1)-state range-backed view
  def iterator: Iterator[(UnitKey, A)]        // O(1) iterator state; evaluates units on demand
  def map[B](f: A => B): Plan[B, Cov]
  def zip[B, C2 <: Coverage](that: Plan[B, C2]): Either[ShapeMismatch, Plan[(A, B), Cov | C2]]
  def materialize: Vector[(UnitKey, A)]
  def materialized: Plan[A, Cov]             // eagerly builds an immutable Vector-backed plan

type AnyPlan[+A] = Plan[A, Coverage]
```

Three things are load-bearing here.

**Coverage is a type-level capability, not a runtime flag.** One-repeat partitioning designs (KFold and its grouped/stratified variants, LOO, LOGO, and jackknife delete-1) compile to `Plan[Split[Selection], Coverage.ExactOnce]`; `ExactOnce <: Exact`, where `Exact` means that assessments partition the population within each repeat. Calling `.repeat(r)` preserves `Exact` but deliberately drops `ExactOnce`: a two-repeat K-fold assesses every row twice over the whole plan and cannot drive one out-of-fold reconstruction. Holdout, Monte Carlo, Bootstrap, and delete-*d* for `d > 1` compile to `Plan[Split[…], Coverage]`. `map` preserves either capability, so result plans retain the strongest valid provenance. `zip` widens to `Cov | C2`, the *weaker* claim. This is what lets Alder's `CompleteResampler` (Alder D19) accept `ExactOnce` through a total factory while rejecting Holdout, Bootstrap, and repeated K-fold statically.

**Plans are lazy.** A `Plan` is a shape plus a pure, deterministic `UnitKey => A`. Bootstrap and permutation designs store per-unit child seeds and regenerate draws on demand; exhaustive delete-*d* jackknife stores nothing and unranks the *j*-th combination; LOO stores one implicit partition. Any unit-generation condition that can fail is resolved before the `Plan` is constructed: in particular, bootstrap compilation preflights `OobPolicy.Fail` and `OobPolicy.Redraw`, storing only unit seeds already known to satisfy the policy. Consequently `Plan.at` has no late `DesignError` path; after key validation, regeneration is total.

`PlanShape.of` makes the non-empty axes and `Int`-sized total unit count structural; `UnitKey` remains freely constructible because an out-of-shape key is a valid total lookup request. `keys` is a custom immutable mixed-radix range over that shape, not a `Vector`; asking for keys does not allocate O(u) objects. `iterator` holds only the current ordinal. `materialize` is the explicit O(u)-element result vector. `materialized` eagerly calls that traversal once and returns an immutable vector-backed plan; there is no hidden mutable cache, synchronization, or observationally stateful “memoized” value. The cost of holding everything is therefore an explicit caller decision consistent with the no-effects/no-concurrency boundary.

`FoldPartition` is extensional too. Ordinary designs own `ExplicitAssignments` plus O(n) packed block-member offsets, so a `Block` iterates in O(block size); LOO uses `SingletonIdentity(n)`, whose fold `j` is implicitly the singleton `{j}` and whose total state is O(1). Block selections and their complements observe the same public partition API under either backing. This is the missing representation fact behind the O(1) LOO claim in §4.8.

**Frozen-surface types are final classes with accessors, not case classes** (mirroring alder D15(3)): `Split`, `Plan`, `Labels`, `PlanReceipt`, `FoldPartition`. Final classes can gain fields compatibly; case classes cannot. `case class` is reserved for types whose field set is provably closed (`UnitKey`, `PlanShape`). Under `-language:strictEquality` each public type derives an explicit `CanEqual`.

`Plan` is deliberately generic: designs compile to `Plan[Split[Selection], _]` (CV), `Plan[Split[Draw], Coverage]` (bootstrap + out-of-bag), or `Plan[Permutation, Coverage]` (permutation designs) — and consumers reuse the same shape for results (`Plan[FoldScore, Coverage.Exact]` in notes3), so per-fold provenance zips structurally with per-fold outputs.

Vocabulary is rsample's: **analysis** (fitting role) / **assessment** (evaluation role). Tessera does not use alder's `Train`/`Test` phantom roles; role tagging is the consumer's concern.

### 4.4 Reproducible randomness

```scala
opaque type Seed = Long              // Seed.fromLong, .value — interops with alder's Seed via Long
opaque type DesignKey = Long         // fixed internal randomization key; not an audit digest
final class Rand private (...)       // pure splitmix64: next(state) => (state, value); no mutation escapes
final class StreamPath private (...) // domain tag + ordered integer ordinals
```

- splitmix64 core plus precisely one shuffle: for `i = length − 1` down to `1`, draw `j ∈ [0, i]` and swap positions `i` and `j`. `nextIntBounded(b)` interprets a generated word as unsigned, rejects values below `2⁶⁴ mod b`, and returns the unsigned remainder modulo `b`. `nextBigIntBounded(upperExclusive)` takes the minimum required bit width, concatenates unsigned 64-bit words, masks unused high bits, and rejects candidates `≥ upperExclusive`. Thus bounded draws are unbiased and the Fisher–Yates variant is not left to platform/library choice. All arithmetic and output are bit-stable across JVM/JS/Native (Scala.js `Long` is exact; no floating point enters index generation).
- Child-stream derivation: `derive(seed, designKey: DesignKey, path: StreamPath)`. `DesignKey` is the fixed FNV-1a-64 checksum of the versioned canonical design bytes, used only to separate random streams; it is not the policy-selectable receipt fingerprint and carries no audit claim. Compile therefore never depends on an ambient `DigestAlgorithm`. A path is a non-empty ordered sequence of `(domainTag, ordinal)` segments; the closed tags are `Repeat`, `Unit`, `Stratum`, `GroupSizeBucket`, `FoldPriority`, `ExchangeabilityBlock`, and `RedrawAttempt`. Segments are length-framed and mixed sequentially with the splitmix64 finalizer; they are not XOR-reduced as an unordered tuple. Thus `[(Repeat, 1), (Unit, 2)]` cannot alias `[(Repeat, 2), (Unit, 1)]` by commutative cancellation, and adding a new stream family cannot silently reuse an old domain.
- Golden fixtures pin exact assignments per (design, seed, n) and are asserted identical on all three platforms in CI. **Golden fixtures are compatibility locks, not correctness proofs** — see §6.3.

### 4.5 Designs and compilation

```scala
trait Design[+A, +Cov <: Coverage]:
  def definition: DesignDefinition[A, Cov]              // the sole extension method
  final def randomizationKey: DesignKey
  final def fingerprint(using DigestAlgorithm): Either[DigestError, ContentDigest]
  final def labelsFingerprint(using DigestAlgorithm): Either[DigestError, Option[ContentDigest]]
  final def compile(space: IndexSpace, seed: Seed): Either[DesignError, Compiled[A, Cov]]

final class Compiled[+A, +Cov <: Coverage] private (
  val plan: Plan[A, Cov],
  val diagnostics: PlanDiagnostics)                     // §4.6 — best-effort quality, observable

final class DesignDescriptor private (...)               // AlgorithmId + canonical typed fields
final class DesignDefinition[+A, +Cov <: Coverage] private (...)
final class PlanCost private (
  val residentElementsUpperBound: Long,
  val workPerUnitUpperBound: Long,
  val receiptWorkPerUnitUpperBound: Long)
object PlanCost:
  def of(
    residentElementsUpperBound: Long,
    workPerUnitUpperBound: Long,
    receiptWorkPerUnitUpperBound: Long): Either[DesignError, PlanCost]

sealed trait DescriptorValue                             // closed canonical value grammar
object DescriptorValue:
  def int(value: Int): DescriptorValue
  def long(value: Long): DescriptorValue
  def bool(value: Boolean): DescriptorValue
  def text(value: String): Either[DesignError, DescriptorValue]
  def fraction(value: Fraction): DescriptorValue
  def sequence(values: IArray[DescriptorValue]): DescriptorValue
  def variant(tag: String, value: DescriptorValue): Either[DesignError, DescriptorValue]

object DesignDescriptor:
  def of(
    algorithm: AlgorithmId,
    fields: IArray[(String, DescriptorValue)]): Either[DesignError, DesignDescriptor]
  // validates field names, rejects duplicates, sorts by UTF-8 field-name bytes, and owns all inputs

object DesignDefinition:
  def general[A](
    descriptor: DesignDescriptor,
    labels: Option[Labels])(
    build: BuildContext => Either[DesignError, GeneralPlanSpec[A]])
    : DesignDefinition[A, Coverage]

  def exactPartitions(
    descriptor: DesignDescriptor,
    labels: Option[Labels])(
    build: BuildContext => Either[DesignError, ExactPartitionSpec])
    : DesignDefinition[Split[Selection], Coverage.Exact]
  // both routes also accept a defensively copied IArray[Labels] for designs
  // with multiple label authorities, such as grouped-stratified K-fold

trait CanonicalAssignmentEncoder[-A]:
  def encode(value: A, out: CanonicalWriter): Either[DigestError, Unit]
  // CanonicalWriter exposes only the framed scalar/sequence/sum primitives from §4.7

object GeneralPlanSpec:
  def of[A](
    shape: PlanShape,
    diagnostics: PlanDiagnostics,
    cost: PlanCost)(
    unit: UnitKey => A,
    encoder: CanonicalAssignmentEncoder[A]): Either[DesignError, GeneralPlanSpec[A]]

object ExactPartitionSpec:
  def of(
    partitions: IArray[FoldPartition],
    diagnostics: PlanDiagnostics): Either[DesignError, ExactPartitionSpec]

final class Labels private (...)                        // groups or strata over I_n
object Labels:
  def of(codes: IArray[Int], cardinality: Int, n: Int): Either[DesignError, Labels]
  // validates: codes.length == n; every code in [0, cardinality); cardinality >= 1;
  //            every declared code occurs; defensive copy (IArray may alias a mutable Array)
  // canonicalizes equivalence classes to 0..cardinality-1 by ascending minimum member ordinal
  // Labels.dense(codes, n) additionally accepts sparse/arbitrary input codes before canonicalizing
```

The consumer (alder) turns `row => row.site` into `Labels`; tessera never sees the function. Typed errors, not silent degradation, for infeasible configurations — and §4.6 fixes exactly *which* configurations are infeasible, because "oversized group" and "stratum too small" turn out not to be.

Label-bearing designs take one or more `Labels` values as design parameters, not compile parameters, so a `Design` value is self-contained. The ordinary route takes `Option[Labels]`; a defensively owned `IArray[Labels]` overload covers designs with multiple authorities (grouped-stratified owns groups and strata). Every public `Labels` factory stores the canonical recoding described above, not the caller's numeric codes. Therefore bijective recoding yields equal `Labels`, equal `DesignKey`s, equal label/design fingerprints under the same audit provider, and the same derived random streams—not merely the same allocation after randomness has already diverged. Both the fixed internal key and the provider-selected design fingerprint cover the framed label set. `labelsFingerprint` is one digest over that embedded set; callers never pass a second labels value to receipt verification. The separately stored labels digest lets `ReceiptMismatch` diagnose drift in either label authority specifically.

**Consumer-defined designs are supported through a narrow construction SPI.** `Design` has exactly one non-final member, `definition`; all fingerprint, key, compilation, receipt, and validation plumbing remains framework-owned. A `DesignDescriptor` uses the closed `DescriptorValue` grammar—not `Map[String, Any]`, `toString`, raw bytes, or consumer-defined hashing. `AlgorithmId` versions the consumer's schema; typed value tags, sorted unique field names, length framing, defensive copies, and separately committed canonical labels make equal descriptors byte-identical across platforms.

`BuildContext` exposes only the validated `IndexSpace`, design-owned canonical labels, seed, fixed `DesignKey`, and §4.4 child-stream derivation; it has no clock, audit-digest provider, or mutable global registry. A custom implementation may compute purely from those values but earns the cross-platform claim only by passing the published JVM/JS/Native conformance bundle.

`DesignDefinition.general` is the route for arbitrary/custom plans and can produce only `Coverage`. Its `GeneralPlanSpec` contains a validated shape, a total deterministic unit generator, diagnostics, a concrete non-negative `PlanCost` upper-bound declaration for that compilation, and a `CanonicalAssignmentEncoder[A]` that can write only the framed primitives of §4.7. Core frames `IndexSpace.size`, the plan shape, and each `UnitKey` around the consumer's value encoding, so population-size drift or reordering equal-looking units cannot alias a receipt. Every condition that could fail must be preflighted by `build`; the unit generator cannot return `Either` or throw on a valid key. `GeneralPlanSpec.of` rejects invalid shapes/costs before a plan exists. The published conformance laws check replay, totality over every declared key, shape, counted output/encoding work, backing-independent encoding, and single-unit perturbation sensitivity. Arbitrary consumer computation remains a declared-and-tested obligation—core cannot prove the CPU complexity of an opaque function. A custom compact encoder is likewise an explicit capability claim: it must uniquely determine the public artifact or fail the conformance suite.

`DesignDefinition.exactPartitions` is the **only public route to `Coverage.Exact`**. Its `ExactPartitionSpec` supplies a non-empty, defensively owned sequence with one validated `FoldPartition` per repeat; every partition must have the same population and fold count. Core derives every analysis/assessment split, exact coverage witness, lazy plan, cost declaration, and canonical assignment encoding. Consumers cannot assert `Exact` with a phantom type, arbitrary generator, or custom encoder. `Plan`, `Compiled`, `GeneralPlanSpec`, and `ExactPartitionSpec` retain private constructors and expose only validated factories, so the SPI makes extension possible without making coverage forgeable.

### 4.6 Allocation contracts for grouped and stratified designs

This section is normative. Every rule here is deterministic, and none of them reads a label *code value* except through a canonical order defined over ordinals — which is what makes the metamorphic law (§6.1, law 12) hold: **relabeling groups or strata by any bijection produces the same partition.**

**Canonical label identity.** Groups and strata are identified canonically by their ascending smallest member ordinal. Raw label code values never participate in ordering, seed derivation, allocation, or tie-breaking.

**Seeded LPT processing order.** For grouped algorithms, groups are bucketed by descending member count. Each equal-size bucket begins in ascending smallest-member-ordinal order, then Fisher–Yates uses a domain-separated child stream derived from `(design seed, repeat, bucket ordinal)` to determine processing order. This preserves the defining LPT size order while giving repeated grouped designs a real seed-sensitive degree of freedom. A seed-derived Fisher–Yates permutation of initially ascending fold indices supplies the priority order for equal-cost/equal-load fold ties. No randomization input is derived from raw label codes. Given the same design and seed the result is deterministic, while two different repeat seeds are permitted—but not required—to produce different partitions.

Strata are processed by descending member count, ties broken by ascending smallest member ordinal. The stratum child stream is derived from its canonical ordinal in that order, never from its code value.

**Grouped k-fold** (`KFold.grouped(k, groups)`):
- Algorithm: longest-processing-time-first bin packing. Walk groups in seeded LPT processing order; assign each to the currently-smallest fold; equal-load folds use the seed-derived fold-priority permutation.
- Guarantee: **group atomicity is absolute** (law 5) — a group never straddles roles. Fold size balance is **best-effort**, not bounded: a single group of size > n/k makes balance arithmetically impossible, and that is not an error. `PlanDiagnostics` reports `maxFoldSize`, `minFoldSize`, and `sizeImbalance`.
- For bounded small configurations (`n ≤ 32` and `k^g ≤ 100000`), compilation also exhausts every non-empty-fold group allocation and reports the exact optimum imbalance plus non-negative additive regret. Larger configurations omit `Optimum`/`Regret` rather than pretending a heuristic baseline is exact. Repeated designs retain the worst achieved imbalance/regret and record the repeat count.
- Infeasible only when: `distinctGroups < k` (cannot fill k non-empty folds) → `DesignError.TooFewGroups`.
- **An oversized group is never an error.** The v0.1 draft said otherwise; it was wrong.

**Stratified k-fold** (`KFold.stratified(k, strata)`):
- Algorithm: within each stratum, take members in ascending ordinal order, shuffle with a stratum-derived child seed, then deal round-robin into folds starting at a rotating offset carried across strata in canonical order.
- Guarantee: **provable, not best-effort.** Every fold's count for stratum *s* is `floor(n_s/k)` or `ceil(n_s/k)`. Law 6 asserts exactly that ±1 bound, from which the proportion bound follows.
- Infeasible only when: `n < k`, or the deal leaves a fold empty. **A stratum with fewer than k members is not an error** — it simply appears in some folds and not others, which is the correct behavior and what the ±1 bound already describes. The v0.1 draft said otherwise; it was wrong.

**Grouped-stratified k-fold** (`KFold.groupedStratified(k, groups, strata)`):
- Mixed-stratum groups are **accepted**. Each group carries a canonical sparse stratum profile; a pure group is the special case. Across all groups the number `q` of non-zero group×stratum cells is at most `n`, so the implementation never allocates a dense `groups × strata` matrix.
- Define the final allocation objective, scaled to avoid fractions:

  `J = Σ_f [ Σ_s (k · count[f][s] − n_s)² + (k · size[f] − n)² ]`

  where `n_s` is the population count of stratum `s`. The size term has weight λ = 1. All products, squares, sums, and comparisons use `BigInt`; allocation never passes through floating point and cannot overflow.
- Algorithm: walk groups in seeded LPT processing order. For each candidate fold, compute the exact increment

  `ΔJ(f, g) = J(allocation with g added to f) − J(current allocation)`

  and choose the fold with minimum `ΔJ`. Equal increments use the seed-derived fold-priority permutation. There is no informal “remaining-mass target”: the equations above are the complete executable specification.
- Guarantee: group atomicity is absolute; stratum balance is **best-effort with no bound**. This is a multi-objective allocation problem and pretending otherwise would be dishonest. `PlanDiagnostics` reports per-stratum deviation and `groupPurity` (fraction of groups that are single-stratum), so callers can see how well it went.
- For the same bounded small frontier (`n ≤ 32` and `k^g ≤ 100000`), compilation reports the true minimum `J*` and additive regret; outside it those two diagnostics are absent. Repeated designs retain the worst achieved objective/regret while preserving the exact optimum and other worst-case diagnostics.
- Verification strategy: exhaustive allocation oracles over every canonical label partition at `n ≤ 5` (and grouped-only partitions through `n ≤ 6`), each legal `k ≤ 3`, compute the true optimum independently. The quality measure is non-negative additive regret `Jheuristic − J*`, which remains defined when `J* = 0`. Regression thresholds are one-sided: larger regret can fail; smaller regret is always accepted.

**Grouped bootstrap** (`Bootstrap.grouped(times, groups)`):
- Let `g` be the number of groups. Each unit samples **exactly `g` group ids independently and uniformly with replacement** from the canonical group order. A group drawn *j* times contributes all of its rows *j* times, in ascending member-ordinal order on each occurrence; the order of group draws remains observable in the resulting `Draw`.
- Let `m_min` and `m_max` be the smallest and largest group sizes. The row-level analysis length is variable: `L = Σ_draw |group(draw)|`, with `g·m_min ≤ L ≤ g·m_max` and `E[L] = n`. The generator records the drawn-class bitset while emitting rows; `Draw.support` is the corresponding `LabelClasses` view and out-of-bag is its ascending complement, neither expanded at split construction. The expected OOB fraction of both groups and rows under `OobPolicy.Allow` is exactly `(1 − 1/g)^g`, even when group sizes differ.
- Empty-OOB policy is evaluated at the group-support level using §4.9. Duplicate group draws are legal and are never removed.
- Because `Draw.domain` is `Int`, compilation requires `g.toLong · m_max ≤ Int.MaxValue`; otherwise it returns `DesignError.PotentialDrawSizeExceeded(g, m_max)` before constructing a plan. This conservative representability check does not redraw or condition the bootstrap distribution.
- Two-stage (groups then rows-within-groups) is post-v0.1. Naming it explicitly now prevents the ambiguity the review caught.

### 4.7 Fingerprints, receipts, and what they do not prove

```scala
opaque type DigestAlgorithmId = String       // validated stable id, e.g. "fnv1a64/v1"
object DigestAlgorithmId:
  def of(value: String): Either[DigestError, DigestAlgorithmId]

final class DigestValue private (...)         // arbitrary-length owned bytes
object DigestValue:
  def fromBytes(bytes: IArray[Byte]): Either[DigestError, DigestValue]
  // rejects empty values; defensively copies

trait DigestAccumulator:                      // one incremental invocation
  def update(chunk: IArray[Byte]): Either[DigestError, Unit]
  def finish(): Either[DigestError, DigestValue]

trait DigestAlgorithm:                        // open consumer capability
  def id: DigestAlgorithmId
  def newAccumulator(): Either[DigestError, DigestAccumulator]
  final def digest(chunks: Iterator[IArray[Byte]]): Either[DigestError, DigestValue]

object DigestAlgorithm:
  val fnv1a64: DigestAlgorithm                // built-in, zero-dep; emits 8 bytes

sealed trait Fingerprint                     // policy-tagged, mirroring alder D15(2)
final class ContentDigest private (
  val algorithm: DigestAlgorithmId,
  val value: DigestValue) extends Fingerprint
object ContentDigest:
  def of(algorithm: DigestAlgorithmId, value: DigestValue): ContentDigest
final class SourceIdentity private (val uri: String, val version: String) extends Fingerprint
object SourceIdentity:
  def of(uri: String, version: String): Either[FingerprintError, SourceIdentity]
final class Summary private (val policyId: String, val value: Long) extends Fingerprint
object Summary:
  def of(policyId: String, value: Long): Either[FingerprintError, Summary]

final class PlanReceipt private (
  val algorithm:  AlgorithmId,                // design/compiler schema, e.g. kfold-stratified/v1
  val design:     ContentDigest,              // canonical design description
  val population: Fingerprint,                // consumer-supplied policy
  val labels:     Option[ContentDigest],      // derived from labels embedded in design
  val seed:       Seed,
  val assignment: ContentDigest)              // canonical semantic assignment encoding

final class Compiled[+A, +Cov <: Coverage] ...:
  def receipt(population: Fingerprint)(using DigestAlgorithm)
    : Either[DigestError, PlanReceipt]         // explicit, streaming, costed in §4.8

extension (receipt: PlanReceipt)
  def verify(
    design: Design[?, ?],
    space: IndexSpace,
    population: Fingerprint)(using DigestAlgorithm)
    : Either[ReceiptError, Unit]
```

**A receipt verifies; it does not reconstruct.** Verification requires the caller to re-supply the self-contained design, the `IndexSpace`, and the population fingerprint. Label-bearing designs already own their `Labels` (§4.5), so there is deliberately no second labels argument that could disagree with the design; the labels digest is derived from that single authority. `verify` first requires the supplied digest capability's id to match the receipt's internally computed design/labels/assignment digest ids, recompiles with the receipt seed, streams a fresh receipt using the supplied population fingerprint, and compares fields. A consumer-supplied population `ContentDigest` may use a different algorithm because verification compares that already-computed policy value directly rather than recomputing it. `ReceiptError` distinguishes digest-provider mismatch/failure, compilation failure, and `ReceiptMismatch`, whose latter case names the differing component (design / population / labels / assignment).

The seed is an input recorded by the receipt, not independently re-supplied to `verify`. If changing only the stored seed changes the allocation, verification reports an **assignment mismatch** after recompilation; it cannot honestly label that a seed mismatch without an external expected seed. If the design is seed-independent or the two seeds collide on the same finite assignment, verification succeeds—necessarily, because every value it can recompute is equal. Consumers that possess an expected seed compare `receipt.seed` directly before verification.

`DigestAlgorithm` is a real open capability, not a closed enum. `DigestValue` holds arbitrary-length bytes, so a consumer can adapt SHA-256 or another implementation without tessera depending on that library. Every invocation creates an independent `DigestAccumulator`; Tessera pushes framed chunks to `update` as they are generated and calls `finish` once. The capability contract is pure and deterministic: an id names one exact byte-level algorithm/version, equal concatenated canonical bytes yield equal digest bytes regardless of chunk boundaries, and the accumulator neither mutates nor retains chunks after `update` returns. The final `digest` convenience method is framework-owned and obeys the same protocol. Digest results and all public byte inputs are defensively copied. A cryptographic digest can provide collision-resistant content commitments, but authentication of the receipt still requires trusted storage or a signature outside tessera.

**Canonical bytes are versioned and framed.** `AlgorithmId` selects the encoding schema. Tags and identifiers are UTF-8 with byte-length prefixes; `Int`/`Long` values are signed fixed-width big-endian; sequences carry element counts; sum alternatives carry explicit tags. Every assignment stream begins with `IndexSpace.size` and `PlanShape`, then frames each `UnitKey` before its semantic value. No `toString`, platform hash, locale, or collection iteration order enters the stream. Chunk boundaries are transport only and are not hashed as data. Golden byte fixtures precede digest fixtures so an encoding regression is distinguishable from a digest-provider regression.

**Honest built-in limits, stated in the Scaladoc as well as here.** FNV-1a 64 is an 8-byte non-adversarial checksum intended to detect accidental divergence—a changed seed, a recompiled design, a drifted platform. Tessera assigns it **no distribution-free collision probability**: FNV is deterministic and its collision behavior depends on the inputs. It is not collision-resistant against an adversary and does not make a `PlanReceipt` tamper-evident.

The population fingerprint is whatever the consumer can honestly claim. `Summary("tessera/size", n)` is the default and says exactly what it is: this plan was built for a population of this size, and nothing more. Because `verify` accepts that fingerprint explicitly, it can verify `SourceIdentity` and consumer content digests as well as the default size summary.

### 4.8 Complexity contract

Normative, and guarded by allocation and work-accounting tests (§6.4) rather than prose. `n` = population size, `k` = folds, `r` = repeats, `t` = draws/permutations, `u` = unit count, `g` = distinct groups, `s` = strata, `q ≤ n` = non-zero group×stratum profile cells, `m_max` = largest group size, and `L` = the emitted row count of one grouped-bootstrap draw. `M(b)` denotes the cost of one exact `BigInt` operation on `b`-bit operands. `U(n,d) = O(d²·log n·M(n))` is a conservative no-table bound for lexicographically unranking one size-`d` combination; implementations may improve it without weakening any observable contract.

| Design | Units `u` | Stored state | Per-unit work (on `at`) |
|---|---|---|---|
| `KFold(k)`, stratified, grouped, grouped-stratified | `k` | O(n) — one `FoldPartition` | O(1) view; O(n) if selections materialized |
| `.repeat(r)` | `r·k` | O(r·n) — one partition per repeat | as above |
| `Holdout`, `MonteCarlo(t)` | `1`, `t` | O(t) child seeds | O(n) regeneration |
| `LeaveOneOut` | `n` | **O(1)** — implicit identity partition | O(1) view; O(n) materialized |
| `LeaveOneGroupOut` | `#groups` | O(n) | O(1) view |
| `Bootstrap(t)` | `t` | **O(t)** child seeds | O(n) regeneration |
| `Bootstrap.grouped(t, groups)` | `t` | O(n + t) labels + child seeds | O(g + L) lazy split; +O(n) if OOB materialized |
| `Jackknife.delete1` | `n` | **O(1)** | O(1) view |
| `Jackknife.deleteD.exhaustive(d)` | `C(n,d)` | **O(1)** — combinatorial unranking | O(U(n,d) + d) view; +O(n) if analysis materialized |
| `Jackknife.deleteD.sampled(d, t)` | `t` | O(t) child seeds | O(M(n) + U(n,d) + d) view |
| `PermutationDesign(t)` | `t` | **O(t)** child seeds | O(n) regeneration |
| `PermutationDesign.within(blocks, t)` | `t` | O(n + t) labels + child seeds | O(n) regeneration |

Laziness is what buys this. The v0.1 draft materialized every selection, which made leave-one-out and delete-1 jackknife Θ(n²) in stored indices and exhaustive delete-*d* combinatorially hopeless; the compiled LOO/delete-1 plans and the exhaustive delete-*d* generator are now O(1) resident state. A returned delete-*d* split necessarily owns its O(d) assessment.

`Plan.keys` and an unstarted `Plan.iterator` are O(1) resident state. `materialize` allocates O(u) result entries plus the state of every generated value. `materialized` performs exactly that eager traversal and retains the immutable result; for bootstrap this is Θ(t·n) row ordinals, while partition views may remain O(u) over their already-owned partitions. Neither operation installs a cache in the source plan.

**Compilation work and failure timing.** The table above describes resident state and `at`; compilation has its own contract:

- plain/stratified partition compilation is O(n + s·log s); grouped LPT compilation is O(n + g·log g + g·log k) using a fold-load priority queue; grouped-stratified compilation is O(n + g·log g + k·q·M(log n)) using sparse profiles and exact `BigInt` delta comparisons. On the fixed diagnostic frontier `n ≤ 32 ∧ k^g ≤ 100000`, grouped designs additionally enumerate at most 100000 allocations for exact optimum/regret; the hard constant cap preserves the asymptotic bounds and is exercised near its frontier. These designs finish allocation before returning `Compiled`; `.repeat(r)` multiplies allocation work by `r`, while the seed-invariant exact optimum is computed once per compilation;
- seed-only designs validate parameters and derive their `u` child seeds in O(u), without generating assignments;
- `Bootstrap(..., OobPolicy.Allow)` follows that seed-only path;
- `Bootstrap(..., OobPolicy.Fail)` generates each candidate draw once during compilation, for O(t·n) work, and fails before constructing a plan if any unit has empty OOB;
- `Bootstrap(..., OobPolicy.Redraw(a))` performs at most `a` candidate generations per unit during compilation, for O(t·a·n) worst-case work, and stores the first accepted seed. Exhaustion is a compile-time `DesignError.EmptyOutOfBag`, never a later `Plan.at` failure;
- grouped bootstrap first indexes canonical groups and checks `g·m_max` in O(n + g), then preflights OOB from the `g` sampled group ids without expanding their rows: O(t·a·g) worst-case work under `Redraw(a)` (take `a = 1` for `Fail`); `Allow` still compiles in O(n + g + t);
- exhaustive delete-*d* computes `C(n,d)` exactly before construction in O(d·M(n)), then either returns `UnitCountExceeded` or stores only `(n,d)`; sampled delete-*d* derives `t` child seeds and does not draw ranks during compilation.

Regenerating an already-validated accepted seed is deterministic, so the lazy plan remains total without storing the draw itself. Tests instrument candidate-generation counts and assert these bounds directly.

**Primitive bounded draws.** An `Int` bound uses unsigned 64-bit rejection
arithmetic without constructing `BigInt` operands. It is exactly equivalent to
accepting a SplitMix64 word `w` when
`unsigned(w) >= 2^64 mod bound` and returning `unsigned(w) mod bound`.
`nextBigIntBounded` remains the separate arbitrary-width path. A differential
test compares the optimized `Int` stream with the literal `BigInt` oracle on
every supported platform.

**Receipt work is explicit.** `Compiled.receipt(population)` streams a canonical *semantic* assignment encoding through the supplied `DigestAlgorithm`; `compile` never computes it implicitly. The encoding is independent of `Selection` backing:

- a partition plan encodes its per-repeat ordinal-to-fold assignment vector in O(r·n);
- holdout and Monte Carlo encode the selected role per unit in O(u·n);
- ordinary bootstrap encodes each ordered draw in O(t·n); grouped bootstrap encodes each emitted ordered row draw in `O(Σ_j (g + L_j))`, using a deterministic counting pass where needed rather than retaining it; permutation encodes each permutation in O(t·n);
- jackknife encodes the deleted combination, not its materialized complement, in O(u·(U(n,d) + d)).

Each family proves that its compact canonical encoding, together with the design digest and the explicitly encoded `IndexSpace.size`, uniquely determines the public plan it represents. Built-in receipt construction uses O(1) incremental traversal state plus the digest provider's state and the immutable design/partition state already owned by `Compiled`; it does not retain assignment vectors or generated units. A custom `CanonicalAssignmentEncoder` is held to the same work declaration and conformance tests. Provider failure returns `DigestError`. Receipt cost guardrails are separate from plan-residency guardrails.

**Unit-count budget.** `Jackknife.deleteD.exhaustive(d)` requires `C(n,d) ≤ min(Int.MaxValue, budget)` where `budget` defaults to 10⁷ and is an explicit constructor parameter; exceeding it is `DesignError.UnitCountExceeded(requested, budget)`, never a silent truncation. Exhaustive and sampled delete-*d* are **separately named constructors**, so no caller can be unsure which they got.

### 4.9 Boundary semantics

Normative decisions on every edge the review flagged. All of these are settled before any code is written, because each one is a place where a plausible default is silently wrong.

**Fractions are exact rationals, never doubles.**

```scala
final class Fraction private (val num: Int, val den: Int)   // 0 < num < den
object Fraction:
  def of(num: Int, den: Int): Either[DesignError, Fraction]
```
Size from a fraction is `(n.toLong * num + den / 2) / den` — integer arithmetic, round-half-up, Long-widened against overflow. No floating point enters any size or index computation (P3).

**Holdout and Monte Carlo name their role explicitly.** There is no bare `Holdout(fraction)`; the ambiguity the review caught is removed at the constructor rather than documented away:
```scala
Holdout.assessing(f)   /  Holdout.analyzing(f)
MonteCarlo.assessing(f, times)  /  MonteCarlo.analyzing(f, times)
```
The derived assessment size must satisfy `0 < size < n`; otherwise `DesignError.DegenerateSplit(n, size)`.

**Empty out-of-bag.** For small `n`, a bootstrap draw has positive probability of covering the whole population; each particular row's omission probability is `(1 − 1/n)^n → e⁻¹`, so neither OOB behavior nor the full-coverage event may be waved away with an asymptotic slogan. Policy is an explicit parameter, defaulting to bounded redraw:
```scala
enum OobPolicy:
  case Allow                      // empty assessment permitted; the Split ctor admits it for Draw analyses
  case Redraw(maxAttempts: Int)   // default Redraw(8); deterministic seed advance per attempt
  case Fail
```
`Redraw` exhausting its attempts is `DesignError.EmptyOutOfBag(unit, attempts)`. **Redrawing conditions the draw distribution on non-empty OOB and therefore biases it** at small `n`; this is documented at the constructor and in the Scaladoc, not buried. `Allow` is the unbiased choice and exists for callers who can handle an empty assessment.

For `Fail` and `Redraw`, policy evaluation happens during `Design.compile`. `Redraw(maxAttempts)` counts the initial candidate as attempt 1 and requires `maxAttempts >= 1`. Compilation stores the accepted child seed, and lazy `Plan.at` later regenerates that already-validated draw. Thus an empty-OOB policy can never surface as an untyped or late access failure.

**Degenerate populations.**

| Case | Behavior |
|---|---|
| `n = 0` | `IndexSpace.of(0)` succeeds; every design → `DesignError.EmptyPopulation` |
| `n = 1` | Legal space; any design needing a non-empty analysis *and* assessment → `DesignError.DegenerateSplit`. `PermutationDesign` succeeds (the identity). |
| `LeaveOneOut`, `n = 1` | `DegenerateSplit` — the analysis set would be empty |
| `LeaveOneGroupOut`, 1 group | `DesignError.TooFewGroups(1, 2)` |
| `KFold(1)` | `DesignError.TooFewFolds` — a 1-fold CV has an empty analysis set |
| `KFold(k), k > n` | `DesignError.TooManyFolds(k, n)` |
| `Jackknife.deleteD`, `d < 2` or `d ≥ n` | `DesignError.InvalidDeleteCount(d, n)`; use `delete1` for `d = 1` |
| Grouped bootstrap, `g·m_max > Int.MaxValue` | `DesignError.PotentialDrawSizeExceeded(g, m_max)` before any lazy plan exists |

**Repeated permutations may repeat.** `PermutationDesign(t)` draws `t` independent permutations; duplicates (including the identity) are possible and are **not** rejected. Deduplicating would condition the null distribution on distinctness and bias every permutation test built on it. Callers wanting exhaustive-distinct permutations over small `n` want a different design, deferred.

**Draw order is semantic.** Stated in §4.2 and repeated here because it is the trap: `Draw` equality is sequence equality, the assignment digest commits to order, and pullback observes it. `sameMultiset` is the opt-in weaker comparison.

### 4.10 Normative generation algorithms

This section closes the remaining catalogue-level ambiguity. “Random” below always means a pure child stream from §4.4. For a plan key, `linear(key) = key.repeat · shape.foldsPerRepeat + key.fold`; independently randomized units use `(Repeat, key.repeat) / (Unit, linear(key))`. An algorithm that constructs all folds of one partition uses one repeat-level stream so its blocks are derived from the same shuffled population. Every emitted `Selection` is sorted in ascending population ordinal; `Draw` and `Permutation` retain their generated order. Repeated units may collide unless a constructor is explicitly named exhaustive.

**Plain K-fold and repeats.**

- For one repeat of `KFold(k)`, Fisher–Yates shuffle `[0, n)` and deal shuffled position `j` to fold `j mod k`. Sort each fold's members to form the assessment `Selection`; analysis is its complement. This produces non-empty fold sizes differing by at most one when `2 ≤ k ≤ n`.
- `.repeat(r)` is available on a design with one native repeat and requires `r ≥ 1`. It preserves the base fold axis and per-repeat `Coverage.Exact`, but drops `Coverage.ExactOnce`, producing shape `(r, baseFolds)`; repeat `p` recompiles the base allocation with the `(Repeat, p)` child stream. Families with an explicit `times` parameter already own the repeat axis and do not also expose `.repeat`, avoiding an ambiguous nested flattening rule.
- Stratified, grouped, and grouped-stratified K-fold replace only the allocation step with §4.6; they use the same complement construction and repeat rule. `LeaveOneOut` is the seed-independent partition into the `n` singleton assessments in ascending ordinal order. `LeaveOneGroupOut` uses the canonical group order from §4.6.

**Holdout and Monte Carlo.**

- Compute the named-role size `q` by §4.9. Fisher–Yates shuffle `[0, n)` once per unit; the first `q` ordinals form the named role and the remaining ordinals form the other role. Sort both selections before constructing the split.
- An implementation may stop the descending Fisher–Yates loop after processing
  position `q`: all later swaps are confined to `[0, q)` and therefore only
  permute a prefix whose order `Selection` discards. A linear membership scan
  may then emit both roles in increasing order. This shortcut must be
  extensionally identical to the complete shuffle-and-sort algorithm for the
  same seed; exhaustive small differential tests and the golden fixtures lock
  that identity.
- `Holdout` has one unit. `MonteCarlo(..., times)` has shape `(times, 1)`, requires `times ≥ 1`, and gives each unit its own child stream. Assessments may overlap and duplicate splits are legal.

**Ordinary and grouped bootstrap.**

- `Bootstrap(times)` has shape `(times, 1)`. Each unit makes exactly `n` independent uniform draws from `[0, n)` using bounded rejection sampling. Their generation order is the analysis `Draw`; assessment is the ascending complement of its support.
- `Bootstrap.grouped` uses the exactly-`g` whole-group procedure in §4.6, not `n` group draws and not a second within-group stage.
- `OobPolicy.Allow`, `Fail`, and `Redraw` change only candidate acceptance as specified in §4.9. Attempt 1 uses the unit stream; later attempts derive `(RedrawAttempt, attempt - 1)` beneath it. No policy sorts, deduplicates, or otherwise changes an accepted draw.

**Jackknife.**

- `delete1` has shape `(1, n)` and enumerates deleted singleton ordinals `0, …, n−1`; each assessment is that singleton and analysis is its `ComplementOf` view. It is exactly covered and seed-independent.
- `deleteD.exhaustive(d, budget)` requires `2 ≤ d < n` (`d = 1` has the separately typed `delete1` constructor), has shape `(1, C(n,d))`, orders all ascending size-`d` combinations lexicographically, and maps unit rank `j` to the `j`-th combination by combinatorial unranking. The assessment stores those `d` ordinals; analysis is `ComplementOf(assessment)`. The constructor first enforces §4.8's exact unit-count budget.
- `deleteD.sampled(d, times)` has the same `2 ≤ d < n` bound and shape `(times, 1)`. Each of its `times ≥ 1` unit streams draws one rank uniformly from `[0, C(n,d))` with `nextBigIntBounded` and uses the same unranking. Sampled units are independent with replacement: duplicate deleted combinations are legal and are not retried.

**Permutations.**

- `PermutationDesign(times)` has shape `(times, 1)` and Fisher–Yates shuffles `[0, n)` once for each of `times ≥ 1` units. The resulting sequence is the `Permutation`; identity and duplicate permutations are legal.
- `PermutationDesign.within(blocks, times)` takes exchangeability blocks from canonical `Labels`. For each unit and each canonical block, it Fisher–Yates shuffles that block's ascending members using an `(ExchangeabilityBlock, blockOrdinal)` child stream, then writes them back into that block's ascending positions. Thus every output is bijective and cannot move an ordinal across block membership.

## 5. Design catalogue (`tessera-designs`)

v0.1 (ordered by dependency). The `Cov` column is the type-level coverage capability from §4.3. `Exact` means once per repeat; the stronger `ExactOnce` additionally proves that the plan has one repeat and is therefore admissible to Alder's `CompleteResampler` (Alder D19).

| Design | Compiles to | `Cov` | Notes |
|---|---|---|---|
| `Holdout.assessing/analyzing(f)` | `Plan[Split[Selection], _]` | `Coverage` | §4.9 rounding |
| `MonteCarlo.assessing/analyzing(f, t)` | 〃 | `Coverage` | shuffle-split; overlapping assessments |
| `KFold(k)` | 〃 | **`ExactOnce`** | |
| `KFold.stratified(k, strata)` | 〃 | **`ExactOnce`** | ±1 count bound, provable (§4.6) |
| `KFold.grouped(k, groups)` | 〃 | **`ExactOnce`** | group atomicity absolute; balance best-effort |
| `KFold.groupedStratified(k, g, s)` | 〃 | **`ExactOnce`** | atomicity absolute; balance best-effort, diagnosed |
| `.repeat(r)` combinator | shape `(r, k)` | **`Exact`** | drops `ExactOnce`; independent child streams (§4.10) |
| `LeaveOneOut` / `LeaveOneGroupOut` | 〃 | **`ExactOnce`** | degenerate KFold; O(1) state (§4.8) |
| `Bootstrap(t)` / `.grouped(t, g)` | `Plan[Split[Draw], Coverage]` | `Coverage` | assessment = OOB; grouped draws exactly `g` whole clusters (§4.6) |
| `Jackknife.delete1` | `Plan[Split[Selection], _]` | **`ExactOnce`** | |
| `Jackknife.deleteD.exhaustive/sampled` | 〃 | `Coverage` | unit budget enforced (§4.8) |
| `PermutationDesign(t)` | `Plan[Permutation, Coverage]` | `Coverage` | free shuffle; duplicates allowed |
| `PermutationDesign.within(blocks, t)` | 〃 | `Coverage` | exchangeability blocks |

Post-v0.1: rolling-origin / time-series windows, balanced/blocked bootstrap, two-level (nested-group) designs, two-stage grouped bootstrap. Rolling-origin is the motivating *counterexample* for the `Coverage` type parameter — it leaves its initial analysis window unassessed and so must compile to `Coverage`, never `Exact` (alder D19 names it explicitly). Having the capability in the type before that design exists is the point.

Nested CV requires **no special design**: the consumer compiles an inner design against each outer analysis set's own `IndexSpace` (of size m = analysis size), then embeds the inner ordinals through the outer `Selection` by composition. `Selection ∘ Selection = Selection` (§4.2) is precisely what guarantees inner folds cannot touch outer assessment rows — the closure table is the proof obligation, and law 1 plus law 3 discharge it.

## 6. Verification (`tessera-laws` + test suites)

Published module (ScalaCheck at compile scope, like alder-laws) so consumers can run the same bundles over their adapters. The v0.1 draft filed everything under "laws"; two of those statements were not laws, and golden fixtures were doing work they cannot do. Five distinct kinds of evidence, kept apart:

### 6.1 Laws — deterministic universal statements

1. **Pullback functoriality** — `x^(ρ∘σ) = (x^ρ)^σ`; identity reindexing is neutral.
2. **Coverage** — for any `Plan[Split[Selection], Coverage.Exact]`, assessment blocks within a repeat partition `I_n` exactly. Statically scoped to `Exact` plans, which is the point of the type parameter.
3. **Disjointness** — per split, `analysis.support ∩ assessment = ∅` (also enforced at construction; the law guards the factories).
4. **Determinism / replay** — same `(design, seed, n)` (with labels already embedded in the design) ⇒ bit-identical plan and, when receipt production is requested with the same digest capability, identical assignment digest on all three platforms.
5. **Group atomicity** — a group never straddles analysis/assessment within a split. Absolute for every grouped design.
6. **Stratification balance** — for `KFold.stratified`, every fold's stratum count is `floor(n_s/k)` or `ceil(n_s/k)`. A hard bound, provable, therefore a law. (`groupedStratified` is best-effort and is checked by the §6.2 diagnostics suite instead — a best-effort heuristic has no law to satisfy.)
7. **Multiset and order preservation** — bootstrap draw sizes, multiplicities, *and sequence order* survive composition and pullback; OOB = complement of draw support.
8. **Permutation group laws** — closure, associativity, identity, inverse; within-block permutations fix block membership.
9. **Reconstruction** — scattering per-fold assessment outputs back through their `Selection`s reassembles the original order exactly once per repeat, for `Coverage.Exact` plans (the substrate for alder's out-of-fold predictions).
10. **Receipt verification** — `verify` succeeds against a faithful recompilation with the matching digest provider and population fingerprint. It fails, naming the right component, against perturbations of design, design-owned labels, population, and assignment, and reports provider-id mismatch before comparing digest bytes. Perturbing only the stored seed reports assignment mismatch exactly when recompilation changes the assignment; seed-independent/colliding assignments still verify, so an expected-seed check is a separate consumer comparison.
11. **Error totality** — infeasible configurations return typed errors; no exceptions (ScalaCheck over adversarial configs).
12. **Label recoding invariance** (metamorphic) — applying any bijection to group or stratum codes yields the same partition. This is the law that keeps §4.6's algorithms honest about never reading code values.
13. **Injection factorization** — every `Injection` equals `sel.after(perm)` for its unique `factor` decomposition.
14. **Backing transparency** — equal partitions/selections have equal equality/hash/encoding behavior under explicit assignments, `SingletonIdentity`, `Explicit`, `Block`, `ComplementBlock`, `LabelClasses`, and `ComplementOf`; complementing a complement returns the original semantic selection, and the specialized `ComplementOf` double-complement returns the same base value.
15. **Design-extension conformance** — a general definition is total and deterministic over its declared shape, observes its `PlanCost`, and changes its canonical assignment encoding whenever one public unit changes. An exact-partition definition additionally inherits laws 2, 3, and 9 from core-derived splits. Compile-time negative tests prove that `general` cannot produce `Coverage.Exact`.

### 6.2 Statistical suite — calibrated, not universal

Explicitly **not** laws, run with fixed seed sets and stated confidence bounds:

- **Seed sensitivity.** "Different seeds produce different assignments" is false as a universal statement — output spaces are finite, collisions are legal, and some designs/configurations are seed-independent by contract (`LeaveOneOut`, `Jackknife.delete1`, and degenerate K-fold cases). No collision-rate bound is inferred merely from output-space cardinality; that would require a uniformity proof. Instead, every seed-sensitive algorithm has a purpose-built fixture that exercises its random degrees of freedom and demonstrates at least two distinct assignments over a fixed seed set. Grouped and grouped-stratified fixtures contain equal-size groups and equal-cost fold choices so both seeded tie-break paths are exercised. Seed-independent configurations are declared and asserted invariant. Repeated units may legally collide.
- **Bootstrap out-of-bag fraction.** Under `OobPolicy.Allow`, ordinary bootstrap's finite-*n* expectation is `(1 − 1/n)^n`, not `e⁻¹` — the latter is only its limit (at n=10 the true value is 0.349, ~5% below e⁻¹, which a naive tolerance would either miss or false-alarm on). Grouped bootstrap uses the distinct expectation `(1 − 1/g)^g` for both row- and group-level OOB fractions. Each is tested over fixed seeds with a Hoeffding bound at a stated α, including unequal group sizes for the grouped case. `Redraw` is tested separately against exact small-case enumeration of its conditioned distribution and exhaustion probability; it is never compared to an unconditional formula.
- **Grouped-bootstrap draw length.** With exactly `g` uniform group draws, `E[L] = n` even for unequal group sizes. The fixed-seed suite checks the normalized bounded variable `(L − g·m_min) / (g·(m_max − m_min))` against the corresponding transformed expectation using a stated Hoeffding bound and α; the all-equal-size case is asserted exactly rather than sent through a zero-denominator statistic.
- **Sampled delete-*d* uniformity.** For small `C(n,d)`, observed sampled ranks are checked against the discrete uniform distribution with a predeclared goodness-of-fit statistic, α, and fixed seed set. Duplicate combinations remain legal; the test does not condition on distinct outputs.
- **Balance diagnostics.** `groupedStratified` uses exact `BigInt` objective `J`; exhaustive small-case oracles report additive regret `Jheuristic − J*`. One-sided regression thresholds reject larger regret and accept improvements. `grouped` size imbalance is treated the same way.

### 6.3 Oracles and adversarial cases

- **Exhaustive oracles.** For small `n`, `k`, and label configurations, enumerate every legal allocation and compare: the exact plain/stratified K-fold deals from §4.10; exact minimum `J*` and zero-safe additive regret for grouped-stratified allocation; exact minimum fold-size imbalance for grouped allocation; lexicographic rank/unrank round trips and complete enumeration for `Jackknife.deleteD.exhaustive`; sampled-delete-*d* ranks against the same unranking table; every generated `Permutation` against the full symmetric group on `n ≤ 8`.
- **Extension oracles.** A test-only general design and exact-partition design run through the public SPI. Deliberately perturbed generators, encoders, cost declarations, and partitions demonstrate that each conformance check bites; a compile-time negative fixture demonstrates that the general route cannot forge `Coverage.Exact`.
- **Adversarial degeneracies.** `n ∈ {0,1,2}`, `k ∈ {1, n−1, n, n+1}`, single-member strata, one group covering everything, all-distinct groups, empty OOB, `C(n,d)` overflow.
- **Golden fixtures** pin exact outputs per `(design, seed, n)` on all three platforms. They are **compatibility locks** — they detect drift, they prove nothing about correctness, and they must never be the only evidence for a design. Every design ships oracle or law coverage in addition.

### 6.4 Cost guardrails

Allocation, unit-count, candidate-generation, and canonical-encoding work assertions against §4.8 — e.g. `LeaveOneOut(n = 100_000)` compiles within a bounded allocation budget and never materializes n² indices; obtaining `keys` for a million-unit shape remains O(1) allocation; exhaustive delete-*d* retains no complement-sized analysis arrays; grouped bootstrap work tracks `g + L`, not an assumed `n`; `Redraw(a)` never evaluates more than `a` candidates per unit; receipt construction retains no assignment vectors. `materialized` is separately asserted to perform one eager traversal and to leave the source plan observationally unchanged. Correctness gates that pass while the library quietly becomes unusable at scale are not gates.

### 6.5 Cross-language benchmark evidence

Cross-language timing is accepted only after semantic parity is established.
The benchmark task is to generate, canonicalize, materialize, and consume every
analysis and assessment ordinal through each library's public API. Model
fitting, scoring, process startup, dependency loading, and feature-data copying
are outside the timer.

Each manifest case owns a deterministic input fixture and contract id. All
runners must agree on the fixture checksum, unit count, analysis count, and,
except for random bootstrap OOB size, assessment count. They independently
prove exact coverage, role disjointness, group atomicity, stratum diagnostics,
requested Monte Carlo size, or draw/OOB semantics before a timing row is
accepted. Different RNGs and allocation algorithms may produce different legal
assignments.

Non-bootstrap comparator roles are sorted inside the timed region so the
observable artifact matches Tessera's increasing `Selection`. Bootstrap retains
draw order and uses `OobPolicy.Allow`, which matches an unconditional n-of-n
bootstrap rather than Tessera's redraw-conditioned default. Grouped-stratified
timings are reported with the common integer objective `J`; a faster but much
worse allocation is not represented as an unqualified win.

The primary low-level comparators are scikit-learn and splitTools. rsample is a
separate public-workflow comparator whose timing necessarily includes its
data-frame split objects. Raw rows, validated aggregates, environment versions,
and interpretation boundaries are stored together. Ratios are machine-specific
directional evidence, not laws, complexity guarantees, or universal speed
claims.

## 7. Module and dependency map

```
tessera-core      IndexSpace, Reindexing lattice (Draw/Injection/Selection/Permutation),
                  FoldPartition, Split, Coverage, Plan, Seed/Rand, Design, Labels,
                  DesignDescriptor/DesignDefinition, PlanCost, canonical encoders,
                  Fingerprint/DigestAlgorithm/DigestAccumulator/PlanReceipt,
                  DesignError
tessera-designs   the catalogue of §5 (depends on core)
tessera-laws      law bundles + generators + oracles (depends on core+designs; scalacheck at compile scope)
tessera-benchmarks non-published JVM harness + Python/R comparators (test/evidence only)
```

`Design` and `Labels` live in **core**, not designs — the protocol is a core type that both the catalogue and consumers' own designs implement. (The v0.1 plan had them arriving with the KFold family, which made the phase graph circular; see §12 D8.)

**Runtime dependencies: none.** Not even cats — the core needs nothing from it, zero-deps maximizes reuse across the workspace (gale proves the model), and alder already layers cats on its side. If typeclass instances are ever wanted, they go in an optional `tessera-cats` interop module, never the core.

## 8. Build and compatibility

Match the alder/gale house style exactly:

- Scala **3.3.8 LTS**; sbt 1.11.x; org `io.github.canardlapin`; Apache-2.0; version `0.1.0-SNAPSHOT`.
- `crossProject(JVM, JS, Native)`, `CrossType.Pure` — Native included because alder targets it.
- Alder's strict flags verbatim: `-Werror -deprecation -feature -unchecked -Wunused:all -Wvalue-discard -Yexplicit-nulls -language:strictEquality`.
- Tests: munit + munit-scalacheck; laws via the same pattern as alder-laws (discipline optional — plain ScalaCheck bundles suffice without cats).
- Public frozen types are final classes with accessors (§4.3), which is what makes a MiMa-frozen surface extensible later.
- Post-v0.1: MiMa + TASTy-MiMa baselines once the surface freezes; golden-fixture cross-platform CI job from day one.
- The non-published benchmark harness is JVM-only. Python and R environments
  are separately locked; their dependencies never enter Tessera artifacts.

## 9. Integration contract with alder (informative, not normative for tessera)

What `alder-resampling` will do with tessera — recorded here so the boundary is designed on purpose:

- **Ordinals ↔ RowId.** Alder holds the `Int → RowId` correspondence privately (it can; tessera cannot — `RowId.apply` is `private[alder]`). Tessera plans are pure ordinal allocations.
- **Labels.** Alder derives `Labels` from its `GroupOf`/`TimeOf`/stratum capabilities and hands tessera coded vectors.
- **Seeds.** `tessera.Seed.fromLong(alderSeed.value)`; alder decides which of its derived seeds feeds which design.
- **Roles.** Alder maps analysis/assessment onto its `Use.Train`/`Use.Test` phantom types when materializing views.
- **Completeness (Alder D19).** The adapter's `CompleteResampler` factory takes `Plan[Split[Selection], Coverage.ExactOnce]` and is **total** — no runtime coverage check, no coverage failure mode. Plans from Holdout/MonteCarlo/Bootstrap and repeated exact designs do not typecheck there. Split-time checks bind the already compiled plan to the later Alder value (population size, population fingerprint, and seed); those are not coverage checks. This is the concrete reason coverage has both `Exact` and `ExactOnce` capabilities (§12 D2, D23).
- **Audit (alder D15).** `PlanReceipt`'s policy-tagged `Fingerprint`s map directly onto alder's `ContentDigest | SourceIdentity | Summary` tags in `Audit`/`PreparationLineage`. Alder supplies the population fingerprint (it knows the RowIds; tessera does not) and stores the receipt verbatim.
- **Cross-fit exclusion.** Alder's `crossFitExclusion` law (its open blocker for `FeatureMap.crossFitted`) is proved on alder's side with instrumented data; tessera's disjointness + reconstruction laws are the substrate it relies on.

**Flagged conflicts for alder to resolve (not tessera decisions):**

1. Alder's ratified PRD.json currently homes the `Resampler` protocol and splitting inside `alder-data`. Adopting tessera means an alder PRD amendment: `alder-data` (or a thin `alder-resampling`) *interprets* tessera plans instead of owning splitting. Alder's forbidden-dependency list must whitelist `tessera-core` (zero-dep, so this is cheap).
2. `notes3.txt` sketches `Workflow(features, learner)` and `NestedCrossValidation.run(...)`; alder's ratified D3 removed `Workflow` (a workflow *is* `featureMap.learnWith(learner)`). The notes3 surface is aspiration, not spec. The three distinct prediction products it rightly demands — `OutOfFoldPredictions`, `ModelPredictions`, `ExternalPredictions` — are alder result types; tessera's contribution is the `Plan`/`Split`/`UnitKey`/`Coverage` provenance that makes them constructible without leakage.
3. Alder is under active concurrent development (kernel plan-normalization in flight). Integration is a later phase gated on `alder-data` existing; tessera's *development* does not block on it, though tessera's *surface freeze* does (PLAN phase 5, §12 D9).

## 10. Open decisions

- **O1 — Name.** `tessera` recommended; decide before first publish.
- **O2 — `Labels` for rolling-origin.** Time-ordered designs need an order contract, not labels; deferred with the time-series phase. The `Coverage` machinery it needs is now in place from day one.
- **O3 — Weighted draws.** resample4s sketched weights on `RowSample`; excluded from v0.1 (no consumer yet), revisit with importance-sampling use cases.
- **O4 — Int vs Long populations.** v0.1 fixes `Int` ordinals (n ≤ 2³¹−1); alder's `RowId` is Long-backed but population *sizes* beyond Int range are out of scope for in-memory resampling.
- **O5 — `UnitKey.fold` width.** Exhaustive delete-*d* can exceed `Int.MaxValue` units in principle; v0.1 caps at the unit budget (§4.8) rather than widening the key. Revisit only if a consumer needs it.

## 11. Risks

- **Two seed-derivation schemes** (alder's private one, tessera's) could confuse audits. Mitigation: `PlanReceipt` records tessera's inputs explicitly and tags its algorithm; alder records which alder-seed it passed in.
- **Cross-platform bit-stability** is claimed, so it must be tested, not asserted: golden fixtures on JVM+JS+Native in CI from phase 1, and no floating point in any size or index path (§4.9).
- **Lazy plans expose recomputation cost.** A caller who repeatedly reads `at` on a bootstrap plan pays O(n), or O(g + L) for grouped bootstrap, each time. Mitigation: `materialize` and the eager immutable `materialized` conversion are explicit and documented at the call site; §6.4 guardrails cover both lazy and eager storage, and the §4.8 table states per-unit work so the tradeoff is visible.
- **Best-effort allocation could regress silently.** `groupedStratified` has no provable approximation bound, so exhaustive small cases compute exact additive regret under objective `J`, and larger reference distributions use one-sided regression thresholds (§6.2). Improvements never fail merely because they differ from an old snapshot.
- **API freeze pressure from alder.** Alder plans MiMa-frozen surfaces including resampling-adjacent traits; the phase-5 integration spike exists precisely to feed boundary corrections back into tessera before tessera tags 0.1.0.

## 12. Decision log

Resolutions from three independent review passes on 2026-07-25, beginning with PRD v0.1.

| # | Decision | Rationale |
|---|---|---|
| **D1** | `PlanReceipt` is a **verification** artifact, not a replay specification: policy-tagged digests + seed, with `verify(design, space, population)(using DigestAlgorithm)`. Labels have one authority: they are embedded in the design. | A self-contained receipt would carry design parameters and label material into stored audits — the exact hazard alder D15 exists to prevent. Verification gets the audit property without the payload. v0.1's `populationSize: Int` also contradicted its own P6 promise of a population *digest*. |
| **D2** | Coverage is a **type parameter** on `Plan[+A, +Cov <: Coverage]`: `Exact` means once per repeat and `ExactOnce <: Exact` additionally proves one repeat. `map` preserves the capability; `.repeat` preserves `Exact` but drops `ExactOnce`; `zip` widens to `Cov \| C2`. | Alder D19 requires exactly once over the entire plan to determine which programs are legal. Holdout lacks even per-repeat coverage, while repeated K-fold has per-repeat coverage but would produce multiple OOF values per row. Both distinctions must be static for a total adapter. |
| **D3** | `Plan` is **lazy** (shape + pure `UnitKey => A`) and `Selection` gains block/complement backings; `materialize` and eager immutable `materialized` are explicit. | v0.1's materialized `IArray[Int]` selections made LOO and delete-1 jackknife Θ(n²) stored indices and exhaustive delete-*d* impossible. Laziness plus combinatorial unranking gives the compiled generators O(1) state (§4.8), without a hidden mutable memoization cache. |
| **D4** | Exhaustive and sampled delete-*d* are **separate constructors**; exhaustive enforces an explicit unit budget with a typed error. | `Jackknife` in v0.1 said "delete-1 / delete-d" without saying whether delete-*d* was exhaustive, which is a `C(n,d)` vs `t` difference in unit count. |
| **D5** | Grouped/stratified allocation is **fully specified** (§4.6): canonical orders, named algorithms, deterministic tie-breaks, and an explicit split between *absolute* guarantees (group atomicity, stratified ±1) and *best-effort* ones (grouped balance, grouped-stratified balance) with diagnostics. Oversized groups and small strata are **not** errors. | v0.1 listed "oversized group" and "unstratifiable stratum" as infeasible configurations; neither is. A group larger than n/k just makes folds unbalanced, and a stratum with < k members is exactly what the ±1 bound already describes. |
| **D6** | `Injection` joins the lattice and the composition closure table is explicit (§4.2), realized as a `Compose[F, G] { type Out }` typeclass. | `Selection ∘ Permutation` is injective but not increasing, so v0.1's three-class hierarchy was not closed under its own keystone operation. |
| **D7** | Public accessors are total (`at: Either[…, Int]`, `Plan.at: Either[UnknownUnit, A]`); unchecked variants are `private[tessera]`; `IndexSpace`/`Labels` get private constructors, validated factories, and defensive copies; frozen types are final classes, not case classes. | v0.1 claimed "total functions, no partial functions" (P5) while specifying `apply(i: Int): Int` and public case-class constructors. `IArray` can alias a mutable `Array`, so validation without copying proves nothing. |
| **D8** | `Design` and `Labels` move to **tessera-core** and land in phase 1. | Every phase-3 design depends on `Design`, and grouped bootstrap depends on `Labels`; with both arriving in phase 2, phases 2 and 3 could not be independent as the plan claimed. |
| **D9** | Phase 5 slipping blocks the **surface freeze**, not development: without it, tessera tags `0.1.0-M1` with no MiMa baseline and an explicitly unfrozen surface. | v0.1 said both "phase 5 feeds corrections back before tagging" and "phase 5 can slip without blocking tessera". Those are incompatible; separating *tag* from *freeze* satisfies both intentions. |
| **D10** | Rolling-origin is **post-v0.1, unconditionally**, and PLAN phase 6 no longer offers it as an option. | The PRD deferred it and the plan conditionally included it. It is also the alder D19 counterexample, so it is more useful as the thing `Coverage` was designed against than as a rushed v0.1 addition. |
| **D11** | Seed sensitivity and OOB fraction move from **laws to a calibrated statistical suite** (§6.2), with the exact finite-*n* expectation `(1 − 1/n)^n`; golden fixtures are declared compatibility locks and never sole evidence (§6.3). | "Different seeds ⇒ different assignments" is false pairwise on a finite output space and false by construction for LOO-class designs. `e⁻¹` is a limit, not the expectation — at n=10 the true value is ~5% lower. |
| **D12** | Fractions are **exact rationals** with integer round-half-up; `Holdout`/`MonteCarlo` name their role at the constructor (`.assessing`/`.analyzing`); empty-OOB policy is an explicit `OobPolicy` defaulting to bounded redraw, with the resulting distributional bias documented. | Each was an unstated default where the plausible reading is silently wrong; naming beats documenting. Round-half-up in integer arithmetic also keeps P3's no-floating-point rule intact. |
| **D13** | Digesting is an **open capability**: `DigestAlgorithm` has a validated id and consumes canonical chunks; `DigestValue` owns arbitrary-length bytes. Receipt design/labels/assignment fields require `ContentDigest`, while population retains the full policy-tagged `Fingerprint` union. | v0.2 called a closed enum plus a `Long` an extension point, but that representation could neither name consumer algorithms nor hold a cryptographic digest. Arbitrary bytes permit real adapters without adding a tessera runtime dependency. |
| **D14** | Lazy plans have **no late design failures**. Compilation preflights every fallible unit-generation policy and stores accepted child seeds; `Compiled.receipt` is a separate streaming traversal with an explicit work contract. | v0.2 allowed bounded OOB redraw to fail while `Plan.at` could report only `UnknownUnit`, and it did not account for hashing a lazy plan. Resolving fallible generation before plan construction preserves total access without hiding receipt work inside compilation. |
| **D15** | Grouped and grouped-stratified designs use **seeded, recoding-invariant tie-breaking**: `Labels` are canonically recoded by minimum member ordinal, a fixed internal `DesignKey` separates RNG from audit-digest choice, size buckets retain LPT order, equal-size groups are shuffled by canonical bucket streams, and equal-cost folds use a seed-derived priority permutation. Repeated units may still collide. | v0.2's canonical order and smallest-fold tie-break never read the seed, so `.repeat(r)` produced identical grouped partitions while claiming independent child streams. Canonical label storage plus domain-separated seed paths preserves recoding invariance through randomization, while the separate key prevents a consumer-selected receipt algorithm from changing assignments. |
| **D16** | Grouped-stratified allocation minimizes the exact incremental change in a fully specified global objective `J`, using `BigInt`; oracle quality is zero-safe additive regret with one-sided regression thresholds. | “Targets recomputed from remaining mass” was not executable, fractional targets conflicted with the no-floating-point allocation rule, fixed-width squares could overflow, and an optimum ratio is undefined when the optimum is zero. |
| **D17** | `Selection` adds `ComplementOf(base)` and `LabelClasses` backings, `FoldPartition` adds O(1) `SingletonIdentity`, and equality/hash/encoding are extensional across backings. Delete-*d* stores O(d); grouped OOB stores a class bitset; LOO stores O(1). | Naming only “complement backings” did not show how non-block deletion, grouped OOB, or the shared LOO partition avoided O(n) arrays. Representation-transparent semantics and the specialized double-complement rule keep these optimizations out of user-visible behavior. |
| **D18** | `PlanShape.of` validates positive axes and an `Int`-sized product; `Plan.keys` is an O(1)-state mixed-radix view; `materialized` is an eager immutable conversion, not a cache. | A lazy plan whose `keys` allocated a `Vector` was not lazy at large unit counts, an unchecked shape could overflow its linear ordinal, and a mutable memoization cell would violate the pure-value/concurrency boundary and make costs history-dependent. |
| **D19** | Grouped bootstrap draws exactly `g` canonical groups uniformly with replacement; row length is variable with expectation `n`, row/group OOB expectation is `(1 − 1/g)^g`, and `g·m_max > Int.MaxValue` is rejected before plan construction. | “Whole clusters with replacement” did not say how many cluster draws to make, so assignment law, OOB calibration, complexity, and representability were under-specified. The chosen rule is the direct cluster analogue of the ordinary n-of-n bootstrap without a late oversized-`Draw` failure. |
| **D20** | Public design extension uses `Design.definition`: a canonical typed descriptor plus either a general `Coverage` plan spec or core-derived exact partitions. The general route cannot mint `Coverage.Exact`. | Private `Plan`/`Compiled` constructors protected invariants but otherwise made `Design` nominally extensible and practically sealed. A two-route SPI restores consumer designs while keeping coverage evidence, receipts, failure timing, and cost accounting framework-owned. |
| **D21** | Every v0.1 catalogue family has a normative generator (§4.10), including shuffle/deal K-fold, shuffle-split holdout, n-of-n bootstrap, lexicographic delete-*d* unranking with uniform sampled ranks, and free/within-block Fisher–Yates permutations. | Names such as “shuffle-split” and “sampled delete-d” are not algorithms: they leave stream paths, ordering, replacement, rank distribution, and duplicate handling open, defeating cross-platform fixtures and honest statistical tests. |
| **D22** | The implementation discipline pass removes erased coverage casts, replaces string-keyed diagnostics with `DiagnosticMetric`, and lets a definition own a framed, defensively copied sequence of `Labels`. | The initial implementation could recover its generic route only with `asInstanceOf`, could misspell observable quality metrics, and could commit only groups—not strata—as the grouped-stratified label fingerprint. A typed compile closure, closed metric ADT, and label-set framing preserve all three guarantees without widening the public error channel. |
| **D23** | Alder integration adds `Coverage.ExactOnce`; one-repeat exact catalogue designs and the core `exactOncePartitions` route mint it, while repeated designs return only `Exact`. Alder's total `CompleteResampler` factory accepts `ExactOnce` and retains a policy-tagged rendering of the full `PlanReceipt`. | The phase-5 spike found that v0.5's `Exact` promise was per repeat but Alder D19 is exactly once over the whole plan. A repeated K-fold was therefore a counterexample to the claimed adapter signature. The stronger capability fixes the consumer boundary without weakening the per-repeat coverage and reconstruction laws. |
| **D24** | An open digest provider creates an independent incremental `DigestAccumulator` per invocation. Canonical receipt bytes are pushed synchronously as they are generated; the framework never buffers one general-plan unit before hashing it. | The phase-4 fresh review found that the iterator-shaped provider API was open but the writer accumulated Θ(unit size) chunks, contradicting the O(1)-state receipt contract. A stateful per-invocation capability keeps providers open while making streaming observable and testable. |
| **D25** | Exact `Optimum`/`Regret` diagnostics are computed only on the explicit bounded frontier `n ≤ 32 ∧ k^g ≤ 100000`; repeated grouped designs aggregate worst achieved quality instead of discarding diagnostics. The independent test oracle exhausts all canonical small label partitions. | “Where available” was previously implemented only as a few handpicked test fixtures, and repeated grouped-stratified plans retained only their repeat count. A bounded exact frontier makes availability honest and predictable without changing the asymptotic compilation contract. |
| **D26** | The published law module exposes full label-recoding equivalence (owned labels, randomization key, fingerprints, and compiled assignments) and bootstrap order/multiplicity preservation through composition. | The catalogue tests covered these universal claims, but the consumer-facing bundle exposed only weaker assignment equivalence and single-plan bootstrap semantics. The release surface now matches laws 7 and 12 rather than relying on internal evidence. |
| **D27** | Cross-language benchmarks compare one canonical public artifact, not similarly named constructors: every accepted timing cell first proves the same fixture and semantic contract; canonical sorting, complete materialization, and linear consumption occur inside the timer. Grouped-stratified quality accompanies time, and rsample's public-object lane is separated from index-kernel comparators. | Constructor-only races would reward laziness or eager allocation arbitrarily, Python-level checksum loops would measure the harness, redraw-conditioned and unconditional bootstrap are different distributions, and grouped heuristics can trade quality for speed. The parity protocol makes different algorithms and structures comparable without pretending they are identical. |
| **D28** | `Int`-bounded rejection uses primitive unsigned 64-bit arithmetic, and shuffle-split stops once the named prefix set is fixed before emitting both sorted roles with one membership scan. Both kernels must remain exactly equivalent to the previous `BigInt` rejection and complete Fisher–Yates-plus-sort definitions for every seed. | JFR showed `BigInt` division/allocation and dual array sorting dominated Monte Carlo. The optimized kernels remove representation work without changing random words, accepted draws, child-stream state, role membership, golden fixtures, or the public O(n) per-unit contract. |
