# Integration Tests

These tests exercise the SSO HTTP client over verified HTTPS in the separate JVM sbt `It` configuration.
They use synthetic data and do not require AWS credentials.
For general build commands, see [AGENTS.md](../../AGENTS.md#testing-and-validation).

## Running the Suites

Run commands from the repository root:

```sh
# All integration suites, without cached test results
sbt integrationTest

# Independent HTTPS server: requires local Docker
sbt 'It / testOnly *SsoApiClientIntegrationTests'
```

`sbt test` runs unit tests only.
`It / test`, `It / testOnly`, and `It / testFull` retain sbt's normal selection and caching semantics.
[build.sbt](../../build.sbt) configures the suite and JUnit XML reporting, and includes fixture contents in test digests.
Discovery does not start the containers.
CI runs sbt in the foreground to retain test output.

Testcontainers uses the Docker API directly.
For Colima, configure its socket before starting sbt:

```sh
docker context use colima
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

The synthetic certificates cover `localhost` and `127.0.0.1`, so use a local Docker daemon.

## Independent SSO HTTPS Suite

[`SsoApiClientIntegrationTests`](scala/synnks/credenza/config/sso/SsoApiClientIntegrationTests.scala) runs the SDK-backed SSO/OIDC clients with the Netty HTTP transport against WireMock.
It uses WireMock's Java DSL to stub responses and verify request methods, paths, query parameters, unsigned headers, and JSON bodies.
The integration cases cover successful HTTPS responses, service-error redaction, malformed JSON, transport timeouts, response-size limits, cancellation, and certificate/hostname rejection.
The unit suite covers the remaining decoding and error-classification combinations.

[`HttpsTestContainers`](scala/synnks/credenza/config/sso/HttpsTestContainers.scala) pins the WireMock image and manages the fixture lifetime through Cats Effect `Resource`:

- Each suite gets fresh valid-host and wrong-host containers, with host and mapped ports supplied by Testcontainers.
- Prebuilt synthetic PKCS12 stores are copied into the containers.
- HTTP readiness checks have a 60-second startup deadline; Testcontainers logs startup failures and container output.
- Cleanup is registered before startup, and Ryuk provides additional cleanup when the owning JVM exits.

Missing Docker or failed fixture startup fails the suite.

## Certificates

The [PEM fixtures](resources/tls) are synthetic.
[`HttpsTestCertificates`](scala/synnks/credenza/config/sso/HttpsTestCertificates.scala) reads the CA certificate and prebuilt server keystores with FS2, supplying PKCS12 data as immutable byte chunks.
Fixture endpoints use Scala URI values, converted to Java URIs at the SDK boundary.
[`HttpsTestConnection`](scala/synnks/credenza/config/sso/HttpsTestConnection.scala) holds the immutable connection data.
[`HttpsTestTls`](scala/synnks/credenza/config/sso/HttpsTestTls.scala) uses Scala SSL Config's PEM keystore builder for CA trust and adapts its result to the SDK's JDK trust managers.
It supplies a resource-managed SSO client and typed transport-failure observation to the suite.
`valid.pem` covers `127.0.0.1` and `localhost`; `wrong-host.pem` covers `wrong.example.invalid`.
The server private key is intentionally public test data; the CA signing key is not committed.
Regenerate the certificates and key as a set before expiry, then rerun the suite.
After changing the PEM fixtures, regenerate both server keystores from the repository root:

```sh
for identity in valid wrong-host; do
  openssl pkcs12 -export \
    -in "src/it/resources/tls/$identity.pem" \
    -inkey src/it/resources/tls/server-key.pem \
    -certfile src/it/resources/tls/ca.pem \
    -name server \
    -out "src/it/resources/tls/$identity.p12" \
    -passout pass:synthetic-key-password
done
```

The PKCS12 stores and their password are public synthetic test data.
The tests load these files directly and do not parse private keys or generate server keystores.
Inspect validity from the repository root with:

```sh
openssl x509 -in src/it/resources/tls/valid.pem -noout -dates
```
