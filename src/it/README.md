# Integration Tests

These suites exercise the Native HTTP/TLS boundary in the separate sbt `It` configuration.
They use synthetic data and do not require AWS credentials.
For the Native toolchain and general build commands, see the [project README](../../README.md#building-and-testing).

## Running the Suites

Run commands from the repository root:

```sh
# All integration suites, without cached test results
sbt integrationTest

# Independent HTTPS server: requires local Docker and Docker Compose
sbt 'It / testOnly *SsoApiClientIntegrationTests'

# In-process Native TLS suite: no Docker required
sbt 'It / testOnly *NativeTlsCompatibilityTests'
```

`sbt test` runs unit tests only.
`It / test`, `It / testOnly`, and `It / testFull` retain sbt's normal selection and caching semantics.
The [integration plugin](../../project/NativeIntegrationTestsPlugin.scala) configures Native release mode; [build.sbt](../../build.sbt) embeds fixture resources and includes their contents in test digests.
Discovery does not start either fixture.

## Independent SSO HTTPS Suite

[`SsoApiClientIntegrationTests`](scala/synnks/credenza/config/sso/SsoApiClientIntegrationTests.scala) runs the real Native Ember client against WireMock.
It checks request methods, paths, query parameters, unsigned headers, JSON, response decoding, timeouts, response-size limits, error redaction, and certificate/hostname rejection.

The [Compose definition](resources/https/compose.yaml) pins the server image and owns certificate generation and health checks.
[`HttpsTestConnection`](scala/synnks/credenza/config/sso/HttpsTestConnection.scala) manages the fixture lifetime:

- Each suite gets a unique Compose project and dynamically allocated ports published on `127.0.0.1`.
- A short-lived synthetic CA signs the valid and wrong-host server certificates inside the container.
- MUnit registers teardown before startup, so `compose down --volumes` also covers partial startup failures.
- CLI commands have bounded execution and forced-stop fallback.
  Normal teardown removes containers, the network, certificate volume, and temporary files.

Use a local Docker context; for Colima, select it with `docker context use colima`.
Missing Docker, Compose, or failed fixture startup fails the suite.
If the Native test process is killed before teardown, list projects with `docker compose ls --all` and remove the affected project from the repository root:

```sh
docker compose -f src/it/resources/https/compose.yaml -p <project-name> down --volumes
```

## Native TLS Compatibility Suite

[`NativeTlsCompatibilityTests`](scala/synnks/credenza/config/sso/NativeTlsCompatibilityTests.scala) uses the resource-managed [`NativeHttpsFixture`](scala/synnks/credenza/config/sso/NativeHttpsFixture.scala).
It covers real ALPN negotiation and encrypted I/O, repeated and concurrent HTTPS requests, certificate rejection, and cancellation with active requests.
Keep it separate from the WireMock suite, which provides the independent-server control.

### ALPN Adapter

The Native FS2 backend treats an absent ALPN result as an error ([http4s#7917](https://github.com/http4s/http4s/issues/7917)).
The resulting message can reflect stale s2n error state, so message matching is not reliable.

[`NativeAlpnCompat`](scala/fs2/io/net/tls/NativeAlpnCompat.scala) uses FS2's package-private unsealed interfaces to adapt only the server socket's `applicationProtocol` getter.
For a live connection, the inspected s2n getter reads metadata: it returns a protocol or null for absence, without performing handshake or network I/O.
The adapter translates `S2nException` from that operation into the absence signal Ember handles.
Other operations and resource finalizers are delegated unchanged.

This relies on the implementation versions named in the adapter.
Review its assumptions when upgrading FS2, http4s, or s2n, and validate through real TLS operations.
Do not extend recovery to configuration, handshake, or socket I/O.

### Certificates and Acceptance

The [PEM fixtures](resources/native-tls) are synthetic.
`valid.pem` covers `127.0.0.1` and `localhost`; `wrong-host.pem` covers `wrong.example.invalid`.
The server private key is intentionally public test data; the CA signing key is not committed.
Regenerate the certificates and key as a set before expiry, then rerun the suite.
Inspect validity from the repository root with:

```sh
openssl x509 -in src/it/resources/native-tls/valid.pem -noout -dates
```

The adapter is test-scoped.
A native `s2n_recv` crash observed during earlier validation remains undiagnosed; passing the current checks does not establish its cause.
Both in-process peers use Ember/s2n, so those checks do not replace independent-server interoperability coverage.
Live AWS behavior and any production Native HTTPS listener require their own validation.

Certificate-rejection cases can take tens of seconds because s2n deliberately delays some TLS failures.
Keep test deadlines bounded without disabling certificate verification or blinding.
