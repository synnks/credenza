# Credenza

Credenza is a local AWS credential broker for development tools.
The goal is to reuse an existing AWS login across Docker builds, containers, and IDEs, with credential caching and refresh handled in one place.

The current implementation provides the credential-resolution core: it reads a named AWS SSO profile and its existing login, then obtains temporary role credentials.

## Architecture

Credenza uses Scala 3 on the JVM, with Cats Effect for resource management and the AWS SDK for profile parsing and SSO/OIDC calls.
GraalVM Native Image is the standalone distribution target.

```text
AWS configuration + AWS CLI SSO cache
                  |
                  v
       Profile and session resolution
                  |
                  v
       Token-authenticated SSO request
                  |
                  v
       Temporary AWS role credentials
```

The code separates external formats and effects from validated configuration data:

- **[Reader](src/main/scala/synnks/credenza/config/reader/):** adapts AWS SDK configuration parsing into immutable section data.
- **[Model](src/main/scala/synnks/credenza/config/model/) and [decoding](src/main/scala/synnks/credenza/config/decoding/):** validate profile and session settings independently of file I/O.
- **[SSO](src/main/scala/synnks/credenza/config/sso/):** loads host login material, manages API clients, and composes role-credential resolution.

The resolution boundary returns temporary role credentials with their actual expiration.
Credential values and raw configuration content are excluded from diagnostics, and source and service failures remain distinguishable.
File and network operations use resource-managed effects, with bounded requests and cancellation propagation.

## Supported Sources

The built-in source uses AWS profiles that reference named SSO sessions and their exact AWS CLI cache records.
It respects `AWS_CONFIG_FILE` and keeps the SSO session's region separate from the profile's service region.
Other credential-provider configurations are rejected explicitly.

Role credentials are resolved on demand from a usable cached SSO login.
An expired login requires reauthentication; resolution does not refresh tokens or modify the AWS cache.

## Development

The project uses sbt and JDK 25.
Unit tests use synthetic configuration and credentials; [HTTPS integration tests](src/it/README.md) use managed Docker fixtures.
Build and validation commands are documented in [`AGENTS.md`](AGENTS.md#testing-and-validation).
