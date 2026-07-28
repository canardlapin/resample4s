package resample4s

import scala.annotation.targetName
import resample4s.core.*

/** Zero-cost semantic wrapper for group labels. */
opaque type Groups = Labels

object Groups:
  def from(labels: Labels): Groups = labels

  def from(codes: IndexedSeq[Int]): Either[DesignError, Groups] =
    Labels.dense(IArray.from(codes))

  @targetName("fromArray")
  def from(codes: Array[Int]): Either[DesignError, Groups] =
    Labels.dense(IArray.unsafeFromArray(codes.clone()))

  extension (groups: Groups) def labels: Labels = groups

/** Zero-cost semantic wrapper for stratum labels. */
opaque type Strata = Labels

object Strata:
  def from(labels: Labels): Strata = labels

  def from(codes: IndexedSeq[Int]): Either[DesignError, Strata] =
    Labels.dense(IArray.from(codes))

  @targetName("fromArray")
  def from(codes: Array[Int]): Either[DesignError, Strata] =
    Labels.dense(IArray.unsafeFromArray(codes.clone()))

  extension (strata: Strata) def labels: Labels = strata

/** Zero-cost semantic wrapper for exchangeability blocks. */
opaque type Blocks = Labels

object Blocks:
  def from(labels: Labels): Blocks = labels

  def from(codes: IndexedSeq[Int]): Either[DesignError, Blocks] =
    Labels.dense(IArray.from(codes))

  @targetName("fromArray")
  def from(codes: Array[Int]): Either[DesignError, Blocks] =
    Labels.dense(IArray.unsafeFromArray(codes.clone()))

  extension (blocks: Blocks) def labels: Labels = blocks
