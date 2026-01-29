package protocatalyst

class MainSuite extends munit.FunSuite:
  test("example test"):
    val obtained = 2 + 2
    val expected = 4
    assertEquals(obtained, expected)
