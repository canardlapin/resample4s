package tessera.examples

import tessera.core.*
import tessera.designs.*

/** Nested cross-validation is ordinary composition, not a separate design.
  *
  * The inner design is compiled in the outer analysis space. Composing its
  * assessment `Selection` with the outer analysis `Selection` embeds the inner
  * ordinals back into the original population. The result remains a
  * `Selection`, and disjointness from the outer assessment follows from the
  * reindexing algebra.
  */
object NestedCrossValidation:
  def embeddedAssessments(
      outerAnalysis: Selection,
      inner: Plan[Split[Selection], Coverage.ExactOnce]
  ): Either[DomainMismatch, Vector[Selection]] =
    inner.iterator.foldLeft(
      Right(Vector.empty): Either[DomainMismatch, Vector[Selection]]
    ) { (accumulated, unit) =>
      for
        values <- accumulated
        embedded <- outerAnalysis.after(unit._2.assessment)
      yield values :+ embedded
    }

  def verifyExclusion(
      outer: Plan[Split[Selection], Coverage.ExactOnce],
      innerFolds: Int,
      seed: Seed
  ): Either[DesignError | DomainMismatch | CodomainMismatch, Boolean] =
    outer.iterator.foldLeft(
      Right(true): Either[
        DesignError | DomainMismatch | CodomainMismatch,
        Boolean
      ]
    ) { (verified, outerUnit) =>
      verified.flatMap { validSoFar =>
        val split = outerUnit._2
        for
          innerSpace <- IndexSpace.of(split.analysis.domain)
          inner <- KFold(innerFolds).compile(innerSpace, seed)
          embedded <- embeddedAssessments(split.analysis, inner.plan)
          validHere <-
            embedded.foldLeft(
              Right(true): Either[CodomainMismatch, Boolean]
            ) { (checked, assessment) =>
              checked.flatMap { valid =>
                assessment
                  .intersection(split.assessment)
                  .map(overlap => valid && overlap.domain == 0)
              }
            }
        yield validSoFar && validHere
      }
    }
