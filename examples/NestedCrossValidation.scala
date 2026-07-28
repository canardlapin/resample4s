package resample4s.examples

import resample4s.core.*
import resample4s.designs.*

/**
 * Compiles a complete nested cross-validation allocation.
 *
 * Each outer unit contains its outer split and an inner plan. The inner
 * selections are already expressed in the original population, so callers do
 * not perform ordinal composition themselves.
 */
object NestedCrossValidationExample:
  val compiled: Either[
    DesignError,
    Compiled[NestedFold, Coverage.ExactOnce]
  ] =
    for
      space <- IndexSpace.of(120)
      nested <- NestedCrossValidation(
        outerFolds = 5,
        innerFolds = 4
      ).compile(space, Seed.fromLong(42L))
    yield nested

  val firstInnerAssessment: Either[DesignError, Selection] =
    compiled.map(_.plan.first.inner.first.assessment)
