package resample4s

/**
 * SPI ring: design authoring, descriptors, diagnostics, and stream tags.
 *
 * Prefer `import resample4s.spi.*` when writing auditable designs.
 */
package object spi:
  export resample4s.core.{
    AlgorithmId,
    BuildContext,
    CanonicalAssignmentEncoder,
    CanonicalWriter,
    Compiled,
    Design,
    DesignDefinition,
    DesignDescriptor,
    DesignError,
    DesignKey,
    DescriptorValue,
    ErrorCode,
    ErrorCodes,
    ExactPartitionSpec,
    GeneralPlanSpec,
    MetricId,
    Metrics,
    DiagnosticMetric,
    PlanCost,
    PlanDiagnostics,
    StreamDomain,
    StreamPath,
    StreamSegment
  }
  export resample4s.core.StreamDomain.{StreamTag, StreamTags}
