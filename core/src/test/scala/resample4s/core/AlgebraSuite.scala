package resample4s.core

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.compiletime.testing.typeCheckErrors

final class AlgebraSuite extends munit.ScalaCheckSuite:
  private def ints(values: Int*): IArray[Int] =
    IArray.unsafeFromArray(values.toArray)

  private def vector(value: Reindexing): Vector[Int] =
    Vector.tabulate(value.domain)(index => value.at(index).toOption.get)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(s"expected Right, obtained $error")

  property("law 1: pullback is functorial") {
    val caseGen =
      for
        n <- Gen.choose(1, 30)
        m <- Gen.choose(0, n)
        selected <- Gen.pick(m, 0 until n)
        q <- Gen.choose(0, m)
        inner <- Gen.pick(q, 0 until m)
      yield (n, selected.sorted, inner.sorted)

    forAll(caseGen) { (n, selected, inner) =>
      val outer = right(
        Selection.from(
          IArray.unsafeFromArray(selected.toArray),
          right(IndexSpace.of(n))
        )
      )
      val nested = right(
        Selection.from(
          IArray.unsafeFromArray(inner.toArray),
          right(IndexSpace.of(selected.size))
        )
      )
      val composed = right(outer.after(nested))
      val values = IArray.unsafeFromArray(Array.tabulate(n)(index => index * 7))
      val direct = right(pull(values, composed))
      val staged = right(pull(right(pull(values, outer)), nested))
      assertEquals(
        Vector.tabulate(direct.length)(direct(_)),
        Vector.tabulate(staged.length)(staged(_))
      )
    }
  }

  test("composition closure retains the strongest valid result types") {
    val space = right(IndexSpace.of(5))
    val selection = right(Selection.from(ints(0, 2, 4), space))
    val local = right(
      Selection.from(ints(0, 2), right(IndexSpace.of(selection.domain)))
    )
    val nested: Selection = right(selection.after(local))
    assertEquals(vector(nested), Vector(0, 4))

    val permutation = right(Permutation.from(ints(2, 0, 1)))
    val reordered: Injection = right(selection.after(permutation))
    assertEquals(vector(reordered), Vector(4, 0, 2))

    val draw = right(Draw.from(ints(1, 1, 0), right(IndexSpace.of(3))))
    val redrawn: Draw = right(selection.after(draw))
    assertEquals(vector(redrawn), Vector(2, 2, 0))

    val widenedSelection: Injection = selection.widen
    val widenedInjection: Draw = widenedSelection.widen
    assertEquals(vector(widenedSelection), vector(selection))
    assertEquals(vector(widenedInjection), vector(selection))
    assertEquals(widenedSelection.codomain, selection.codomain)
    assertEquals(widenedInjection.codomain, selection.codomain)

    assertEquals(
      selection.after(right(Selection.from(ints(0), right(IndexSpace.of(4))))),
      Left(DomainMismatch(3, 4))
    )
  }

  test("abstract composition reports how to retain the concrete result type") {
    val errors = typeCheckErrors(
      """import resample4s.core.*
def invalid(left: Reindexing, right: Reindexing) =
  left.after(right)
"""
    )
    assertEquals(errors.length, 1)
    assert(
      errors.head.message.contains(
        "Keep both values typed as Draw, Injection, Selection, or Permutation"
      )
    )
  }

  property("law 13: every injection factors into selection after permutation") {
    val caseGen =
      for
        n <- Gen.choose(1, 30)
        m <- Gen.choose(0, n)
        values <- Gen.pick(m, 0 until n)
      yield (n, values)

    forAll(caseGen) { (n, values) =>
      val injection = right(
        Injection.from(
          IArray.unsafeFromArray(values.toArray),
          right(IndexSpace.of(n))
        )
      )
      val (selection, permutation) = injection.factor
      val reconstructed: Injection = right(selection.after(permutation))
      assertEquals(reconstructed, injection)
    }
  }

  test("law 8: permutations satisfy identity and inverse laws") {
    val left = right(Permutation.from(ints(2, 4, 1, 0, 3)))
    val rightValue = right(Permutation.from(ints(1, 3, 4, 2, 0)))
    val identity = right(Permutation.identity(5))

    assertEquals(right(left.after(identity)), left)
    assertEquals(right(identity.after(left)), left)
    assertEquals(right(left.after(left.inverse)), identity)
    assertEquals(right(left.inverse.after(left)), identity)

    val third = right(Permutation.from(ints(4, 0, 3, 1, 2)))
    val lhs = right(right(left.after(rightValue)).after(third))
    val rhs = right(left.after(right(rightValue.after(third))))
    assertEquals(lhs, rhs)
  }

  test("draw order, support, multiplicity, and multiset are distinct") {
    val space = right(IndexSpace.of(5))
    val first = right(Draw.from(ints(3, 1, 3, 4), space))
    val second = right(Draw.from(ints(3, 3, 1, 4), space))

    assertNotEquals(first, second)
    assert(first.sameMultiset(second))
    assertEquals(first.multiplicity(3), 2)
    assertEquals(vector(first.support), Vector(1, 3, 4))
  }

  test("public reindexing factories defensively copy source arrays") {
    val selectionSource = Array(0, 2)
    val drawSource = Array(1, 1, 2)
    val injectionSource = Array(2, 0)
    val permutationSource = Array(1, 2, 0)

    val selection = right(
      Selection.from(
        IArray.unsafeFromArray(selectionSource),
        right(IndexSpace.of(3))
      )
    )
    val draw = right(
      Draw.from(
        IArray.unsafeFromArray(drawSource),
        right(IndexSpace.of(3))
      )
    )
    val injection = right(
      Injection.from(
        IArray.unsafeFromArray(injectionSource),
        right(IndexSpace.of(3))
      )
    )
    val permutation =
      right(Permutation.from(IArray.unsafeFromArray(permutationSource)))

    selectionSource(0) = 1
    drawSource(0) = 0
    injectionSource(0) = 1
    permutationSource(0) = 0

    assertEquals(vector(selection), Vector(0, 2))
    assertEquals(vector(draw), Vector(1, 1, 2))
    assertEquals(vector(injection), Vector(2, 0))
    assertEquals(vector(permutation), Vector(1, 2, 0))
  }

  test("checked constructors reject malformed reindexings") {
    val space = right(IndexSpace.of(4))
    assertEquals(
      Selection.from(ints(0, 0), space),
      Left(DesignError.NonIncreasingIndex(0, 0))
    )
    assertEquals(
      Injection.from(ints(2, 2), space),
      Left(DesignError.DuplicateIndex(2))
    )
    assertEquals(
      Draw.from(ints(4), space),
      Left(DesignError.OutOfRangeIndex(4, 4))
    )
    assertEquals(
      Permutation.from(ints(0, 2)),
      Left(DesignError.OutOfRangeIndex(2, 2))
    )
    assertEquals(selectionAtFailure(space), Left(OutOfDomain(1, 1)))
  }

  private def selectionAtFailure(
      space: IndexSpace
  ): Either[OutOfDomain, Int] =
    right(Selection.from(ints(0), space)).at(1)
