package object resample4s:
  export resample4s.core.{
    Coverage,
    CompleteOnce,
    CompletePerRepeat,
    Design,
    Draw,
    FoldLayout,
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
    DesignError,
    ErrorCode,
    ErrorCodes,
    pull
  }

  export resample4s.designs.NestedFold

  type ResampleError = DesignError
