# Credenza

Credenza is a local AWS credential broker for development tools.
The goal is to reuse an existing AWS login across Docker builds, containers, and IDEs, with credential caching and refresh handled in one place.

The current implementation is the configuration layer: resolving named SSO profiles from supplied AWS config text.

## Architecture

Configuration resolution has three parts:

- **[Reader](src/main/scala/synnks/credenza/config/reader/):** uses the AWS SDK's profile parser and copies its output into immutable section data.
- **[Model](src/main/scala/synnks/credenza/config/model/):** represents profile and session settings with validated opaque types and structured errors.
- **[Decoding](src/main/scala/synnks/credenza/config/decoding/):** composes typed fields with Cats, collecting independent validation errors together.

[`AwsConfig`](src/main/scala/synnks/credenza/config/AwsConfig.scala) connects these parts: it selects a profile, resolves its session reference, and runs the decoders.
The decoders operate on immutable data and receive session resolution as a function.

## Profile Resolution

`AwsConfig.resolveSsoProfile` takes config text and a validated `ProfileName`, returning either a resolved `SsoProfile` or a nonempty chain of configuration errors.
It supports `[default]`, `[profile NAME]`, and referenced `[sso-session NAME]` sections.
Session names are matched exactly, including case; the profile's optional service region is separate from the required SSO region.

Parsing follows the AWS reader's rules: repeated sections are merged, the last duplicate setting wins, setting names are case-sensitive, and indented lines continue the preceding setting.
Unrelated profiles and service settings do not participate in SSO resolution.

Selected profiles must use a named SSO session; static credentials, external processes, role chaining, and legacy inline SSO configuration are rejected.

Agent development instructions are in [`AGENTS.md`](AGENTS.md).
