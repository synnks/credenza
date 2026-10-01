# Integration Tests

These tests exercise the Native HTTP/TLS boundary in the separate sbt `It` configuration.
They use synthetic data and do not require AWS credentials.
For the Native toolchain and general build commands, see the [project README](../../README.md#building-and-testing).

## Running the Suite

Run commands from the repository root with local Docker and Docker Compose available:

```sh
# All integration tests, without cached test results
sbt integrationTest

# Select the SSO HTTPS suite
sbt 'It / testOnly *SsoApiClientIntegrationTests'
```

`sbt test` runs unit tests only.
`It / test`, `It / testOnly`, and `It / testFull` retain sbt's normal selection and caching semantics.
The [integration plugin](../../project/NativeIntegrationTestsPlugin.scala) configures Native release mode; [build.sbt](../../build.sbt) embeds fixture resources and includes their contents in test digests.
Discovery does not start the fixture.

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

Certificate-rejection cases can take tens of seconds because s2n deliberately delays some TLS failures.
Keep test deadlines bounded without disabling certificate verification or blinding.
