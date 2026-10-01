# Credenza

Credenza is a local AWS credential broker for development tools.
The goal is to reuse an existing AWS login across Docker builds, containers, and IDEs, with credential caching and refresh handled in one place.

The current implementation resolves named SSO profiles and reads their AWS CLI SSO cache records.

## Architecture

Configuration and cache handling are split across:

- **[Reader](src/main/scala/synnks/credenza/config/reader):** parses AWS-compatible configuration into immutable section data and resolves configuration-file overrides.
- **[Model](src/main/scala/synnks/credenza/config/model/):** represents profile and session settings with validated opaque types and structured errors.
- **[Decoding](src/main/scala/synnks/credenza/config/decoding/):** composes typed profile fields with Cats, collecting independent validation errors together.
- **[SSO](src/main/scala/synnks/credenza/config/sso/):** decodes and reads the selected SSO cache record.

[`AwsConfig`](src/main/scala/synnks/credenza/config/AwsConfig.scala) connects these parts: it selects a profile, resolves its session reference, and runs the decoders.
The decoders operate on immutable data and receive session resolution as a function.

## Profile Resolution

`AwsConfig.resolveSsoProfile` takes config text and a validated `ProfileName`, returning either a resolved `SsoProfile` or a nonempty chain of configuration errors.
It supports `[default]`, `[profile NAME]`, and referenced `[sso-session NAME]` sections.
Session names are matched exactly, including case; the profile's optional service region is separate from the required SSO region.

Parsing follows the AWS reader's rules: repeated sections are merged, the last duplicate setting wins, setting names are case-sensitive, and indented lines continue the preceding setting.
`[profile default]` takes precedence over `[default]`; nested properties remain beneath their parent.
Unrelated profiles and service settings do not participate in SSO resolution.

Selected profiles must use a named SSO session; static credentials, external processes, role chaining, and legacy inline SSO configuration are rejected.

## SSO Cache

[`SsoTokenCache`](src/main/scala/synnks/credenza/config/sso/SsoTokenCache.scala) reads a cache directory supplied by the caller. It selects exactly `<sha1(session name)>.json`; it does not scan files or substitute a token from another session sharing the same start URL.
The disk read is deferred in Cats Effect `IO` and runs on its blocking pool; UTF-8 and JSON decoding and fingerprinting remain separate from file I/O.
The token decoder checks the record's start URL and SSO region against the selected session, reads its access token and actual expiry, and retains complete refresh material when present. The read result includes the source path and a content fingerprint for later guarded writes.

An expired record remains readable; asking for a usable access token reports that the login has expired. Automatic refresh and credential resolution are not implemented yet.

## Host Source

[`SsoSource`](src/main/scala/synnks/credenza/config/sso/SsoSource.scala) locates the config file with `aws.configFile` taking precedence over `AWS_CONFIG_FILE`, expanding a leading `~/` using the selected home.
The default is `~/.aws/config`; an override does not relocate the SSO cache.
It reads the named profile and its exact cache entry, reporting missing, unreadable, invalid, or expired sessions without exposing cached credentials.
The first present override retains precedence: a blank or invalid selected path returns `InvalidLocation` rather than falling back to another source.
The host entry point reads the home directory through Cats Effect's environment and system-property APIs.
File paths and the evaluation time can be supplied explicitly for testing.

## AWS HTTP Compatibility Proof

[`SsoApiClient`](src/main/scala/synnks/credenza/config/sso/SsoApiClient.scala) makes the token-authenticated `GetRoleCredentials` and refresh-token `CreateToken` requests through http4s 0.23.38, using explicit endpoint URIs and typed Circe codecs. Loopback Ember tests verify their HTTP methods, paths, headers, JSON, expiry units, and redacted errors without SigV4 or requiring ambient IAM credentials.

Source inspection of [smithy4s v0.19.13](https://github.com/disneystreaming/smithy4s/blob/v0.19.13/modules/aws-http4s/src/smithy4s/aws/AwsClient.scala) found that its standard `AwsClient` installs credential-dependent signing for these operations, so this proof uses a small dedicated adapter. These JVM stub tests do not establish live AWS, HTTPS trust, or GraalVM Native Image compatibility.

Agent development instructions are in [`AGENTS.md`](AGENTS.md).
