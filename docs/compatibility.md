# Compatibility policy

Resample4s records `0.1.0` as its first binary and TASTy compatibility baseline.
There is no earlier artifact to compare while building the baseline release, so
MiMa and TASTy-MiMa intentionally receive an empty previous-artifact set for
`0.1.0` and its prerelease qualifiers.

For later versions, every published `resample4s`, `resample4s-core`,
`resample4s-designs`, and `resample4s-laws` artifact on JVM, Scala.js, and
Scala Native is compared against:

```text
io.github.canardlapin:<platform artifact>:0.1.0
```

The full compatibility gate is:

```text
sbt -batch compatibilityAll
```

MiMa covers binary linkage and TASTy-MiMa covers Scala 3 retyping
compatibility. Neither tool proves semantic compatibility; behavioral laws,
golden locks, and release notes remain separate requirements.

## Seed-to-assignment compatibility

For a fixed triple `(design, population size, seed)`, assignment bytes must be
identical across JVM, Scala.js, and Scala Native. Cross-platform golden fixtures
under the designs module are the normative lock for that contract.

A **semantic break** includes any of the following, even when MiMa is silent:

1. Changing a catalogue algorithm id (`kfold/v1` → `kfold/v2`).
2. Changing descriptor fields that participate in the randomization key.
3. Changing stream-path domains, ordinals, or derivation order.
4. Changing the normative generator for a family (shuffle deal, shuffle-split,
   bootstrap draw rule, delete-d unranking, and so on).
5. Changing label canonicalization or seeded tie-breaking.
6. Changing receipt canonical framing for an assignment encoding.

Allowed without a seed-compatibility bump:

- Additive façade constructors that expand to an existing algorithm id.
- Documentation, rendering, and non-assignment diagnostics.
- New algorithm ids that do not replace an old id’s meaning.
- Binary-compatible type additions that do not alter existing generators.

When a seed-compatible repair is impossible, publish a new algorithm id, keep
the old id’s goldens until a major removal, and record the cutover in
`CHANGELOG.md`.

## Coverage capabilities

`Coverage.Exact` / `ExactOnce` are part of the published type contract for
catalogue designs that mint them. Public `Plan.map` / `zip` return ordinary
`Coverage` and must not be treated as capability-preserving. Constructive
schedules (`CompleteOnce`, `CompletePerRepeat`) carry fold witnesses explicitly.

## Fixed allocations

The fixed-split descriptor schema begins at `fixed-splits/v1`. The
fixed-partition design schema begins at `fixed-partitions/v1`. Their golden
canonical bytes and receipts are part of the semantic compatibility evidence.
