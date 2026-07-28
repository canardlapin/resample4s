package object resample4s:
  export resample4s.core.{
    AlgorithmId,
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
    DesignError,
    ErrorCode,
    ErrorCodes,
    pull
  }
  export resample4s.core.Seed.{derive, value}
  export resample4s.core.AlgorithmId.value

  export resample4s.designs.NestedFold

  type ResampleError = DesignError
