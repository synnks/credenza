# Credenza

Credenza is a local AWS credential broker for development tools.
The goal is to reuse an existing AWS login across Docker builds, containers, and IDEs, with credential caching and refresh handled in one place.

The first component is an AWS profile parser that resolves SSO configuration from supplied config text.

## AWS Profiles

[`AwsConfig.resolveSsoProfile`](src/main/scala/synnks/credenza/config/AwsConfig.scala) takes config text and a validated `ProfileName`, returning either a nonempty chain of configuration errors or a resolved `SsoProfile`.
The AWS SDK's `profiles` module reads `[default]`, `[profile NAME]`, and `[sso-session NAME]` into immutable sections.
The session name is matched exactly, including case; the profile's optional service region is kept separate from the required SSO region.

Parsing follows the AWS reader's rules: repeated sections are merged, the last duplicate setting wins, setting names are case-sensitive, and indented lines continue the preceding setting.
Unrelated profiles and service settings do not participate in SSO resolution.

Names, account IDs, roles, regions, and start URLs use [validated opaque types](src/main/scala/synnks/credenza/config/model/ProfileValues.scala).
Pure [SSO decoders](src/main/scala/synnks/credenza/config/decoding/SsoDecoders.scala) compose typed fields with Cats and collect independent errors together; profile and session lookups remain sequential where one depends on the other.
Selected profiles must use a named SSO session; static credentials, external processes, role chaining, and legacy inline SSO configuration are rejected.

## Development

Development uses Scala 3 and sbt 2.
Versions are pinned in [`build.sbt`](build.sbt) and [`project/build.properties`](project/build.properties).
You need an sbt runner and a JDK; the build has been verified with JDK 25.

Compile the project:

```sh
sbt compile
```

Run the tests:

```sh
sbt test
```

Format Scala sources and build files using [`.scalafmt.conf`](.scalafmt.conf):

```sh
sbt --batch "; scalafmtSbt; scalafmtAll"
```

Check formatting and compilation:

```sh
sbt --batch "; scalafmtSbtCheck; scalafmtCheckAll; compile"
```

Contributor guidance is in [`AGENTS.md`](AGENTS.md).
