package synnks.credenza.config.reader

import munit.FunSuite

class AwsProfileReaderTests extends FunSuite {
  test("raw config snapshots redact property values") {
    val secret = "synthetic-secret-value"

    assert(!AwsProfileReader.read(s"[profile staging]\naws_secret_access_key = $secret").toString.contains(secret))
  }
}
