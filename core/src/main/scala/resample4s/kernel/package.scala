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
    Permutation,
    Plan,
    PlanShape,
    Reindexing,
    Seed,
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
