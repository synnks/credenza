package synnks.credenza.config.reader

import munit.FunSuite

import java.nio.file.Path

class AwsConfigLocationTests extends FunSuite {
  private val home = Path.of("/synthetic/home")

  test("default config lives in the supplied home and overrides do not relocate the cache") {
    assertEquals(AwsConfigLocation.resolve(home, None, None), Some(home.resolve(".aws/config")))
    assertEquals(AwsConfigLocation.resolve(home, None, Some("alternate/config")), Some(Path.of("alternate/config")))
  }

  test("the JVM property takes precedence over the environment override") {
    assertEquals(
      AwsConfigLocation.resolve(home, Some(" property/config "), Some("environment/config")),
      Some(Path.of("property/config"))
    )
    assertEquals(
      AwsConfigLocation.resolve(home, Some("property/config"), Some("\u0000")),
      Some(Path.of("property/config"))
    )
  }

  test("blank selected overrides are invalid and never fall through to another source") {
    List("", " \t ").foreach { blank =>
      assertEquals(AwsConfigLocation.resolve(home, Some(blank), Some("environment/config")), None)
      assertEquals(AwsConfigLocation.resolve(home, Some(blank), None), None)
      assertEquals(AwsConfigLocation.resolve(home, None, Some(blank)), None)
    }
  }

  test("invalid selected paths are rejected without falling back") {
    assertEquals(AwsConfigLocation.resolve(home, Some("bad\u0000path"), Some("environment/config")), None)
    assertEquals(AwsConfigLocation.resolve(home, None, Some("bad\u0000path")), None)
  }

  test("expand a leading tilde slash using the supplied home without expanding other tilde forms") {
    assertEquals(
      AwsConfigLocation.resolve(home, None, Some("~/alternate/config")),
      Some(home.resolve("alternate/config"))
    )
    assertEquals(AwsConfigLocation.resolve(home, None, Some("~someone/config")), Some(Path.of("~someone/config")))
    assertEquals(AwsConfigLocation.resolve(home, None, Some("~")), Some(Path.of("~")))
  }
}
