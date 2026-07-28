package resample4s

/**
 * Kernel ring: reindexing algebra, plans, and constructive completeness.
 *
 * Prefer `import resample4s.kernel.*` for library integrators. Types currently
 * live in [[resample4s.core]] and are re-exported here as the stable ring
 * boundary before the façade artifact lands.
 */
package object kernel:
  export resample4s.core.{
    AlgorithmId,
    Coverage,
    CompleteOnce,
    CompletePerRepeat,
    Compose,
    Draw,
    FoldLayout,
    FoldPartition,
    Fraction,
    IndexSpace,
    Injection,
    Labels,
    LabelRefinement,
    Permutation,
    Plan,
    PlanShape,
    Reindexing,
    Seed,
    StreamDomain,
    StreamPath,
    StreamSegment,
    Selection,
    Split,
    SplitPlans,
    UnitId,
    UnitKey,
    AnyPlan,
    pull,
    CodomainMismatch,
    DomainMismatch,
    OutOfDomain,
    ShapeMismatch,
    UnknownFold,
    UnknownUnit
  }
  export resample4s.core.Seed.{derive, value}
  export resample4s.core.AlgorithmId.value
