# Release readiness

Last verified: 2026-07-28 (in-repo freeze focus: CI, surface review, benches)

## Scope for early development

Focus on this repository. Sonatype / Maven Central publication can wait.
External consumer rehearsals (ScalaFIM, further Alder follow-ups) stay outside
the critical path for day-to-day development. Local `publishLocalAll` is the
publication rehearsal.

## Active in-repo gates

1. **Hosted CI on HEAD** — JVM, Scala.js, Scala Native, compatibility, façade
   tests, and Scalafmt (`fmtCheck`) must pass on the freeze surface.
2. **Independent public-surface review** — record findings and resolve release
   blockers before tagging. A Bugbot pass on 2026-07-28 filed two façade
   defects (literal fold ids for `PredefinedSplit.fromAssignments`; grouped
   `SplitSize.Count` as group count). Both are fixed in-tree with façade
   regressions.
3. **Docs match the façade** — README status, concepts / integrator / author
   guides, and seed-to-assignment policy stay current with the API.
4. **Benchmark / optimization hygiene** — keep `benchmarkCheck` green; refresh
   standard-profile evidence when kernels change; retain JFR / performance
   notes under `docs/performance/`. Optimizations are ongoing, not a one-shot
   gate.

`CHANGELOG.md` remains `Unreleased` and the version remains `0.1.0-SNAPSHOT`
until hosted CI and the surface review are closed.

## Completed evidence

- Freeze-readiness Phases A–E landed in-tree: coverage honesty, linear compact
  traversal, constructive schedules / open SPI / API rings, façade artifact
  `resample4s`, catalogue growth, Scalafmt, POM metadata, and docs guides.
  Rolling-origin remains deferred (PRD D10).
- JVM suites after the 2026-07-28 façade fixes: re-run before push. Prior Phase E
  counts were core 62, designs 67, api 9, laws 10 with `fmtCheck` green.
- `publishLocalAll` refreshed local jars/sources/Scaladoc/POMs for core,
  designs, api, and laws on JVM, Scala.js, and Scala Native (2026-07-27).
- `sbt -batch compatibilityAll`: MiMa / TASTy-MiMa remain configured with an
  empty previous-artifact set while version is `0.1.0-SNAPSHOT` / baseline
  `0.1.0`.
- Hosted CI run `30256827936` passed JVM, Scala.js, Scala Native, and
  compatibility on commit `81e4a22` (pre-façade). Re-establish green on HEAD
  after push.
- Semantic-parity benchmark protocol and Monte Carlo kernel evidence remain
  under `benchmarks/results/2026-07-26-*` and
  `docs/performance/monte-carlo-2026-07-26.md`. `benchmarkCheck` passed on
  2026-07-28 (3 protocol tests + 7 smoke measurements).
- Capability soundness, compact-traversal, façade, and SPI openness suites gate
  the Phase A–C repairs.

## Local gate

```bash
sbt -batch fmtCheck testAll compatibilityAll benchmarkCheck publishLocalAll
```

## Deferred (not blocking early development)

- Sonatype / `sbt-ci-release` credentials and Central publish
- ScalaFIM fixed-allocation consumer rehearsal
- Broader Alder follow-up beyond the already-recorded integration spike

The stable tag must not be created while active in-repo gates above remain open.
