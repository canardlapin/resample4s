package tessera.laws

class LawsSmokeSuite extends munit.FunSuite:
  test("laws test runtime is available") {
    assertEquals(Vector("law").size, 1)
  }
